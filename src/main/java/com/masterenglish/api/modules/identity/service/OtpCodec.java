package com.masterenglish.api.modules.identity.service;

import com.masterenglish.api.config.RegistrationProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

@Service
public class OtpCodec {
    private final String secret;
    private static final SecureRandom RANDOM = new SecureRandom();

    public OtpCodec(RegistrationProperties properties) {
        secret = properties.otpSecret();
    }

    public String generate() {
        return String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1000000));
    }

    public String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public String hashToken(String token) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String hmac(String purpose, UUID id, String otp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of()
                    .formatHex(
                            mac.doFinal(
                                    (purpose + ":" + id + ":" + otp)
                                            .getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean matches(String expected, String purpose, UUID id, String otp) {
        return expected != null
                && MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.US_ASCII),
                        hmac(purpose, id, otp).getBytes(StandardCharsets.US_ASCII));
    }
}
