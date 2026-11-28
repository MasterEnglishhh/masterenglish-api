package com.masterenglish.api.modules.content.repository.fragment;

import com.masterenglish.api.modules.content.repository.ContentRevisionRepository.Asset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PronunciationOperations {

    @Query(
            value =
                    """
                    SELECT EXISTS (
                        SELECT 1
                        FROM content_revisions r,
                             jsonb_array_elements(COALESCE(r.snapshot -> 'data', r.snapshot) -> 'senses') s
                        WHERE r.id = :revisionId
                          AND s ->> 'id' = :senseId
                    )
                    """,
            nativeQuery = true)
    boolean hasSenseInRevision(
            @Param("revisionId") UUID revisionId, @Param("senseId") String senseId);

    @Query(
            value =
                    """
                    SELECT r.content_item_id AS "wordId", m.bucket, m.object_key AS "key"
                    FROM content_revisions r
                    JOIN LATERAL jsonb_array_elements(
                             COALESCE(r.snapshot -> 'data', r.snapshot) -> 'senses'
                         ) s ON TRUE
                    JOIN revision_media_refs ref
                        ON ref.revision_id = r.id
                        AND ref.media_id = CAST(s ->> 'audio_media_id' AS uuid)
                    JOIN media_assets m ON m.id = ref.media_id
                    WHERE r.id = :revisionId
                      AND s ->> 'id' = :senseId
                      AND s ->> 'audio_media_id' IS NOT NULL
                      AND m.status IN ('READY', 'ARCHIVED')
                      AND m.media_type = 'AUDIO'
                      AND m.purpose = 'CONTENT'
                      AND m.deletion_started_at IS NULL
                    LIMIT 1
                    """,
            nativeQuery = true)
    Optional<Asset> findPlayableSenseAsset(
            @Param("revisionId") UUID revisionId, @Param("senseId") String senseId);

    @Query(
            value =
                    """
                    SELECT "wordId", bucket, "key" FROM (
                        SELECT r.content_item_id AS "wordId", m.bucket, m.object_key AS "key", 0 AS priority
                        FROM revision_media_refs ref
                        JOIN content_revisions r ON r.id = ref.revision_id
                        JOIN media_assets m ON m.id = ref.media_id
                        WHERE ref.revision_id = :revisionId
                          AND ref.usage_role = 'STIMULUS'
                          AND m.status IN ('READY', 'ARCHIVED')
                          AND m.media_type = 'AUDIO'
                          AND m.purpose = 'CONTENT'
                          AND m.deletion_started_at IS NULL
                        UNION ALL
                        SELECT p.word_id AS "wordId", m.bucket, m.object_key AS "key", 1 AS priority
                        FROM word_pronunciations p
                        JOIN media_assets m ON m.id = p.media_asset_id
                        WHERE p.word_revision_id = :revisionId
                          AND m.status = 'READY'
                          AND m.media_type = 'AUDIO'
                          AND m.purpose = 'CONTENT'
                          AND m.deletion_started_at IS NULL
                    ) assets
                    ORDER BY priority
                    LIMIT 1
                    """,
            nativeQuery = true)
    Optional<Asset> findPlayableAsset(@Param("revisionId") UUID revisionId);

    @Query(
            value =
                    """
                    SELECT EXISTS (
                        SELECT 1 FROM content_revisions r
                        JOIN content_items i ON i.id = r.content_item_id
                        WHERE i.id = :itemId AND r.id = :revisionId AND i.content_type = 'WORD'
                          AND r.status = 'PUBLISHED' AND i.access_revoked_at IS NULL
                    )
                    """,
            nativeQuery = true)
    boolean isPlaybackAllowed(@Param("itemId") UUID itemId, @Param("revisionId") UUID revisionId);

    @Query(
            value =
                    """
                    SELECT EXISTS (
                        SELECT 1 FROM word_pronunciations
                        WHERE word_revision_id = :revisionId AND locale = :locale
                    )
                    """,
            nativeQuery = true)
    boolean bindingExists(@Param("revisionId") UUID revisionId, @Param("locale") String locale);

    @Query(
            value =
                    """
                    SELECT EXISTS (
                        SELECT 1 FROM word_pronunciations p JOIN media_assets m ON m.id = p.media_asset_id
                        WHERE p.word_id = :wordId AND p.word_revision_id = :revisionId
                          AND p.locale = :locale AND p.voice_id = :voice
                          AND m.bucket = :bucket AND m.checksum_sha256 = :checksum AND m.status = 'READY'
                    )
                    """,
            nativeQuery = true)
    boolean bindingMatches(
            @Param("wordId") UUID wordId,
            @Param("revisionId") UUID revisionId,
            @Param("locale") String locale,
            @Param("voice") String voice,
            @Param("bucket") String bucket,
            @Param("checksum") String checksum);

    @Modifying
    @Query(
            value =
                    """
                    INSERT INTO media_assets(id, media_type, purpose, bucket, object_key, original_filename,
                        mime_type, file_size_bytes, duration_ms, checksum_sha256, source_id, uploaded_by, status)
                    VALUES (:id, 'AUDIO', 'CONTENT', :bucket, :key, :filename,
                        'audio/mpeg', :size, :duration, :checksum, :sourceId, :actor, 'READY')
                    ON CONFLICT (id) DO NOTHING
                    """,
            nativeQuery = true)
    void insertAsset(
            @Param("id") UUID id,
            @Param("bucket") String bucket,
            @Param("key") String key,
            @Param("filename") String filename,
            @Param("size") long size,
            @Param("duration") int duration,
            @Param("checksum") String checksum,
            @Param("sourceId") UUID sourceId,
            @Param("actor") UUID actor);

    @Modifying
    @Query(
            value =
                    """
                    INSERT INTO word_pronunciations(word_id, word_revision_id, locale, voice_id, media_asset_id, created_by)
                    VALUES (:wordId, :revisionId, :locale, :voice, :assetId, :actor)
                    """,
            nativeQuery = true)
    void insertBinding(
            @Param("wordId") UUID wordId,
            @Param("revisionId") UUID revisionId,
            @Param("locale") String locale,
            @Param("voice") String voice,
            @Param("assetId") UUID assetId,
            @Param("actor") UUID actor);
}
