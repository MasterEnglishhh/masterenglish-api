package com.masterenglish.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.security.RoleCode;
import com.masterenglish.api.modules.identity.entity.AuthRateLimit;
import com.masterenglish.api.modules.identity.entity.AuthRateLimitEvent;
import com.masterenglish.api.modules.identity.entity.AuthSession;
import com.masterenglish.api.modules.identity.entity.RevokeReason;
import com.masterenglish.api.modules.identity.entity.Role;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.entity.UserStatus;
import com.masterenglish.api.modules.identity.repository.AuthRateLimitEventRepository;
import com.masterenglish.api.modules.identity.repository.AuthRateLimitRepository;
import com.masterenglish.api.modules.identity.repository.AuthSessionRepository;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import com.masterenglish.api.modules.identity.security.UserPrincipal;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class IdentityDatabaseTests {

    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired UserRepository users;
    @Autowired AuthSessionRepository authSessions;
    @Autowired AuthRateLimitRepository rateLimits;
    @Autowired AuthRateLimitEventRepository rateLimitEvents;

    @Test
    void mapsMultipleRolesAndLoadsThemForAuthentication() {
        User user = new User("  Learner@Example.com  ", "test-password-hash", "Learner");
        user.addRole(entityManager.find(Role.class, (short) 1));
        user.addRole(entityManager.find(Role.class, (short) 2));
        users.saveAndFlush(user);
        entityManager.clear();

        User loaded = users.findForAuthenticationByEmail("LEARNER@EXAMPLE.COM").orElseThrow();
        assertThat(loaded.getEmail()).isEqualTo("learner@example.com");
        assertThat(loaded.getRoleNames()).containsExactlyInAnyOrder("LEARNER", "CONTENT_ADMIN");
        assertThat(UserPrincipal.create(loaded).getAuthorities())
                .extracting(authority -> authority.getAuthority())
                .containsExactly("ROLE_CONTENT_ADMIN", "ROLE_LEARNER");
        assertThat(users.existsByEmailIgnoreCase("LEARNER@EXAMPLE.COM")).isTrue();
    }

    @Test
    void rejectsEmailDuplicatesWithDifferentCase() {
        insertUser("Learner@example.com");
        assertThatThrownBy(() -> insertUser("learner@EXAMPLE.COM"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void changingPersistedRolesInvalidatesTheAuthenticationVersion() {
        User user = new User("change-role@example.com", "test-hash", "Learner");
        user.addRole(entityManager.find(Role.class, (short) 1));
        user.addRole(entityManager.find(Role.class, (short) 2));
        users.saveAndFlush(user);
        int previous = user.getAuthVersion();
        user.removeRole(RoleCode.CONTENT_ADMIN);
        users.flush();
        assertThat(user.getAuthVersion()).isEqualTo(previous + 1);
        assertThat(user.getRoleNames()).containsExactly("LEARNER");
    }

    @Test
    void profileKeepsPhoneWithoutInvalidatingAuthentication() {
        User user = new User("profile@example.com", "test-hash", "Learner");
        user.addRole(entityManager.find(Role.class, (short) 1));
        users.saveAndFlush(user);
        int authenticationVersion = user.getAuthVersion();
        user.updateProfile("New name", " +84912345678 ", null);
        users.flush();
        entityManager.clear();
        User loaded = users.findById(user.getId()).orElseThrow();
        assertThat(loaded.getPhone()).isEqualTo("+84912345678");
        assertThat(loaded.getAuthVersion()).isEqualTo(authenticationVersion);
    }

    @Test
    void cannotRemoveTheLastRoleFromAnAccount() {
        User user = new User("last-role@example.com", "test-hash", "Learner");
        user.addRole(entityManager.find(Role.class, (short) 1));
        assertThatThrownBy(() -> user.removeRole(RoleCode.LEARNER))
                .isInstanceOf(BusinessException.class);
        assertThat(user.getRoleNames()).containsExactly("LEARNER");
    }

    @Test
    void rejectsDuplicateRoleAssignments() {
        UUID user = insertUser("roles@example.com");
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO user_roles(user_id, role_id) VALUES (?, 1)",
                                        user))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void onlyOneUnrevokedRefreshTokenCanExistPerSession() {
        UUID session = insertSession(insertUser("tokens@example.com"));
        insertToken(session, UUID.randomUUID(), 'a');
        assertThatThrownBy(() -> insertToken(session, UUID.randomUUID(), 'b'))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rotationCanRevokeOldTokenBeforeInsertingReplacement() {
        UUID session = insertSession(insertUser("rotation@example.com"));
        UUID previous = UUID.randomUUID();
        UUID replacement = UUID.randomUUID();
        insertToken(session, previous, 'a');
        jdbc.update(
                """
                UPDATE refresh_tokens SET revoked_at = CURRENT_TIMESTAMP, replaced_by_id = ?
                WHERE id = ?
                """,
                replacement,
                previous);
        insertToken(session, replacement, 'b');
        jdbc.execute("SET CONSTRAINTS fk_refresh_tokens_replacement IMMEDIATE");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM refresh_tokens WHERE session_id = ? AND revoked_at IS NULL",
                                Integer.class,
                                session))
                .isEqualTo(1);
    }

    @Test
    void replacementMustBelongToTheSameSession() {
        UUID user = insertUser("cross-session@example.com");
        UUID firstSession = insertSession(user);
        UUID secondSession = insertSession(user);
        UUID previous = UUID.randomUUID();
        UUID replacement = UUID.randomUUID();
        insertToken(firstSession, previous, 'a');
        insertToken(secondSession, replacement, 'b');
        jdbc.update(
                "UPDATE refresh_tokens SET revoked_at=CURRENT_TIMESTAMP, replaced_by_id=? WHERE id=?",
                replacement,
                previous);
        assertThatThrownBy(
                        () ->
                                jdbc.execute(
                                        "SET CONSTRAINTS fk_refresh_tokens_replacement IMMEDIATE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsConcurrentOpenResetChallenges() {
        UUID user = insertUser("otp@example.com");
        insertChallenge(user);
        assertThatThrownBy(() -> insertChallenge(user))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void verifiedResetGrantMustHaveAnExpiry() {
        UUID user = insertUser("grant@example.com");
        UUID challenge = insertChallenge(user);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        """
                UPDATE password_reset_challenges
                SET verified_at=CURRENT_TIMESTAMP, reset_token_hash=?
                WHERE id=?
                """,
                                        "c".repeat(64),
                                        challenge))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void learningGoalRequiresAValidToeicScore() {
        UUID user = insertUser("goal@example.com");
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        """
                INSERT INTO learning_goals(user_id, target_score, daily_minutes) VALUES (?, 499, 30)
                """,
                                        user))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void learnerHasAtMostOneCurrentGoal() {
        UUID user = insertUser("single-goal@example.com");
        jdbc.update(
                "INSERT INTO learning_goals(user_id,target_score,daily_minutes) VALUES (?,500,30)",
                user);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        """
                INSERT INTO learning_goals(user_id,target_score,daily_minutes) VALUES (?,600,45)
                """,
                                        user))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void v3MigrationAddsFirstAndLastNameAndStatusPendingVerification() {
        User user = new User("unverified@example.com", "test-password-hash", "Huy", "Trịnh Đăng");
        assertThat(user.getStatus()).isEqualTo(UserStatus.PENDING_VERIFICATION);
        assertThat(user.getFirstName()).isEqualTo("Huy");
        assertThat(user.getLastName()).isEqualTo("Trịnh Đăng");
        assertThat(user.getDisplayName()).isEqualTo("Trịnh Đăng Huy");
        assertThat(user.getEmailVerifiedAt()).isNull();
        assertThat(user.isActive()).isFalse();

        user.addRole(entityManager.find(Role.class, (short) 1));
        users.saveAndFlush(user);
        entityManager.clear();

        User loaded = users.findById(user.getId()).orElseThrow();
        assertThat(loaded.getFirstName()).isEqualTo("Huy");
        assertThat(loaded.getLastName()).isEqualTo("Trịnh Đăng");
        assertThat(loaded.getDisplayName()).isEqualTo("Trịnh Đăng Huy");
        assertThat(loaded.getStatus()).isEqualTo(UserStatus.PENDING_VERIFICATION);
        assertThat(loaded.getEmailVerifiedAt()).isNull();
        assertThat(loaded.isActive()).isFalse();

        loaded.markEmailVerified(Instant.now());
        users.saveAndFlush(loaded);
        entityManager.clear();

        User activated = users.findById(user.getId()).orElseThrow();
        assertThat(activated.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(activated.getEmailVerifiedAt()).isNotNull();
        assertThat(activated.isActive()).isTrue();
    }

    @Test
    void sessionLimitRevocationReasonCanBeSaved() {
        User user = new User("session-limit@example.com", "test-hash", "User", "Test");
        user.markEmailVerified(Instant.now());
        user.addRole(entityManager.find(Role.class, (short) 1));
        users.saveAndFlush(user);

        AuthSession session =
                new AuthSession(
                        user, "192.168.1.1", "Mozilla/5.0", Instant.now().plus(Duration.ofDays(7)));
        authSessions.saveAndFlush(session);
        entityManager.clear();

        AuthSession loaded = authSessions.findById(session.getId()).orElseThrow();
        assertThat(loaded.getRevokedAt()).isNull();
        assertThat(loaded.getRevokeReason()).isNull();
        assertThat(loaded.getIpAddress()).isEqualTo("192.168.1.1");

        loaded.revoke(RevokeReason.SESSION_LIMIT);
        authSessions.saveAndFlush(loaded);
        entityManager.clear();

        AuthSession revoked = authSessions.findById(session.getId()).orElseThrow();
        assertThat(revoked.getRevokedAt()).isNotNull();
        assertThat(revoked.getRevokeReason()).isEqualTo(RevokeReason.SESSION_LIMIT);
    }

    @Test
    void authRateLimitsCanBePersistedAndQueriedByScopeAndHash() {
        String scope = "LOGIN_FAILURE_EMAIL";
        String subjectHash = "a".repeat(64);

        AuthRateLimit rateLimit = new AuthRateLimit(scope, subjectHash);
        rateLimit.blockUntil(Instant.now().plus(Duration.ofMinutes(15)));
        rateLimits.saveAndFlush(rateLimit);

        AuthRateLimitEvent event = new AuthRateLimitEvent(rateLimit);
        rateLimitEvents.saveAndFlush(event);
        entityManager.clear();

        AuthRateLimit loaded =
                rateLimits.findByScopeAndSubjectHash(scope, subjectHash).orElseThrow();
        assertThat(loaded.getScope()).isEqualTo(scope);
        assertThat(loaded.getSubjectHash()).isEqualTo(subjectHash);
        assertThat(loaded.isBlocked()).isTrue();
        assertThat(loaded.getBlockedUntil()).isNotNull();

        long count =
                rateLimitEvents.countByRateLimitIdAndOccurredAtAfter(
                        loaded.getId(), Instant.now().minus(Duration.ofMinutes(15)));
        assertThat(count).isEqualTo(1);
    }

    @Test
    void insertIfNotExistsDoesNotThrowOnConflict() {
        String scope = "LOGIN_IP";
        String subjectHash = "b".repeat(64);
        Instant now = Instant.now();

        rateLimits.insertIfNotExists(UUID.randomUUID(), scope, subjectHash, now);
        // Second call with different UUID and same scope/subjectHash should not throw or fail
        rateLimits.insertIfNotExists(UUID.randomUUID(), scope, subjectHash, now);

        AuthRateLimit loaded =
                rateLimits.findByScopeAndSubjectHash(scope, subjectHash).orElseThrow();
        assertThat(loaded.getScope()).isEqualTo(scope);
        assertThat(loaded.getSubjectHash()).isEqualTo(subjectHash);
    }

    @Test
    void userNameTrimmingAndBlankNormalization() {
        User user = new User("blank-name@example.com", "test-hash", "   ", "   ");
        assertThat(user.getFirstName()).isNull();
        assertThat(user.getLastName()).isNull();

        user.updateProfile("  John  ", "   ", null, null);
        assertThat(user.getFirstName()).isEqualTo("John");
        assertThat(user.getLastName()).isNull();

        user.updateProfile("   ", "  Doe  ", null, null);
        assertThat(user.getFirstName()).isNull();
        assertThat(user.getLastName()).isEqualTo("Doe");

        user.addRole(entityManager.find(Role.class, (short) 1));
        users.saveAndFlush(user);
        entityManager.clear();

        User loaded = users.findById(user.getId()).orElseThrow();
        assertThat(loaded.getFirstName()).isNull();
        assertThat(loaded.getLastName()).isEqualTo("Doe");
    }

    @Test
    void userStatusTransitionsAlignConstraints() {
        User user = new User("status-flow@example.com", "test-hash", "Status", "Tester");
        user.addRole(entityManager.find(Role.class, (short) 1));
        users.saveAndFlush(user);

        // LOCKED via changeStatus must throw IllegalArgumentException
        assertThatThrownBy(() -> user.changeStatus(UserStatus.LOCKED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Use lock(Instant until) to lock an account.");

        // Lock properly
        Instant lockUntil = Instant.now().plus(Duration.ofDays(1));
        user.lock(lockUntil);
        users.saveAndFlush(user);
        entityManager.clear();

        User lockedUser = users.findById(user.getId()).orElseThrow();
        assertThat(lockedUser.getStatus()).isEqualTo(UserStatus.LOCKED);
        assertThat(lockedUser.getLockedUntil()).isNotNull();

        // Transition to ACTIVE sets emailVerifiedAt and clears lockedUntil
        lockedUser.changeStatus(UserStatus.ACTIVE);
        assertThat(lockedUser.getEmailVerifiedAt()).isNotNull();
        assertThat(lockedUser.getLockedUntil()).isNull();
        users.saveAndFlush(lockedUser);
        entityManager.clear();

        // Transition to PENDING_VERIFICATION clears emailVerifiedAt and lockedUntil
        User activeUser = users.findById(user.getId()).orElseThrow();
        activeUser.changeStatus(UserStatus.PENDING_VERIFICATION);
        assertThat(activeUser.getEmailVerifiedAt()).isNull();
        assertThat(activeUser.getLockedUntil()).isNull();
        users.saveAndFlush(activeUser);
        entityManager.clear();

        // Transition to DISABLED clears lockedUntil
        User pendingUser = users.findById(user.getId()).orElseThrow();
        pendingUser.changeStatus(UserStatus.DISABLED);
        assertThat(pendingUser.getLockedUntil()).isNull();
        users.saveAndFlush(pendingUser);
    }

    private UUID insertUser(String email) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users(id,email,password_hash,display_name) VALUES (?,?,'test-hash','Test')",
                id,
                email);
        jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (?,1)", id);
        return id;
    }

    private UUID insertSession(UUID user) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO auth_sessions(id,user_id,expires_at)
                VALUES (?,?,CURRENT_TIMESTAMP + INTERVAL '7 days')
                """,
                id,
                user);
        return id;
    }

    private void insertToken(UUID session, UUID id, char hash) {
        jdbc.update(
                """
                INSERT INTO refresh_tokens(id,session_id,token_hash,expires_at)
                VALUES (?,?,?,CURRENT_TIMESTAMP + INTERVAL '1 day')
                """,
                id,
                session,
                String.valueOf(hash).repeat(64));
    }

    private UUID insertChallenge(UUID user) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO password_reset_challenges(id,user_id,otp_hmac,expires_at)
                VALUES (?,?,?,CURRENT_TIMESTAMP + INTERVAL '5 minutes')
                """,
                id,
                user,
                "a".repeat(64));
        return id;
    }
}
