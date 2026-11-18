package com.masterenglish.api.modules.identity.repository.fragment;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountChallengeOperations {

    @Modifying
    @Query(
            value =
                    """
                    INSERT INTO otp_challenges(id, user_id, purpose, otp_hmac, expires_at, last_sent_at, created_at)
                    VALUES (:id, :userId, :purpose, :hmac, :expiresAt, :now, :now)
                    """,
            nativeQuery = true)
    void insertOtpChallenge(
            @Param("id") UUID id,
            @Param("userId") UUID userId,
            @Param("purpose") String purpose,
            @Param("hmac") String hmac,
            @Param("now") Instant now,
            @Param("expiresAt") Instant expiresAt);

    @Modifying
    @Query(
            value =
                    """
                    UPDATE otp_challenges SET invalidated_at = :now, otp_hmac = NULL
                    WHERE user_id = :userId AND purpose = :purpose
                      AND consumed_at IS NULL AND invalidated_at IS NULL
                    """,
            nativeQuery = true)
    void invalidateOpenOtpChallenges(
            @Param("userId") UUID userId,
            @Param("purpose") String purpose,
            @Param("now") Instant now);

    @Modifying
    @Query(
            value =
                    """
                    UPDATE otp_challenges SET invalidated_at = :now, otp_hmac = NULL
                    WHERE id = :id AND purpose = :purpose
                      AND consumed_at IS NULL AND invalidated_at IS NULL
                    """,
            nativeQuery = true)
    void invalidateOpenOtpChallenge(
            @Param("id") UUID id, @Param("purpose") String purpose, @Param("now") Instant now);

    @Modifying
    @Query(
            value = "UPDATE otp_challenges SET consumed_at = :now, otp_hmac = NULL WHERE id = :id",
            nativeQuery = true)
    void consumeOtpChallenge(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying
    @Query(
            value =
                    "UPDATE otp_challenges SET attempt_count = least(attempt_count + 1, 5) WHERE id = :id",
            nativeQuery = true)
    void recordWrongCode(@Param("id") UUID id);

    @Modifying
    @Query(
            value =
                    """
                    UPDATE registration_requests
                    SET invalidated_at = :now, updated_at = :now, pending_password_hash = NULL
                    WHERE user_id = :userId AND completed_at IS NULL AND invalidated_at IS NULL
                    """,
            nativeQuery = true)
    void invalidateOpenRegistrationRequests(
            @Param("userId") UUID userId, @Param("now") Instant now);
}
