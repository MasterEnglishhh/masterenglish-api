package com.masterenglish.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.Transactional;

/** Proposals are created inside a test transaction and rolled back afterwards. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class ProposedDatabaseSchemaTests {

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void createProposedTables() {
        new ResourceDatabasePopulator(
                        new FileSystemResource("docs/database/proposals/remaining-mvp.sql"))
                .execute(dataSource);
    }

    @Test
    void completeBaselineContains44TablesWithoutAi() {
        assertThat(
                        jdbc.queryForObject(
                                """
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema='public' AND table_type='BASE TABLE'
                  -- Keep this historical proposal count independent of newer Identity migrations.
                  AND table_name NOT IN ('flyway_schema_history', 'auth_rate_limits', 'auth_rate_limit_events', 'otp_challenges', 'registration_requests', 'password_reset_grants')
                """,
                                Integer.class))
                .isEqualTo(44);
        assertThat(jdbc.queryForObject("SELECT to_regclass('public.ai_requests')", String.class))
                .isNull();
    }

    @Test
    void publishedRevisionMustBelongToItsContentItem() {
        UUID user = user();
        UUID first = content(user, "WORD");
        UUID second = content(user, "WORD");
        UUID revision = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO content_revisions(id,content_item_id,version_no,snapshot,status,created_by)
                VALUES (?,?,1,'{}','APPROVED',?)
                """,
                revision,
                second,
                user);
        jdbc.update("UPDATE content_items SET published_revision_id=? WHERE id=?", revision, first);
        assertThatThrownBy(
                        () ->
                                jdbc.execute(
                                        "SET CONSTRAINTS fk_content_items_published_revision IMMEDIATE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aQuestionCannotHaveTwoCorrectOptions() {
        UUID question = question(user());
        jdbc.update(
                """
                INSERT INTO question_options(id,question_id,position,body,is_correct)
                VALUES (?,?,1,'first',true)
                """,
                UUID.randomUUID(),
                question);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        """
                INSERT INTO question_options(id,question_id,position,body,is_correct)
                VALUES (?,?,2,'second',true)
                """,
                                        UUID.randomUUID(),
                                        question))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aSetItemMustReferenceAQuestionOrAGroup() {
        UUID user = user();
        UUID set = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO practice_sets(id,content_item_id,category,title,selection_mode)
                VALUES (?,?,'GRAMMAR','Test','FIXED')
                """,
                set,
                content(user, "PRACTICE_SET"));
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO set_items(id,set_id,position) VALUES (?,?,1)",
                                        UUID.randomUUID(),
                                        set))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void mistakeSourceMustBeOwnedByTheSameUser() {
        UUID owner = user();
        UUID other = user();
        UUID attempt = UUID.randomUUID();
        UUID snapshot = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO attempts(id,user_id,category,status,total_count)
                VALUES (?,?,'GRAMMAR','IN_PROGRESS',1)
                """,
                attempt,
                other);
        jdbc.update(
                """
                INSERT INTO attempt_question_snapshots(id,attempt_id,ordinal,question_snapshot,answer_key_snapshot)
                VALUES (?,?,1,'{}','{}')
                """,
                snapshot,
                attempt);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        """
                INSERT INTO mistake_records(id,user_id,source_attempt_id,source_snapshot_id,status)
                VALUES (?,?,?,?,'OPEN')
                """,
                                        UUID.randomUUID(),
                                        owner,
                                        attempt,
                                        snapshot))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void preventsDuplicateNotificationsForTheSameUserAndEvent() {
        UUID user = user();
        insertNotification(user);
        assertThatThrownBy(() -> insertNotification(user))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void avatarMustReferenceAnExistingMediaAsset() {
        UUID user = user();
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE users SET avatar_media_id=? WHERE id=?",
                                        UUID.randomUUID(),
                                        user))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users(id,email,password_hash,display_name) VALUES (?,?,'test-hash','Test')",
                id,
                id + "@example.com");
        jdbc.update("INSERT INTO user_roles(user_id,role_id) VALUES (?,1)", id);
        return id;
    }

    private UUID content(UUID user, String type) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO content_items(id,content_type,created_by) VALUES (?,?,?)",
                id,
                type,
                user);
        return id;
    }

    private UUID question(UUID user) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO questions(id,content_item_id,category,question_type,explanation,difficulty)
                VALUES (?,?,'GRAMMAR','MULTIPLE_CHOICE','Explanation','EASY')
                """,
                id,
                content(user, "QUESTION"));
        return id;
    }

    private void insertNotification(UUID user) {
        jdbc.update(
                """
                INSERT INTO notifications(id,user_id,type,title,body,dedupe_key)
                VALUES (?,?,'SYSTEM','Title','Body','event-123')
                """,
                UUID.randomUUID(),
                user);
    }
}
