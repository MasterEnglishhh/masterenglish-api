package com.masterenglish.api.modules.identity.repository.fragment;

import com.masterenglish.api.modules.identity.repository.UserRepository.Account;
import com.masterenglish.api.modules.identity.repository.UserRepository.Challenge;
import com.masterenglish.api.modules.identity.repository.UserRepository.Grant;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PasswordResetOperations extends AccountChallengeOperations {

    String RESET_PURPOSE = "PASSWORD_RESET";

    default Optional<Account> lockPasswordResetUser(String email) {
        return findResetAccountForUpdate(email)
                .map(
                        row ->
                                new Account(
                                        row.getId(),
                                        row.getEmail(),
                                        row.getStatus(),
                                        row.getAuthVersion(),
                                        row.getPasswordHash(),
                                        row.getVerified()));
    }

    @Query(
            value =
                    """
                    SELECT id, email, status, auth_version AS "authVersion",
                           password_hash AS "passwordHash", (email_verified_at IS NOT NULL) AS verified
                    FROM users WHERE lower(email) = lower(:email) FOR UPDATE
                    """,
            nativeQuery = true)
    Optional<ResetAccountRow> findResetAccountForUpdate(@Param("email") String email);

    default void issue(Account account, UUID challengeId, String hmac, Instant now) {
        invalidateOpenOtpChallenges(account.id(), RESET_PURPOSE, now);
        insertOtpChallenge(
                challengeId, account.id(), RESET_PURPOSE, hmac, now, now.plusSeconds(300));
    }

    default Optional<Challenge> challenge(UUID id, UUID userId) {
        return findResetChallenge(id, userId)
                .map(
                        row ->
                                new Challenge(
                                        row.getId(),
                                        row.getHmac(),
                                        row.getExpiresAt(),
                                        row.getClosed()));
    }

    @Query(
            value =
                    """
                    SELECT id, otp_hmac AS hmac, expires_at AS "expiresAt",
                           (consumed_at IS NOT NULL OR invalidated_at IS NOT NULL) AS closed
                    FROM otp_challenges
                    WHERE id = :id AND user_id = :userId AND purpose = 'PASSWORD_RESET'
                    """,
            nativeQuery = true)
    Optional<ResetChallengeRow> findResetChallenge(
            @Param("id") UUID id, @Param("userId") UUID userId);

    default void invalidateFailedDelivery(UUID id, Instant now) {
        invalidateOpenOtpChallenge(id, RESET_PURPOSE, now);
    }

    default void issueGrant(Account account, UUID challengeId, String tokenHash, Instant now) {
        consumeOtpChallenge(challengeId, now);
        invalidateOpenResetGrants(account.id(), now);
        insertResetGrant(
                UUID.randomUUID(),
                account.id(),
                challengeId,
                tokenHash,
                account.authVersion(),
                now,
                now.plusSeconds(300));
    }

    @Modifying
    @Query(
            value =
                    """
                    UPDATE password_reset_grants SET invalidated_at = :now, grant_hash = NULL
                    WHERE user_id = :userId AND used_at IS NULL AND invalidated_at IS NULL
                    """,
            nativeQuery = true)
    void invalidateOpenResetGrants(@Param("userId") UUID userId, @Param("now") Instant now);

    @Modifying
    @Query(
            value =
                    """
                    INSERT INTO password_reset_grants(id, user_id, otp_challenge_id, grant_hash,
                        auth_version_at_issue, issued_at, expires_at)
                    VALUES (:id, :userId, :challengeId, :grantHash, :authVersion, :now, :expiresAt)
                    """,
            nativeQuery = true)
    void insertResetGrant(
            @Param("id") UUID id,
            @Param("userId") UUID userId,
            @Param("challengeId") UUID challengeId,
            @Param("grantHash") String grantHash,
            @Param("authVersion") int authVersion,
            @Param("now") Instant now,
            @Param("expiresAt") Instant expiresAt);

    default Optional<Grant> grant(UUID userId, String hash) {
        return findResetGrant(userId, hash)
                .map(
                        row ->
                                new Grant(
                                        row.getId(),
                                        row.getAuthVersion(),
                                        row.getExpiresAt(),
                                        row.getClosed()));
    }

    @Query(
            value =
                    """
                    SELECT id, auth_version_at_issue AS "authVersion", expires_at AS "expiresAt",
                           (used_at IS NOT NULL OR invalidated_at IS NOT NULL) AS closed
                    FROM password_reset_grants WHERE user_id = :userId AND grant_hash = :hash
                    """,
            nativeQuery = true)
    Optional<ResetGrantRow> findResetGrant(
            @Param("userId") UUID userId, @Param("hash") String hash);

    default void completePasswordReset(
            Account account, Grant grant, String passwordHash, Instant now) {
        markResetGrantUsed(grant.id(), now);
        UUID userId = account.id();
        updateResetPassword(userId, passwordHash, now);
        revokeResetSessions(userId, now);
        revokeResetRefreshTokens(userId, now);
        invalidateOpenResetGrants(userId, now);
        invalidateAllOpenOtpChallenges(userId, now);
        invalidateOpenRegistrationRequests(userId, now);
    }

    @Modifying
    @Query(
            value =
                    "UPDATE password_reset_grants SET used_at = :now, grant_hash = NULL WHERE id = :id",
            nativeQuery = true)
    void markResetGrantUsed(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying
    @Query(
            value =
                    """
                    UPDATE users SET password_hash = :passwordHash, password_changed_at = :now,
                        auth_version = auth_version + 1, version = version + 1, updated_at = :now
                    WHERE id = :userId
                    """,
            nativeQuery = true)
    void updateResetPassword(
            @Param("userId") UUID userId,
            @Param("passwordHash") String passwordHash,
            @Param("now") Instant now);

    @Modifying
    @Query(
            value =
                    """
                    UPDATE auth_sessions SET revoked_at = :now, revoke_reason = 'PASSWORD_RESET'
                    WHERE user_id = :userId AND revoked_at IS NULL
                    """,
            nativeQuery = true)
    void revokeResetSessions(@Param("userId") UUID userId, @Param("now") Instant now);

    @Modifying
    @Query(
            value =
                    """
                    UPDATE refresh_tokens SET revoked_at = :now
                    WHERE revoked_at IS NULL AND session_id IN (SELECT id FROM auth_sessions WHERE user_id = :userId)
                    """,
            nativeQuery = true)
    void revokeResetRefreshTokens(@Param("userId") UUID userId, @Param("now") Instant now);

    @Modifying
    @Query(
            value =
                    """
                    UPDATE otp_challenges SET invalidated_at = :now, otp_hmac = NULL
                    WHERE user_id = :userId AND consumed_at IS NULL AND invalidated_at IS NULL
                    """,
            nativeQuery = true)
    void invalidateAllOpenOtpChallenges(@Param("userId") UUID userId, @Param("now") Instant now);

    interface ResetAccountRow {
        UUID getId();

        String getEmail();

        String getStatus();

        int getAuthVersion();

        String getPasswordHash();

        boolean getVerified();
    }

    interface ResetChallengeRow {
        UUID getId();

        String getHmac();

        Instant getExpiresAt();

        boolean getClosed();
    }

    interface ResetGrantRow {
        UUID getId();

        int getAuthVersion();

        Instant getExpiresAt();

        boolean getClosed();
    }
}
