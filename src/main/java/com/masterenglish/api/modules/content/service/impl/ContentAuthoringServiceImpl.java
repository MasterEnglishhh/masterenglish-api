package com.masterenglish.api.modules.content.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.error.FieldViolation;
import com.masterenglish.api.common.events.ContentEvents;
import com.masterenglish.api.common.events.IdentityEvents;
import com.masterenglish.api.common.validation.HttpUrlValidator;
import com.masterenglish.api.modules.content.dto.response.ContentItemResponse;
import com.masterenglish.api.modules.content.dto.response.ContentRevisionResponse;
import com.masterenglish.api.modules.content.dto.response.ContentSourceResponse;
import com.masterenglish.api.modules.content.dto.response.ValidationResult;
import com.masterenglish.api.modules.content.entity.AuditLog;
import com.masterenglish.api.modules.content.entity.ContentItem;
import com.masterenglish.api.modules.content.entity.ContentRevision;
import com.masterenglish.api.modules.content.entity.ContentSource;
import com.masterenglish.api.modules.content.entity.ContentSourceLink;
import com.masterenglish.api.modules.content.repository.AuditLogRepository;
import com.masterenglish.api.modules.content.repository.ContentItemRepository;
import com.masterenglish.api.modules.content.repository.ContentRevisionRepository;
import com.masterenglish.api.modules.content.repository.ContentSourceLinkRepository;
import com.masterenglish.api.modules.content.repository.ContentSourceRepository;
import com.masterenglish.api.modules.content.service.ContentAuthoringService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class ContentAuthoringServiceImpl implements ContentAuthoringService {

    private final ContentSourceRepository sourceRepository;
    private final ContentItemRepository itemRepository;
    private final ContentRevisionRepository revisionRepository;
    private final ContentSourceLinkRepository sourceLinkRepository;
    private final AuditLogRepository auditLogRepository;
    private final IdentityEvents identity;
    private final ContentSnapshots snapshots;
    private final ContentPublication publication;
    private final ContentEvents contentEvents;
    private final ObjectMapper objectMapper;

    private static final Set<String> WORD_KNOWN_FIELDS =
            Set.of("lemma", "language_code", "ipa", "audio_media_id", "senses");
    private static final Set<String> WORD_SENSE_KNOWN_FIELDS =
            Set.of(
                    "id",
                    "sense_no",
                    "part_of_speech",
                    "cefr_level",
                    "meaning_vi",
                    "definition_en",
                    "audio_media_id",
                    "examples");
    private static final Set<String> WORD_EXAMPLE_KNOWN_FIELDS =
            Set.of("id", "position", "example_en", "example_vi");

    private static final Set<String> DECK_KNOWN_FIELDS =
            Set.of("title", "description", "level", "members", "wordSenseIds");
    private static final Set<String> DECK_MEMBER_KNOWN_FIELDS =
            Set.of("word_id", "word_sense_id", "pinned_word_revision_id", "position");
    private static final int MAX_JSON_DEPTH = 10;

    private static final Set<String> GRAMMAR_TOPIC_KNOWN_FIELDS =
            Set.of("slug", "name", "description", "difficulty", "sort_order");
    private static final Set<String> GRAMMAR_TOPIC_DIFFICULTIES =
            Set.of("BASIC", "INTERMEDIATE", "ADVANCED");

    private static final Set<String> GRAMMAR_LESSON_KNOWN_FIELDS =
            Set.of(
                    "topic_id",
                    "pinned_topic_revision_id",
                    "title",
                    "slug",
                    "position",
                    "body",
                    "body_schema_version",
                    "estimated_minutes");

    private static final Set<String> QUESTION_KNOWN_FIELDS =
            Set.of(
                    "category",
                    "part_number",
                    "question_type_code",
                    "practice_mode",
                    "difficulty",
                    "prompt",
                    "group",
                    "options",
                    "answer_key",
                    "explanation",
                    "materials",
                    "topic_ids",
                    "word_sense_ids");
    private static final Set<String> QUESTION_OPTION_FIELDS = Set.of("id", "position", "body");
    private static final Set<String> QUESTION_ANSWER_KEY_FIELDS = Set.of("correctOptionId");
    private static final Set<String> QUESTION_PRACTICE_MODES = Set.of("STANDARD", "FILL_OPTION");
    private static final Set<String> QUESTION_DIFFICULTIES = Set.of("EASY", "MEDIUM", "HARD");

    private static final Set<String> QUESTION_GROUP_KNOWN_FIELDS =
            Set.of("category", "part_number", "group_type", "title", "materials", "children");

    private static final Set<String> PRACTICE_SET_KNOWN_FIELDS =
            Set.of(
                    "category",
                    "title",
                    "selection_mode",
                    "selection_config",
                    "entries",
                    "deck_id",
                    "topic_id",
                    "part_number",
                    "time_limit_seconds");

    private static final Set<String> LESSON_BLOCK_TYPES =
            Set.of("heading", "paragraph", "note", "example", "table");
    private static final Set<String> HEADING_FIELDS = Set.of("type", "text");
    private static final Set<String> PARAGRAPH_FIELDS = Set.of("type", "text");
    private static final Set<String> NOTE_FIELDS = Set.of("type", "text");
    private static final Set<String> EXAMPLE_FIELDS = Set.of("type", "example_en", "example_vi");
    private static final Set<String> TABLE_FIELDS = Set.of("type", "rows");

    private static final java.util.regex.Pattern SLUG_PATTERN =
            java.util.regex.Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final java.util.regex.Pattern HTML_PATTERN =
            java.util.regex.Pattern.compile(
                    "</?[a-zA-Z][a-zA-Z0-9-]*(?:\\s[^>]*|/)?>|<!--|<!doctype",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    public ContentAuthoringServiceImpl(
            ContentSourceRepository sourceRepository,
            ContentItemRepository itemRepository,
            ContentRevisionRepository revisionRepository,
            ContentSourceLinkRepository sourceLinkRepository,
            AuditLogRepository auditLogRepository,
            IdentityEvents identity,
            ContentSnapshots snapshots,
            ContentPublication publication,
            ContentEvents contentEvents,
            ObjectMapper objectMapper) {
        this.sourceRepository = sourceRepository;
        this.itemRepository = itemRepository;
        this.revisionRepository = revisionRepository;
        this.sourceLinkRepository = sourceLinkRepository;
        this.auditLogRepository = auditLogRepository;
        this.identity = identity;
        this.snapshots = snapshots;
        this.publication = publication;
        this.contentEvents = contentEvents;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public ContentSourceResponse createSource(
            UUID id,
            String title,
            String sourceType,
            String sourceUrl,
            String author,
            String licenseCode,
            String licenseUrl,
            String attribution,
            String permissionNote,
            UUID createdBy) {
        identity.verifyContentAuthor(createdBy);
        validateSourceFields(title, sourceType, sourceUrl, licenseUrl, "ACTIVE");
        ContentSource source =
                new ContentSource(
                        id,
                        title,
                        sourceType,
                        sourceUrl,
                        author,
                        licenseCode,
                        licenseUrl,
                        attribution,
                        permissionNote,
                        "ACTIVE",
                        createdBy);
        return response(sourceRepository.save(source));
    }

    @Override
    @Transactional
    public ContentSourceResponse updateSource(
            UUID id,
            String title,
            String sourceType,
            String sourceUrl,
            String author,
            String licenseCode,
            String licenseUrl,
            String attribution,
            String permissionNote,
            String status,
            UUID actorId) {
        identity.verifyContentAuthor(actorId);
        validateSourceFields(title, sourceType, sourceUrl, licenseUrl, status);
        ContentSource source =
                sourceRepository
                        .findById(id)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND,
                                                "Source not found: " + id));
        source.update(
                title,
                sourceType,
                sourceUrl,
                author,
                licenseCode,
                licenseUrl,
                attribution,
                permissionNote,
                status);
        auditLogRepository.save(
                new AuditLog(
                        "USER",
                        actorId,
                        "CONTENT_SOURCE_UPDATED",
                        "CONTENT_SOURCE",
                        id,
                        String.format("{\"status\":\"%s\"}", source.getStatus()),
                        "Updated content source"));
        return response(sourceRepository.save(source));
    }

    @Override
    @Transactional
    public ContentItemResponse createItemWithDraft(
            UUID itemId,
            String contentType,
            UUID createdBy,
            String importNamespace,
            String externalKey,
            String snapshotJson,
            String changeSummary,
            UUID sourceId) {
        return createItemWithDraft(
                itemId,
                contentType,
                createdBy,
                importNamespace,
                externalKey,
                snapshotJson,
                changeSummary,
                sourceId != null ? List.of(sourceId) : List.of());
    }

    @Override
    @Transactional
    public ContentItemResponse createItemWithDraft(
            UUID itemId,
            String contentType,
            UUID createdBy,
            String importNamespace,
            String externalKey,
            String snapshotJson,
            String changeSummary,
            List<UUID> sourceIds) {
        identity.verifyContentAuthor(createdBy);
        return response(
                createDraft(
                                itemId,
                                contentType,
                                createdBy,
                                importNamespace,
                                externalKey,
                                snapshotJson,
                                changeSummary,
                                sourceIds)
                        .item());
    }

    private Draft createDraft(
            UUID itemId,
            String contentType,
            UUID createdBy,
            String importNamespace,
            String externalKey,
            String snapshotJson,
            String changeSummary,
            List<UUID> sourceIds) {
        List<UUID> uniqueSources =
                sourceIds != null
                        ? sourceIds.stream().filter(Objects::nonNull).distinct().toList()
                        : List.of();
        for (UUID sid : uniqueSources) {
            if (!sourceRepository.existsById(sid)) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "Nguồn trích dẫn không tồn tại: " + sid);
            }
        }

        JsonNode dataNode = snapshots.data(snapshotJson);
        if (dataNode != null && dataNode.isObject()) {
            validateDraftKnownFieldsAndDepth(contentType, dataNode);
        }

        UUID id = itemId != null ? itemId : UUID.randomUUID();
        ContentItem item =
                itemRepository
                        .findByIdForUpdate(id)
                        .orElseGet(
                                () -> {
                                    if (importNamespace != null
                                            && externalKey != null
                                            && itemRepository
                                                    .findByImportNamespaceAndExternalKey(
                                                            importNamespace, externalKey)
                                                    .isPresent()) {
                                        throw new BusinessException(
                                                ErrorCode.CONFLICT,
                                                "Nội dung mã '" + externalKey + "' đã tồn tại.");
                                    }
                                    return itemRepository.save(
                                            new ContentItem(
                                                    id,
                                                    contentType,
                                                    createdBy,
                                                    importNamespace,
                                                    externalKey));
                                });
        if (!item.getContentType().equals(contentType) || !item.allowsRevisionChanges()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Content item cannot be edited.");
        }
        if (revisionRepository.existsByContentItemIdAndStatusNot(id, "PUBLISHED")) {
            throw new BusinessException(ErrorCode.CONFLICT, "An open revision already exists.");
        }

        if ("DECK".equals(contentType)) {
            try {
                if (dataNode != null && (dataNode.has("members") || dataNode.has("wordSenseIds"))) {
                    List<Map<String, Object>> canonical =
                            deckMembers(dataNode.path("members"), dataNode.path("wordSenseIds"));
                    if (dataNode.isObject()) {
                        ((ObjectNode) dataNode).set("members", objectMapper.valueToTree(canonical));
                        snapshotJson = dataNode.toString();
                    }
                }
            } catch (BusinessException ignored) {
                // Keep raw draft if canonicalization fails so validateRevision / publish can report
                // errors
            }
        }
        int nextVersion = revisionRepository.findMaxVersionNoByContentItemId(item.getId()) + 1;
        ContentRevision draft =
                revisionRepository.save(
                        new ContentRevision(
                                UUID.randomUUID(),
                                item.getId(),
                                nextVersion,
                                ContentSnapshots.SCHEMA_VERSION,
                                snapshots.envelope(contentType, snapshotJson),
                                "DRAFT",
                                changeSummary,
                                createdBy));

        for (UUID sid : uniqueSources) {
            ContentSourceLink link =
                    new ContentSourceLink(
                            draft.getId(), sid, "Initial draft source attribution", null);
            sourceLinkRepository.save(link);
        }

        auditLogRepository.save(
                new AuditLog(
                        "USER",
                        createdBy,
                        "CONTENT_DRAFT_CREATED",
                        contentType,
                        item.getId(),
                        String.format(
                                "{\"revisionId\":\"%s\",\"versionNo\":%d}",
                                draft.getId(), nextVersion),
                        "Created draft content item"));

        return new Draft(item, draft);
    }

    @Override
    @Transactional
    public ContentRevisionResponse createRevisionFromPublished(
            UUID itemId,
            UUID baseRevisionId,
            Long expectedItemVersion,
            String changeSummary,
            UUID actorId) {
        identity.verifyContentAuthor(actorId);
        ContentItem item =
                itemRepository
                        .findByIdForUpdate(itemId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND,
                                                "Content item not found: " + itemId));

        if (!item.allowsRevisionChanges()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Content item cannot be edited.");
        }

        if (expectedItemVersion != null && expectedItemVersion < 0) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "expectedItemVersion must be non-negative.");
        }

        if (expectedItemVersion != null && item.getVersion() != expectedItemVersion) {
            throw new BusinessException(
                    ErrorCode.CONFLICT,
                    "Nội dung đã bị thay đổi bởi người khác (stale item version).");
        }

        UUID targetBaseRevId =
                baseRevisionId != null ? baseRevisionId : item.getPublishedRevisionId();
        if (targetBaseRevId == null) {
            throw new BusinessException(
                    ErrorCode.CONFLICT, "Nội dung chưa có bản xuất bản để tạo phiên bản mới.");
        }

        ContentRevision baseRevision =
                revisionRepository
                        .findById(targetBaseRevId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND,
                                                "Base revision not found: " + targetBaseRevId));

        if (!baseRevision.getContentItemId().equals(item.getId())
                || !"PUBLISHED".equals(baseRevision.getStatus())) {
            throw new BusinessException(
                    ErrorCode.CONFLICT,
                    "Chỉ có thể tạo phiên bản mới từ bản đã xuất bản của nội dung này.");
        }

        if (revisionRepository.existsByContentItemIdAndStatusNot(itemId, "PUBLISHED")) {
            throw new BusinessException(ErrorCode.CONFLICT, "An open revision already exists.");
        }

        int nextVersion = revisionRepository.findMaxVersionNoByContentItemId(item.getId()) + 1;
        ContentRevision draft =
                revisionRepository.save(
                        new ContentRevision(
                                UUID.randomUUID(),
                                item.getId(),
                                nextVersion,
                                baseRevision.getSchemaVersion(),
                                baseRevision.getSnapshot(),
                                "DRAFT",
                                changeSummary != null
                                        ? changeSummary
                                        : "Tạo phiên bản mới từ v" + baseRevision.getVersionNo(),
                                actorId));

        List<ContentSourceLink> baseLinks =
                sourceLinkRepository.findByIdRevisionId(baseRevision.getId());
        for (ContentSourceLink link : baseLinks) {
            sourceLinkRepository.save(
                    new ContentSourceLink(
                            draft.getId(), link.getId().getSourceId(), link.getUsageNote(), null));
        }

        auditLogRepository.save(
                new AuditLog(
                        "USER",
                        actorId,
                        "CONTENT_DRAFT_CREATED",
                        item.getContentType(),
                        item.getId(),
                        String.format(
                                "{\"revisionId\":\"%s\",\"versionNo\":%d,\"baseRevisionId\":\"%s\"}",
                                draft.getId(), nextVersion, baseRevision.getId()),
                        "Created draft content revision from published"));

        return response(draft);
    }

    @Override
    @Transactional
    public ContentRevisionResponse updateDraft(
            UUID revisionId,
            long expectedVersion,
            JsonNode data,
            List<UUID> sourceIds,
            String changeSummary,
            UUID actorId) {
        identity.verifyContentAuthor(actorId);

        ContentRevision rev =
                revisionRepository
                        .findById(revisionId)
                        .orElseThrow(() -> revisionNotFound(revisionId));

        ContentItem item =
                itemRepository
                        .findByIdForUpdate(rev.getContentItemId())
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND,
                                                "Content item not found: "
                                                        + rev.getContentItemId()));

        ContentRevision revision =
                revisionRepository
                        .findByIdForUpdate(revisionId)
                        .orElseThrow(() -> revisionNotFound(revisionId));

        if (!item.allowsRevisionChanges()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Content item cannot be edited.");
        }

        if (expectedVersion < 0) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "expectedVersion must be non-negative.");
        }

        if (revision.getVersion() != expectedVersion) {
            throw new BusinessException(
                    ErrorCode.CONFLICT,
                    "Bản sửa đổi đã bị thay đổi bởi người khác (stale version).");
        }

        if ("IN_REVIEW".equals(revision.getStatus())) {
            throw new BusinessException(
                    ErrorCode.CONFLICT,
                    "Bản sửa đổi đang trong quá trình duyệt, không thể chỉnh sửa.");
        }

        if ("PUBLISHED".equals(revision.getStatus())) {
            throw new BusinessException(
                    ErrorCode.CONFLICT,
                    "Bản sửa đổi đã xuất bản không thể chỉnh sửa trực tiếp. Hãy tạo phiên bản"
                            + " mới.");
        }

        if (data == null || !data.isObject()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "Dữ liệu bản sửa đổi phải là một đối tượng JSON.");
        }

        validateDraftKnownFieldsAndDepth(item.getContentType(), data);

        if ("DECK".equals(item.getContentType())) {
            List<Map<String, Object>> canonicalMembers =
                    deckMembers(data.path("members"), data.path("wordSenseIds"));
            ((ObjectNode) data).set("members", objectMapper.valueToTree(canonicalMembers));
        } else if ("WORD".equals(item.getContentType())) {
            if (data.has("senses") && data.path("senses").isArray()) {
                Set<UUID> seenSenseIds = new HashSet<>();
                Set<Integer> seenPositions = new HashSet<>();
                Set<UUID> seenExampleIds = new HashSet<>();
                for (JsonNode sense : data.path("senses")) {
                    if (sense.hasNonNull("id")) {
                        UUID senseId = parseUuid(sense.path("id").asText(), "sense id");
                        if (!seenSenseIds.add(senseId)) {
                            throw new BusinessException(
                                    ErrorCode.VALIDATION_ERROR, "Duplicate sense ID: " + senseId);
                        }
                        var owner =
                                revisionRepository.findOwnerItemIdBySenseProbe(
                                        "[{\"id\":\"" + senseId + "\"}]");
                        if (owner.isPresent() && !owner.get().equals(item.getId())) {
                            throw new BusinessException(
                                    ErrorCode.VALIDATION_ERROR,
                                    "A sense cannot move to another word.");
                        }
                    }
                    if (sense.hasNonNull("sense_no")) {
                        int pos = sense.path("sense_no").asInt();
                        if (!seenPositions.add(pos)) {
                            throw new BusinessException(
                                    ErrorCode.VALIDATION_ERROR, "Duplicate sense position: " + pos);
                        }
                    }
                    if (sense.has("examples") && sense.path("examples").isArray()) {
                        Set<Integer> exPositions = new HashSet<>();
                        for (JsonNode ex : sense.path("examples")) {
                            if (ex.hasNonNull("id")) {
                                UUID exId = parseUuid(ex.path("id").asText(), "example id");
                                if (!seenExampleIds.add(exId)) {
                                    throw new BusinessException(
                                            ErrorCode.VALIDATION_ERROR,
                                            "Duplicate example ID: " + exId);
                                }
                                var exOwner =
                                        revisionRepository.findOwnerItemIdByExampleProbe(
                                                "[{\"examples\":[{\"id\":\"" + exId + "\"}]}]");
                                if (exOwner.isPresent() && !exOwner.get().equals(item.getId())) {
                                    throw new BusinessException(
                                            ErrorCode.VALIDATION_ERROR,
                                            "An example cannot move to another word.");
                                }
                                if (exOwner.isPresent() && exOwner.get().equals(item.getId())) {
                                    var snapOpt =
                                            revisionRepository.findSnapshotContainingExample(
                                                    item.getId(),
                                                    "[{\"examples\":[{\"id\":\"" + exId + "\"}]}]");
                                    if (snapOpt.isPresent()) {
                                        JsonNode prevData = snapshots.data(snapOpt.get());
                                        for (JsonNode prevSense : prevData.path("senses")) {
                                            for (JsonNode prevEx : prevSense.path("examples")) {
                                                if (exId.toString()
                                                        .equals(prevEx.path("id").asText())) {
                                                    String origSenseId =
                                                            prevSense.path("id").asText();
                                                    if (sense.hasNonNull("id")
                                                            && !origSenseId.equals(
                                                                    sense.path("id").asText())) {
                                                        throw new BusinessException(
                                                                ErrorCode.VALIDATION_ERROR,
                                                                "An example cannot move to"
                                                                        + " another sense.");
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (ex.hasNonNull("position")) {
                                int epos = ex.path("position").asInt();
                                if (!exPositions.add(epos)) {
                                    throw new BusinessException(
                                            ErrorCode.VALIDATION_ERROR,
                                            "Duplicate example position: " + epos);
                                }
                            }
                        }
                    }
                }
            }
        }

        String snapshotJson = snapshots.envelope(item.getContentType(), data.toString());

        if (sourceIds == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "sourceIds must not be null.");
        }
        List<UUID> uniqueSources = sourceIds.stream().filter(Objects::nonNull).distinct().toList();
        for (UUID sourceId : uniqueSources) {
            if (!sourceRepository.existsById(sourceId)) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "Nguồn trích dẫn không tồn tại: " + sourceId);
            }
        }

        List<ContentSourceLink> existingLinks =
                sourceLinkRepository.findByIdRevisionId(revision.getId());
        sourceLinkRepository.deleteAll(existingLinks);
        sourceLinkRepository.flush();
        for (UUID sourceId : uniqueSources) {
            sourceLinkRepository.save(
                    new ContentSourceLink(
                            revision.getId(), sourceId, "Draft source attribution", null));
        }

        revision.updateDraft(snapshotJson, changeSummary);
        revision = revisionRepository.saveAndFlush(revision);

        auditLogRepository.save(
                new AuditLog(
                        "USER",
                        actorId,
                        "CONTENT_DRAFT_UPDATED",
                        item.getContentType(),
                        item.getId(),
                        String.format(
                                "{\"revisionId\":\"%s\",\"versionNo\":%d,\"status\":\"%s\"}",
                                revision.getId(), revision.getVersionNo(), revision.getStatus()),
                        changeSummary));

        return response(revision);
    }

    private List<Map<String, Object>> deckMembers(JsonNode membersNode, JsonNode wordSenseIdsNode) {
        List<Map<String, Object>> members = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();

        if (membersNode != null && membersNode.isArray()) {
            for (JsonNode m : membersNode) {
                UUID senseId;
                if (m.hasNonNull("word_sense_id")) {
                    senseId = parseUuid(m.path("word_sense_id").asText(), "word_sense_id");
                } else if (m.isTextual()) {
                    senseId = parseUuid(m.asText(), "word_sense_id");
                } else {
                    continue;
                }

                if (!seen.add(senseId)) {
                    throw new BusinessException(
                            ErrorCode.VALIDATION_ERROR, "Nghĩa từ bị chọn trùng: " + senseId);
                }

                var owner =
                        itemRepository
                                .findPublishedWordBySense("[{\"id\":\"" + senseId + "\"}]")
                                .orElseThrow(
                                        () ->
                                                new BusinessException(
                                                        ErrorCode.VALIDATION_ERROR,
                                                        "Nghĩa từ chưa thuộc từ nào đã xuất bản: "
                                                                + senseId));

                UUID pinnedRevId = null;
                if (m.hasNonNull("pinned_word_revision_id")) {
                    UUID requestedPin =
                            parseUuid(
                                    m.path("pinned_word_revision_id").asText(),
                                    "pinned_word_revision_id");
                    var revOpt = revisionRepository.findById(requestedPin);
                    if (revOpt.isPresent()
                            && revOpt.get().getContentItemId().equals(owner.getWordId())
                            && "PUBLISHED".equals(revOpt.get().getStatus())) {
                        JsonNode snapData = snapshots.data(revOpt.get().getSnapshot());
                        for (JsonNode s : snapData.path("senses")) {
                            if (senseId.toString().equals(s.path("id").asText())) {
                                pinnedRevId = requestedPin;
                                break;
                            }
                        }
                    }
                    if (pinnedRevId == null) {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR,
                                "pinned_word_revision_id is not a valid published revision"
                                        + " containing sense "
                                        + senseId);
                    }
                } else {
                    pinnedRevId = owner.getRevisionId();
                }

                Map<String, Object> member = new HashMap<>();
                member.put("word_id", owner.getWordId().toString());
                member.put("word_sense_id", senseId.toString());
                member.put("pinned_word_revision_id", pinnedRevId.toString());
                member.put("position", members.size() + 1);
                members.add(member);
            }
        } else if (wordSenseIdsNode != null && wordSenseIdsNode.isArray()) {
            for (JsonNode s : wordSenseIdsNode) {
                UUID senseId = parseUuid(s.asText(), "wordSenseId");
                if (!seen.add(senseId)) {
                    throw new BusinessException(
                            ErrorCode.VALIDATION_ERROR, "Nghĩa từ bị chọn trùng: " + senseId);
                }
                var owner =
                        itemRepository
                                .findPublishedWordBySense("[{\"id\":\"" + senseId + "\"}]")
                                .orElseThrow(
                                        () ->
                                                new BusinessException(
                                                        ErrorCode.VALIDATION_ERROR,
                                                        "Nghĩa từ chưa thuộc từ nào đã xuất bản: "
                                                                + senseId));
                Map<String, Object> member = new HashMap<>();
                member.put("word_id", owner.getWordId().toString());
                member.put("word_sense_id", senseId.toString());
                member.put("pinned_word_revision_id", owner.getRevisionId().toString());
                member.put("position", members.size() + 1);
                members.add(member);
            }
        }
        return members;
    }

    private void validateSourceFields(
            String title, String sourceType, String sourceUrl, String licenseUrl, String status) {
        if (title == null || title.isBlank() || title.length() > 255) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "Title must be between 1 and 255 characters.");
        }
        if (sourceType == null
                || (!"SELF_AUTHORED".equals(sourceType) && !"EXTERNAL".equals(sourceType))) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "sourceType must be SELF_AUTHORED or EXTERNAL.");
        }
        if (status != null && (!"ACTIVE".equals(status) && !"ARCHIVED".equals(status))) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "status must be ACTIVE or ARCHIVED.");
        }
        if (!HttpUrlValidator.isValid(sourceUrl)) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "sourceUrl must be an http or https URL.");
        }
        if (!HttpUrlValidator.isValid(licenseUrl)) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "licenseUrl must be an http or https URL.");
        }
    }

    private void validateDraftKnownFieldsAndDepth(String contentType, JsonNode data) {
        if (data.has("data")) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "Unsupported snapshot field: data");
        }
        if ("WORD".equals(contentType)) {
            if (jsonDepth(data) > MAX_JSON_DEPTH) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "Dữ liệu vượt quá độ sâu cho phép.");
            }
            assertOnlyKnownFields(data, WORD_KNOWN_FIELDS);
            if (data.has("senses") && data.path("senses").isArray()) {
                for (JsonNode sense : data.path("senses")) {
                    if (sense.isObject()) {
                        assertOnlyKnownFields(sense, WORD_SENSE_KNOWN_FIELDS);
                        if (sense.has("examples") && sense.path("examples").isArray()) {
                            for (JsonNode ex : sense.path("examples")) {
                                if (ex.isObject()) {
                                    assertOnlyKnownFields(ex, WORD_EXAMPLE_KNOWN_FIELDS);
                                }
                            }
                        }
                    }
                }
            }
        } else if ("DECK".equals(contentType)) {
            if (jsonDepth(data) > MAX_JSON_DEPTH) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "Dữ liệu vượt quá độ sâu cho phép.");
            }
            assertOnlyKnownFields(data, DECK_KNOWN_FIELDS);
            if (data.has("members") && data.path("members").isArray()) {
                for (JsonNode member : data.path("members")) {
                    if (member.isObject()) {
                        assertOnlyKnownFields(member, DECK_MEMBER_KNOWN_FIELDS);
                    }
                }
            }
        } else if ("GRAMMAR_TOPIC".equals(contentType)) {
            if (jsonDepth(data) > MAX_JSON_DEPTH) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "Dữ liệu vượt quá độ sâu cho phép.");
            }
            assertOnlyKnownFields(data, GRAMMAR_TOPIC_KNOWN_FIELDS);
            validateGrammarTopicDraftFields(data);
        } else if ("GRAMMAR_LESSON".equals(contentType)) {
            assertOnlyKnownFields(data, GRAMMAR_LESSON_KNOWN_FIELDS);
            validateGrammarLessonDraftFields(data);
        } else if ("QUESTION".equals(contentType)) {
            if (jsonDepth(data) > MAX_JSON_DEPTH) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "Dữ liệu vượt quá độ sâu cho phép.");
            }
            assertOnlyKnownFields(data, QUESTION_KNOWN_FIELDS);
            validateQuestionDraftFields(data);
        } else if ("QUESTION_GROUP".equals(contentType)) {
            if (jsonDepth(data) > MAX_JSON_DEPTH) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "Dữ liệu vượt quá độ sâu cho phép.");
            }
            assertOnlyKnownFields(data, QUESTION_GROUP_KNOWN_FIELDS);
            validateQuestionGroupDraftFields(data);
        } else if ("PRACTICE_SET".equals(contentType)) {
            if (jsonDepth(data) > MAX_JSON_DEPTH) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "Dữ liệu vượt quá độ sâu cho phép.");
            }
            assertOnlyKnownFields(data, PRACTICE_SET_KNOWN_FIELDS);
            validatePracticeSetDraftFields(data);
        }
    }

    private void validateQuestionGroupDraftFields(JsonNode data) {
        if (data.hasNonNull("category")) {
            if (!data.path("category").isTextual()) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "category must be a string.");
            }
            String cat = data.path("category").asText();
            if (!Set.of("GRAMMAR", "VOCABULARY", "TOEIC").contains(cat)) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "category phải là GRAMMAR, VOCABULARY hoặc TOEIC.");
            }
        }
        if (data.has("part_number") && !data.path("part_number").isNull()) {
            String cat = data.path("category").asText(null);
            if (!"TOEIC".equals(cat)) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "part_number phải là null đối với non-TOEIC.");
            }
            int part = data.path("part_number").asInt();
            if (!Set.of(3, 4, 6, 7).contains(part)) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "Nhóm câu hỏi TOEIC chỉ áp dụng cho Part 3, 4, 6 hoặc 7.");
            }
        }
        if (data.has("materials") && !data.path("materials").isArray()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "materials phải là mảng.");
        }
        if (data.has("children") && !data.path("children").isArray()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "children phải là mảng.");
        }
        if (data.hasNonNull("title")) {
            if (!data.path("title").isTextual()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "title must be a string.");
            }
            String title = data.path("title").asText();
            if (title.length() > 200) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "title vượt quá 200 ký tự.");
            }
            if (HTML_PATTERN.matcher(title).find()) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "title không được chứa thẻ HTML.");
            }
        }
    }

    private void validatePracticeSetDraftFields(JsonNode data) {
        if (data.hasNonNull("category")) {
            if (!data.path("category").isTextual()) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "category must be a string.");
            }
            String cat = data.path("category").asText();
            if (!Set.of("GRAMMAR", "VOCABULARY", "TOEIC").contains(cat)) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "category phải là GRAMMAR, VOCABULARY hoặc TOEIC.");
            }
        }
        if (data.hasNonNull("title")) {
            if (!data.path("title").isTextual()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "title must be a string.");
            }
            String title = data.path("title").asText();
            if (title.length() > 200) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "title vượt quá 200 ký tự.");
            }
            if (HTML_PATTERN.matcher(title).find()) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "title không được chứa thẻ HTML.");
            }
        }
        if (data.hasNonNull("selection_mode")) {
            String mode = data.path("selection_mode").asText();
            if (!Set.of("FIXED", "DYNAMIC").contains(mode)) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "selection_mode chỉ nhận FIXED hoặc DYNAMIC.");
            }
        }
        if (data.has("entries") && !data.path("entries").isArray()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "entries phải là mảng.");
        }
        if (data.has("selection_config") && !data.path("selection_config").isObject()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "selection_config phải là object.");
        }
        if (data.hasNonNull("time_limit_seconds")
                && (!data.path("time_limit_seconds").isInt()
                        || data.path("time_limit_seconds").asInt() <= 0)) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "time_limit_seconds phải là số nguyên dương.");
        }
    }

    private void validateQuestionDraftFields(JsonNode data) {
        if (data.hasNonNull("category")) {
            if (!data.path("category").isTextual()) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "category must be a string.");
            }
            String cat = data.path("category").asText();
            if (!Set.of("GRAMMAR", "VOCABULARY", "TOEIC").contains(cat)) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "category phải là GRAMMAR, VOCABULARY hoặc TOEIC.");
            }
        }
        if (data.has("part_number") && !data.path("part_number").isNull()) {
            String cat = data.path("category").asText(null);
            if (!"TOEIC".equals(cat)) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "part_number phải là null đối với non-TOEIC.");
            }
            int part = data.path("part_number").asInt();
            if (part < 1 || part > 7) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "part_number phải từ 1 đến 7.");
            }
        }
        if (data.has("group") && !data.path("group").isNull()) {
            // Group object validated in Chặng F
        }
        if (data.has("materials")) {
            JsonNode materials = data.path("materials");
            if (!materials.isArray()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "materials phải là mảng.");
            }
        }
        if (data.has("word_sense_ids")) {
            JsonNode wordSenseIds = data.path("word_sense_ids");
            if (!wordSenseIds.isArray()) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "word_sense_ids phải là mảng.");
            }
            if (wordSenseIds.size() > 10) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "word_sense_ids phải là mảng tối đa 10 phần tử.");
            }
        }
        if (data.hasNonNull("question_type_code")) {
            if (!data.path("question_type_code").isTextual()) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "question_type_code must be a string.");
            }
            if (!"SINGLE_CHOICE".equals(data.path("question_type_code").asText())) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "question_type_code chỉ nhận SINGLE_CHOICE.");
            }
        }
        if (data.hasNonNull("practice_mode")) {
            if (!data.path("practice_mode").isTextual()) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "practice_mode must be a string.");
            }
            if (!QUESTION_PRACTICE_MODES.contains(data.path("practice_mode").asText())) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "practice_mode không hợp lệ.");
            }
        }
        if (data.hasNonNull("difficulty")) {
            if (!data.path("difficulty").isTextual()) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "difficulty must be a string.");
            }
            if (!QUESTION_DIFFICULTIES.contains(data.path("difficulty").asText())) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "difficulty chỉ nhận EASY, MEDIUM hoặc HARD.");
            }
        }
        if (data.hasNonNull("prompt")) {
            if (!data.path("prompt").isTextual()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "prompt must be a string.");
            }
            String prompt = data.path("prompt").asText();
            if (HTML_PATTERN.matcher(prompt).find()) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "prompt không được chứa thẻ HTML.");
            }
            if (prompt.length() > 10_000) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "prompt vượt quá 10000 ký tự.");
            }
        }
        if (data.hasNonNull("explanation")) {
            if (!data.path("explanation").isTextual()) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "explanation must be a string.");
            }
            String explanation = data.path("explanation").asText();
            if (HTML_PATTERN.matcher(explanation).find()) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "explanation không được chứa thẻ HTML.");
            }
            if (explanation.length() > 20_000) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "explanation vượt quá 20000 ký tự.");
            }
        }
        if (data.hasNonNull("topic_ids")) {
            JsonNode topics = data.path("topic_ids");
            if (!topics.isArray()) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "topic_ids phải là mảng từ 1 đến 10 phần tử.");
            }
            if (topics.size() > 10) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "topic_ids phải là mảng từ 1 đến 10 phần tử.");
            }
            Set<UUID> seenTopics = new HashSet<>();
            for (JsonNode topic : topics) {
                if (!topic.isTextual()) {
                    throw new BusinessException(
                            ErrorCode.VALIDATION_ERROR, "topic_ids chứa UUID không hợp lệ.");
                }
                UUID topicId;
                try {
                    topicId = UUID.fromString(topic.asText().strip());
                } catch (IllegalArgumentException e) {
                    throw new BusinessException(
                            ErrorCode.VALIDATION_ERROR, "topic_ids chứa UUID không hợp lệ.");
                }
                if (!seenTopics.add(topicId)) {
                    throw new BusinessException(
                            ErrorCode.VALIDATION_ERROR, "topic_ids không được trùng nhau.");
                }
            }
        }
        Set<UUID> seenOptionIds = new HashSet<>();
        if (data.hasNonNull("options")) {
            JsonNode options = data.path("options");
            if (!options.isArray()) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "options phải là mảng từ 2 đến 6 phần tử.");
            }
            if (options.size() > 6) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "options phải là mảng từ 2 đến 6 phần tử.");
            }
            Set<Integer> seenPositions = new HashSet<>();
            for (JsonNode option : options) {
                if (!option.isObject()) {
                    throw new BusinessException(
                            ErrorCode.VALIDATION_ERROR, "Mỗi lựa chọn phải là object.");
                }
                assertOnlyKnownFields(option, QUESTION_OPTION_FIELDS);
                if (option.hasNonNull("id")) {
                    if (!option.path("id").isTextual()) {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR, "options.id phải là UUID.");
                    }
                    UUID optId;
                    try {
                        optId = UUID.fromString(option.path("id").asText().strip());
                    } catch (IllegalArgumentException e) {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR, "options.id phải là UUID.");
                    }
                    if (!seenOptionIds.add(optId)) {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR, "options.id không được trùng nhau.");
                    }
                }
                if (option.hasNonNull("position")) {
                    JsonNode posNode = option.path("position");
                    if (!posNode.isIntegralNumber()
                            || !posNode.canConvertToInt()
                            || posNode.asInt() < 1
                            || posNode.asInt() > 6
                            || !seenPositions.add(posNode.asInt())) {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR,
                                "position phải là số nguyên duy nhất từ 1 đến 6.");
                    }
                }
                if (option.hasNonNull("body")) {
                    if (!option.path("body").isTextual()) {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR, "options.body must be a string.");
                    }
                    String body = option.path("body").asText();
                    if (HTML_PATTERN.matcher(body).find()) {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR,
                                "options.body không được chứa thẻ HTML.");
                    }
                    if (body.length() > 2_000) {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR, "options.body vượt quá 2000 ký tự.");
                    }
                }
            }
        }
        if (data.hasNonNull("answer_key")) {
            JsonNode answerKey = data.path("answer_key");
            if (!answerKey.isObject()) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "answer_key phải là object.");
            }
            assertOnlyKnownFields(answerKey, QUESTION_ANSWER_KEY_FIELDS);
            if (answerKey.hasNonNull("correctOptionId")) {
                if (!answerKey.path("correctOptionId").isTextual()) {
                    throw new BusinessException(
                            ErrorCode.VALIDATION_ERROR, "answer_key.correctOptionId phải là UUID.");
                }
                UUID correctId;
                try {
                    correctId = UUID.fromString(answerKey.path("correctOptionId").asText().strip());
                } catch (IllegalArgumentException e) {
                    throw new BusinessException(
                            ErrorCode.VALIDATION_ERROR, "answer_key.correctOptionId phải là UUID.");
                }
            }
        }
    }

    private void validateGrammarTopicDraftFields(JsonNode data) {
        if (data.hasNonNull("slug")) {
            if (!data.path("slug").isTextual()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "slug must be a string.");
            }
            String slug = data.path("slug").asText();
            if (slug.length() > 120 || !SLUG_PATTERN.matcher(slug).matches()) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "slug must use lowercase letters, digits and hyphens, max 120 chars.");
            }
        }
        if (data.hasNonNull("name")) {
            if (!data.path("name").isTextual()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "name must be a string.");
            }
            String name = data.path("name").asText();
            if (HTML_PATTERN.matcher(name).find()) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "HTML is not allowed in grammar content.");
            }
            if (name.length() > 200) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "name must be at most 200 characters.");
            }
        }
        if (data.hasNonNull("description")) {
            if (!data.path("description").isTextual()) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "description must be a string.");
            }
            String desc = data.path("description").asText();
            if (HTML_PATTERN.matcher(desc).find()) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "HTML is not allowed in grammar content.");
            }
        }
        if (data.hasNonNull("difficulty")) {
            if (!data.path("difficulty").isTextual()) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "difficulty must be BASIC, INTERMEDIATE or ADVANCED.");
            }
            String diff = data.path("difficulty").asText();
            if (!GRAMMAR_TOPIC_DIFFICULTIES.contains(diff)) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "difficulty must be BASIC, INTERMEDIATE or ADVANCED.");
            }
        }
        if (data.hasNonNull("sort_order")) {
            JsonNode sortOrderNode = data.path("sort_order");
            if (!sortOrderNode.isIntegralNumber()
                    || !sortOrderNode.canConvertToInt()
                    || sortOrderNode.asInt() < 0) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "sort_order must be a non-negative integer.");
            }
        }
    }

    private void validateGrammarLessonDraftFields(JsonNode data) {
        if (data.hasNonNull("topic_id")) {
            if (!data.path("topic_id").isTextual()) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "topic_id must be a string.");
            }
            parseUuid(data.path("topic_id").asText(), "topic_id");
        }
        if (data.hasNonNull("pinned_topic_revision_id")) {
            if (!data.path("pinned_topic_revision_id").isTextual()) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "pinned_topic_revision_id must be a string.");
            }
            parseUuid(data.path("pinned_topic_revision_id").asText(), "pinned_topic_revision_id");
        }
        if (data.hasNonNull("title")) {
            if (!data.path("title").isTextual()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "title must be a string.");
            }
            String title = data.path("title").asText();
            if (HTML_PATTERN.matcher(title).find()) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "HTML is not allowed in grammar content.");
            }
            if (title.length() > 200) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "title must be at most 200 characters.");
            }
        }
        if (data.hasNonNull("slug")) {
            if (!data.path("slug").isTextual()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "slug must be a string.");
            }
            String slug = data.path("slug").asText();
            if (slug.length() > 120 || !SLUG_PATTERN.matcher(slug).matches()) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "slug must use lowercase letters, digits and hyphens, max 120 chars.");
            }
        }
        if (data.hasNonNull("position")) {
            JsonNode posNode = data.path("position");
            if (!posNode.isIntegralNumber()
                    || !posNode.canConvertToInt()
                    || posNode.asInt() < 0
                    || posNode.asInt() > 100_000) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "position must be an integer between 0 and 100000.");
            }
        }
        if (data.hasNonNull("body_schema_version")) {
            JsonNode schemaNode = data.path("body_schema_version");
            if (!schemaNode.isIntegralNumber()
                    || !schemaNode.canConvertToInt()
                    || schemaNode.asInt() != 1) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "body_schema_version must be 1.");
            }
        }
        if (data.hasNonNull("estimated_minutes")) {
            JsonNode minNode = data.path("estimated_minutes");
            if (!minNode.isIntegralNumber()
                    || !minNode.canConvertToInt()
                    || minNode.asInt() < 1
                    || minNode.asInt() > 600) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "estimated_minutes must be an integer between 1 and 600.");
            }
        }
        if (data.has("body") && !data.path("body").isNull()) {
            JsonNode body = data.path("body");
            if (!body.isObject()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "body must be an object.");
            }
            if (jsonDepth(body) > 16) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "body nesting must be at most 16 levels.");
            }
            String bodyJson = body.toString();
            if (bodyJson.codePointCount(0, bodyJson.length()) > 100_000) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "body must be at most 100000 characters.");
            }
            if (bodyJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024 * 1024) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR, "body must be at most 1 MiB in UTF-8.");
            }
            assertOnlyKnownFields(body, Set.of("blocks"));
            if (body.has("blocks") && !body.path("blocks").isNull()) {
                JsonNode blocks = body.path("blocks");
                if (!blocks.isArray()) {
                    throw new BusinessException(
                            ErrorCode.VALIDATION_ERROR, "body.blocks must be an array.");
                }
                for (JsonNode block : blocks) {
                    validateLessonBlock(block);
                }
            }
        }
    }

    private void validateLessonBlock(JsonNode block) {
        if (!block.isObject()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "block must be an object.");
        }
        if (!block.path("type").isTextual()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "block type must be a string.");
        }
        String type = block.path("type").asText("");
        if (!LESSON_BLOCK_TYPES.contains(type)) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "Unsupported block type: " + type);
        }
        switch (type) {
            case "heading" -> {
                assertOnlyKnownFields(block, HEADING_FIELDS);
                if (block.hasNonNull("text")) {
                    if (!block.path("text").isTextual()) {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR, "heading text must be a string.");
                    }
                    String text = block.path("text").asText();
                    if (HTML_PATTERN.matcher(text).find()) {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR,
                                "HTML is not allowed in grammar content.");
                    }
                    if (text.length() > 200) {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR, "heading text must be <= 200 chars.");
                    }
                }
            }
            case "paragraph" -> {
                assertOnlyKnownFields(block, PARAGRAPH_FIELDS);
                if (block.hasNonNull("text")) {
                    if (!block.path("text").isTextual()) {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR, "paragraph text must be a string.");
                    }
                    String text = block.path("text").asText();
                    if (HTML_PATTERN.matcher(text).find()) {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR,
                                "HTML is not allowed in grammar content.");
                    }
                    if (text.length() > 50_000) {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR,
                                "paragraph text must be <= 50000 chars.");
                    }
                }
            }
            case "note" -> {
                assertOnlyKnownFields(block, NOTE_FIELDS);
                if (block.hasNonNull("text")) {
                    if (!block.path("text").isTextual()) {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR, "note text must be a string.");
                    }
                    String text = block.path("text").asText();
                    if (HTML_PATTERN.matcher(text).find()) {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR,
                                "HTML is not allowed in grammar content.");
                    }
                    if (text.length() > 50_000) {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR, "note text must be <= 50000 chars.");
                    }
                }
            }
            case "example" -> {
                assertOnlyKnownFields(block, EXAMPLE_FIELDS);
                if (block.hasNonNull("example_en")) {
                    if (!block.path("example_en").isTextual()) {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR, "example_en must be a string.");
                    }
                    String en = block.path("example_en").asText();
                    if (HTML_PATTERN.matcher(en).find()) {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR,
                                "HTML is not allowed in grammar content.");
                    }
                    if (en.length() > 10_000) {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR, "example_en must be <= 10000 chars.");
                    }
                }
                if (block.hasNonNull("example_vi")) {
                    if (!block.path("example_vi").isTextual()) {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR, "example_vi must be a string.");
                    }
                    String vi = block.path("example_vi").asText();
                    if (HTML_PATTERN.matcher(vi).find()) {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR,
                                "HTML is not allowed in grammar content.");
                    }
                    if (vi.length() > 10_000) {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR, "example_vi must be <= 10000 chars.");
                    }
                }
            }
            case "table" -> {
                assertOnlyKnownFields(block, TABLE_FIELDS);
                if (block.has("rows") && !block.path("rows").isNull()) {
                    if (!block.path("rows").isArray()) {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR, "table rows must be an array.");
                    }
                    JsonNode rows = block.path("rows");
                    if (rows.size() > 100) {
                        throw new BusinessException(
                                ErrorCode.VALIDATION_ERROR, "table rows must be <= 100.");
                    }
                    for (JsonNode row : rows) {
                        if (!row.isArray()) {
                            throw new BusinessException(
                                    ErrorCode.VALIDATION_ERROR, "table row must be an array.");
                        }
                        if (row.size() > 20) {
                            throw new BusinessException(
                                    ErrorCode.VALIDATION_ERROR, "table row cells must be <= 20.");
                        }
                        for (JsonNode cell : row) {
                            if (!cell.isTextual()) {
                                throw new BusinessException(
                                        ErrorCode.VALIDATION_ERROR, "table cell must be a string.");
                            }
                            String cellText = cell.asText();
                            if (HTML_PATTERN.matcher(cellText).find()) {
                                throw new BusinessException(
                                        ErrorCode.VALIDATION_ERROR,
                                        "HTML is not allowed in grammar content.");
                            }
                            if (cellText.length() > 500) {
                                throw new BusinessException(
                                        ErrorCode.VALIDATION_ERROR,
                                        "table cell must be <= 500 chars.");
                            }
                        }
                    }
                }
            }
        }
    }

    private void assertOnlyKnownFields(JsonNode node, Set<String> allowed) {
        node.fieldNames()
                .forEachRemaining(
                        name -> {
                            if (!allowed.contains(name)) {
                                throw new BusinessException(
                                        ErrorCode.VALIDATION_ERROR,
                                        "Unsupported snapshot field: " + name);
                            }
                        });
    }

    private static int jsonDepth(JsonNode node) {
        if (node == null || !node.isContainerNode()) {
            return 0;
        }
        int maxChild = 0;
        for (JsonNode child : node) {
            maxChild = Math.max(maxChild, jsonDepth(child));
        }
        return 1 + maxChild;
    }

    private UUID parseUuid(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, fieldName + " is required.");
        }
        try {
            return UUID.fromString(value.strip());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR, "Invalid UUID for " + fieldName + ": " + value);
        }
    }

    @Override
    @Transactional
    public ValidationResult validateRevision(UUID revisionId) {
        ContentRevision revision =
                revisionRepository
                        .findById(revisionId)
                        .orElseThrow(() -> revisionNotFound(revisionId));
        ContentItem item =
                itemRepository
                        .findById(revision.getContentItemId())
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND,
                                                "Content item not found: "
                                                        + revision.getContentItemId()));

        List<FieldViolation> errors = new ArrayList<>();

        try {
            JsonNode data = snapshots.data(revision.getSnapshot());
            contentEvents.validateSnapshot(item.getId(), item.getContentType(), data);
        } catch (BusinessException ex) {
            errors.add(new FieldViolation("data", ex.getMessage()));
        }

        List<ContentSourceLink> links = sourceLinkRepository.findByIdRevisionId(revision.getId());
        if (links.isEmpty()) {
            errors.add(new FieldViolation("sources", "Published content needs a declared source."));
        } else {
            for (ContentSourceLink link : links) {
                var sourceOpt = sourceRepository.findById(link.getId().getSourceId());
                if (sourceOpt.isEmpty()) {
                    errors.add(
                            new FieldViolation(
                                    "sources",
                                    "Source unavailable: " + link.getId().getSourceId()));
                } else if (!sourceOpt.get().availableForPublication()) {
                    errors.add(
                            new FieldViolation(
                                    "sources", "Active source and declared permission required."));
                }
            }
        }

        if ("DECK".equals(item.getContentType())) {
            JsonNode data = snapshots.data(revision.getSnapshot());
            JsonNode members = data.path("members");
            if (!members.isArray() || members.isEmpty()) {
                errors.add(new FieldViolation("members", "Deck must contain at least one member."));
            } else {
                Set<UUID> seenSenses = new HashSet<>();
                for (int i = 0; i < members.size(); i++) {
                    JsonNode member = members.get(i);
                    String senseIdStr = member.path("word_sense_id").asText();
                    if (senseIdStr.isBlank()) {
                        errors.add(
                                new FieldViolation(
                                        "members[" + i + "].word_sense_id",
                                        "word_sense_id is required."));
                    } else {
                        UUID senseId = null;
                        try {
                            senseId = UUID.fromString(senseIdStr.strip());
                        } catch (IllegalArgumentException e) {
                            errors.add(
                                    new FieldViolation(
                                            "members[" + i + "].word_sense_id",
                                            "Invalid UUID format: " + senseIdStr));
                        }
                        if (senseId != null) {
                            if (!seenSenses.add(senseId)) {
                                errors.add(
                                        new FieldViolation(
                                                "members[" + i + "].word_sense_id",
                                                "Nghĩa từ bị chọn trùng: " + senseId));
                            }
                            var owner =
                                    itemRepository.findPublishedWordBySense(
                                            "[{\"id\":\"" + senseId + "\"}]");
                            if (owner.isEmpty()) {
                                errors.add(
                                        new FieldViolation(
                                                "members[" + i + "].word_sense_id",
                                                "Nghĩa từ chưa thuộc từ nào đã xuất bản: "
                                                        + senseId));
                            }
                        }
                    }
                }
            }
        }

        if ("GRAMMAR_LESSON".equals(item.getContentType())) {
            if (hasRevisionMediaRefs(revision.getId())) {
                errors.add(
                        new FieldViolation(
                                "revision_media_refs",
                                "Grammar lesson v1 must not reference media."));
            }
        }

        return new ValidationResult(errors.isEmpty(), errors);
    }

    private boolean hasRevisionMediaRefs(UUID revisionId) {
        return revisionRepository.hasRevisionMediaRefs(revisionId);
    }

    @Override
    @Transactional
    public ContentRevisionResponse submitForReview(UUID revisionId, UUID actorUserId) {
        identity.verifyContentAuthor(actorUserId);
        ContentRevision revision = revisionForUpdate(revisionId);
        submitRevision(revision, actorUserId);
        revision = revisionRepository.saveAndFlush(revision);
        return response(revision);
    }

    private void submitRevision(ContentRevision revision, UUID actorUserId) {
        revision.submitForReview();
        auditLogRepository.save(
                new AuditLog(
                        "USER",
                        actorUserId,
                        "CONTENT_REVIEW_REQUESTED",
                        "CONTENT_REVISION",
                        revision.getId(),
                        "{}",
                        "Submitted for review"));
    }

    @Override
    @Transactional
    public ContentRevisionResponse approve(UUID revisionId, UUID reviewerId, String reviewNote) {
        identity.verifyContentAuthor(reviewerId);
        ContentRevision revision = revisionForUpdate(revisionId);
        approveRevision(revision, reviewerId, reviewNote);
        revision = revisionRepository.saveAndFlush(revision);
        return response(revision);
    }

    private void approveRevision(ContentRevision revision, UUID reviewerId, String reviewNote) {
        revision.approve(reviewerId, reviewNote != null ? reviewNote : "Approved");
        auditLogRepository.save(
                new AuditLog(
                        "USER",
                        reviewerId,
                        "CONTENT_APPROVED",
                        "CONTENT_REVISION",
                        revision.getId(),
                        "{}",
                        reviewNote));
    }

    @Override
    @Transactional
    public ContentRevisionResponse reject(UUID revisionId, UUID reviewerId, String reviewNote) {
        identity.verifyContentAuthor(reviewerId);
        ContentRevision revision =
                revisionRepository
                        .findByIdForUpdate(revisionId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND,
                                                "Revision not found: " + revisionId));
        revision.reject(reviewerId, reviewNote);
        auditLogRepository.save(
                new AuditLog(
                        "USER",
                        reviewerId,
                        "CONTENT_REJECTED",
                        "CONTENT_REVISION",
                        revisionId,
                        "{}",
                        reviewNote));
        revision = revisionRepository.saveAndFlush(revision);
        return response(revision);
    }

    @Override
    @Transactional
    public void archiveItem(UUID itemId, String reason, UUID actorId) {
        identity.verifyContentAuthor(actorId);
        ContentItem item =
                itemRepository
                        .findByIdForUpdate(itemId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND,
                                                "Content item not found: " + itemId));
        item.archive(reason);
        itemRepository.saveAndFlush(item);
        auditLogRepository.saveAndFlush(
                new AuditLog(
                        "USER",
                        actorId,
                        "CONTENT_ARCHIVED",
                        item.getContentType(),
                        itemId,
                        "{}",
                        reason));
    }

    @Override
    @Transactional
    public void unpublishItem(UUID itemId, String reason, UUID actorId) {
        identity.verifyContentAuthor(actorId);
        ContentItem item =
                itemRepository
                        .findByIdForUpdate(itemId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND,
                                                "Content item not found: " + itemId));
        if (item.getPublishedRevisionId() == null) {
            throw new BusinessException(
                    ErrorCode.CONFLICT, "Only published content can be unpublished.");
        }
        if (!item.isArchived()) {
            item.unpublish();
            itemRepository.saveAndFlush(item);
            auditLogRepository.saveAndFlush(
                    new AuditLog(
                            "USER",
                            actorId,
                            "CONTENT_UNPUBLISHED",
                            item.getContentType(),
                            itemId,
                            "{}",
                            reason));
        }
    }

    private void publishApprovedQuestionChild(
            UUID groupId, ContentItem item, ContentRevision revision, UUID publisherId) {
        if (!"QUESTION".equals(item.getContentType())) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Child question has the wrong content type: " + item.getId());
        }
        itemRepository.flush();
        revisionRepository.flush();
        ContentItem lockedItem = itemRepository.findByIdForUpdate(item.getId()).orElseThrow();
        ContentRevision lockedRevision =
                revisionRepository.findByIdForUpdate(revision.getId()).orElseThrow();
        item = lockedItem;
        revision = lockedRevision;
        if (!revision.getContentItemId().equals(item.getId())
                || !"APPROVED".equals(revision.getStatus())) {
            throw new BusinessException(
                    ErrorCode.CONFLICT,
                    "Pinned child question revision is no longer approved for publication.");
        }
        if (!item.allowsRevisionChanges()) {
            throw new BusinessException(
                    ErrorCode.CONFLICT, "Child question is archived or access-revoked.");
        }
        publishRevision(item, revision, publisherId);
    }

    @Override
    @Transactional
    public ContentRevisionResponse publish(UUID revisionId, UUID publisherId) {
        identity.verifyContentAuthor(publisherId);
        ContentRevision revision =
                revisionRepository
                        .findById(revisionId)
                        .orElseThrow(() -> revisionNotFound(revisionId));

        ContentItem item =
                itemRepository
                        .findByIdForUpdate(revision.getContentItemId())
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND,
                                                "Content item not found: "
                                                        + revision.getContentItemId()));

        publishRevision(item, revision, publisherId);
        return response(revision);
    }

    private void publishRevision(ContentItem item, ContentRevision revision, UUID publisherId) {
        log.debug("Publishing content revision {}", revision.getId());
        if (!item.allowsRevisionChanges()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Content item cannot be edited.");
        }

        revisionRepository.flush();
        revisionRepository.refreshForUpdate(revision);
        if ("PUBLISHED".equals(revision.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Revision is already published.");
        }
        if (!"APPROVED".equals(revision.getStatus())) {
            throw new BusinessException(
                    ErrorCode.CONFLICT, "Only an explicitly approved revision can be published.");
        }
        publication.prepare(
                item,
                revision,
                publisherId,
                (groupId, childItem, childRevision) ->
                        publishApprovedQuestionChild(
                                groupId, childItem, childRevision, publisherId));
        revisionRepository.flush();
        revision.publish(publisherId);
        item.setPublishedRevisionId(revision.getId());
        publication.revisionPublished(item, revision);

        auditLogRepository.save(
                new AuditLog(
                        "USER",
                        publisherId,
                        "CONTENT_PUBLISHED",
                        item.getContentType(),
                        item.getId(),
                        String.format(
                                "{\"revisionId\":\"%s\",\"versionNo\":%d}",
                                revision.getId(), revision.getVersionNo()),
                        "Published content revision"));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasSource(UUID sourceId) {
        return sourceRepository.existsById(sourceId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> findPublishedRevisionId(String importNamespace, String externalKey) {
        return itemRepository
                .findByImportNamespaceAndExternalKey(importNamespace, externalKey)
                .map(ContentItem::getPublishedRevisionId);
    }

    @Override
    @Transactional
    public UUID createAndPublish(
            UUID itemId,
            String contentType,
            UUID createdBy,
            String importNamespace,
            String externalKey,
            String snapshotJson,
            String changeSummary,
            UUID sourceId) {
        identity.verifyContentAdmin(createdBy);
        Draft draft =
                createDraft(
                        itemId,
                        contentType,
                        createdBy,
                        importNamespace,
                        externalKey,
                        snapshotJson,
                        changeSummary,
                        sourceId != null ? List.of(sourceId) : List.of());
        submitRevision(draft.revision(), createdBy);
        approveRevision(draft.revision(), createdBy, "Explicit author approval");
        publishRevision(draft.item(), draft.revision(), createdBy);
        return draft.revision().getId();
    }

    private ContentRevision revisionForUpdate(UUID revisionId) {
        return revisionRepository
                .findByIdForUpdate(revisionId)
                .orElseThrow(() -> revisionNotFound(revisionId));
    }

    private static BusinessException revisionNotFound(UUID revisionId) {
        return new BusinessException(
                ErrorCode.RESOURCE_NOT_FOUND, "Revision not found: " + revisionId);
    }

    private record Draft(ContentItem item, ContentRevision revision) {}

    private static ContentItemResponse response(ContentItem item) {
        return new ContentItemResponse(
                item.getId(),
                item.getContentType(),
                item.getPublishedRevisionId(),
                item.getVersion(),
                item.getCreatedAt());
    }

    private static ContentRevisionResponse response(ContentRevision revision) {
        return new ContentRevisionResponse(
                revision.getId(),
                revision.getContentItemId(),
                revision.getVersionNo(),
                revision.getStatus(),
                revision.getSnapshot(),
                revision.getReviewedBy(),
                revision.getReviewedAt(),
                revision.getPublishedAt(),
                revision.getReviewNote(),
                revision.getUpdatedAt(),
                revision.getVersion());
    }

    private static ContentSourceResponse response(ContentSource source) {
        return new ContentSourceResponse(
                source.getId(),
                source.getTitle(),
                source.getSourceType(),
                source.getSourceUrl(),
                source.getAuthor(),
                source.getLicenseCode(),
                source.getLicenseUrl(),
                source.getAttribution(),
                source.getPermissionNote(),
                source.getStatus(),
                source.getCreatedAt());
    }
}
