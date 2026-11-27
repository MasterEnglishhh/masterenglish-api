package com.masterenglish.api.modules.content.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.modules.content.service.impl.FfmpegMediaDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Runs inside the backend image, so the decoder owns the native FFmpeg process directly. */
public class NativeDecoderProcessCheck {
    public static void main(String[] args) throws Exception {
        var nativeDecoder = new FfmpegMediaDecoder("ffprobe", "ffmpeg", new ObjectMapper());
        for (String[] fixture :
                List.of(
                        new String[] {"valid.jpg", "image/jpeg"},
                        new String[] {"valid.png", "image/png"},
                        new String[] {"valid.webp", "image/webp"},
                        new String[] {"valid.mp3", "audio/mpeg"},
                        new String[] {"valid_22k.mp3", "audio/mpeg"},
                        new String[] {"valid.m4a", "audio/mp4"},
                        new String[] {"valid_alac.m4a", "audio/mp4"})) {
            var decoded = nativeDecoder.decode(Path.of("/media", fixture[0]), fixture[1]);
            if (!decoded.mimeType().equals(fixture[1])) {
                throw new AssertionError("Wrong decoded MIME for " + fixture[0]);
            }
        }
        System.out.println("NATIVE_JAVA_DECODER_FORMATS_CONFIRMED");
        Path directory = Files.createTempDirectory("native-decoder-check-");
        Path pidFile = directory.resolve("ffmpeg.pid");
        Path probe = directory.resolve("probe.sh");
        Files.writeString(
                probe,
                "#!/bin/sh\nprintf '%s' \"$$\" > '"
                        + pidFile
                        + "'\n"
                        + "exec ffmpeg -nostdin -v error -re -stream_loop -1 -i /media/valid.mp3 -f null -\n");
        if (!probe.toFile().setExecutable(true)) {
            throw new IllegalStateException("Cannot make native probe executable");
        }
        var decoder =
                new FfmpegMediaDecoder(
                        probe.toString(),
                        "ffmpeg",
                        List.of(),
                        Path::toString,
                        Duration.ofSeconds(3),
                        new ObjectMapper());
        try {
            decoder.decode(Path.of("/media/valid.mp3"), "audio/mpeg");
            throw new AssertionError("Expected native FFmpeg timeout");
        } catch (BusinessException failure) {
            if (!failure.getMessage().contains("timed out")) {
                throw failure;
            }
        }
        long pid = Long.parseLong(Files.readString(pidFile));
        if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
            throw new AssertionError("Native FFmpeg still alive after decoder timeout: " + pid);
        }
        System.out.println("NATIVE_FFMPEG_TIMEOUT_EXIT_CONFIRMED pid=" + pid);
        Files.delete(pidFile);
        var interruptedFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var entered = new java.util.concurrent.CountDownLatch(1);
        Thread worker =
                new Thread(
                        () -> {
                            entered.countDown();
                            try {
                                decoder.decode(Path.of("/media/valid.mp3"), "audio/mpeg");
                                interruptedFailure.set(
                                        new AssertionError("Expected decoder interruption"));
                            } catch (BusinessException failure) {
                                if (!failure.getMessage().contains("interrupted")
                                        || !Thread.currentThread().isInterrupted()) {
                                    interruptedFailure.set(failure);
                                }
                            } catch (Throwable failure) {
                                interruptedFailure.set(failure);
                            }
                        });
        worker.start();
        entered.await();
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!Files.exists(pidFile) && worker.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        try {
            if (!Files.exists(pidFile)) {
                throw new AssertionError("Interrupted FFmpeg did not start");
            }
            long interruptedPid = Long.parseLong(Files.readString(pidFile));
            worker.interrupt();
            worker.join(5000);
            if (worker.isAlive() || interruptedFailure.get() != null) {
                throw new AssertionError("Decoder interruption failed", interruptedFailure.get());
            }
            if (ProcessHandle.of(interruptedPid).map(ProcessHandle::isAlive).orElse(false)) {
                throw new AssertionError(
                        "Native FFmpeg alive after interruption: " + interruptedPid);
            }
            System.out.println("NATIVE_FFMPEG_INTERRUPTION_EXIT_CONFIRMED pid=" + interruptedPid);
        } finally {
            worker.interrupt();
            worker.join(5000);
        }
        Files.delete(pidFile);
        Files.delete(probe);
        Files.delete(directory);
    }
}
