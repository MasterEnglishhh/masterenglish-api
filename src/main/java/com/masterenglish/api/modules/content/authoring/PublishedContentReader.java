package com.masterenglish.api.modules.content.authoring;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read immutable historical snapshots without exposing content entities across modules. */
@Service
@Transactional
public class PublishedContentReader {
    private final JdbcTemplate jdbc;

    public PublishedContentReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> snapshot(UUID itemId, UUID revisionId, String type, boolean lock) {
        String sql =
                """
                SELECT r.snapshot::text FROM content_revisions r
                JOIN content_items i ON i.id=r.content_item_id
                WHERE i.id=? AND r.id=? AND i.content_type=? AND r.status='PUBLISHED'
                  AND NOT i.is_archived AND i.access_revoked_at IS NULL
                """
                        + (lock ? " FOR SHARE OF i,r" : "");
        return jdbc.query(sql, (rs, row) -> rs.getString(1), itemId, revisionId, type).stream()
                .findFirst();
    }
}
