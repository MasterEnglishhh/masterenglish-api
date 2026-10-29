package com.masterenglish.api.modules.identity.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JDBC operations participate in the same transaction and use explicit lock ordering. */
@Repository
public class RegistrationStore {
    private final JdbcTemplate jdbc;

    public RegistrationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record PendingUser(UUID id, String email, String status, Instant createdAt) {}

    public record Registration(
            UUID id,
            UUID userId,
            UUID challengeId,
            String firstName,
            String lastName,
            String passwordHash,
            Instant requestExpiresAt,
            Instant otpExpiresAt,
            String otpHmac,
            boolean closed) {
        @Override
        public String toString() {
            return "Registration[redacted]";
        }
    }

    public String emailKey(String email) {
        return jdbc.queryForObject("SELECT lower(?)", String.class, email.strip());
    }

    public PendingUser createAndLockUser(String email, Instant now) {
        jdbc.update(
                "INSERT INTO users(id,email,status,created_at,updated_at) VALUES (?,?, 'PENDING_VERIFICATION',?,?) ON CONFLICT (lower(email)) DO NOTHING",
                UUID.randomUUID(),
                email,
                ts(now),
                ts(now));
        return lockUser(email).orElseThrow();
    }

    public Optional<PendingUser> lockUser(String email) {
        return jdbc
                .query(
                        "SELECT id,email,status,created_at FROM users WHERE lower(email)=lower(?) FOR UPDATE",
                        (rs, row) ->
                                new PendingUser(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("email"),
                                        rs.getString("status"),
                                        instant(rs, "created_at")),
                        email)
                .stream()
                .findFirst();
    }

    public PendingUser restartExpiredPendingCycle(PendingUser user, Instant now) {
        // Only a never-verified pending account can be removed, after its seven-day cycle.
        jdbc.update("DELETE FROM registration_requests WHERE user_id=?", user.id());
        jdbc.update("DELETE FROM otp_challenges WHERE user_id=?", user.id());
        jdbc.update(
                "DELETE FROM users WHERE id=? AND status='PENDING_VERIFICATION' AND email_verified_at IS NULL",
                user.id());
        return createAndLockUser(user.email(), now);
    }

    public void invalidateOpen(UUID userId, Instant now) {
        jdbc.update(
                "UPDATE registration_requests SET invalidated_at=?,updated_at=?,pending_password_hash=NULL WHERE user_id=? AND completed_at IS NULL AND invalidated_at IS NULL",
                ts(now),
                ts(now),
                userId);
        jdbc.update(
                "UPDATE otp_challenges SET invalidated_at=?,otp_hmac=NULL WHERE user_id=? AND purpose='EMAIL_VERIFICATION' AND consumed_at IS NULL AND invalidated_at IS NULL",
                ts(now),
                userId);
    }

    public void insertChallenge(UUID id, UUID userId, String hmac, Instant now, Instant expiresAt) {
        jdbc.update(
                "INSERT INTO otp_challenges(id,user_id,purpose,otp_hmac,expires_at,last_sent_at,created_at) VALUES (?,?,'EMAIL_VERIFICATION',?,?,?,?)",
                id,
                userId,
                hmac,
                ts(expiresAt),
                ts(now),
                ts(now));
    }

    public void insertRequest(
            UUID id,
            UUID userId,
            UUID challengeId,
            String firstName,
            String lastName,
            String passwordHash,
            Instant now,
            Instant expiresAt) {
        jdbc.update(
                "INSERT INTO registration_requests(id,user_id,otp_challenge_id,pending_first_name,pending_last_name,pending_password_hash,expires_at,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
                id,
                userId,
                challengeId,
                firstName,
                lastName,
                passwordHash,
                ts(expiresAt),
                ts(now),
                ts(now));
    }

    public Optional<Registration> find(UUID id, UUID userId) {
        List<Registration> rows =
                jdbc.query(
                        "SELECT r.*,c.expires_at AS otp_expires_at,c.otp_hmac,c.consumed_at,c.invalidated_at AS otp_invalidated_at FROM registration_requests r JOIN otp_challenges c ON c.id=r.otp_challenge_id AND c.user_id=r.user_id AND c.purpose='EMAIL_VERIFICATION' WHERE r.id=? AND r.user_id=?",
                        (rs, row) ->
                                new Registration(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("user_id", UUID.class),
                                        rs.getObject("otp_challenge_id", UUID.class),
                                        rs.getString("pending_first_name"),
                                        rs.getString("pending_last_name"),
                                        rs.getString("pending_password_hash"),
                                        instant(rs, "expires_at"),
                                        instant(rs, "otp_expires_at"),
                                        rs.getString("otp_hmac"),
                                        rs.getTimestamp("completed_at") != null
                                                || rs.getTimestamp("invalidated_at") != null
                                                || rs.getTimestamp("consumed_at") != null
                                                || rs.getTimestamp("otp_invalidated_at") != null),
                        id,
                        userId);
        return rows.stream().findFirst();
    }

    public void replaceChallenge(
            Registration request, UUID challengeId, String hmac, Instant now, Instant expiresAt) {
        jdbc.update(
                "UPDATE otp_challenges SET invalidated_at=?,otp_hmac=NULL WHERE id=? AND consumed_at IS NULL AND invalidated_at IS NULL",
                ts(now),
                request.challengeId());
        insertChallenge(challengeId, request.userId(), hmac, now, expiresAt);
        jdbc.update(
                "UPDATE registration_requests SET otp_challenge_id=?,updated_at=? WHERE id=?",
                challengeId,
                ts(now),
                request.id());
    }

    public void recordWrongCode(UUID challengeId) {
        jdbc.update(
                "UPDATE otp_challenges SET attempt_count=LEAST(attempt_count+1,5) WHERE id=?",
                challengeId);
    }

    public void complete(Registration request, Instant now) {
        jdbc.update(
                "UPDATE otp_challenges SET consumed_at=?,otp_hmac=NULL WHERE id=?",
                ts(now),
                request.challengeId());
        int activated =
                jdbc.update(
                        "UPDATE users SET first_name=?,last_name=?,password_hash=?,email_verified_at=?,status='ACTIVE',updated_at=?,version=version+1 WHERE id=? AND status='PENDING_VERIFICATION' AND email_verified_at IS NULL",
                        request.firstName(),
                        request.lastName(),
                        request.passwordHash(),
                        ts(now),
                        ts(now),
                        request.userId());
        if (activated != 1)
            throw new IllegalStateException("Registration activation state changed.");
        jdbc.update("DELETE FROM user_roles WHERE user_id=?", request.userId());
        if (jdbc.update(
                        "INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code='LEARNER'",
                        request.userId())
                != 1) throw new IllegalStateException("LEARNER role is not configured.");
        jdbc.update(
                "UPDATE registration_requests SET completed_at=?,updated_at=?,pending_password_hash=NULL WHERE id=?",
                ts(now),
                ts(now),
                request.id());
        invalidateOpen(request.userId(), now);
    }

    private static Timestamp ts(Instant value) {
        return Timestamp.from(value);
    }

    private static Instant instant(ResultSet rs, String field) throws SQLException {
        return rs.getTimestamp(field).toInstant();
    }
}
