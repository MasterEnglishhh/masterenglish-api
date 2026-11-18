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
                    SELECT p.word_id AS "wordId", m.bucket, m.object_key AS "key"
                    FROM word_pronunciations p JOIN media_assets m ON m.id = p.media_asset_id
                    WHERE p.word_revision_id = :revisionId AND m.status = 'READY'
                      AND m.media_type = 'AUDIO' AND m.purpose = 'CONTENT'
                      AND m.deletion_started_at IS NULL
                    ORDER BY CASE WHEN p.locale = 'en-GB' THEN 0 ELSE 1 END, p.locale
                    LIMIT 1
                    """,
            nativeQuery = true)
    Optional<Asset> findPlayableAsset(@Param("revisionId") UUID revisionId);

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
