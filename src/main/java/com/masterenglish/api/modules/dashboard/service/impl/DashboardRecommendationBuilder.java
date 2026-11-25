package com.masterenglish.api.modules.dashboard.service.impl;

import com.masterenglish.api.common.events.GrammarEvents.VisibleLessonView;
import com.masterenglish.api.common.events.LearningEvents.LessonProgressSummaryView;
import com.masterenglish.api.common.events.LearningEvents.VocabularyProgressView;
import com.masterenglish.api.modules.dashboard.dto.response.DashboardResponse.DueDeckItem;
import com.masterenglish.api.modules.dashboard.dto.response.DashboardResponse.RecommendationItem;
import com.masterenglish.api.modules.dashboard.dto.response.DashboardResponse.WeakTopicItem;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Deterministic rule-based recommendation builder (M02.05); never uses AI Tutor. */
final class DashboardRecommendationBuilder {

    private DashboardRecommendationBuilder() {}

    static List<RecommendationItem> build(
            VocabularyProgressView vocab,
            List<DueDeckItem> dueDecks,
            List<WeakTopicItem> weakTopics,
            List<VisibleLessonView> visibleLessons,
            LessonProgressSummaryView lessonProgress,
            boolean grammarDeepLinksEnabled) {
        List<RecommendationItem> items = new ArrayList<>();
        Set<String> seenTargets = new HashSet<>();

        if (vocab.dueNow() > 0) {
            addUnique(
                    items,
                    seenTargets,
                    "FLASHCARD_REVIEW",
                    "VOCABULARY",
                    "DUE_FLASHCARDS",
                    "Ôn tập " + vocab.dueNow() + " thẻ Flashcard đến hạn",
                    "Hoàn thành hàng ôn SRS hôm nay để giữ nhịp ghi nhớ.",
                    "/learn/review");
            for (DueDeckItem deck : dueDecks) {
                if (addUnique(
                        items,
                        seenTargets,
                        "DUE_DECK",
                        "VOCABULARY",
                        "DUE_DECK_CARDS",
                        "Ôn bộ thẻ: " + deck.title(),
                        deck.dueCount() + " thẻ đang đến hạn trong bộ thẻ này.",
                        "/learn/review?deck=" + deck.deckId())) {
                    break;
                }
            }
        }

        if (grammarDeepLinksEnabled) {
            for (WeakTopicItem weak : weakTopics) {
                if (items.size() >= 5) {
                    break;
                }
                addUnique(
                        items,
                        seenTargets,
                        "WEAK_TOPIC_PRACTICE",
                        "GRAMMAR",
                        "WEAK_GRAMMAR_TOPIC",
                        "Cải thiện chủ điểm: " + weak.topicName(),
                        "Độ chính xác "
                                + weak.accuracyPercent()
                                + "% trên "
                                + weak.questionCount()
                                + " câu trong 30 ngày gần nhất.",
                        "/learn/grammar?topic=" + weak.topicId() + "&mode=practice");
            }

            Set<UUID> completed = lessonProgress.completedLessonIds();
            Set<UUID> inProgress = lessonProgress.inProgressLessonIds();
            List<VisibleLessonView> nextLessons =
                    visibleLessons.stream()
                            .filter(l -> !completed.contains(l.id()))
                            .sorted(
                                    Comparator.comparing(
                                                    (VisibleLessonView l) ->
                                                            !inProgress.contains(l.id()))
                                            .thenComparingInt(VisibleLessonView::sortOrder)
                                            .thenComparing(VisibleLessonView::id))
                            .toList();
            int lessonsAdded = 0;
            for (VisibleLessonView lesson : nextLessons) {
                if (lessonsAdded >= 2 || items.size() >= 6) {
                    break;
                }
                String prefix = inProgress.contains(lesson.id()) ? "Học tiếp: " : "Bài học mới: ";
                String reasonCode =
                        inProgress.contains(lesson.id())
                                ? "RESUME_GRAMMAR_LESSON"
                                : "NEXT_GRAMMAR_LESSON";
                if (addUnique(
                        items,
                        seenTargets,
                        "GRAMMAR_LESSON",
                        "GRAMMAR",
                        reasonCode,
                        prefix + lesson.title(),
                        "Bài ngữ pháp tiếp theo trong lộ trình.",
                        "/learn/grammar?lesson=" + lesson.id())) {
                    lessonsAdded++;
                }
            }
        }

        if (vocab.dueNow() == 0 && vocab.savedSenses() > 0 && items.size() < 6) {
            addUnique(
                    items,
                    seenTargets,
                    "SAVED_VOCABULARY",
                    "VOCABULARY",
                    "SAVED_FLASHCARDS",
                    "Xem lại " + vocab.savedSenses() + " từ đã lưu",
                    "Củng cố các từ vựng bạn đã đánh dấu.",
                    "/learn/vocabulary?view=saved");
        }

        return List.copyOf(items);
    }

    private static boolean addUnique(
            List<RecommendationItem> items,
            Set<String> seenTargets,
            String type,
            String skill,
            String reasonCode,
            String title,
            String description,
            String targetUrl) {
        if (!seenTargets.add(targetUrl)) {
            return false;
        }
        items.add(
                new RecommendationItem(
                        type, skill, reasonCode, title, description, targetUrl, items.size() + 1));
        return true;
    }
}
