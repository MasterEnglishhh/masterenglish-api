package com.masterenglish.api.modules.identity.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The account row must be locked before changing a request or its OTP. */
@Repository
public class PasswordChangeStore {

    private final JdbcTemplate jdbc;

    public PasswordChangeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void issue(
            UUID id,
            UUID userId,
            UUID sessionId,
            int authVersion,
            String passwordHash,
            String otpHmac,
            Instant now,
            Instant expiresAt) {
        invalidateOpen(userId, now);
        jdbc.update(
                "INSERT INTO otp_challenges(id,user_id,purpose,otp_hmac,expires_at,last_sent_at,created_at) VALUES (?,?,'PASSWORD_CHANGE',?,?,?,?)",
                id,
                userId,
                otpHmac,
                ts(expiresAt),
                ts(now),
                ts(now));
        jdbc.update(
                """
                INSERT INTO password_change_requests(id,user_id,session_id,otp_challenge_id,
                    auth_version_at_request,pending_password_hash,created_at,expires_at)
                VALUES (?,?,?,?,?,?,?,?)
                """,
                id,
                userId,
                sessionId,
                id,
                authVersion,
                passwordHash,
                ts(now),
                ts(expiresAt));
    }

    public Optional<Pending> find(
            UUID id, UUID userId, UUID sessionId, int authVersion, Instant now) {
        return jdbc
                .query(
                        """
                        SELECT r.pending_password_hash, r.expires_at, c.otp_hmac
                        FROM password_change_requests r JOIN otp_challenges c ON c.id = r.otp_challenge_id
                        WHERE r.id = ? AND r.user_id = ? AND r.session_id = ?
                          AND r.auth_version_at_request = ?
                          AND r.completed_at IS NULL AND c.consumed_at IS NULL
                          AND ((r.invalidated_at IS NULL AND c.invalidated_at IS NULL)
                               OR (r.expires_at <= ? AND r.invalidated_at >= r.expires_at))
                        """,
                        (rs, row) ->
                                new Pending(
                                        rs.getString("pending_password_hash"),
                                        rs.getString("otp_hmac"),
                                        rs.getTimestamp("expires_at").toInstant()),
                        id,
                        userId,
                        sessionId,
                        authVersion,
                        ts(now))
                .stream()
                .findFirst();
    }

    public void resend(UUID id, String hmac, Instant now) {
        jdbc.update(
                "UPDATE otp_challenges SET otp_hmac=?,last_sent_at=? WHERE id=?",
                hmac,
                ts(now),
                id);
    }

    public void wrongCode(UUID id) {
        jdbc.update(
                "UPDATE otp_challenges SET attempt_count=LEAST(attempt_count+1,5) WHERE id=?", id);
    }

    public void complete(UUID id, Instant now) {
        jdbc.update(
                "UPDATE otp_challenges SET consumed_at=?,otp_hmac=NULL WHERE id=?", ts(now), id);
        jdbc.update(
                "UPDATE password_change_requests SET completed_at=?,pending_password_hash=NULL WHERE id=?",
                ts(now),
                id);
    }

    public void invalidateDelivery(UUID id, String hmac, Instant now) {
        int changed =
                jdbc.update(
                        "UPDATE otp_challenges SET invalidated_at=?,otp_hmac=NULL WHERE id=? AND otp_hmac=? AND consumed_at IS NULL AND invalidated_at IS NULL",
                        ts(now),
                        id,
                        hmac);
        if (changed > 0) {
            jdbc.update(
                    "UPDATE password_change_requests SET invalidated_at=?,pending_password_hash=NULL WHERE id=?",
                    ts(now),
                    id);
        }
    }

    public void invalidateOpen(UUID userId, Instant now) {
        jdbc.update(
                "UPDATE otp_challenges SET invalidated_at=?,otp_hmac=NULL WHERE user_id=? AND purpose='PASSWORD_CHANGE' AND consumed_at IS NULL AND invalidated_at IS NULL",
                ts(now),
                userId);
        jdbc.update(
                "UPDATE password_change_requests SET invalidated_at=?,pending_password_hash=NULL WHERE user_id=? AND completed_at IS NULL AND invalidated_at IS NULL",
                ts(now),
                userId);
    }

    public List<UUID> lockUsersWithExpiredRequests(Instant now, int batchSize) {
        return jdbc.queryForList(
                """
                SELECT u.id FROM password_change_requests r JOIN users u ON u.id = r.user_id
                WHERE r.expires_at <= ? AND r.completed_at IS NULL AND r.invalidated_at IS NULL
                ORDER BY r.expires_at, r.id LIMIT ?
                FOR UPDATE OF u SKIP LOCKED
                """,
                UUID.class,
                ts(now),
                batchSize);
    }

    public void expireOpenForUser(UUID userId, Instant now) {
        jdbc.update(
                """
                WITH expired AS (
                    UPDATE password_change_requests SET invalidated_at = ?, pending_password_hash = NULL
                    WHERE user_id = ? AND expires_at <= ? AND completed_at IS NULL AND invalidated_at IS NULL
                    RETURNING otp_challenge_id
                )
                UPDATE otp_challenges SET invalidated_at = ?, otp_hmac = NULL
                WHERE id IN (SELECT otp_challenge_id FROM expired)
                  AND consumed_at IS NULL AND invalidated_at IS NULL
                """,
                ts(now),
                userId,
                ts(now),
                ts(now));
    }

    private static Timestamp ts(Instant value) {
        return Timestamp.from(value);
    }

    public record Pending(String passwordHash, String otpHmac, Instant expiresAt) {}
}
