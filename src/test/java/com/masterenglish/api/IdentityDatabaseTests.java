package com.masterenglish.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.security.RoleCode;
import com.masterenglish.api.modules.identity.entity.Role;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import com.masterenglish.api.modules.identity.security.UserPrincipal;
import jakarta.persistence.EntityManager;
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
