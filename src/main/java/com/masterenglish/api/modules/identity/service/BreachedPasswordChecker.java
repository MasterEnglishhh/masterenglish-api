package com.masterenglish.api.modules.identity.service;

import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.config.RegistrationProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import org.springframework.stereotype.Service;

/** Only a five-character hash prefix leaves this server; never a password or full hash. */
@Service
public class BreachedPasswordChecker {
    private final RegistrationProperties properties;
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public BreachedPasswordChecker(RegistrationProperties properties) {
        this.properties = properties;
    }

    public void rejectIfBreached(String password) {
        if (!properties.breachCheckEnabled()) return;
        String hash;
        try {
            hash =
                    HexFormat.of()
                            .withUpperCase()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-1")
                                            .digest(password.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        HttpRequest request =
                HttpRequest.newBuilder(
                                URI.create(
                                        "https://api.pwnedpasswords.com/range/"
                                                + hash.substring(0, 5)))
                        .timeout(Duration.ofSeconds(5))
                        .header("Add-Padding", "true")
                        .header("User-Agent", "MasterEnglish-Password-Check")
                        .GET()
                        .build();
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
                if (response.statusCode() != 200) throw unavailable();
                byte[] bytes = body.readNBytes(200001);
                if (bytes.length > 200000) throw unavailable();
                if (matchesRange(hash.substring(5), new String(bytes, StandardCharsets.US_ASCII))) {
                    throw new BusinessException(
                            ErrorCode.PASSWORD_REJECTED,
                            "Mật khẩu này đã xuất hiện trong dữ liệu bị lộ. Vui lòng chọn mật khẩu khác.");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException e) {
            throw unavailable();
        }
    }

    static boolean matchesRange(String suffix, String response) {
        for (String line : response.split("\\R")) {
            String[] parts = line.strip().split(":", 2);
            if (parts.length == 2
                    && parts[0].equalsIgnoreCase(suffix)
                    && parts[1].matches("[1-9][0-9]*")) return true;
        }
        return false;
    }

    private static BusinessException unavailable() {
        return new BusinessException(
                ErrorCode.SERVICE_UNAVAILABLE,
                "Chưa thể kiểm tra độ an toàn của mật khẩu. Vui lòng thử lại sau.");
    }
}
