package com.masterenglish.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class VocabularyDatabaseMigrationTests {

    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName(
            "V6 migration applies cleanly and creates content, vocabulary, learning and audit tables")
    void verifyTablesAndSchemaExist() {
        List<String> expectedTables =
                List.of(
                        "content_sources",
                        "media_assets",
                        "content_items",
                        "content_revisions",
                        "content_source_links",
                        "revision_dependencies",
                        "revision_media_refs",
                        "words",
                        "word_senses",
                        "word_sense_examples",
                        "decks",
                        "deck_words",
                        "user_saved_words",
                        "user_word_progress",
                        "word_reviews",
                        "audit_logs");

        for (String table : expectedTables) {
            Integer count =
                    jdbc.queryForObject(
                            "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?",
                            Integer.class,
                            table);
            assertThat(count)
                    .withFailMessage("Expected table '%s' to exist after migration", table)
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName(
            "Deferred foreign key allows atomic circular pointer insertion for published content items")
    void verifyDeferredConstraintOnContentItemAndRevision() {
        java.util.UUID adminId = java.util.UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, first_name, last_name, status, email_verified_at) VALUES (?, ?, ?, ?, ?, 'ACTIVE', NOW())",
                adminId,
                "admin-content@example.com",
                "$2a$12$e8YkC6XQ8XbY0u9VqT6eIeaFmP/2eZ.z1c8yU2MvX3mN.rLw5QkOe",
                "Admin",
                "User");

        java.util.UUID itemId = java.util.UUID.randomUUID();
        java.util.UUID revisionId = java.util.UUID.randomUUID();

        // Inserting content_item pointing to revisionId before revision is inserted works because
        // the FK is DEFERRED
        jdbc.update(
                "INSERT INTO content_items (id, content_type, created_by, published_revision_id) VALUES (?, 'WORD', ?, ?)",
                itemId,
                adminId,
                revisionId);

        jdbc.update(
                "INSERT INTO content_revisions (id, content_item_id, version_no, schema_version, snapshot, status, created_by, reviewed_by, reviewed_at, published_at) "
                        + "VALUES (?, ?, 1, 1, '{\"lemma\":\"test\"}'::jsonb, 'PUBLISHED', ?, ?, NOW(), NOW())",
                revisionId,
                itemId,
                adminId,
                adminId);

        Integer itemFound =
                jdbc.queryForObject(
                        "SELECT count(*) FROM content_items WHERE id = ? AND published_revision_id = ?",
                        Integer.class,
                        itemId,
                        revisionId);
        assertThat(itemFound).isEqualTo(1);
    }

    @Test
    @DisplayName("Identity accounts, roles and sessions from V1-V5 operate cleanly with V6 schema")
    void verifyExistingIdentityCompatibility() {
        java.util.UUID userId = java.util.UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, first_name, last_name, status, email_verified_at) VALUES (?, ?, ?, ?, ?, 'ACTIVE', NOW())",
                userId,
                "learner-v6@example.com",
                "$2a$12$e8YkC6XQ8XbY0u9VqT6eIeaFmP/2eZ.z1c8yU2MvX3mN.rLw5QkOe",
                "Learner",
                "V6");

        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (?, 1)", userId);

        java.util.UUID sessionId = java.util.UUID.randomUUID();
        jdbc.update(
                "INSERT INTO auth_sessions (id, user_id, expires_at, created_at) VALUES (?, ?, NOW() + INTERVAL '7 days', NOW())",
                sessionId,
                userId);

        Integer sessionCount =
                jdbc.queryForObject(
                        "SELECT count(*) FROM auth_sessions WHERE user_id = ?",
                        Integer.class,
                        userId);
        assertThat(sessionCount).isEqualTo(1);
    }
}
