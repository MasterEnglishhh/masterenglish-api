package com.masterenglish.api.modules.identity.repository.fragment;

import com.masterenglish.api.modules.identity.repository.UserRepository.PendingUser;
import com.masterenglish.api.modules.identity.repository.UserRepository.Registration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RegistrationOperations extends AccountChallengeOperations {

    String REGISTRATION_PURPOSE = "EMAIL_VERIFICATION";

    default String emailKey(String email) {
        return normalizeEmail(email.strip());
    }

    @Query(value = "SELECT lower(:email)", nativeQuery = true)
    String normalizeEmail(@Param("email") String email);

    default PendingUser createAndLockUser(String email, Instant now) {
        insertPendingUser(UUID.randomUUID(), email, now);
        return lockRegistrationUser(email).orElseThrow();
    }

    @Modifying
    @Query(
            value =
                    """
                    INSERT INTO users(id, email, status, created_at, updated_at)
                    VALUES (:id, :email, 'PENDING_VERIFICATION', :now, :now)
                    ON CONFLICT (lower(email)) DO NOTHING
                    """,
            nativeQuery = true)
    void insertPendingUser(
            @Param("id") UUID id, @Param("email") String email, @Param("now") Instant now);

    default Optional<PendingUser> lockRegistrationUser(String email) {
        return findPendingUserForUpdate(email)
                .map(
                        row ->
                                new PendingUser(
                                        row.getId(),
                                        row.getEmail(),
                                        row.getStatus(),
                                        row.getCreatedAt()));
    }

    @Query(
            value =
                    """
                    SELECT id, email, status, created_at AS "createdAt"
                    FROM users WHERE lower(email) = lower(:email) FOR UPDATE
                    """,
            nativeQuery = true)
    Optional<PendingUserRow> findPendingUserForUpdate(@Param("email") String email);

    default PendingUser restartExpiredPendingCycle(PendingUser user, Instant now) {
        deleteRegistrationRequests(user.id());
        deleteOtpChallenges(user.id());
        deletePendingUser(user.id());
        return createAndLockUser(user.email(), now);
    }

    @Modifying
    @Query(value = "DELETE FROM registration_requests WHERE user_id = :userId", nativeQuery = true)
    void deleteRegistrationRequests(@Param("userId") UUID userId);

    @Modifying
    @Query(value = "DELETE FROM otp_challenges WHERE user_id = :userId", nativeQuery = true)
    void deleteOtpChallenges(@Param("userId") UUID userId);

    @Modifying
    @Query(
            value =
                    """
                    DELETE FROM users
                    WHERE id = :userId AND status = 'PENDING_VERIFICATION' AND email_verified_at IS NULL
                    """,
            nativeQuery = true)
    void deletePendingUser(@Param("userId") UUID userId);

    default void invalidateOpen(UUID userId, Instant now) {
        invalidateOpenRegistrationRequests(userId, now);
        invalidateOpenOtpChallenges(userId, REGISTRATION_PURPOSE, now);
    }

    default void insertChallenge(
            UUID id, UUID userId, String hmac, Instant now, Instant expiresAt) {
        insertOtpChallenge(id, userId, REGISTRATION_PURPOSE, hmac, now, expiresAt);
    }

    @Modifying
    @Query(
            value =
                    """
                    INSERT INTO registration_requests(id, user_id, otp_challenge_id,
                        pending_first_name, pending_last_name, pending_password_hash,
                        expires_at, created_at, updated_at)
                    VALUES (:id, :userId, :challengeId, :firstName, :lastName, :passwordHash,
                        :expiresAt, :now, :now)
                    """,
            nativeQuery = true)
    void insertRequest(
            @Param("id") UUID id,
            @Param("userId") UUID userId,
            @Param("challengeId") UUID challengeId,
            @Param("firstName") String firstName,
            @Param("lastName") String lastName,
            @Param("passwordHash") String passwordHash,
            @Param("now") Instant now,
            @Param("expiresAt") Instant expiresAt);

    default Optional<Registration> find(UUID id, UUID userId) {
        return findRegistration(id, userId)
                .map(
                        row ->
                                new Registration(
                                        row.getId(),
                                        row.getUserId(),
                                        row.getChallengeId(),
                                        row.getFirstName(),
                                        row.getLastName(),
                                        row.getPasswordHash(),
                                        row.getRequestExpiresAt(),
                                        row.getOtpExpiresAt(),
                                        row.getOtpHmac(),
                                        row.getClosed()));
    }

    @Query(
            value =
                    """
                    SELECT r.id, r.user_id AS "userId", r.otp_challenge_id AS "challengeId",
                           r.pending_first_name AS "firstName", r.pending_last_name AS "lastName",
                           r.pending_password_hash AS "passwordHash", r.expires_at AS "requestExpiresAt",
                           c.expires_at AS "otpExpiresAt", c.otp_hmac AS "otpHmac",
                           (r.completed_at IS NOT NULL OR r.invalidated_at IS NOT NULL
                              OR c.consumed_at IS NOT NULL OR c.invalidated_at IS NOT NULL) AS closed
                    FROM registration_requests r
                    JOIN otp_challenges c ON c.id = r.otp_challenge_id AND c.user_id = r.user_id
                      AND c.purpose = 'EMAIL_VERIFICATION'
                    WHERE r.id = :id AND r.user_id = :userId
                    """,
            nativeQuery = true)
    Optional<RegistrationRow> findRegistration(@Param("id") UUID id, @Param("userId") UUID userId);

    default void replaceChallenge(
            Registration request, UUID challengeId, String hmac, Instant now, Instant expiresAt) {
        invalidateOpenOtpChallenge(request.challengeId(), REGISTRATION_PURPOSE, now);
        insertChallenge(challengeId, request.userId(), hmac, now, expiresAt);
        updateRegistrationChallenge(request.id(), challengeId, now);
    }

    @Modifying
    @Query(
            value =
                    "UPDATE registration_requests SET otp_challenge_id = :challengeId, updated_at = :now WHERE id = :id",
            nativeQuery = true)
    void updateRegistrationChallenge(
            @Param("id") UUID id,
            @Param("challengeId") UUID challengeId,
            @Param("now") Instant now);

    default void completeRegistration(Registration request, Instant now) {
        consumeOtpChallenge(request.challengeId(), now);
        if (activateRegistrationUser(
                        request.userId(),
                        request.firstName(),
                        request.lastName(),
                        request.passwordHash(),
                        now)
                != 1) {
            throw new IllegalStateException("Registration activation state changed.");
        }
        deleteRegistrationUserRoles(request.userId());
        if (insertLearnerRole(request.userId()) != 1) {
            throw new IllegalStateException("LEARNER role is not configured.");
        }
        markRegistrationCompleted(request.id(), now);
        invalidateOpen(request.userId(), now);
    }

    @Modifying
    @Query(
            value =
                    """
                    UPDATE users SET first_name = :firstName, last_name = :lastName,
                        password_hash = :passwordHash, email_verified_at = :now, status = 'ACTIVE',
                        updated_at = :now, version = version + 1
                    WHERE id = :userId AND status = 'PENDING_VERIFICATION' AND email_verified_at IS NULL
                    """,
            nativeQuery = true)
    int activateRegistrationUser(
            @Param("userId") UUID userId,
            @Param("firstName") String firstName,
            @Param("lastName") String lastName,
            @Param("passwordHash") String passwordHash,
            @Param("now") Instant now);

    @Modifying
    @Query(value = "DELETE FROM user_roles WHERE user_id = :userId", nativeQuery = true)
    void deleteRegistrationUserRoles(@Param("userId") UUID userId);

    @Modifying
    @Query(
            value =
                    "INSERT INTO user_roles(user_id, role_id) SELECT :userId, id FROM roles WHERE code = 'LEARNER'",
            nativeQuery = true)
    int insertLearnerRole(@Param("userId") UUID userId);

    @Modifying
    @Query(
            value =
                    """
                    UPDATE registration_requests
                    SET completed_at = :now, updated_at = :now, pending_password_hash = NULL WHERE id = :id
                    """,
            nativeQuery = true)
    void markRegistrationCompleted(@Param("id") UUID id, @Param("now") Instant now);

    interface PendingUserRow {
        UUID getId();

        String getEmail();

        String getStatus();

        Instant getCreatedAt();
    }

    interface RegistrationRow {
        UUID getId();

        UUID getUserId();

        UUID getChallengeId();

        String getFirstName();

        String getLastName();

        String getPasswordHash();

        Instant getRequestExpiresAt();

        Instant getOtpExpiresAt();

        String getOtpHmac();

        boolean getClosed();
    }
}
