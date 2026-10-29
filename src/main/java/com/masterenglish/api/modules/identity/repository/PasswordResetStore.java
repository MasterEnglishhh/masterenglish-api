package com.masterenglish.api.modules.identity.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PasswordResetStore {
    private final JdbcTemplate jdbc;

    public PasswordResetStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Account(
            UUID id,
            String email,
            String status,
            int authVersion,
            String passwordHash,
            boolean verified) {
        public boolean eligible() {
            return status.equals("ACTIVE") && verified;
        }

        @Override
        public String toString() {
            return "ResetAccount[redacted]";
        }
    }

    public record Challenge(UUID id, String hmac, Instant expiresAt, boolean closed) {
        @Override
        public String toString() {
            return "ResetChallenge[redacted]";
        }
    }

    public record Grant(UUID id, int authVersion, Instant expiresAt, boolean closed) {}

    public Optional<Account> lockUser(String email) {
        return jdbc
                .query(
                        "SELECT id,email,status,auth_version,password_hash,email_verified_at FROM users WHERE lower(email)=lower(?) FOR UPDATE",
                        (rs, row) ->
                                new Account(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("email"),
                                        rs.getString("status"),
                                        rs.getInt("auth_version"),
                                        rs.getString("password_hash"),
                                        rs.getTimestamp("email_verified_at") != null),
                        email)
                .stream()
                .findFirst();
    }

    public void issue(Account account, UUID challengeId, String hmac, Instant now) {
        jdbc.update(
                "UPDATE otp_challenges SET invalidated_at=?,otp_hmac=NULL WHERE user_id=? AND purpose='PASSWORD_RESET' AND consumed_at IS NULL AND invalidated_at IS NULL",
                ts(now),
                account.id());
        jdbc.update(
                "INSERT INTO otp_challenges(id,user_id,purpose,otp_hmac,expires_at,last_sent_at,created_at) VALUES (?,?,'PASSWORD_RESET',?,?,?,?)",
                challengeId,
                account.id(),
                hmac,
                ts(now.plusSeconds(300)),
                ts(now),
                ts(now));
    }

    public Optional<Challenge> challenge(UUID id, UUID userId) {
        return jdbc
                .query(
                        "SELECT id,otp_hmac,expires_at,consumed_at,invalidated_at FROM otp_challenges WHERE id=? AND user_id=? AND purpose='PASSWORD_RESET'",
                        (rs, row) ->
                                new Challenge(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("otp_hmac"),
                                        rs.getTimestamp("expires_at").toInstant(),
                                        rs.getTimestamp("consumed_at") != null
                                                || rs.getTimestamp("invalidated_at") != null),
                        id,
                        userId)
                .stream()
                .findFirst();
    }

    public void wrongCode(UUID id) {
        jdbc.update(
                "UPDATE otp_challenges SET attempt_count=LEAST(attempt_count+1,5) WHERE id=?", id);
    }

    public void invalidateFailedDelivery(UUID id, Instant now) {
        jdbc.update(
                "UPDATE otp_challenges SET invalidated_at=?,otp_hmac=NULL WHERE id=? AND purpose='PASSWORD_RESET' AND consumed_at IS NULL AND invalidated_at IS NULL",
                ts(now),
                id);
    }

    public void issueGrant(Account account, UUID challengeId, String tokenHash, Instant now) {
        jdbc.update(
                "UPDATE otp_challenges SET consumed_at=?,otp_hmac=NULL WHERE id=?",
                ts(now),
                challengeId);
        jdbc.update(
                "UPDATE password_reset_grants SET invalidated_at=?,grant_hash=NULL WHERE user_id=? AND used_at IS NULL AND invalidated_at IS NULL",
                ts(now),
                account.id());
        jdbc.update(
                "INSERT INTO password_reset_grants(id,user_id,otp_challenge_id,grant_hash,auth_version_at_issue,issued_at,expires_at) VALUES (?,?,?,?,?,?,?)",
                UUID.randomUUID(),
                account.id(),
                challengeId,
                tokenHash,
                account.authVersion(),
                ts(now),
                ts(now.plusSeconds(300)));
    }

    public Optional<Grant> grant(UUID userId, String hash) {
        return jdbc
                .query(
                        "SELECT id,auth_version_at_issue,expires_at,used_at,invalidated_at FROM password_reset_grants WHERE user_id=? AND grant_hash=?",
                        (rs, row) ->
                                new Grant(
                                        rs.getObject("id", UUID.class),
                                        rs.getInt("auth_version_at_issue"),
                                        rs.getTimestamp("expires_at").toInstant(),
                                        rs.getTimestamp("used_at") != null
                                                || rs.getTimestamp("invalidated_at") != null),
                        userId,
                        hash)
                .stream()
                .findFirst();
    }

    public void complete(Account account, Grant grant, String passwordHash, Instant now) {
        jdbc.update(
                "UPDATE password_reset_grants SET used_at=?,grant_hash=NULL WHERE id=?",
                ts(now),
                grant.id());
        jdbc.update(
                "UPDATE users SET password_hash=?,password_changed_at=?,auth_version=auth_version+1,version=version+1,updated_at=? WHERE id=?",
                passwordHash,
                ts(now),
                ts(now),
                account.id());
        jdbc.update(
                "UPDATE auth_sessions SET revoked_at=?,revoke_reason='PASSWORD_RESET' WHERE user_id=? AND revoked_at IS NULL",
                ts(now),
                account.id());
        jdbc.update(
                "UPDATE refresh_tokens SET revoked_at=? WHERE session_id IN (SELECT id FROM auth_sessions WHERE user_id=?) AND revoked_at IS NULL",
                ts(now),
                account.id());
        jdbc.update(
                "UPDATE password_reset_grants SET invalidated_at=?,grant_hash=NULL WHERE user_id=? AND used_at IS NULL AND invalidated_at IS NULL",
                ts(now),
                account.id());
        jdbc.update(
                "UPDATE otp_challenges SET invalidated_at=?,otp_hmac=NULL WHERE user_id=? AND consumed_at IS NULL AND invalidated_at IS NULL",
                ts(now),
                account.id());
        jdbc.update(
                "UPDATE registration_requests SET invalidated_at=?,updated_at=?,pending_password_hash=NULL WHERE user_id=? AND completed_at IS NULL AND invalidated_at IS NULL",
                ts(now),
                ts(now),
                account.id());
    }

    private static Timestamp ts(Instant value) {
        return Timestamp.from(value);
    }
}
