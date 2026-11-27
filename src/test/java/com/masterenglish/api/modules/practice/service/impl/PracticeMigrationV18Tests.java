package com.masterenglish.api.modules.practice.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Upgrades a database that already applied V1..V13 (and V16, V17) to V18 and checks that the
 * attempts survive with the baseline shape. No Spring context: Flyway runs against its own
 * PostgreSQL 17 container.
 */
class PracticeMigrationV18Tests {

    private static PostgreSQLContainer postgres;

    @BeforeAll
    static void startDatabase() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17"));
        postgres.start();
    }

    @AfterAll
    static void stopDatabase() {
        postgres.stop();
    }

    private static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .target(target)
                .load();
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @Test
    void v13AttemptsSurviveTheUpgradeWithBaselineShape() throws Exception {
        flyway("13").clean();
        flyway("13").migrate();
        flyway("17").migrate();

        UUID user = UUID.randomUUID();
        UUID topic = UUID.randomUUID();
        UUID topicRev = UUID.randomUUID();
        UUID question = UUID.randomUUID();
        UUID questionRev = UUID.randomUUID();
        UUID submitted = UUID.randomUUID();
        UUID inProgress = UUID.randomUUID();
        UUID optionA = UUID.randomUUID();
        UUID optionB = UUID.randomUUID();
        UUID snapA = UUID.randomUUID();
        UUID snapB = UUID.randomUUID();

        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.executeUpdate(
                        "INSERT INTO users (id, email, password_hash, display_name, status,"
                                + " email_verified_at, first_name, last_name) VALUES ('"
                                + user
                                + "', 'v18-upgrade@example.com', 'x', 'Learner', 'ACTIVE', now(),"
                                + " 'Upgrade', 'Learner')");
                s.executeUpdate(
                        "INSERT INTO content_items (id, content_type, created_by,"
                                + " published_revision_id) VALUES ('"
                                + topic
                                + "', 'GRAMMAR_TOPIC', '"
                                + user
                                + "', '"
                                + topicRev
                                + "'), ('"
                                + question
                                + "', 'QUESTION', '"
                                + user
                                + "', '"
                                + questionRev
                                + "')");
                s.executeUpdate(
                        "INSERT INTO content_revisions (id, content_item_id, version_no, status,"
                                + " created_by, published_at, reviewed_by, reviewed_at)"
                                + " VALUES ('"
                                + topicRev
                                + "', '"
                                + topic
                                + "', 1, 'PUBLISHED', '"
                                + user
                                + "', now(), '"
                                + user
                                + "', now()), ('"
                                + questionRev
                                + "', '"
                                + question
                                + "', 1, 'PUBLISHED', '"
                                + user
                                + "', now(), '"
                                + user
                                + "', now())");
                s.executeUpdate(
                        "INSERT INTO grammar_topics (id, published_revision_id, slug, name,"
                                + " difficulty) VALUES ('"
                                + topic
                                + "', '"
                                + topicRev
                                + "', 'upgrade-topic', 'Upgrade topic', 'BASIC')");
                s.executeUpdate(
                        "INSERT INTO questions (id, published_revision_id, category, prompt,"
                                + " explanation) VALUES ('"
                                + question
                                + "', '"
                                + questionRev
                                + "', 'GRAMMAR', 'Pick A', 'Because A')");
                s.executeUpdate(
                        "INSERT INTO question_topics (question_id, topic_id) VALUES ('"
                                + question
                                + "', '"
                                + topic
                                + "')");
                s.executeUpdate(
                        "INSERT INTO attempts (id, user_id, category, source_type,"
                                + " source_topic_id, request_id, request_hash, status, total_count,"
                                + " correct_count, score, started_at, submitted_at)"
                                + " VALUES ('"
                                + submitted
                                + "', '"
                                + user
                                + "', 'GRAMMAR', 'GRAMMAR_TOPIC', '"
                                + topic
                                + "', '"
                                + UUID.randomUUID()
                                + "', '"
                                + "a".repeat(64)
                                + "', 'SUBMITTED', 1, 1, 100, now() - interval '2 minutes',"
                                + " now() - interval '1 minute')");
                s.executeUpdate(
                        "INSERT INTO attempts (id, user_id, category, source_type, request_id,"
                                + " request_hash, status, total_count)"
                                + " VALUES ('"
                                + inProgress
                                + "', '"
                                + user
                                + "', 'GRAMMAR', 'MIXED_GRAMMAR', '"
                                + UUID.randomUUID()
                                + "', '"
                                + "b".repeat(64)
                                + "', 'IN_PROGRESS', 1)");
                s.executeUpdate(
                        "INSERT INTO attempt_source_topics (attempt_id, topic_id) VALUES ('"
                                + inProgress
                                + "', '"
                                + topic
                                + "')");
                for (UUID attempt : new UUID[] {submitted, inProgress}) {
                    s.executeUpdate(
                            "INSERT INTO attempt_question_snapshots (attempt_id, position,"
                                    + " question_id, topic_id, question_type_code, practice_mode,"
                                    + " prompt, explanation) VALUES ('"
                                    + attempt
                                    + "', 1, '"
                                    + question
                                    + "', '"
                                    + topic
                                    + "', 'SINGLE_CHOICE', 'STANDARD', 'Pick A', 'Because A')");
                }
                s.executeUpdate(
                        "INSERT INTO attempt_snapshot_options (id, attempt_id, question_position,"
                                + " position, body, is_correct) VALUES ('"
                                + optionA
                                + "', '"
                                + submitted
                                + "', 1, 1, 'A', TRUE), ('"
                                + optionB
                                + "', '"
                                + submitted
                                + "', 1, 2, 'B', FALSE)");
                s.executeUpdate(
                        "INSERT INTO attempt_answers (attempt_id, question_position,"
                                + " selected_option_id, answered_at) VALUES ('"
                                + submitted
                                + "', 1, '"
                                + optionA
                                + "', now())");
                s.executeUpdate(
                        "INSERT INTO study_sessions (id, user_id, source_type, attempt_id,"
                                + " timezone, local_day, credit_token, started_at,"
                                + " last_credited_at, active_seconds) VALUES ('"
                                + UUID.randomUUID()
                                + "', '"
                                + user
                                + "', 'PRACTICE', '"
                                + submitted
                                + "', 'Asia/Ho_Chi_Minh', current_date, '"
                                + UUID.randomUUID()
                                + "', now() - interval '2 minutes', now() - interval '1 minute',"
                                + " 0)");
            }
            c.commit();
            c.setAutoCommit(true);
        }

        flyway("18").migrate();

        try (Connection c = connect()) {
            assertThat(latestVersion(c)).isEqualTo("18");
            assertThat(count(c, "legacy_v13_attempts")).isEqualTo(2);
            assertThat(count(c, "attempts")).isEqualTo(2);
            assertThat(count(c, "attempt_question_snapshots")).isEqualTo(2);
            assertThat(count(c, "attempt_snapshot_options")).isEqualTo(2);
            assertThat(count(c, "attempt_answers")).isEqualTo(2);
            assertThat(count(c, "attempt_source_topics")).isEqualTo(1);

            // Score moves from percent to correct count; the percent is computed by the API.
            assertThat(
                            singleValue(
                                    c,
                                    "SELECT score || '/' || correct_count || '/' || answered_count"
                                            + " || '/' || submit_reason FROM attempts WHERE id = '"
                                            + submitted
                                            + "'"))
                    .isEqualTo("1/1/1/MANUAL");
            assertThat(
                            singleValue(
                                    c,
                                    "SELECT duration_seconds FROM attempts WHERE id = '"
                                            + submitted
                                            + "'"))
                    .isEqualTo("60");
            assertThat(
                            singleValue(
                                    c,
                                    "SELECT source_config->>'sourceType' FROM attempts WHERE id = '"
                                            + submitted
                                            + "'"))
                    .isEqualTo("GRAMMAR_TOPIC");

            // Snapshot JSON keeps what the engine reads: attributed topic and the answer key.
            assertThat(
                            singleValue(
                                    c,
                                    "SELECT question_snapshot->>'attributedTopicId'"
                                            + " FROM attempt_question_snapshots"
                                            + " WHERE attempt_id = '"
                                            + submitted
                                            + "'"))
                    .isEqualTo(topic.toString());
            assertThat(
                            singleValue(
                                    c,
                                    "SELECT answer_key_snapshot->>'correctOptionId'"
                                            + " FROM attempt_question_snapshots"
                                            + " WHERE attempt_id = '"
                                            + submitted
                                            + "'"))
                    .isEqualTo(optionA.toString());

            // Every snapshot has one answer row; an unanswered slot of an open attempt stays open.
            assertThat(
                            singleValue(
                                    c,
                                    "SELECT a.is_correct || '/' || a.score FROM attempt_answers a"
                                            + " JOIN attempts t ON t.id = a.attempt_id"
                                            + " WHERE t.id = '"
                                            + submitted
                                            + "'"))
                    .isEqualTo("true/1");
            assertThat(
                            singleValue(
                                    c,
                                    "SELECT count(*) FROM attempt_answers a"
                                            + " WHERE a.attempt_id = '"
                                            + inProgress
                                            + "' AND a.is_correct IS NULL AND a.score IS NULL"))
                    .isEqualTo("1");
            assertThat(
                            singleValue(
                                    c,
                                    "SELECT count(*) FROM study_sessions s"
                                            + " JOIN attempts t ON t.id = s.attempt_id"))
                    .isEqualTo("1");
            assertThat(
                            singleValue(
                                    c,
                                    "SELECT count(*) FROM pg_constraint"
                                            + " WHERE conname = 'fk_study_sessions_user_id_attempt_id_attempts'"))
                    .isEqualTo("1");
        }
    }

    @Test
    void freshDatabaseReachesV18WithTheBaselineShells() throws Exception {
        flyway("18").clean();
        flyway("18").migrate();

        try (Connection c = connect()) {
            assertThat(latestVersion(c)).isEqualTo("18");
            for (String shell :
                    new String[] {
                        "question_groups", "practice_sets", "set_items", "attempt_group_snapshots"
                    }) {
                assertThat(count(c, shell)).as(shell).isZero();
            }
            assertThat(
                            singleValue(
                                    c,
                                    "SELECT count(*) FROM pg_constraint"
                                            + " WHERE conname = 'questions_c7'"))
                    .isEqualTo("1");
        }
    }

    private static String latestVersion(Connection c) throws Exception {
        return singleValue(
                c,
                "SELECT version FROM flyway_schema_history WHERE success"
                        + " ORDER BY installed_rank DESC LIMIT 1");
    }

    private static long count(Connection c, String table) throws Exception {
        return Long.parseLong(singleValue(c, "SELECT count(*) FROM " + table));
    }

    private static String singleValue(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(sql)) {
            assertThat(rs.next()).as(sql).isTrue();
            return rs.getString(1);
        }
    }
}
