package com.masterenglish.api.modules.content.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterenglish.api.MasterenglishApiApplication;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.storage.MediaStorage;
import com.masterenglish.api.common.storage.StorageException;
import com.masterenglish.api.infrastructure.storage.R2MediaStorage;
import com.masterenglish.api.modules.content.dto.request.ReserveMediaUploadRequest;
import com.masterenglish.api.modules.content.dto.response.CompleteMediaResult;
import com.masterenglish.api.modules.content.dto.response.DecodedMediaInfo;
import com.masterenglish.api.modules.content.dto.response.ReserveMediaUploadResult;
import com.masterenglish.api.modules.content.service.impl.FfmpegMediaDecoder;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/**
 * Test-only worker launcher executed inside the backend Docker image via PropertiesLauncher.
 * Connects to the test coordinator over a framed line-based TCP socket and exposes real
 * MediaVerificationService workflows backed by PostgreSQL, MinIO, and native FFmpeg.
 */
public class MediaVerificationWorkerLauncher {

    private static final Path SLOW_FLAG_PATH = Path.of("/tmp/ffmpeg-slow");
    private static final Path FFMPEG_WRAPPER_PATH = Path.of("/tmp/ffmpeg-wrapper.sh");

    private static volatile PrintWriter ipcOut;
    private static final Object IPC_LOCK = new Object();
    private static volatile String workerId = "worker";
    private static final Set<String> FAIL_AFTER_PUT_KEYS = ConcurrentHashMap.newKeySet();

    public static void main(String[] args) throws Exception {
        workerId = envOrThrow("WORKER_ID");
        String ipcHost = envOrThrow("WORKER_IPC_HOST");
        int ipcPort = Integer.parseInt(envOrThrow("WORKER_IPC_PORT"));

        installFfmpegWrapper();

        Socket socket = new Socket();
        socket.connect(new java.net.InetSocketAddress(ipcHost, ipcPort), 10_000);
        socket.setSoTimeout(120_000);
        ipcOut = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
        BufferedReader ipcIn =
                new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

        SpringApplication app =
                new SpringApplication(MasterenglishApiApplication.class, WorkerTestConfig.class);
        ConfigurableApplicationContext ctx = app.run(args);

        // Verify DataSource and MediaStorage readiness before emitting READY
        DataSource dataSource = ctx.getBean(DataSource.class);
        try (var conn = dataSource.getConnection();
                var st = conn.createStatement();
                var rs = st.executeQuery("SELECT 1")) {
            if (!rs.next()) {
                throw new IllegalStateException("DB readiness check failed");
            }
        }
        MediaStorage storage = ctx.getBean(MediaStorage.class);
        if (!(storage instanceof ObservableMediaStorage) || !storage.isAvailable()) {
            throw new IllegalStateException("Expected ObservableMediaStorage primary bean");
        }

        long jvmPid = ProcessHandle.current().pid();
        long startMs =
                ProcessHandle.current()
                        .info()
                        .startInstant()
                        .map(java.time.Instant::toEpochMilli)
                        .orElse(System.currentTimeMillis());
        String pgAppName = envOrDefault("WORKER_PG_APP_NAME", workerId);

        sendIpc(
                "READY workerId="
                        + workerId
                        + " jvmPid="
                        + jvmPid
                        + " startEpochMs="
                        + startMs
                        + " pgAppName="
                        + pgAppName);

        MediaReservationService reservationService = ctx.getBean(MediaReservationService.class);
        MediaVerificationService verificationService = ctx.getBean(MediaVerificationService.class);
        ExecutorService executor = Executors.newCachedThreadPool();
        AtomicBoolean running = new AtomicBoolean(true);

        try {
            String line;
            while (running.get() && (line = ipcIn.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                String[] parts = trimmed.split("\\s+");
                String cmd = parts[0];
                String cmdId = extractArg(parts, "cmdId");
                switch (cmd) {
                    case "PING" -> sendIpc("PONG cmdId=" + cmdId);
                    case "CHECK_STORAGE_PRIMARY" ->
                            sendIpc(
                                    "STORAGE_PRIMARY cmdId="
                                            + cmdId
                                            + " ok="
                                            + (storage instanceof ObservableMediaStorage));
                    case "SET_SLOW_DECODE" -> {
                        boolean enabled = Boolean.parseBoolean(extractArg(parts, "enabled"));
                        if (enabled) {
                            Files.writeString(SLOW_FLAG_PATH, "1");
                        } else {
                            Files.deleteIfExists(SLOW_FLAG_PATH);
                        }
                        sendIpc("ACK cmdId=" + cmdId);
                    }
                    case "SET_FAIL_AFTER_PUT" -> {
                        String key = extractArg(parts, "key");
                        FAIL_AFTER_PUT_KEYS.add(key);
                        sendIpc("ACK cmdId=" + cmdId);
                    }
                    case "RESERVE_CONTENT_UPLOAD" -> {
                        UUID actorId = UUID.fromString(extractArg(parts, "actorId"));
                        String fileName = extractArg(parts, "fileName");
                        String mimeType = extractArg(parts, "mimeType");
                        long sizeBytes = Long.parseLong(extractArg(parts, "sizeBytes"));
                        UUID sourceId = UUID.fromString(extractArg(parts, "sourceId"));
                        ReserveMediaUploadResult res =
                                reservationService.reserveContentUpload(
                                        actorId,
                                        new ReserveMediaUploadRequest(
                                                fileName, mimeType, sizeBytes, sourceId));
                        sendIpc(
                                "RESERVE_RESULT cmdId="
                                        + cmdId
                                        + " ok=true uploadRequestId="
                                        + res.uploadRequestId()
                                        + " mediaId="
                                        + res.mediaId()
                                        + " tempBucket="
                                        + res.tempBucket()
                                        + " tempObjectKey="
                                        + res.tempObjectKey());
                    }
                    case "COMPLETE_UPLOAD" -> {
                        UUID actorId = UUID.fromString(extractArg(parts, "actorId"));
                        UUID requestId = UUID.fromString(extractArg(parts, "requestId"));
                        executor.submit(
                                () -> {
                                    try {
                                        CompleteMediaResult res =
                                                verificationService.completeUpload(
                                                        actorId, requestId);
                                        sendIpc(
                                                "CMD_RESULT cmdId="
                                                        + cmdId
                                                        + " ok=true status="
                                                        + res.status()
                                                        + " leaseAcquired="
                                                        + res.leaseAcquired()
                                                        + " claimToken="
                                                        + res.claimToken());
                                    } catch (BusinessException be) {
                                        sendIpc(
                                                "CMD_RESULT cmdId="
                                                        + cmdId
                                                        + " ok=false errorCode="
                                                        + be.getErrorCode().name()
                                                        + " msg="
                                                        + sanitize(be.getMessage()));
                                    } catch (Throwable t) {
                                        sendIpc(
                                                "CMD_RESULT cmdId="
                                                        + cmdId
                                                        + " ok=false errorCode="
                                                        + t.getClass().getSimpleName()
                                                        + " msg="
                                                        + sanitize(t.getMessage()));
                                    }
                                });
                    }
                    case "VERIFY_AND_FINALIZE" -> {
                        UUID requestId = UUID.fromString(extractArg(parts, "requestId"));
                        UUID claimToken = UUID.fromString(extractArg(parts, "claimToken"));
                        executor.submit(
                                () -> {
                                    try {
                                        CompleteMediaResult res =
                                                verificationService.verifyAndFinalize(
                                                        requestId, claimToken);
                                        sendIpc(
                                                "VERIFY_RESULT cmdId="
                                                        + cmdId
                                                        + " ok=true status="
                                                        + res.status());
                                    } catch (BusinessException be) {
                                        sendIpc(
                                                "VERIFY_RESULT cmdId="
                                                        + cmdId
                                                        + " ok=false errorCode="
                                                        + be.getErrorCode().name()
                                                        + " msg="
                                                        + sanitize(be.getMessage()));
                                    } catch (Throwable t) {
                                        sendIpc(
                                                "VERIFY_RESULT cmdId="
                                                        + cmdId
                                                        + " ok=false errorCode="
                                                        + t.getClass().getSimpleName()
                                                        + " msg="
                                                        + sanitize(t.getMessage()));
                                    }
                                });
                    }
                    case "SHUTDOWN" -> {
                        running.set(false);
                        sendIpc("ACK cmdId=" + cmdId);
                    }
                    default -> sendIpc("ERROR cmdId=" + cmdId + " msg=unknown_command_" + cmd);
                }
            }
        } finally {
            executor.shutdownNow();
            ctx.close();
            socket.close();
        }
    }

    private static void installFfmpegWrapper() throws IOException {
        String script =
                """
                #!/bin/sh
                if [ -f /tmp/ffmpeg-slow ]; then
                  n=$#
                  i=0
                  while [ $i -lt $n ]; do
                    arg="$1"
                    shift
                    if [ "$arg" = "-i" ]; then
                      set -- "$@" "-re" "-i"
                    else
                      set -- "$@" "$arg"
                    fi
                    i=$((i + 1))
                  done
                fi
                exec /usr/bin/ffmpeg "$@"
                """;
        Files.writeString(FFMPEG_WRAPPER_PATH, script, StandardCharsets.UTF_8);
        if (!FFMPEG_WRAPPER_PATH.toFile().setExecutable(true)) {
            throw new IllegalStateException("Cannot mark ffmpeg wrapper executable");
        }
    }

    private static void requireNoTransactionDuringIo() {
        if (org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive()) {
            throw new AssertionError("Verification I/O must run outside a database transaction");
        }
    }

    static void sendIpc(String msg) {
        synchronized (IPC_LOCK) {
            if (ipcOut != null) {
                ipcOut.println(msg);
                ipcOut.flush();
            }
        }
    }

    private static String extractArg(String[] parts, String key) {
        String prefix = key + "=";
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].startsWith(prefix)) {
                return parts[i].substring(prefix.length());
            }
        }
        return "";
    }

    private static String sanitize(String msg) {
        if (msg == null) {
            return "";
        }
        return msg.replace('\n', ' ').replace('\r', ' ');
    }

    private static String envOrThrow(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required env var: " + name);
        }
        return value;
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    @TestConfiguration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "app.test.dual-jvm-worker", havingValue = "true")
    static class WorkerTestConfig {

        @Bean
        @Primary
        MediaStorage workerMediaStorage() {
            String endpoint = envOrThrow("WORKER_MINIO_ENDPOINT");
            String bucket = envOrThrow("WORKER_MINIO_BUCKET");
            String accessKey = envOrThrow("WORKER_MINIO_ACCESS_KEY");
            String secretKey = envOrThrow("WORKER_MINIO_SECRET_KEY");

            S3Client s3Client =
                    S3Client.builder()
                            .endpointOverride(java.net.URI.create(endpoint))
                            .region(Region.US_EAST_1)
                            .credentialsProvider(
                                    StaticCredentialsProvider.create(
                                            AwsBasicCredentials.create(accessKey, secretKey)))
                            .serviceConfiguration(
                                    S3Configuration.builder()
                                            .pathStyleAccessEnabled(true)
                                            .chunkedEncodingEnabled(false)
                                            .build())
                            .build();
            R2MediaStorage delegate = new R2MediaStorage(bucket, s3Client);
            return new ObservableMediaStorage(delegate);
        }

        @Bean
        @Primary
        MediaDecoder workerMediaDecoder(ObjectMapper objectMapper) {
            FfmpegMediaDecoder delegate =
                    new FfmpegMediaDecoder(
                            "/usr/bin/ffprobe",
                            FFMPEG_WRAPPER_PATH.toString(),
                            List.of(),
                            p -> p.toAbsolutePath().toString(),
                            Duration.ofSeconds(15),
                            objectMapper);
            return new ObservableMediaDecoder(delegate);
        }
    }

    static class ObservableMediaStorage implements MediaStorage {
        private final R2MediaStorage delegate;

        ObservableMediaStorage(R2MediaStorage delegate) {
            this.delegate = delegate;
        }

        @Override
        public String bucket() {
            return delegate.bucket();
        }

        @Override
        public boolean isAvailable() {
            return delegate.isAvailable();
        }

        @Override
        public void put(String key, Path file, String contentType, String sha256Checksum) {
            delegate.put(key, file, contentType, sha256Checksum);
        }

        @Override
        public void putIfAbsent(String key, Path file, String contentType, String sha256Checksum) {
            requireNoTransactionDuringIo();
            sendIpc("EVENT workerId=" + workerId + " type=STORAGE_PUT_STARTED key=" + key);
            delegate.putIfAbsent(key, file, contentType, sha256Checksum);
            sendIpc("EVENT workerId=" + workerId + " type=STORAGE_PUT_COMPLETED key=" + key);
            if (FAIL_AFTER_PUT_KEYS.remove(key)) {
                throw new StorageException("Injected lost response after putIfAbsent for " + key);
            }
        }

        @Override
        public InputStream openStream(String key) {
            requireNoTransactionDuringIo();
            return delegate.openStream(key);
        }

        @Override
        public boolean exists(String key) {
            return delegate.exists(key);
        }

        @Override
        public boolean delete(String key) {
            return delegate.delete(key);
        }
    }

    static class ObservableMediaDecoder implements MediaDecoder {
        private final FfmpegMediaDecoder delegate;

        ObservableMediaDecoder(FfmpegMediaDecoder delegate) {
            this.delegate = delegate;
        }

        @Override
        public DecodedMediaInfo decode(Path stagedFile, String expectedMimeType) {
            requireNoTransactionDuringIo();
            sendIpc("EVENT workerId=" + workerId + " type=DECODER_STARTED");
            AtomicBoolean active = new AtomicBoolean(true);
            Set<Long> seenPids = ConcurrentHashMap.newKeySet();
            Thread watcher =
                    new Thread(
                            () -> {
                                while (active.get()) {
                                    ProcessHandle.current()
                                            .descendants()
                                            .filter(ProcessHandle::isAlive)
                                            .forEach(
                                                    ph -> {
                                                        String cmd = ph.info().command().orElse("");
                                                        if ((cmd.endsWith("/ffmpeg")
                                                                        || cmd.equals("ffmpeg"))
                                                                && seenPids.add(ph.pid())) {
                                                            long startMs =
                                                                    ph.info()
                                                                            .startInstant()
                                                                            .map(
                                                                                    java.time
                                                                                                    .Instant
                                                                                            ::toEpochMilli)
                                                                            .orElse(0L);
                                                            sendIpc(
                                                                    "EVENT workerId="
                                                                            + workerId
                                                                            + " type=FFMPEG_STARTED pid="
                                                                            + ph.pid()
                                                                            + " startMs="
                                                                            + startMs);
                                                        }
                                                    });
                                    try {
                                        Thread.sleep(25);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                        break;
                                    }
                                }
                            });
            watcher.setDaemon(true);
            watcher.start();
            try {
                return delegate.decode(stagedFile, expectedMimeType);
            } finally {
                active.set(false);
                watcher.interrupt();
                sendIpc("EVENT workerId=" + workerId + " type=DECODER_FINISHED");
            }
        }
    }
}
