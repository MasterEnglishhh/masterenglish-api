package com.masterenglish.api.modules.content.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
class PronunciationManifestReader {
    private static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    private static final int MAX_ENTRIES = 1000;
    private static final int MAX_AUDIO_BYTES = 20 * 1024 * 1024;
    private static final Pattern VOICE_ID = Pattern.compile("[a-zA-Z0-9_-]{1,100}");
    private static final Pattern LOCALE = Pattern.compile("[a-z]{2}-[A-Z]{2}");
    private static final Pattern MP3_FILENAME = Pattern.compile("[a-zA-Z0-9_-]+\\.mp3");

    private final ObjectMapper mapper;

    PronunciationManifestReader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    Manifest read(Path path) throws IOException {
        Path manifest = path.toRealPath();
        if (Files.size(manifest) > MAX_MANIFEST_BYTES) {
            throw new IllegalArgumentException("Manifest too large.");
        }
        JsonNode root = mapper.readTree(Files.readString(manifest));
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Manifest must be a JSON object.");
        }
        String voice = root.path("voiceId").asText();
        String locale = root.path("locale").asText();
        if (!VOICE_ID.matcher(voice).matches() || !LOCALE.matcher(locale).matches()) {
            throw new IllegalArgumentException("Invalid voice or locale.");
        }
        JsonNode entries = root.path("entries");
        if (!entries.isArray() || entries.isEmpty() || entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Manifest has invalid audio entries.");
        }
        var wordIds = new HashSet<UUID>();
        var recordings = new ArrayList<Recording>(entries.size());
        for (JsonNode entry : entries) {
            UUID wordId = UUID.fromString(entry.path("wordId").asText());
            if (!wordIds.add(wordId)) {
                throw new IllegalArgumentException("Duplicate pronunciation word.");
            }
            recordings.add(readRecording(manifest.getParent(), wordId, entry));
        }
        return new Manifest(
                voice,
                locale,
                root.path("modelSha256").asText(),
                root.path("sourceUrl").asText(),
                root.path("licenseCode").asText(),
                root.path("attribution").asText(),
                List.copyOf(recordings));
    }

    private static Recording readRecording(Path directory, UUID wordId, JsonNode entry)
            throws IOException {
        String name = entry.path("file").asText();
        if (!MP3_FILENAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Audio filename must be a plain MP3 filename.");
        }
        Path file = directory.resolve(name).toRealPath();
        if (!file.startsWith(directory)) {
            throw new IllegalArgumentException("Audio path is outside the manifest directory.");
        }
        int duration = entry.path("durationMs").asInt();
        long size = Files.size(file);
        if (size < 128 || size > MAX_AUDIO_BYTES || duration < 100 || duration > 30000) {
            throw new IllegalArgumentException("Invalid pronunciation file size or duration.");
        }
        String checksum = entry.path("sha256").asText();
        verifyAudio(file, size, checksum);
        return new Recording(
                wordId,
                entry.path("wordRevisionId").asText(),
                entry.path("lemma").asText(),
                file,
                size,
                duration,
                checksum);
    }

    static void verifyAudio(Path file, long size, String checksum) throws IOException {
        byte[] bytes;
        try (var stream = Files.newInputStream(file)) {
            bytes = stream.readNBytes((int) size + 1);
        }
        if (bytes.length != size) {
            throw new IllegalArgumentException("Pronunciation file size changed while reading.");
        }
        if (!isMp3(bytes)) {
            throw new IllegalArgumentException("Pronunciation file is not MP3.");
        }
        if (!sha256(bytes).equals(checksum)) {
            throw new IllegalArgumentException("Pronunciation checksum mismatch.");
        }
    }

    private static boolean isMp3(byte[] bytes) {
        return (bytes[0] == 'I' && bytes[1] == 'D' && bytes[2] == '3')
                || ((bytes[0] & 255) == 255 && (bytes[1] & 224) == 224);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    record Manifest(
            String voice,
            String locale,
            String modelChecksum,
            String sourceUrl,
            String license,
            String attribution,
            List<Recording> recordings) {}

    record Recording(
            UUID wordId,
            String sourceRevisionId,
            String lemma,
            Path file,
            long size,
            int durationMs,
            String checksum) {}
}
