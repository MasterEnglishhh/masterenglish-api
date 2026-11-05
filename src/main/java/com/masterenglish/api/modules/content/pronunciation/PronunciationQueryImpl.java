package com.masterenglish.api.modules.content.pronunciation;

import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.storage.PronunciationStorage;
import com.masterenglish.api.modules.content.authoring.PublishedContentReader;
import java.net.URI;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class PronunciationQueryImpl implements PronunciationQuery {
    private final JdbcTemplate jdbc;
    private final ObjectProvider<PronunciationStorage> storage;
    private final PublishedContentReader reader;

    public PronunciationQueryImpl(
            JdbcTemplate jdbc,
            ObjectProvider<PronunciationStorage> storage,
            PublishedContentReader reader) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.reader = reader;
    }

    private record Asset(UUID wordId, String bucket, String key) {}

    private java.util.Optional<Asset> asset(UUID revisionId) {
        return jdbc
                .query(
                        """
                SELECT p.word_id,m.bucket,m.object_key FROM word_pronunciations p
                JOIN media_assets m ON m.id=p.media_asset_id
                WHERE p.word_revision_id=? AND m.status='READY' AND m.media_type='AUDIO'
                  AND m.purpose='CONTENT' AND m.deletion_started_at IS NULL
                ORDER BY CASE WHEN p.locale='en-GB' THEN 0 ELSE 1 END,p.locale LIMIT 1
                """,
                        (rs, n) ->
                                new Asset(
                                        rs.getObject(1, UUID.class),
                                        rs.getString(2),
                                        rs.getString(3)),
                        revisionId)
                .stream()
                .findFirst();
    }

    @Override
    public String audioUrl(UUID wordId, UUID revisionId) {
        var backend = storage.getIfAvailable();
        if (backend == null) return null;
        return asset(revisionId)
                .filter(a -> a.wordId().equals(wordId) && a.bucket().equals(backend.bucket()))
                .map(a -> "/api/v1/vocabulary/pronunciations/" + revisionId)
                .orElse(null);
    }

    @Override
    public URI playbackLocation(UUID revisionId) {
        var backend = storage.getIfAvailable();
        var found =
                asset(revisionId)
                        .filter(a -> backend != null && a.bucket().equals(backend.bucket()))
                        .filter(
                                a ->
                                        reader.snapshot(a.wordId(), revisionId, "WORD", false)
                                                .isPresent())
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND,
                                                "Pronunciation unavailable."));
        return backend.signGet(found.key());
    }
}
