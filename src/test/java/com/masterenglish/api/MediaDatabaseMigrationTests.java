package com.masterenglish.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class MediaDatabaseMigrationTests {

    private static final String CONTENT_POOL_ID = "20000000-0000-0000-0000-000000000001";
    private static final String TEMP_POOL_ID = "20000000-0000-0000-0000-000000000002";

    @Test
    void applicationDoesNotEnableOutOfOrderMigrationsByDefault() throws Exception {
        var properties = new java.util.Properties();
        try (var input = getClass().getResourceAsStream("/application.properties")) {
            assertThat(input).isNotNull();
            properties.load(input);
        }
        assertThat(
                        Boolean.parseBoolean(
                                properties.getProperty("spring.flyway.out-of-order", "false")))
                .as("Late migrations need an explicit approved upgrade path")
                .isFalse();
    }

    @Test
    void mediaMigrationsStartAfterIntegratedVersion17() throws Exception {
        try (var postgres = new PostgreSQLContainer("postgres:17")) {
            postgres.start();
            var baseline =
                    Flyway.configure()
                            .dataSource(
                                    postgres.getJdbcUrl(),
                                    postgres.getUsername(),
                                    postgres.getPassword())
                            .locations(
                                    "classpath:db/migration",
                                    "classpath:db/m08-integration-baseline")
                            .target("17")
                            .load();
            baseline.migrate();
            try (var connection =
                            DriverManager.getConnection(
                                    postgres.getJdbcUrl(),
                                    postgres.getUsername(),
                                    postgres.getPassword());
                    var statement = connection.createStatement();
                    var result =
                            statement.executeQuery("SELECT to_regclass('public.storage_pools')")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1))
                        .as("M08 must not occupy integrated versions through V17")
                        .isNull();
            }
            var upgrade =
                    Flyway.configure()
                            .dataSource(
                                    postgres.getJdbcUrl(),
                                    postgres.getUsername(),
                                    postgres.getPassword())
                            .locations(
                                    "classpath:db/migration",
                                    "classpath:db/m08-integration-baseline")
                            .load();
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(7);
            upgrade.validate();
            assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("24");
        }
    }

    @Test
    void upgradeIntegratedV17PreservesHistoryAndChecksums() throws Exception {
        try (var postgres = new PostgreSQLContainer("postgres:17")) {
            postgres.start();
            var configuration =
                    Flyway.configure()
                            .dataSource(
                                    postgres.getJdbcUrl(),
                                    postgres.getUsername(),
                                    postgres.getPassword())
                            .locations(
                                    "classpath:db/migration",
                                    "classpath:db/m08-integration-baseline");
            configuration.target("17").load().migrate();
            UUID userId = UUID.randomUUID();
            UUID itemId = UUID.randomUUID();
            UUID revisionId = UUID.randomUUID();
            UUID mediaId = UUID.randomUUID();
            UUID notificationId = UUID.randomUUID();
            UUID senseId = UUID.randomUUID();
            UUID reviewId = UUID.randomUUID();
            try (var connection =
                            DriverManager.getConnection(
                                    postgres.getJdbcUrl(),
                                    postgres.getUsername(),
                                    postgres.getPassword());
                    var statement = connection.createStatement()) {
                statement.execute(
                        "INSERT INTO users (id,email,password_hash,first_name,last_name) VALUES ('"
                                + userId
                                + "','integrated@example.com','hash','Test','User')");
                statement.execute(
                        "INSERT INTO notification_preferences (user_id,content_notifications_enabled) VALUES ('"
                                + userId
                                + "',true)");
                statement.execute(
                        "INSERT INTO notifications (id,user_id,type,title,body,dedupe_key) VALUES ('"
                                + notificationId
                                + "','"
                                + userId
                                + "','SYSTEM','Title','Body','migration-proof')");
                statement.execute(
                        "INSERT INTO media_assets (id,media_type,purpose,original_filename,uploaded_by,status) VALUES ('"
                                + mediaId
                                + "','AUDIO','CONTENT','legacy.mp3','"
                                + userId
                                + "','PENDING')");
                statement.execute(
                        "INSERT INTO content_items (id,content_type,created_by,is_archived) VALUES ('"
                                + itemId
                                + "','WORD','"
                                + userId
                                + "',false)");
                statement.execute(
                        "INSERT INTO content_revisions (id,content_item_id,version_no,schema_version,snapshot,status,created_by,reviewed_by,reviewed_at) VALUES ('"
                                + revisionId
                                + "','"
                                + itemId
                                + "',1,1,'{\"text\":\"legacy\"}'::jsonb,'APPROVED','"
                                + userId
                                + "','"
                                + userId
                                + "',NOW())");
                statement.execute(
                        "INSERT INTO revision_media_refs (revision_id,media_id,usage_role) VALUES ('"
                                + revisionId
                                + "','"
                                + mediaId
                                + "','STIMULUS')");
                statement.execute(
                        "UPDATE content_revisions SET status='PUBLISHED',published_at=NOW() WHERE id='"
                                + revisionId
                                + "'");
                statement.execute(
                        "UPDATE content_items SET published_revision_id='"
                                + revisionId
                                + "' WHERE id='"
                                + itemId
                                + "'");
                statement.execute(
                        "INSERT INTO words (id,published_revision_id,lemma) VALUES ('"
                                + itemId
                                + "','"
                                + revisionId
                                + "','legacy')");
                statement.execute(
                        "INSERT INTO word_senses (id,word_id,word_revision_id,sense_no,part_of_speech,cefr_level,meaning_vi) VALUES ('"
                                + senseId
                                + "','"
                                + itemId
                                + "','"
                                + revisionId
                                + "',1,'NOUN','A1','nghĩa cũ')");
                statement.execute(
                        "INSERT INTO user_saved_words (user_id,word_sense_id) VALUES ('"
                                + userId
                                + "','"
                                + senseId
                                + "')");
                statement.execute(
                        "INSERT INTO user_word_progress (user_id,word_sense_id,repetitions,interval_days,due_at) VALUES ('"
                                + userId
                                + "','"
                                + senseId
                                + "',3,7,NOW())");
                statement.execute(
                        "INSERT INTO word_reviews (id,user_id,word_sense_id,source_word_id,source_revision_id,request_id,request_hash,rating,policy_version,before_state,after_state,next_due_at,timezone,local_day) VALUES ('"
                                + reviewId
                                + "','"
                                + userId
                                + "','"
                                + senseId
                                + "','"
                                + itemId
                                + "','"
                                + revisionId
                                + "','"
                                + UUID.randomUUID()
                                + "',repeat('a',64),'GOOD','SM2_3BTN_V1','{}','{}',NOW(),'UTC',CURRENT_DATE)");
                statement.execute(
                        "CREATE TEMP TABLE baseline_history AS SELECT version,checksum,script FROM flyway_schema_history WHERE success");
                var upgrade = configuration.target("latest").load();
                assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(7);
                upgrade.validate();
                assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("24");
                var entityManagerFactory =
                        new org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean();
                entityManagerFactory.setDataSource(
                        new org.springframework.jdbc.datasource.DriverManagerDataSource(
                                postgres.getJdbcUrl(),
                                postgres.getUsername(),
                                postgres.getPassword()));
                entityManagerFactory.setPackagesToScan("com.masterenglish.api");
                entityManagerFactory.setJpaVendorAdapter(
                        new org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter());
                entityManagerFactory.setJpaPropertyMap(
                        java.util.Map.of(
                                "hibernate.hbm2ddl.auto",
                                "validate",
                                "hibernate.physical_naming_strategy",
                                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"));
                try {
                    entityManagerFactory.afterPropertiesSet();
                    assertThat(entityManagerFactory.getObject()).isNotNull();
                } finally {
                    entityManagerFactory.destroy();
                }
                try (var result =
                        statement.executeQuery(
                                "SELECT count(*) FROM baseline_history b LEFT JOIN flyway_schema_history h USING(version) WHERE h.version IS NULL OR h.checksum IS DISTINCT FROM b.checksum OR h.script IS DISTINCT FROM b.script")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt(1)).isZero();
                }
                try (var result =
                        statement.executeQuery(
                                "SELECT i.published_revision_id,r.snapshot->>'text',m.media_id FROM content_items i JOIN content_revisions r ON r.id=i.published_revision_id JOIN revision_media_refs m ON m.revision_id=r.id WHERE i.id='"
                                        + itemId
                                        + "'")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getObject(1, UUID.class)).isEqualTo(revisionId);
                    assertThat(result.getString(2)).isEqualTo("legacy");
                    assertThat(result.getObject(3, UUID.class)).isEqualTo(mediaId);
                }
                try (var result =
                        statement.executeQuery(
                                "SELECT p.content_notifications_enabled,n.id FROM notification_preferences p JOIN notifications n ON n.user_id=p.user_id WHERE p.user_id='"
                                        + userId
                                        + "'")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getBoolean(1)).isTrue();
                    assertThat(result.getObject(2, UUID.class)).isEqualTo(notificationId);
                }
                try (var result =
                        statement.executeQuery(
                                "SELECT r.id,r.source_revision_id,p.repetitions,p.interval_days FROM word_reviews r JOIN user_word_progress p USING(user_id,word_sense_id) JOIN user_saved_words s USING(user_id,word_sense_id) WHERE r.id='"
                                        + reviewId
                                        + "'")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getObject(1, UUID.class)).isEqualTo(reviewId);
                    assertThat(result.getObject(2, UUID.class)).isEqualTo(revisionId);
                    assertThat(result.getInt(3)).isEqualTo(3);
                    assertThat(result.getInt(4)).isEqualTo(7);
                }
            }
        }
    }

    @Test
    @DisplayName(
            "V15 fresh migration creates storage pools, upload requests, allocations, and views with seed data")
    void freshMigrationCreatesTablesIndexesAndViews() throws Exception {
        try (var postgres = new PostgreSQLContainer("postgres:17")) {
            postgres.start();
            Flyway.configure()
                    .dataSource(
                            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (var conn =
                            DriverManager.getConnection(
                                    postgres.getJdbcUrl(),
                                    postgres.getUsername(),
                                    postgres.getPassword());
                    var stmt = conn.createStatement()) {

                // 1. Verify tables exist
                for (String table :
                        new String[] {
                            "storage_pools", "media_upload_requests", "storage_allocations"
                        }) {
                    try (var rs =
                            stmt.executeQuery(
                                    "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = '"
                                            + table
                                            + "'")) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getInt(1)).as("Table %s must exist", table).isEqualTo(1);
                    }
                }

                // 2. Verify view exists
                try (var rs =
                        stmt.executeQuery(
                                "SELECT count(*) FROM pg_views WHERE schemaname = 'public' AND viewname = 'media_quota_usage'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }

                // 3. Verify seed pools
                try (var rs =
                        stmt.executeQuery(
                                "SELECT id, scope, quota_bytes, reserved_bytes, used_bytes FROM storage_pools ORDER BY scope")) {
                    // CONTENT pool (10 GiB)
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("id")).isEqualTo(CONTENT_POOL_ID);
                    assertThat(rs.getString("scope")).isEqualTo("CONTENT");
                    assertThat(rs.getLong("quota_bytes")).isEqualTo(10737418240L);
                    assertThat(rs.getLong("reserved_bytes")).isEqualTo(0L);
                    assertThat(rs.getLong("used_bytes")).isEqualTo(0L);

                    // TEMP pool (1 GiB)
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("id")).isEqualTo(TEMP_POOL_ID);
                    assertThat(rs.getString("scope")).isEqualTo("TEMP");
                    assertThat(rs.getLong("quota_bytes")).isEqualTo(1073741824L);
                    assertThat(rs.getLong("reserved_bytes")).isEqualTo(0L);
                    assertThat(rs.getLong("used_bytes")).isEqualTo(0L);
                }

                // 4. Verify view calculates available bytes accurately
                try (var rs =
                        stmt.executeQuery(
                                "SELECT scope, available_bytes FROM media_quota_usage WHERE scope = 'CONTENT'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getLong("available_bytes")).isEqualTo(10737418240L);
                }
            }
        }
    }

    @Test
    @DisplayName("V16 migration adds staging claim and receipt columns with constraints")
    void v16MigrationAddsStagingColumnsAndConstraints() throws Exception {
        try (var postgres = new PostgreSQLContainer("postgres:17")) {
            postgres.start();
            Flyway.configure()
                    .dataSource(
                            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (var conn =
                            DriverManager.getConnection(
                                    postgres.getJdbcUrl(),
                                    postgres.getUsername(),
                                    postgres.getPassword());
                    var stmt = conn.createStatement()) {

                for (String col :
                        new String[] {
                            "put_claim_token",
                            "put_claimed_at",
                            "staged_size_bytes",
                            "staged_sha256",
                            "staged_at"
                        }) {
                    try (var rs =
                            stmt.executeQuery(
                                    "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'media_upload_requests' AND column_name = '"
                                            + col
                                            + "'")) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getInt(1)).as("Column %s must exist", col).isEqualTo(1);
                    }
                }

                // Verify constraints: insert dummy user and media asset
                UUID userId = UUID.randomUUID();
                UUID mediaId = UUID.randomUUID();
                UUID reqId = UUID.randomUUID();

                stmt.execute(
                        "INSERT INTO users (id, email, password_hash, first_name, last_name) "
                                + "VALUES ('"
                                + userId
                                + "', 'u"
                                + userId
                                + "@example.com', 'hash', 'Test', 'User')");
                stmt.execute(
                        "INSERT INTO media_assets (id, media_type, purpose, original_filename, uploaded_by, status) "
                                + "VALUES ('"
                                + mediaId
                                + "', 'AUDIO', 'CONTENT', 'audio.mp3', '"
                                + userId
                                + "', 'PENDING')");

                // Mismatched staged_size_bytes != declared_size_bytes violates c14
                assertThatThrownBy(
                                () ->
                                        stmt.execute(
                                                "INSERT INTO media_upload_requests (id, media_id, temp_bucket, temp_object_key, declared_size_bytes, declared_mime_type, max_size_bytes, expires_at, put_claim_token, put_claimed_at, staged_size_bytes, staged_sha256, staged_at) "
                                                        + "VALUES ('"
                                                        + reqId
                                                        + "', '"
                                                        + mediaId
                                                        + "', 'b', 'k1', 100, 'audio/mpeg', 100, NOW() + INTERVAL '5 min', '"
                                                        + UUID.randomUUID()
                                                        + "', NOW(), 99, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', NOW())"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("media_upload_requests_c14");
            }
        }
    }

    @Test
    @DisplayName(
            "Exact quota boundary permits filling pool to exact byte but rejects over-by-one with 23514")
    void exactQuotaEnforcementAndOverByOne() throws Exception {
        try (var postgres = new PostgreSQLContainer("postgres:17")) {
            postgres.start();
            Flyway.configure()
                    .dataSource(
                            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (var conn =
                    DriverManager.getConnection(
                            postgres.getJdbcUrl(),
                            postgres.getUsername(),
                            postgres.getPassword())) {
                UUID adminId = createAdminUser(conn);
                UUID mediaId1 = createMediaAsset(conn, adminId, "IMAGE", "CONTENT", "PENDING", 0);
                UUID mediaId2 = createMediaAsset(conn, adminId, "IMAGE", "CONTENT", "PENDING", 0);

                // TEMP pool quota is 1073741824 bytes (1 GiB)
                long tempQuota = 1073741824L;

                // 1. Reserving exact quota succeeds
                try (var insert =
                        conn.prepareStatement(
                                "INSERT INTO storage_allocations (id, pool_id, allocation_key, media_id, bytes, state) "
                                        + "VALUES (?, ?::uuid, ?, ?, ?, 'RESERVED')")) {
                    insert.setObject(1, UUID.randomUUID());
                    insert.setString(2, TEMP_POOL_ID);
                    insert.setString(3, "temp-alloc-exact");
                    insert.setObject(4, mediaId1);
                    insert.setLong(5, tempQuota);
                    insert.executeUpdate();
                }

                // Verify pool counters
                try (var stmt = conn.createStatement();
                        var rs =
                                stmt.executeQuery(
                                        "SELECT reserved_bytes, used_bytes FROM storage_pools WHERE id = '"
                                                + TEMP_POOL_ID
                                                + "'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getLong("reserved_bytes")).isEqualTo(tempQuota);
                    assertThat(rs.getLong("used_bytes")).isEqualTo(0L);
                }

                // 2. Over-by-one: Reserving even 1 additional byte fails with SQLSTATE 23514
                try (var insert =
                        conn.prepareStatement(
                                "INSERT INTO storage_allocations (id, pool_id, allocation_key, media_id, bytes, state) "
                                        + "VALUES (?, ?::uuid, ?, ?, ?, 'RESERVED')")) {
                    insert.setObject(1, UUID.randomUUID());
                    insert.setString(2, TEMP_POOL_ID);
                    insert.setString(3, "temp-alloc-over-by-one");
                    insert.setObject(4, mediaId2);
                    insert.setLong(5, 1L);

                    assertThatThrownBy(insert::executeUpdate)
                            .isInstanceOf(SQLException.class)
                            .satisfies(
                                    ex -> {
                                        SQLException sqlEx = (SQLException) ex;
                                        assertThat(sqlEx.getSQLState()).isEqualTo("23514");
                                        assertThat(sqlEx.getMessage())
                                                .contains("storage quota exceeded");
                                        assertThat(extractConstraint(sqlEx))
                                                .isEqualTo("storage_pool_quota_exceeded");
                                    });
                }
            }
        }
    }

    @Test
    @DisplayName(
            "Concurrent transactions competing for pool quota serialize via row lock and holder blocks waiter PID")
    void concurrentTransactionsCompetingForPoolQuota() throws Exception {
        try (var postgres = new PostgreSQLContainer("postgres:17")) {
            postgres.start();
            Flyway.configure()
                    .dataSource(
                            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            var executor = Executors.newFixedThreadPool(2);
            var holderAcquiredLatch = new CountDownLatch(1);
            var waiterPidFuture = new CompletableFuture<Long>();
            var waiterResultFuture = new CompletableFuture<String>();

            // Set TEMP pool quota to 1000 bytes for concurrency test
            try (var conn =
                            DriverManager.getConnection(
                                    postgres.getJdbcUrl(),
                                    postgres.getUsername(),
                                    postgres.getPassword());
                    var stmt = conn.createStatement()) {
                stmt.executeUpdate(
                        "UPDATE storage_pools SET quota_bytes = 1000 WHERE id = '"
                                + TEMP_POOL_ID
                                + "'");
            }

            try (var holderConn =
                            DriverManager.getConnection(
                                    postgres.getJdbcUrl(),
                                    postgres.getUsername(),
                                    postgres.getPassword());
                    var probeConn =
                            DriverManager.getConnection(
                                    postgres.getJdbcUrl(),
                                    postgres.getUsername(),
                                    postgres.getPassword())) {

                holderConn.setAutoCommit(false);
                long holderPid;
                try (var stmt = holderConn.createStatement();
                        var rs = stmt.executeQuery("SELECT pg_backend_pid()")) {
                    rs.next();
                    holderPid = rs.getLong(1);
                }

                UUID adminId = createAdminUser(holderConn);
                UUID mediaId1 =
                        createMediaAsset(holderConn, adminId, "IMAGE", "CONTENT", "PENDING", 0);
                UUID mediaId2 =
                        createMediaAsset(holderConn, adminId, "IMAGE", "CONTENT", "PENDING", 0);
                holderConn.commit();

                // 1. Thread 1: Holder acquires 600 bytes allocation and holds lock uncommitted
                holderConn.setAutoCommit(false);
                try (var insert =
                        holderConn.prepareStatement(
                                "INSERT INTO storage_allocations (id, pool_id, allocation_key, media_id, bytes, state) "
                                        + "VALUES (?, ?::uuid, 'competing-holder', ?, 600, 'RESERVED')")) {
                    insert.setObject(1, UUID.randomUUID());
                    insert.setString(2, TEMP_POOL_ID);
                    insert.setObject(3, mediaId1);
                    insert.executeUpdate();
                }
                holderAcquiredLatch.countDown();

                // 2. Thread 2: Waiter attempts to allocate 600 bytes in the same pool and blocks on
                // storage_pools row lock
                Future<?> waiterTask =
                        executor.submit(
                                () -> {
                                    try (var waiterConn =
                                            DriverManager.getConnection(
                                                    postgres.getJdbcUrl(),
                                                    postgres.getUsername(),
                                                    postgres.getPassword())) {
                                        waiterConn.setAutoCommit(false);
                                        try (var stmt = waiterConn.createStatement();
                                                var rs =
                                                        stmt.executeQuery(
                                                                "SELECT pg_backend_pid()")) {
                                            rs.next();
                                            waiterPidFuture.complete(rs.getLong(1));
                                        }
                                        try (var insert =
                                                waiterConn.prepareStatement(
                                                        "INSERT INTO storage_allocations (id, pool_id, allocation_key, media_id, bytes, state) "
                                                                + "VALUES (?, ?::uuid, 'competing-waiter', ?, 600, 'RESERVED')")) {
                                            insert.setObject(1, UUID.randomUUID());
                                            insert.setString(2, TEMP_POOL_ID);
                                            insert.setObject(3, mediaId2);
                                            insert.executeUpdate();
                                            waiterConn.commit();
                                            waiterResultFuture.complete("SUCCESS");
                                        } catch (SQLException ex) {
                                            waiterConn.rollback();
                                            if ("23514".equals(ex.getSQLState())) {
                                                String constraint = extractConstraint(ex);
                                                waiterResultFuture.complete(
                                                        "QUOTA_EXCEEDED:" + constraint);
                                            } else {
                                                waiterResultFuture.completeExceptionally(ex);
                                            }
                                        }
                                    } catch (Exception ex) {
                                        waiterResultFuture.completeExceptionally(ex);
                                    }
                                });

                // Wait for waiter to report its backend PID
                Long waiterPid = waiterPidFuture.get(5, TimeUnit.SECONDS);

                // Verify that PostgreSQL confirms waiterPid is ACTUALLY blocked by holderPid
                boolean isBlocked = awaitBlockedBy(probeConn, waiterPid, holderPid, 5);
                assertThat(isBlocked)
                        .as(
                                "Waiter PID %d must be actively blocked by Holder PID %d in PostgreSQL",
                                waiterPid, holderPid)
                        .isTrue();

                // 3. Commit holder transaction to release pool row lock
                holderConn.commit();

                // 4. Waiter unblocks, sees updated pool counters, and must fail with quota exceeded
                String waiterResult = waiterResultFuture.get(5, TimeUnit.SECONDS);
                assertThat(waiterResult).isEqualTo("QUOTA_EXCEEDED:storage_pool_quota_exceeded");

                // Verify final pool state
                try (var stmt = probeConn.createStatement();
                        var rs =
                                stmt.executeQuery(
                                        "SELECT reserved_bytes, used_bytes FROM storage_pools WHERE id = '"
                                                + TEMP_POOL_ID
                                                + "'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getLong("reserved_bytes")).isEqualTo(600L);
                    assertThat(rs.getLong("used_bytes")).isEqualTo(0L);
                }
            } finally {
                executor.shutdownNow();
                executor.awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    @DisplayName(
            "Allocation lifecycle transitions maintain reserved/used counters and reject invalid transitions or modifications")
    void allocationLifecycleStateTransitionsAndCounterAccounting() throws Exception {
        try (var postgres = new PostgreSQLContainer("postgres:17")) {
            postgres.start();
            Flyway.configure()
                    .dataSource(
                            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (var conn =
                    DriverManager.getConnection(
                            postgres.getJdbcUrl(),
                            postgres.getUsername(),
                            postgres.getPassword())) {
                UUID adminId = createAdminUser(conn);
                UUID mediaId = createMediaAsset(conn, adminId, "IMAGE", "CONTENT", "PENDING", 0);
                UUID allocId = UUID.randomUUID();
                long allocBytes = 5242880L; // 5 MiB

                // 1. RESERVED: reserved_bytes increases, used_bytes = 0
                try (var insert =
                        conn.prepareStatement(
                                "INSERT INTO storage_allocations (id, pool_id, allocation_key, media_id, bytes, state) "
                                        + "VALUES (?, ?::uuid, ?, ?, ?, 'RESERVED')")) {
                    insert.setObject(1, allocId);
                    insert.setString(2, CONTENT_POOL_ID);
                    insert.setString(3, "content-alloc-lifecycle");
                    insert.setObject(4, mediaId);
                    insert.setLong(5, allocBytes);
                    insert.executeUpdate();
                }

                assertPoolCounters(conn, CONTENT_POOL_ID, allocBytes, 0L);

                // 2. Transition RESERVED -> COMMITTED: reserved_bytes decreases, used_bytes
                // increases
                try (var update =
                        conn.prepareStatement(
                                "UPDATE storage_allocations SET state = 'COMMITTED', bucket = 'content-bucket', object_key = 'img/test.jpg', committed_at = NOW() WHERE id = ?")) {
                    update.setObject(1, allocId);
                    update.executeUpdate();
                }

                assertPoolCounters(conn, CONTENT_POOL_ID, 0L, allocBytes);

                // 3. Attempting invalid transition COMMITTED -> RESERVED fails with 23514
                try (var update =
                        conn.prepareStatement(
                                "UPDATE storage_allocations SET state = 'RESERVED' WHERE id = ?")) {
                    update.setObject(1, allocId);
                    assertThatThrownBy(update::executeUpdate)
                            .isInstanceOf(SQLException.class)
                            .satisfies(
                                    ex ->
                                            assertThat(((SQLException) ex).getSQLState())
                                                    .isEqualTo("23514"));
                }

                // 4. Attempting to mutate bytes fails with 23514
                try (var update =
                        conn.prepareStatement(
                                "UPDATE storage_allocations SET bytes = 100 WHERE id = ?")) {
                    update.setObject(1, allocId);
                    assertThatThrownBy(update::executeUpdate)
                            .isInstanceOf(SQLException.class)
                            .satisfies(
                                    ex ->
                                            assertThat(((SQLException) ex).getSQLState())
                                                    .isEqualTo("23514"));
                }

                // 5. Attempting to delete non-RELEASED allocation fails with 23514
                try (var delete =
                        conn.prepareStatement("DELETE FROM storage_allocations WHERE id = ?")) {
                    delete.setObject(1, allocId);
                    assertThatThrownBy(delete::executeUpdate)
                            .isInstanceOf(SQLException.class)
                            .satisfies(
                                    ex ->
                                            assertThat(((SQLException) ex).getSQLState())
                                                    .isEqualTo("23514"));
                }

                // 6. Transition COMMITTED -> RELEASED: used_bytes decreases to 0
                try (var update =
                        conn.prepareStatement(
                                "UPDATE storage_allocations SET state = 'RELEASED', released_at = NOW() WHERE id = ?")) {
                    update.setObject(1, allocId);
                    update.executeUpdate();
                }

                assertPoolCounters(conn, CONTENT_POOL_ID, 0L, 0L);

                // 7. Released allocation can now be deleted
                try (var delete =
                        conn.prepareStatement("DELETE FROM storage_allocations WHERE id = ?")) {
                    delete.setObject(1, allocId);
                    int deleted = delete.executeUpdate();
                    assertThat(deleted).isEqualTo(1);
                }
            }
        }
    }

    @Test
    @DisplayName(
            "Media upload request lifecycle guards immutability, lease limits, and completion consistency")
    void uploadRequestLifecycleAndImmutabilityInvariants() throws Exception {
        try (var postgres = new PostgreSQLContainer("postgres:17")) {
            postgres.start();
            Flyway.configure()
                    .dataSource(
                            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (var conn =
                    DriverManager.getConnection(
                            postgres.getJdbcUrl(),
                            postgres.getUsername(),
                            postgres.getPassword())) {
                UUID adminId = createAdminUser(conn);
                UUID mediaId = createMediaAsset(conn, adminId, "IMAGE", "CONTENT", "PENDING", 0);
                UUID requestId = UUID.randomUUID();

                // 1. Create PENDING upload request
                try (var insert =
                        conn.prepareStatement(
                                "INSERT INTO media_upload_requests (id, media_id, temp_bucket, temp_object_key, declared_size_bytes, declared_mime_type, max_size_bytes, status, expires_at) "
                                        + "VALUES (?, ?, 'temp-bucket', 'temp/test.jpg', 1048576, 'image/jpeg', 5242880, 'PENDING', NOW() + INTERVAL '10 minutes')")) {
                    insert.setObject(1, requestId);
                    insert.setObject(2, mediaId);
                    insert.executeUpdate();
                }

                // 2. Modifying immutable grant context (declared_size_bytes) fails with 23514
                try (var update =
                        conn.prepareStatement(
                                "UPDATE media_upload_requests SET declared_size_bytes = 2048576 WHERE id = ?")) {
                    update.setObject(1, requestId);
                    assertThatThrownBy(update::executeUpdate)
                            .isInstanceOf(SQLException.class)
                            .satisfies(
                                    ex ->
                                            assertThat(((SQLException) ex).getSQLState())
                                                    .isEqualTo("23514"));
                }

                // 3. Setting lease_until > 60 seconds in future fails with 23514
                try (var update =
                        conn.prepareStatement(
                                "UPDATE media_upload_requests SET status = 'VERIFYING', verification_started_at = NOW(), verification_deadline_at = NOW() + INTERVAL '5 minutes', "
                                        + "claim_token = gen_random_uuid(), lease_until = NOW() + INTERVAL '65 seconds' WHERE id = ?")) {
                    update.setObject(1, requestId);
                    assertThatThrownBy(update::executeUpdate)
                            .isInstanceOf(SQLException.class)
                            .satisfies(
                                    ex ->
                                            assertThat(((SQLException) ex).getSQLState())
                                                    .isEqualTo("23514"));
                }

                // 4. Valid lease (<= 60 seconds) succeeds
                try (var update =
                        conn.prepareStatement(
                                "UPDATE media_upload_requests SET status = 'VERIFYING', verification_started_at = NOW(), verification_deadline_at = NOW() + INTERVAL '5 minutes', "
                                        + "claim_token = gen_random_uuid(), lease_until = NOW() + INTERVAL '50 seconds' WHERE id = ?")) {
                    update.setObject(1, requestId);
                    int updated = update.executeUpdate();
                    assertThat(updated).isEqualTo(1);
                }

                // 5. Completion consistency constraint trigger:
                // If media_assets is still PENDING, completing the request fails at commit with
                // 23514
                conn.setAutoCommit(false);
                try (var update =
                        conn.prepareStatement(
                                "UPDATE media_upload_requests SET status = 'COMPLETED', claim_token = null, lease_until = null, completed_at = NOW(), finished_at = NOW() WHERE id = ?")) {
                    update.setObject(1, requestId);
                    update.executeUpdate();
                    assertThatThrownBy(conn::commit)
                            .isInstanceOf(SQLException.class)
                            .satisfies(
                                    ex ->
                                            assertThat(((SQLException) ex).getSQLState())
                                                    .isEqualTo("23514"));
                    conn.rollback();
                }

                // 6. When media_assets is updated to READY with matching size, completion succeeds
                conn.setAutoCommit(false);
                try (var updateMedia =
                                conn.prepareStatement(
                                        "UPDATE media_assets SET status = 'READY', bucket = 'content-bucket', object_key = 'img/test.jpg', "
                                                + "mime_type = 'image/jpeg', file_size_bytes = 1048576, width = 800, height = 600, checksum_sha256 = 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855' WHERE id = ?");
                        var updateReq =
                                conn.prepareStatement(
                                        "UPDATE media_upload_requests SET status = 'COMPLETED', claim_token = null, lease_until = null, completed_at = NOW(), finished_at = NOW() WHERE id = ?")) {
                    updateMedia.setObject(1, mediaId);
                    updateMedia.executeUpdate();

                    updateReq.setObject(1, requestId);
                    updateReq.executeUpdate();

                    conn.commit();
                }

                // 7. Modifying terminal upload metadata fails with 55000
                try (var update =
                        conn.prepareStatement(
                                "UPDATE media_upload_requests SET failure_code = 'LATE_ERR' WHERE id = ?")) {
                    update.setObject(1, requestId);
                    assertThatThrownBy(update::executeUpdate)
                            .isInstanceOf(SQLException.class)
                            .satisfies(
                                    ex ->
                                            assertThat(((SQLException) ex).getSQLState())
                                                    .isEqualTo("55000"));
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"9", "13"})
    @DisplayName(
            "Upgrading an existing V9/V13 database preserves legacy media and backfills quota safely")
    void upgradeBackfillsExistingContentAndHandlesOverQuotaSafely(String baselineVersion)
            throws Exception {
        try (var postgres = new PostgreSQLContainer("postgres:17")) {
            postgres.start();

            // 1. Migrate up to V13
            Flyway.configure()
                    .dataSource(
                            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration")
                    .target(baselineVersion)
                    .load()
                    .migrate();

            UUID adminId = UUID.randomUUID();
            UUID publishedItemId = UUID.randomUUID();
            UUID publishedRevId = UUID.randomUUID();
            UUID publishedMediaId = UUID.randomUUID();
            UUID readyMediaId = UUID.randomUUID();
            UUID archivedMediaId = UUID.randomUUID();
            UUID failedUncleanedMediaId = UUID.randomUUID();
            UUID failedCleanedMediaId = UUID.randomUUID();
            UUID pendingMediaId = UUID.randomUUID();
            UUID anomalyMediaId = UUID.randomUUID();

            long publishedAudioBytes = 20971520L; // 20 MiB
            long readyBytes = 2097152L; // 2 MiB
            long archivedBytes = 4194304L; // 4 MiB
            long failedUncleanedBytes = 1048576L; // 1 MiB
            long pendingBytes = 20971520L; // 20 MiB
            long bulkAudioBytesPerItem = 20971520L; // 20 MiB
            int bulkAudioCount = 512;
            long totalBulkAudioBytes =
                    bulkAudioCount * bulkAudioBytesPerItem; // 10,737,418,240L (exact 10 GiB)

            // 2. Insert V6 legacy media records and published content
            try (var conn =
                    DriverManager.getConnection(
                            postgres.getJdbcUrl(),
                            postgres.getUsername(),
                            postgres.getPassword())) {
                conn.setAutoCommit(false);
                try (var insertUser =
                        conn.prepareStatement(
                                "INSERT INTO users (id, email, password_hash, first_name, last_name, status, email_verified_at) "
                                        + "VALUES (?, 'legacy-admin@example.com', 'hash', 'Legacy', 'Admin', 'ACTIVE', NOW())")) {
                    insertUser.setObject(1, adminId);
                    insertUser.executeUpdate();
                }

                // Insert published media asset (AUDIO, 20 MiB)
                try (var insert =
                        conn.prepareStatement(
                                "INSERT INTO media_assets (id, media_type, purpose, bucket, object_key, original_filename, mime_type, file_size_bytes, duration_ms, checksum_sha256, uploaded_by, status) "
                                        + "VALUES (?, 'AUDIO', 'CONTENT', 'legacy-bucket', 'audio/published.mp3', 'published.mp3', 'audio/mpeg', ?, 60000, 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', ?, 'READY')")) {
                    insert.setObject(1, publishedMediaId);
                    insert.setLong(2, publishedAudioBytes);
                    insert.setObject(3, adminId);
                    insert.executeUpdate();
                }

                // Insert published content item and revision
                try (var insertItem =
                                conn.prepareStatement(
                                        "INSERT INTO content_items (id, content_type, created_by, is_archived) "
                                                + "VALUES (?, 'WORD', ?, false)");
                        var insertRev =
                                conn.prepareStatement(
                                        "INSERT INTO content_revisions (id, content_item_id, version_no, schema_version, snapshot, status, created_by, reviewed_by, reviewed_at) "
                                                + "VALUES (?, ?, 1, 1, '{\"text\":\"published\"}'::jsonb, 'APPROVED', ?, ?, NOW())");
                        var insertRef =
                                conn.prepareStatement(
                                        "INSERT INTO revision_media_refs (revision_id, media_id, usage_role) "
                                                + "VALUES (?, ?, 'STIMULUS')");
                        var publishRev =
                                conn.prepareStatement(
                                        "UPDATE content_revisions SET status = 'PUBLISHED', published_at = NOW() WHERE id = ?");
                        var pointItem =
                                conn.prepareStatement(
                                        "UPDATE content_items SET published_revision_id = ? WHERE id = ?")) {
                    insertItem.setObject(1, publishedItemId);
                    insertItem.setObject(2, adminId);
                    insertItem.executeUpdate();

                    insertRev.setObject(1, publishedRevId);
                    insertRev.setObject(2, publishedItemId);
                    insertRev.setObject(3, adminId);
                    insertRev.setObject(4, adminId);
                    insertRev.executeUpdate();

                    insertRef.setObject(1, publishedRevId);
                    insertRef.setObject(2, publishedMediaId);
                    insertRef.executeUpdate();

                    publishRev.setObject(1, publishedRevId);
                    publishRev.executeUpdate();

                    pointItem.setObject(1, publishedRevId);
                    pointItem.setObject(2, publishedItemId);
                    pointItem.executeUpdate();
                }

                // Insert 512 bulk valid AUDIO media clips (20 MiB each = 10 GiB total) via SQL
                // series
                try (var insertBulk =
                        conn.prepareStatement(
                                """
                                INSERT INTO media_assets (
                                    id, media_type, purpose, bucket, object_key, original_filename,
                                    mime_type, file_size_bytes, duration_ms, checksum_sha256, uploaded_by, status
                                )
                                SELECT
                                    gen_random_uuid(),
                                    'AUDIO',
                                    'CONTENT',
                                    'bulk-audio-bucket',
                                    'audio/bulk-' || i || '.mp3',
                                    'bulk-' || i || '.mp3',
                                    'audio/mpeg',
                                    ?,
                                    60000,
                                    'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
                                    ?,
                                    'READY'
                                FROM generate_series(1, ?) AS i
                                """)) {
                    insertBulk.setLong(1, bulkAudioBytesPerItem);
                    insertBulk.setObject(2, adminId);
                    insertBulk.setInt(3, bulkAudioCount);
                    int insertedBulk = insertBulk.executeUpdate();
                    assertThat(insertedBulk).isEqualTo(bulkAudioCount);
                }

                // Insert READY media (IMAGE, 2 MiB)
                try (var insert =
                        conn.prepareStatement(
                                "INSERT INTO media_assets (id, media_type, purpose, bucket, object_key, original_filename, mime_type, file_size_bytes, width, height, checksum_sha256, uploaded_by, status) "
                                        + "VALUES (?, 'IMAGE', 'CONTENT', 'legacy-bucket', 'img/ready.png', 'ready.png', 'image/png', ?, 800, 600, 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', ?, 'READY')")) {
                    insert.setObject(1, readyMediaId);
                    insert.setLong(2, readyBytes);
                    insert.setObject(3, adminId);
                    insert.executeUpdate();
                }

                // Insert ARCHIVED media (IMAGE, 4 MiB)
                try (var insert =
                        conn.prepareStatement(
                                "INSERT INTO media_assets (id, media_type, purpose, bucket, object_key, original_filename, mime_type, file_size_bytes, width, height, checksum_sha256, uploaded_by, status, archived_at) "
                                        + "VALUES (?, 'IMAGE', 'CONTENT', 'legacy-bucket', 'img/archived.png', 'archived.png', 'image/png', ?, 800, 600, 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855', ?, 'ARCHIVED', NOW())")) {
                    insert.setObject(1, archivedMediaId);
                    insert.setLong(2, archivedBytes);
                    insert.setObject(3, adminId);
                    insert.executeUpdate();
                }

                // Insert FAILED media without deletion (physical object remains, 1 MiB)
                try (var insert =
                        conn.prepareStatement(
                                "INSERT INTO media_assets (id, media_type, purpose, bucket, object_key, original_filename, mime_type, file_size_bytes, uploaded_by, status, failure_code) "
                                        + "VALUES (?, 'IMAGE', 'CONTENT', 'legacy-bucket', 'img/failed-temp.png', 'failed.png', 'image/png', ?, ?, 'FAILED', 'VERIFICATION_FAILED')")) {
                    insert.setObject(1, failedUncleanedMediaId);
                    insert.setLong(2, failedUncleanedBytes);
                    insert.setObject(3, adminId);
                    insert.executeUpdate();
                }

                // Insert FAILED media with object_deleted_at (already cleaned, 512 bytes)
                try (var insert =
                        conn.prepareStatement(
                                "INSERT INTO media_assets (id, media_type, purpose, bucket, object_key, original_filename, mime_type, file_size_bytes, uploaded_by, status, failure_code, object_deleted_at) "
                                        + "VALUES (?, 'IMAGE', 'CONTENT', 'legacy-bucket', 'img/failed-cleaned.png', 'cleaned.png', 'image/png', 512, ?, 'FAILED', 'CORRUPT_FILE', NOW())")) {
                    insert.setObject(1, failedCleanedMediaId);
                    insert.setObject(2, adminId);
                    insert.executeUpdate();
                }

                // Insert PENDING media with physical file / bucket / key / size (20 MiB)
                try (var insert =
                        conn.prepareStatement(
                                "INSERT INTO media_assets (id, media_type, purpose, bucket, object_key, original_filename, mime_type, file_size_bytes, uploaded_by, status) "
                                        + "VALUES (?, 'AUDIO', 'CONTENT', 'legacy-bucket', 'audio/pending.mp3', 'pending.mp3', 'audio/mpeg', ?, ?, 'PENDING')")) {
                    insert.setObject(1, pendingMediaId);
                    insert.setLong(2, pendingBytes);
                    insert.setObject(3, adminId);
                    insert.executeUpdate();
                }

                // Insert anomaly media row with NULL size and NULL bucket/object_key
                try (var insert =
                        conn.prepareStatement(
                                "INSERT INTO media_assets (id, media_type, purpose, original_filename, uploaded_by, status) "
                                        + "VALUES (?, 'IMAGE', 'CONTENT', 'anomaly.png', ?, 'PENDING')")) {
                    insert.setObject(1, anomalyMediaId);
                    insert.setObject(2, adminId);
                    insert.executeUpdate();
                }

                conn.commit();
            }

            // 3. Migrate from V13 to latest (V15) and validate Flyway schema
            Flyway flyway =
                    Flyway.configure()
                            .dataSource(
                                    postgres.getJdbcUrl(),
                                    postgres.getUsername(),
                                    postgres.getPassword())
                            .locations("classpath:db/migration")
                            .load();
            flyway.migrate();
            flyway.validate();

            // 4. Assert backfill accounting and over-quota behavior
            try (var conn =
                            DriverManager.getConnection(
                                    postgres.getJdbcUrl(),
                                    postgres.getUsername(),
                                    postgres.getPassword());
                    var stmt = conn.createStatement()) {

                // Total allocations backfilled: 512 bulk + 1 published + 1 ready + 1 archived + 1
                // failed uncleaned + 1 failed cleaned + 1 pending with file = 518
                // Anomaly row is omitted from allocations ledger per schema policy.
                try (var rs = stmt.executeQuery("SELECT count(*) FROM storage_allocations")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(518);
                }

                // Check states: 517 COMMITTED, 1 RELEASED
                try (var rs =
                        stmt.executeQuery(
                                "SELECT state, count(*) FROM storage_allocations GROUP BY state ORDER BY state")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("state")).isEqualTo("COMMITTED");
                    assertThat(rs.getInt(2)).isEqualTo(517);

                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("state")).isEqualTo("RELEASED");
                    assertThat(rs.getInt(2)).isEqualTo(1);
                }

                // Verify anomaly media has no allocation
                try (var ps =
                        conn.prepareStatement(
                                "SELECT count(*) FROM storage_allocations WHERE media_id = ?")) {
                    ps.setObject(1, anomalyMediaId);
                    try (var rs = ps.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getInt(1)).isEqualTo(0);
                    }
                }

                // Check pool used_bytes equals exact sum of all COMMITTED allocations (> 10 GiB)
                long expectedUsedBytes =
                        totalBulkAudioBytes
                                + publishedAudioBytes
                                + readyBytes
                                + archivedBytes
                                + failedUncleanedBytes
                                + pendingBytes;
                // expectedUsedBytes = 10,737,418,240 + 20,971,520 + 2,097,152 + 4,194,304 +
                // 1,048,576 + 20,971,520 = 10,786,701,312L
                assertThat(expectedUsedBytes).isGreaterThan(10737418240L);

                try (var rs =
                        stmt.executeQuery(
                                "SELECT quota_bytes, reserved_bytes, used_bytes FROM storage_pools WHERE id = '"
                                        + CONTENT_POOL_ID
                                        + "'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getLong("quota_bytes")).isEqualTo(10737418240L);
                    assertThat(rs.getLong("reserved_bytes")).isEqualTo(0L);
                    assertThat(rs.getLong("used_bytes")).isEqualTo(expectedUsedBytes);
                }

                // Verify media_quota_usage view reports available_bytes = 0 (not negative)
                try (var rs =
                        stmt.executeQuery(
                                "SELECT available_bytes FROM media_quota_usage WHERE scope = 'CONTENT'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getLong("available_bytes")).isEqualTo(0L);
                }

                // Verify published content item, revision, and revision_media_refs remain
                // completely intact
                try (var ps =
                        conn.prepareStatement(
                                "SELECT count(*) FROM content_items WHERE id = ? AND published_revision_id = ?")) {
                    ps.setObject(1, publishedItemId);
                    ps.setObject(2, publishedRevId);
                    try (var rs = ps.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getInt(1)).isEqualTo(1);
                    }
                }

                try (var ps =
                        conn.prepareStatement(
                                "SELECT count(*) FROM content_revisions WHERE id = ? AND status = 'PUBLISHED'")) {
                    ps.setObject(1, publishedRevId);
                    try (var rs = ps.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getInt(1)).isEqualTo(1);
                    }
                }

                try (var ps =
                        conn.prepareStatement(
                                "SELECT count(*) FROM revision_media_refs WHERE revision_id = ? AND media_id = ? AND usage_role = 'STIMULUS'")) {
                    ps.setObject(1, publishedRevId);
                    ps.setObject(2, publishedMediaId);
                    try (var rs = ps.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getInt(1)).isEqualTo(1);
                    }
                }

                try (var ps =
                        conn.prepareStatement(
                                "SELECT state, bytes FROM storage_allocations WHERE media_id = ?")) {
                    ps.setObject(1, publishedMediaId);
                    try (var rs = ps.executeQuery()) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getString("state")).isEqualTo("COMMITTED");
                        assertThat(rs.getLong("bytes")).isEqualTo(publishedAudioBytes);
                    }
                }

                // 5. Attempting to allocate additional storage when over-quota fails with 23514 /
                // storage_pool_quota_exceeded
                try (var insert =
                        conn.prepareStatement(
                                "INSERT INTO storage_allocations (id, pool_id, allocation_key, media_id, bytes, state) "
                                        + "VALUES (?, ?::uuid, 'over-quota-attempt', ?, 1, 'RESERVED')")) {
                    insert.setObject(1, UUID.randomUUID());
                    insert.setString(2, CONTENT_POOL_ID);
                    insert.setObject(3, readyMediaId);
                    assertThatThrownBy(insert::executeUpdate)
                            .isInstanceOf(SQLException.class)
                            .satisfies(
                                    ex -> {
                                        SQLException sqlEx = (SQLException) ex;
                                        assertThat(sqlEx.getSQLState()).isEqualTo("23514");
                                        assertThat(sqlEx.getMessage())
                                                .contains("storage quota exceeded");
                                        assertThat(extractConstraint(sqlEx))
                                                .isEqualTo("storage_pool_quota_exceeded");
                                    });
                }

                // Verify pool counters remain completely unchanged after rejected allocation
                try (var rs =
                        stmt.executeQuery(
                                "SELECT reserved_bytes, used_bytes FROM storage_pools WHERE id = '"
                                        + CONTENT_POOL_ID
                                        + "'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getLong("reserved_bytes")).isEqualTo(0L);
                    assertThat(rs.getLong("used_bytes")).isEqualTo(expectedUsedBytes);
                }
            }
        }
    }

    @Test
    @DisplayName(
            "ON CONFLICT DO NOTHING does not increment quota or version, and grant policy validates max_size and MIME")
    void insertOnConflictDoNothingPreservesQuotaWithoutSideEffectsAndGuardsGrantPolicy()
            throws Exception {
        try (var postgres = new PostgreSQLContainer("postgres:17")) {
            postgres.start();
            Flyway.configure()
                    .dataSource(
                            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (var conn =
                    DriverManager.getConnection(
                            postgres.getJdbcUrl(),
                            postgres.getUsername(),
                            postgres.getPassword())) {
                UUID adminId = createAdminUser(conn);
                UUID mediaId = createMediaAsset(conn, adminId, "IMAGE", "CONTENT", "PENDING", 0);

                // Set TEMP pool quota to 100 bytes (at ceiling)
                try (var stmt = conn.createStatement()) {
                    stmt.executeUpdate(
                            "UPDATE storage_pools SET quota_bytes = 100, version = 1 WHERE id = '"
                                    + TEMP_POOL_ID
                                    + "'");
                }

                // 1. First INSERT ... ON CONFLICT DO NOTHING of 100 bytes succeeds
                String sql =
                        "INSERT INTO storage_allocations (id, pool_id, allocation_key, media_id, bytes, state) "
                                + "VALUES (?, ?::uuid, ?, ?, ?, 'RESERVED') ON CONFLICT (allocation_key) DO NOTHING";
                try (var insert = conn.prepareStatement(sql)) {
                    insert.setObject(1, UUID.randomUUID());
                    insert.setString(2, TEMP_POOL_ID);
                    insert.setString(3, "idempotent-key-1");
                    insert.setObject(4, mediaId);
                    insert.setLong(5, 100L);
                    int affected = insert.executeUpdate();
                    assertThat(affected).isEqualTo(1);
                }

                // Verify pool counters: exactly 100 bytes reserved, version = 2
                try (var stmt = conn.createStatement();
                        var rs =
                                stmt.executeQuery(
                                        "SELECT reserved_bytes, used_bytes, version FROM storage_pools WHERE id = '"
                                                + TEMP_POOL_ID
                                                + "'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getLong("reserved_bytes")).isEqualTo(100L);
                    assertThat(rs.getLong("used_bytes")).isEqualTo(0L);
                    assertThat(rs.getLong("version")).isEqualTo(2L);
                }

                // 2. Replaying the EXACT SAME statement with ON CONFLICT DO NOTHING is a no-op:
                // It does NOT throw quota exceeded (even though pool is 100/100 full), does not
                // increment reserved_bytes, and does not bump version
                try (var insert = conn.prepareStatement(sql)) {
                    insert.setObject(1, UUID.randomUUID());
                    insert.setString(2, TEMP_POOL_ID);
                    insert.setString(3, "idempotent-key-1");
                    insert.setObject(4, mediaId);
                    insert.setLong(5, 100L);
                    int affected = insert.executeUpdate();
                    assertThat(affected).isEqualTo(0);
                }

                // Verify pool counters remain completely unchanged
                try (var stmt = conn.createStatement();
                        var rs =
                                stmt.executeQuery(
                                        "SELECT reserved_bytes, used_bytes, version FROM storage_pools WHERE id = '"
                                                + TEMP_POOL_ID
                                                + "'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getLong("reserved_bytes")).isEqualTo(100L);
                    assertThat(rs.getLong("used_bytes")).isEqualTo(0L);
                    assertThat(rs.getLong("version")).isEqualTo(2L);
                }

                // Verify allocation row count is still 1
                try (var stmt = conn.createStatement();
                        var rs =
                                stmt.executeQuery(
                                        "SELECT count(*) FROM storage_allocations WHERE allocation_key = 'idempotent-key-1'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }

                // 3. Plain duplicate INSERT without ON CONFLICT throws 23505 (unique violation)
                try (var insert =
                        conn.prepareStatement(
                                "INSERT INTO storage_allocations (id, pool_id, allocation_key, media_id, bytes, state) "
                                        + "VALUES (?, ?::uuid, ?, ?, ?, 'RESERVED')")) {
                    insert.setObject(1, UUID.randomUUID());
                    insert.setString(2, TEMP_POOL_ID);
                    insert.setString(3, "idempotent-key-1");
                    insert.setObject(4, mediaId);
                    insert.setLong(5, 100L);
                    assertThatThrownBy(insert::executeUpdate)
                            .isInstanceOf(SQLException.class)
                            .satisfies(
                                    ex ->
                                            assertThat(((SQLException) ex).getSQLState())
                                                    .isEqualTo("23505"));
                }

                // 4. Grant policy validation on media_upload_requests:
                // A) max_size_bytes exceeds policy (e.g. 10 MB for an IMAGE whose ceiling is 5 MB)
                // fails with 23514
                try (var insert =
                        conn.prepareStatement(
                                "INSERT INTO media_upload_requests (id, media_id, temp_bucket, temp_object_key, declared_size_bytes, declared_mime_type, max_size_bytes, status, expires_at) "
                                        + "VALUES (?, ?, 'temp-b', 'temp/grant1.jpg', 1048576, 'image/jpeg', 10485760, 'PENDING', NOW() + INTERVAL '10 minutes')")) {
                    insert.setObject(1, UUID.randomUUID());
                    insert.setObject(2, mediaId);
                    assertThatThrownBy(insert::executeUpdate)
                            .isInstanceOf(SQLException.class)
                            .satisfies(
                                    ex -> {
                                        SQLException sqlEx = (SQLException) ex;
                                        assertThat(sqlEx.getSQLState()).isEqualTo("23514");
                                        assertThat(sqlEx.getMessage())
                                                .contains(
                                                        "upload grant max size exceeds type/purpose policy");
                                    });
                }

                // B) declared_mime_type incompatible with media_type (e.g. audio/mpeg on IMAGE)
                // fails with 23514
                try (var insert =
                        conn.prepareStatement(
                                "INSERT INTO media_upload_requests (id, media_id, temp_bucket, temp_object_key, declared_size_bytes, declared_mime_type, max_size_bytes, status, expires_at) "
                                        + "VALUES (?, ?, 'temp-b', 'temp/grant2.jpg', 1048576, 'audio/mpeg', 5242880, 'PENDING', NOW() + INTERVAL '10 minutes')")) {
                    insert.setObject(1, UUID.randomUUID());
                    insert.setObject(2, mediaId);
                    assertThatThrownBy(insert::executeUpdate)
                            .isInstanceOf(SQLException.class)
                            .satisfies(
                                    ex -> {
                                        SQLException sqlEx = (SQLException) ex;
                                        assertThat(sqlEx.getSQLState()).isEqualTo("23514");
                                        assertThat(sqlEx.getMessage())
                                                .contains(
                                                        "upload MIME type not allowed for media type");
                                    });
                }
            }
        }
    }

    private UUID createAdminUser(Connection conn) throws SQLException {
        UUID adminId = UUID.randomUUID();
        try (var insert =
                conn.prepareStatement(
                        "INSERT INTO users (id, email, password_hash, first_name, last_name, status, email_verified_at) "
                                + "VALUES (?, ?, 'hash', 'Admin', 'Media', 'ACTIVE', NOW())")) {
            insert.setObject(1, adminId);
            insert.setString(2, "media-admin-" + adminId + "@example.com");
            insert.executeUpdate();
        }
        return adminId;
    }

    private UUID createMediaAsset(
            Connection conn,
            UUID adminId,
            String mediaType,
            String purpose,
            String status,
            long size)
            throws SQLException {
        UUID mediaId = UUID.randomUUID();
        try (var insert =
                conn.prepareStatement(
                        "INSERT INTO media_assets (id, media_type, purpose, original_filename, uploaded_by, status, file_size_bytes) "
                                + "VALUES (?, ?, ?, 'file.jpg', ?, ?, ?)")) {
            insert.setObject(1, mediaId);
            insert.setString(2, mediaType);
            insert.setString(3, purpose);
            insert.setObject(4, adminId);
            insert.setString(5, status);
            insert.setObject(6, size > 0 ? size : null);
            insert.executeUpdate();
        }
        return mediaId;
    }

    private void assertPoolCounters(
            Connection conn, String poolId, long expectedReserved, long expectedUsed)
            throws SQLException {
        try (var stmt = conn.createStatement();
                var rs =
                        stmt.executeQuery(
                                "SELECT reserved_bytes, used_bytes FROM storage_pools WHERE id = '"
                                        + poolId
                                        + "'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong("reserved_bytes")).isEqualTo(expectedReserved);
            assertThat(rs.getLong("used_bytes")).isEqualTo(expectedUsed);
        }
    }

    private boolean awaitBlockedBy(
            Connection probeConn, long waiterPid, long blockerPid, int timeoutSeconds)
            throws SQLException, InterruptedException {
        String sql =
                """
                SELECT EXISTS(
                    SELECT 1
                    FROM unnest(pg_blocking_pids(CAST(? AS integer))) AS blocker(pid)
                    WHERE blocker.pid = CAST(? AS integer)
                )
                """;
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
        try (PreparedStatement stmt = probeConn.prepareStatement(sql)) {
            stmt.setLong(1, waiterPid);
            stmt.setLong(2, blockerPid);
            while (System.currentTimeMillis() < deadline) {
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next() && rs.getBoolean(1)) {
                        return true;
                    }
                }
                Thread.sleep(50);
            }
        }
        return false;
    }

    private String extractConstraint(SQLException sqlEx) {
        try {
            var getServerErrorMessage = sqlEx.getClass().getMethod("getServerErrorMessage");
            Object serverErrorMessage = getServerErrorMessage.invoke(sqlEx);
            if (serverErrorMessage != null) {
                var getConstraint = serverErrorMessage.getClass().getMethod("getConstraint");
                Object constraint = getConstraint.invoke(serverErrorMessage);
                return constraint != null ? constraint.toString() : "";
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return "";
    }
}
