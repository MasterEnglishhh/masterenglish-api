package com.masterenglish.api.modules.vocabulary.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.masterenglish.api.modules.content.authoring.ContentAuthoringService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class VocabularySeedServiceImpl implements VocabularySeedService {

    public static final UUID DEFAULT_SOURCE_ID =
            UUID.fromString("a0000000-0000-0000-0000-000000000001");
    public static final UUID DECK_1_ID = UUID.fromString("d1000000-0000-0000-0000-000000000001");
    public static final UUID DECK_2_ID = UUID.fromString("d2000000-0000-0000-0000-000000000002");

    private final ContentAuthoringService authoringService;
    private final ObjectMapper objectMapper;

    public VocabularySeedServiceImpl(
            ContentAuthoringService authoringService, ObjectMapper objectMapper) {
        this.authoringService = authoringService;
        this.objectMapper = objectMapper;
    }

    record ExampleDef(UUID id, int position, String en, String vi) {}

    record SenseDef(
            UUID id,
            int senseNo,
            String partOfSpeech,
            String cefrLevel,
            String meaningVi,
            String definitionEn,
            List<ExampleDef> examples) {}

    record WordDef(UUID id, String lemma, String languageCode, String ipa, List<SenseDef> senses) {}

    record DeckMemberDef(UUID wordId, UUID senseId, int position) {}

    record DeckDef(
            UUID id, String title, String description, String level, List<DeckMemberDef> members) {}

    @Override
    public void seedVocabularyData(UUID adminUserId) {
        ensureSourceExists(adminUserId);

        List<WordDef> wordDefs = createWordDefinitions();
        Map<UUID, UUID> wordToPublishedRevisionMap = new HashMap<>();

        for (WordDef w : wordDefs) {
            UUID publishedRevId = ensureWordPublished(w, adminUserId);
            wordToPublishedRevisionMap.put(w.id, publishedRevId);
        }

        List<DeckDef> deckDefs = createDeckDefinitions();
        for (DeckDef d : deckDefs) {
            ensureDeckPublished(d, wordToPublishedRevisionMap, adminUserId);
        }
    }

    @Override
    public void seedTopicVocabularyData(UUID adminUserId) {
        seedVocabularyData(adminUserId);
        try (var input = getClass().getResourceAsStream("/vocabulary/local-topics.json")) {
            if (input == null) throw new IllegalStateException("Missing local vocabulary topics.");
            var topics = objectMapper.readTree(input);
            Map<UUID, UUID> revisions = new HashMap<>();
            for (var topic : topics) {
                var members = new java.util.ArrayList<DeckMemberDef>();
                for (var word : topic.path("words")) {
                    String lemma = word.path("lemma").asText();
                    UUID wordId = topicId("word:" + lemma);
                    UUID senseId = topicId("sense:" + lemma);
                    WordDef definition =
                            new WordDef(
                                    wordId,
                                    lemma,
                                    "en",
                                    word.path("ipa").asText(),
                                    List.of(
                                            new SenseDef(
                                                    senseId,
                                                    1,
                                                    word.path("pos").asText(),
                                                    topic.path("level").asText(),
                                                    word.path("vi").asText(),
                                                    word.path("definition").asText(),
                                                    List.of(
                                                            new ExampleDef(
                                                                    topicId("example:" + lemma),
                                                                    1,
                                                                    word.path("exampleEn").asText(),
                                                                    word.path("exampleVi")
                                                                            .asText())))));
                    revisions.put(wordId, ensureWordPublished(definition, adminUserId));
                    members.add(new DeckMemberDef(wordId, senseId, members.size() + 1));
                }
                ensureDeckPublished(
                        new DeckDef(
                                topicId("deck:" + topic.path("key").asText()),
                                topic.path("title").asText(),
                                topic.path("description").asText(),
                                topic.path("level").asText(),
                                members),
                        revisions,
                        adminUserId);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read local vocabulary topics.", exception);
        }
    }

    private static UUID topicId(String key) {
        return UUID.nameUUIDFromBytes(
                ("masterenglish-local-topics-v1:" + key).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> starterWords() {
        Map<UUID, String> words = new java.util.LinkedHashMap<>();
        for (var word : createWordDefinitions()) words.put(word.id(), word.lemma());
        for (var topic : starterTopics()) {
            for (var word : topic.path("words")) {
                String lemma = word.path("lemma").asText();
                words.put(topicId("word:" + lemma), lemma);
            }
        }
        return Map.copyOf(words);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Set<UUID> starterDeckIds() {
        var decks = new java.util.HashSet<>(java.util.Set.of(DECK_1_ID, DECK_2_ID));
        for (var topic : starterTopics()) decks.add(topicId("deck:" + topic.path("key").asText()));
        return java.util.Set.copyOf(decks);
    }

    private com.fasterxml.jackson.databind.JsonNode starterTopics() {
        try (var input = getClass().getResourceAsStream("/vocabulary/local-topics.json")) {
            if (input == null)
                throw new IllegalStateException("Missing starter vocabulary topics.");
            return objectMapper.readTree(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read starter vocabulary topics.", exception);
        }
    }

    private void ensureSourceExists(UUID adminUserId) {
        if (!authoringService.hasSource(DEFAULT_SOURCE_ID)) {
            authoringService.createSource(
                    DEFAULT_SOURCE_ID,
                    "MasterEnglish Core Curriculum Source",
                    "SELF_AUTHORED",
                    "https://master.trinhdanghuy.id.vn/content/license",
                    "MasterEnglish Academic Team",
                    "PROPRIETARY_ME_2026",
                    null,
                    "© 2026 MasterEnglish Team. All rights reserved.",
                    "Self-authored educational content for IELTS/TOEIC preparation.",
                    adminUserId);
        }
    }

    private UUID ensureWordPublished(WordDef def, UUID adminUserId) {
        String extKey = "word:" + def.lemma;
        return authoringService
                .findPublishedRevisionId("masterenglish_core", extKey)
                .orElseGet(
                        () -> {
                            ObjectNode root = objectMapper.createObjectNode();
                            root.put("lemma", def.lemma);
                            root.put("language_code", def.languageCode);
                            root.put("ipa", def.ipa);
                            ArrayNode sensesNode = root.putArray("senses");

                            for (SenseDef s : def.senses) {
                                ObjectNode senseNode = sensesNode.addObject();
                                senseNode.put("id", s.id.toString());
                                senseNode.put("sense_no", s.senseNo);
                                senseNode.put("part_of_speech", s.partOfSpeech);
                                senseNode.put("cefr_level", s.cefrLevel);
                                senseNode.put("meaning_vi", s.meaningVi);
                                senseNode.put("definition_en", s.definitionEn);
                                ArrayNode examplesNode = senseNode.putArray("examples");
                                for (ExampleDef ex : s.examples) {
                                    ObjectNode exNode = examplesNode.addObject();
                                    exNode.put("id", ex.id.toString());
                                    exNode.put("position", ex.position);
                                    exNode.put("example_en", ex.en);
                                    exNode.put("example_vi", ex.vi);
                                }
                            }

                            return authoringService.createAndPublish(
                                    def.id,
                                    "WORD",
                                    adminUserId,
                                    "masterenglish_core",
                                    extKey,
                                    root.toString(),
                                    "Seed publication for " + def.lemma,
                                    DEFAULT_SOURCE_ID);
                        });
    }

    private void ensureDeckPublished(
            DeckDef def, Map<UUID, UUID> wordToPublishedRevisionMap, UUID adminUserId) {
        String extKey = "deck:" + def.id;
        if (authoringService.findPublishedRevisionId("masterenglish_core", extKey).isPresent()) {
            return;
        }

        ObjectNode root = objectMapper.createObjectNode();
        root.put("title", def.title);
        root.put("description", def.description);
        root.put("level", def.level);
        ArrayNode membersNode = root.putArray("members");

        for (DeckMemberDef m : def.members) {
            ObjectNode memberNode = membersNode.addObject();
            memberNode.put("word_id", m.wordId.toString());
            memberNode.put("word_sense_id", m.senseId.toString());
            UUID pinnedRev = wordToPublishedRevisionMap.get(m.wordId);
            memberNode.put("pinned_word_revision_id", pinnedRev.toString());
            memberNode.put("position", m.position);
        }

        authoringService.createAndPublish(
                def.id,
                "DECK",
                adminUserId,
                "masterenglish_core",
                extKey,
                root.toString(),
                "Seed publication for deck " + def.title,
                DEFAULT_SOURCE_ID);
    }

    private List<WordDef> createWordDefinitions() {
        return List.of(
                // Word 1: approach (multi-sense)
                new WordDef(
                        UUID.fromString("10000000-0000-0000-0000-000000000001"),
                        "approach",
                        "en",
                        "/əˈprəʊtʃ/",
                        List.of(
                                new SenseDef(
                                        UUID.fromString("20000000-0000-0000-0000-000000000001"),
                                        1,
                                        "NOUN",
                                        "B1",
                                        "cách tiếp cận, phương pháp",
                                        "a way of dealing with somebody/something",
                                        List.of(
                                                new ExampleDef(
                                                        UUID.fromString(
                                                                "30000000-0000-0000-0000-000000000001"),
                                                        1,
                                                        "We need a new approach to this problem.",
                                                        "Chúng ta cần một cách tiếp cận mới cho vấn đề này."))),
                                new SenseDef(
                                        UUID.fromString("20000000-0000-0000-0000-000000000002"),
                                        2,
                                        "VERB",
                                        "B1",
                                        "tiếp cận, đến gần",
                                        "to come near to somebody/something in distance or time",
                                        List.of(
                                                new ExampleDef(
                                                        UUID.fromString(
                                                                "30000000-0000-0000-0000-000000000002"),
                                                        1,
                                                        "Winter is approaching quickly.",
                                                        "Mùa đông đang đến rất nhanh."))))),

                // Word 2: schedule (multi-sense)
                new WordDef(
                        UUID.fromString("10000000-0000-0000-0000-000000000002"),
                        "schedule",
                        "en",
                        "/ˈʃedjuːl/",
                        List.of(
                                new SenseDef(
                                        UUID.fromString("20000000-0000-0000-0000-000000000003"),
                                        1,
                                        "NOUN",
                                        "B1",
                                        "lịch trình, thời gian biểu",
                                        "a plan of things that will happen and the times they will happen",
                                        List.of(
                                                new ExampleDef(
                                                        UUID.fromString(
                                                                "30000000-0000-0000-0000-000000000003"),
                                                        1,
                                                        "The meeting is on our schedule for Friday.",
                                                        "Cuộc họp nằm trong lịch trình của chúng ta vào thứ Sáu."))),
                                new SenseDef(
                                        UUID.fromString("20000000-0000-0000-0000-000000000004"),
                                        2,
                                        "VERB",
                                        "B1",
                                        "lên lịch, sắp xếp thời gian",
                                        "to arrange that an event or activity will happen at a particular time",
                                        List.of(
                                                new ExampleDef(
                                                        UUID.fromString(
                                                                "30000000-0000-0000-0000-000000000004"),
                                                        1,
                                                        "The flight is scheduled to arrive at noon.",
                                                        "Chuyến bay được lên lịch hạ cánh lúc giữa trưa."))))),

                // Word 3: confirm
                new WordDef(
                        UUID.fromString("10000000-0000-0000-0000-000000000003"),
                        "confirm",
                        "en",
                        "/kənˈfɜːm/",
                        List.of(
                                new SenseDef(
                                        UUID.fromString("20000000-0000-0000-0000-000000000005"),
                                        1,
                                        "VERB",
                                        "B1",
                                        "xác nhận",
                                        "to state that something is definitely true or correct",
                                        List.of(
                                                new ExampleDef(
                                                        UUID.fromString(
                                                                "30000000-0000-0000-0000-000000000005"),
                                                        1,
                                                        "Please confirm your appointment by email.",
                                                        "Vui lòng xác nhận lịch hẹn qua email."))))),

                // Word 4: budget
                new WordDef(
                        UUID.fromString("10000000-0000-0000-0000-000000000004"),
                        "budget",
                        "en",
                        "/ˈbʌdʒɪt/",
                        List.of(
                                new SenseDef(
                                        UUID.fromString("20000000-0000-0000-0000-000000000006"),
                                        1,
                                        "NOUN",
                                        "B1",
                                        "ngân sách",
                                        "the money that is available to a person or an organization and a plan of how it will be spent",
                                        List.of(
                                                new ExampleDef(
                                                        UUID.fromString(
                                                                "30000000-0000-0000-0000-000000000006"),
                                                        1,
                                                        "We must stay within the project budget.",
                                                        "Chúng ta phải giữ chi tiêu trong giới hạn ngân sách dự án."))))),

                // Word 5: deadline
                new WordDef(
                        UUID.fromString("10000000-0000-0000-0000-000000000005"),
                        "deadline",
                        "en",
                        "/ˈdedlaɪn/",
                        List.of(
                                new SenseDef(
                                        UUID.fromString("20000000-0000-0000-0000-000000000007"),
                                        1,
                                        "NOUN",
                                        "B1",
                                        "hạn chót",
                                        "a point in time by which something must be done",
                                        List.of(
                                                new ExampleDef(
                                                        UUID.fromString(
                                                                "30000000-0000-0000-0000-000000000007"),
                                                        1,
                                                        "The deadline for submitting the report is tomorrow.",
                                                        "Hạn chót nộp báo cáo là ngày mai."))))),

                // Word 6: efficient
                new WordDef(
                        UUID.fromString("10000000-0000-0000-0000-000000000006"),
                        "efficient",
                        "en",
                        "/ɪˈfɪʃnt/",
                        List.of(
                                new SenseDef(
                                        UUID.fromString("20000000-0000-0000-0000-000000000008"),
                                        1,
                                        "ADJECTIVE",
                                        "B1",
                                        "hiệu quả, năng suất cao",
                                        "doing something in a good, careful and complete way with no waste of time or money",
                                        List.of(
                                                new ExampleDef(
                                                        UUID.fromString(
                                                                "30000000-0000-0000-0000-000000000008"),
                                                        1,
                                                        "We need a more efficient workflow.",
                                                        "Chúng ta cần một quy trình làm việc hiệu quả hơn."))))),

                // Word 7: priority
                new WordDef(
                        UUID.fromString("10000000-0000-0000-0000-000000000007"),
                        "priority",
                        "en",
                        "/praɪˈɒrəti/",
                        List.of(
                                new SenseDef(
                                        UUID.fromString("20000000-0000-0000-0000-000000000009"),
                                        1,
                                        "NOUN",
                                        "B1",
                                        "sự ưu tiên",
                                        "something that you think is more important than other things",
                                        List.of(
                                                new ExampleDef(
                                                        UUID.fromString(
                                                                "30000000-0000-0000-0000-000000000009"),
                                                        1,
                                                        "Customer satisfaction is our top priority.",
                                                        "Sự hài lòng của khách hàng là ưu tiên hàng đầu của chúng tôi."))))),

                // Word 8: feedback
                new WordDef(
                        UUID.fromString("10000000-0000-0000-0000-000000000008"),
                        "feedback",
                        "en",
                        "/ˈfiːdbæk/",
                        List.of(
                                new SenseDef(
                                        UUID.fromString("20000000-0000-0000-0000-000000000010"),
                                        1,
                                        "NOUN",
                                        "B1",
                                        "ý kiến phản hồi",
                                        "advice, criticism or information about how good or useful something is",
                                        List.of(
                                                new ExampleDef(
                                                        UUID.fromString(
                                                                "30000000-0000-0000-0000-000000000010"),
                                                        1,
                                                        "We welcome constructive feedback from our users.",
                                                        "Chúng tôi hoan nghênh những phản hồi mang tính xây dựng từ người dùng."))))),

                // Word 9: resource
                new WordDef(
                        UUID.fromString("10000000-0000-0000-0000-000000000009"),
                        "resource",
                        "en",
                        "/rɪˈsɔːs/",
                        List.of(
                                new SenseDef(
                                        UUID.fromString("20000000-0000-0000-0000-000000000011"),
                                        1,
                                        "NOUN",
                                        "B1",
                                        "nguồn lực, tài nguyên",
                                        "a supply of something that a country, an organization or a person has and can use",
                                        List.of(
                                                new ExampleDef(
                                                        UUID.fromString(
                                                                "30000000-0000-0000-0000-000000000011"),
                                                        1,
                                                        "Time is our most valuable resource.",
                                                        "Thời gian là nguồn lực quý giá nhất của chúng ta."))))),

                // Word 10: negotiate
                new WordDef(
                        UUID.fromString("10000000-0000-0000-0000-000000000010"),
                        "negotiate",
                        "en",
                        "/nɪˈɡəʊʃieɪt/",
                        List.of(
                                new SenseDef(
                                        UUID.fromString("20000000-0000-0000-0000-000000000012"),
                                        1,
                                        "VERB",
                                        "B2",
                                        "đàm phán, thương lượng",
                                        "to try to reach an agreement by formal discussion",
                                        List.of(
                                                new ExampleDef(
                                                        UUID.fromString(
                                                                "30000000-0000-0000-0000-000000000012"),
                                                        1,
                                                        "The two companies negotiated a fair deal.",
                                                        "Hai công ty đã thương lượng được một thỏa thuận công bằng."))))),

                // Word 11: strategy
                new WordDef(
                        UUID.fromString("10000000-0000-0000-0000-000000000011"),
                        "strategy",
                        "en",
                        "/ˈstrætədʒi/",
                        List.of(
                                new SenseDef(
                                        UUID.fromString("20000000-0000-0000-0000-000000000013"),
                                        1,
                                        "NOUN",
                                        "B2",
                                        "chiến lược",
                                        "a plan that is intended to achieve a particular purpose",
                                        List.of(
                                                new ExampleDef(
                                                        UUID.fromString(
                                                                "30000000-0000-0000-0000-000000000013"),
                                                        1,
                                                        "Our long-term business strategy focuses on sustainability.",
                                                        "Chiến lược kinh doanh dài hạn của chúng tôi tập trung vào sự bền vững."))))),

                // Word 12: collaborate
                new WordDef(
                        UUID.fromString("10000000-0000-0000-0000-000000000012"),
                        "collaborate",
                        "en",
                        "/kəˈlæbəreɪt/",
                        List.of(
                                new SenseDef(
                                        UUID.fromString("20000000-0000-0000-0000-000000000014"),
                                        1,
                                        "VERB",
                                        "B2",
                                        "hợp tác, cộng tác",
                                        "to work together with somebody in order to produce or achieve something",
                                        List.of(
                                                new ExampleDef(
                                                        UUID.fromString(
                                                                "30000000-0000-0000-0000-000000000014"),
                                                        1,
                                                        "Researchers collaborated across several universities.",
                                                        "Các nhà nghiên cứu đã hợp tác giữa nhiều trường đại học."))))),

                // Word 13: innovate
                new WordDef(
                        UUID.fromString("10000000-0000-0000-0000-000000000013"),
                        "innovate",
                        "en",
                        "/ˈɪnəveɪt/",
                        List.of(
                                new SenseDef(
                                        UUID.fromString("20000000-0000-0000-0000-000000000015"),
                                        1,
                                        "VERB",
                                        "B2",
                                        "đổi mới, sáng tạo",
                                        "to introduce new things, ideas or ways of doing something",
                                        List.of(
                                                new ExampleDef(
                                                        UUID.fromString(
                                                                "30000000-0000-0000-0000-000000000015"),
                                                        1,
                                                        "Tech firms must innovate to stay competitive.",
                                                        "Các công ty công nghệ phải đổi mới để duy trì tính cạnh tranh."))))),

                // Word 14: analyze
                new WordDef(
                        UUID.fromString("10000000-0000-0000-0000-000000000014"),
                        "analyze",
                        "en",
                        "/ˈænəlaɪz/",
                        List.of(
                                new SenseDef(
                                        UUID.fromString("20000000-0000-0000-0000-000000000016"),
                                        1,
                                        "VERB",
                                        "B2",
                                        "phân tích",
                                        "to examine the nature or structure of something, especially by separating it into its parts",
                                        List.of(
                                                new ExampleDef(
                                                        UUID.fromString(
                                                                "30000000-0000-0000-0000-000000000016"),
                                                        1,
                                                        "We need to analyze the quarterly survey data.",
                                                        "Chúng ta cần phân tích dữ liệu khảo sát hàng quý."))))),

                // Word 15: objective
                new WordDef(
                        UUID.fromString("10000000-0000-0000-0000-000000000015"),
                        "objective",
                        "en",
                        "/əbˈdʒektɪv/",
                        List.of(
                                new SenseDef(
                                        UUID.fromString("20000000-0000-0000-0000-000000000017"),
                                        1,
                                        "NOUN",
                                        "B2",
                                        "mục tiêu",
                                        "something that you are trying to achieve",
                                        List.of(
                                                new ExampleDef(
                                                        UUID.fromString(
                                                                "30000000-0000-0000-0000-000000000017"),
                                                        1,
                                                        "Our primary objective is to improve learner retention.",
                                                        "Mục tiêu hàng đầu của chúng tôi là cải thiện khả năng ghi nhớ của học viên."))))),

                // Word 16: proposal
                new WordDef(
                        UUID.fromString("10000000-0000-0000-0000-000000000016"),
                        "proposal",
                        "en",
                        "/prəˈpəʊzl/",
                        List.of(
                                new SenseDef(
                                        UUID.fromString("20000000-0000-0000-0000-000000000018"),
                                        1,
                                        "NOUN",
                                        "B2",
                                        "đề xuất, bản kế hoạch đề nghị",
                                        "a formal suggestion or plan; the act of making a suggestion",
                                        List.of(
                                                new ExampleDef(
                                                        UUID.fromString(
                                                                "30000000-0000-0000-0000-000000000018"),
                                                        1,
                                                        "The committee approved the budget proposal.",
                                                        "Ủy ban đã phê duyệt bản đề xuất ngân sách."))))),

                // Word 17: implement
                new WordDef(
                        UUID.fromString("10000000-0000-0000-0000-000000000017"),
                        "implement",
                        "en",
                        "/ˈɪmplɪment/",
                        List.of(
                                new SenseDef(
                                        UUID.fromString("20000000-0000-0000-0000-000000000019"),
                                        1,
                                        "VERB",
                                        "B2",
                                        "triển khai, thực thi",
                                        "to make something that has been officially decided start to happen or be used",
                                        List.of(
                                                new ExampleDef(
                                                        UUID.fromString(
                                                                "30000000-0000-0000-0000-000000000019"),
                                                        1,
                                                        "The team will implement the new policy next month.",
                                                        "Nhóm sẽ triển khai chính sách mới vào tháng tới."))))),

                // Word 18: comprehensive
                new WordDef(
                        UUID.fromString("10000000-0000-0000-0000-000000000018"),
                        "comprehensive",
                        "en",
                        "/ˌkɒmprɪˈhensɪv/",
                        List.of(
                                new SenseDef(
                                        UUID.fromString("20000000-0000-0000-0000-000000000020"),
                                        1,
                                        "ADJECTIVE",
                                        "B2",
                                        "toàn diện, bao quát",
                                        "including all, or almost all, the items, details, facts, information, etc., that may be concerned",
                                        List.of(
                                                new ExampleDef(
                                                        UUID.fromString(
                                                                "30000000-0000-0000-0000-000000000020"),
                                                        1,
                                                        "This is a comprehensive study of language acquisition.",
                                                        "Đây là một nghiên cứu toàn diện về việc tiếp thu ngôn ngữ."))))),

                // Word 19: significant
                new WordDef(
                        UUID.fromString("10000000-0000-0000-0000-000000000019"),
                        "significant",
                        "en",
                        "/sɪɡˈnɪfɪkənt/",
                        List.of(
                                new SenseDef(
                                        UUID.fromString("20000000-0000-0000-0000-000000000021"),
                                        1,
                                        "ADJECTIVE",
                                        "B2",
                                        "đáng kể, quan trọng",
                                        "large or important enough to have an effect or to be noticed",
                                        List.of(
                                                new ExampleDef(
                                                        UUID.fromString(
                                                                "30000000-0000-0000-0000-000000000021"),
                                                        1,
                                                        "There has been a significant increase in test scores.",
                                                        "Đã có sự gia tăng đáng kể về điểm số bài kiểm tra."))))));
    }

    private List<DeckDef> createDeckDefinitions() {
        // Deck 1: Workplace & Daily Communication (12 senses)
        // Includes approach sense 1, approach sense 2, schedule sense 1, schedule sense 2, confirm,
        // budget, deadline, efficient, priority, feedback, resource, negotiate
        List<DeckMemberDef> deck1Members =
                List.of(
                        new DeckMemberDef(
                                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                                UUID.fromString("20000000-0000-0000-0000-000000000001"),
                                1), // approach (noun) - also in Deck 2
                        new DeckMemberDef(
                                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                                2), // approach (verb)
                        new DeckMemberDef(
                                UUID.fromString("10000000-0000-0000-0000-000000000002"),
                                UUID.fromString("20000000-0000-0000-0000-000000000003"),
                                3), // schedule (noun)
                        new DeckMemberDef(
                                UUID.fromString("10000000-0000-0000-0000-000000000002"),
                                UUID.fromString("20000000-0000-0000-0000-000000000004"),
                                4), // schedule (verb)
                        new DeckMemberDef(
                                UUID.fromString("10000000-0000-0000-0000-000000000003"),
                                UUID.fromString("20000000-0000-0000-0000-000000000005"),
                                5), // confirm
                        new DeckMemberDef(
                                UUID.fromString("10000000-0000-0000-0000-000000000004"),
                                UUID.fromString("20000000-0000-0000-0000-000000000006"),
                                6), // budget
                        new DeckMemberDef(
                                UUID.fromString("10000000-0000-0000-0000-000000000005"),
                                UUID.fromString("20000000-0000-0000-0000-000000000007"),
                                7), // deadline
                        new DeckMemberDef(
                                UUID.fromString("10000000-0000-0000-0000-000000000006"),
                                UUID.fromString("20000000-0000-0000-0000-000000000008"),
                                8), // efficient
                        new DeckMemberDef(
                                UUID.fromString("10000000-0000-0000-0000-000000000007"),
                                UUID.fromString("20000000-0000-0000-0000-000000000009"),
                                9), // priority
                        new DeckMemberDef(
                                UUID.fromString("10000000-0000-0000-0000-000000000008"),
                                UUID.fromString("20000000-0000-0000-0000-000000000010"),
                                10), // feedback
                        new DeckMemberDef(
                                UUID.fromString("10000000-0000-0000-0000-000000000009"),
                                UUID.fromString("20000000-0000-0000-0000-000000000011"),
                                11), // resource
                        new DeckMemberDef(
                                UUID.fromString("10000000-0000-0000-0000-000000000010"),
                                UUID.fromString("20000000-0000-0000-0000-000000000012"),
                                12) // negotiate
                        );

        // Deck 2: Academic & Strategic Essentials (10 senses)
        // Includes approach sense 1 (shared with Deck 1!), strategy, collaborate, innovate,
        // analyze, objective, proposal, implement, comprehensive, significant
        List<DeckMemberDef> deck2Members =
                List.of(
                        new DeckMemberDef(
                                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                                UUID.fromString("20000000-0000-0000-0000-000000000001"),
                                1), // approach (noun) - shared with Deck 1!
                        new DeckMemberDef(
                                UUID.fromString("10000000-0000-0000-0000-000000000011"),
                                UUID.fromString("20000000-0000-0000-0000-000000000013"),
                                2), // strategy
                        new DeckMemberDef(
                                UUID.fromString("10000000-0000-0000-0000-000000000012"),
                                UUID.fromString("20000000-0000-0000-0000-000000000014"),
                                3), // collaborate
                        new DeckMemberDef(
                                UUID.fromString("10000000-0000-0000-0000-000000000013"),
                                UUID.fromString("20000000-0000-0000-0000-000000000015"),
                                4), // innovate
                        new DeckMemberDef(
                                UUID.fromString("10000000-0000-0000-0000-000000000014"),
                                UUID.fromString("20000000-0000-0000-0000-000000000016"),
                                5), // analyze
                        new DeckMemberDef(
                                UUID.fromString("10000000-0000-0000-0000-000000000015"),
                                UUID.fromString("20000000-0000-0000-0000-000000000017"),
                                6), // objective
                        new DeckMemberDef(
                                UUID.fromString("10000000-0000-0000-0000-000000000016"),
                                UUID.fromString("20000000-0000-0000-0000-000000000018"),
                                7), // proposal
                        new DeckMemberDef(
                                UUID.fromString("10000000-0000-0000-0000-000000000017"),
                                UUID.fromString("20000000-0000-0000-0000-000000000019"),
                                8), // implement
                        new DeckMemberDef(
                                UUID.fromString("10000000-0000-0000-0000-000000000018"),
                                UUID.fromString("20000000-0000-0000-0000-000000000020"),
                                9), // comprehensive
                        new DeckMemberDef(
                                UUID.fromString("10000000-0000-0000-0000-000000000019"),
                                UUID.fromString("20000000-0000-0000-0000-000000000021"),
                                10) // significant
                        );

        return List.of(
                new DeckDef(
                        DECK_1_ID,
                        "Workplace & Daily Communication",
                        "Các từ vựng thiết yếu cho giao tiếp công sở, đàm phán và sắp xếp công việc hàng ngày.",
                        "B1",
                        deck1Members),
                new DeckDef(
                        DECK_2_ID,
                        "Academic & Strategic Essentials",
                        "Các từ vựng học thuật, tư duy phân tích và hoạch định chiến lược nâng cao.",
                        "B2",
                        deck2Members));
    }
}
