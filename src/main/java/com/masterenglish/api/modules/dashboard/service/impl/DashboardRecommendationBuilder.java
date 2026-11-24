package com.masterenglish.api.modules.dashboard.service.impl;

import com.masterenglish.api.common.events.GrammarEvents.VisibleLessonView;
import com.masterenglish.api.common.events.LearningEvents.LessonProgressSummaryView;
import com.masterenglish.api.common.events.LearningEvents.VocabularyProgressView;
import com.masterenglish.api.modules.dashboard.dto.response.DashboardResponse.DueDeckItem;
import com.masterenglish.api.modules.dashboard.dto.response.DashboardResponse.RecommendationItem;
import com.masterenglish.api.modules.dashboard.dto.response.DashboardResponse.WeakTopicItem;
import java.util.ArrayList;
import java.util.Comparator;
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
            LessonProgressSummaryView lessonProgress) {
        List<RecommendationItem> items = new ArrayList<>();
        int priority = 1;

        if (vocab.dueNow() > 0) {
            items.add(
                    new RecommendationItem(
                            "FLASHCARD_REVIEW",
                            "Ôn tập " + vocab.dueNow() + " thẻ Flashcard đến hạn",
                            "Hoàn thành hàng ôn SRS hôm nay để giữ nhịp ghi nhớ.",
                            "/learn/review",
                            priority++));
            for (DueDeckItem deck : dueDecks) {
                items.add(
                        new RecommendationItem(
                                "DUE_DECK",
                                "Ôn bộ thẻ: " + deck.title(),
                                deck.dueCount() + " thẻ đang đến hạn trong bộ thẻ này.",
                                "/learn/vocabulary/decks/" + deck.deckId(),
                                priority++));
                break;
            }
        }

        for (WeakTopicItem weak : weakTopics) {
            if (items.size() >= 5) {
                break;
            }
            items.add(
                    new RecommendationItem(
                            "WEAK_TOPIC_PRACTICE",
                            "Cải thiện chủ điểm: " + weak.topicName(),
                            "Độ chính xác "
                                    + weak.accuracyPercent()
                                    + "% trên "
                                    + weak.questionCount()
                                    + " câu trong 30 ngày gần nhất.",
                            "/learn/grammar/topics/" + weak.topicId() + "/practice",
                            priority++));
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
                        .limit(2)
                        .toList();
        for (VisibleLessonView lesson : nextLessons) {
            if (items.size() >= 6) {
                break;
            }
            String prefix = inProgress.contains(lesson.id()) ? "Học tiếp: " : "Bài học mới: ";
            items.add(
                    new RecommendationItem(
                            "GRAMMAR_LESSON",
                            prefix + lesson.title(),
                            "Bài ngữ pháp tiếp theo trong lộ trình.",
                            "/learn/grammar/lessons/" + lesson.id(),
                            priority++));
        }

        if (vocab.dueNow() == 0 && vocab.savedSenses() > 0 && items.size() < 6) {
            items.add(
                    new RecommendationItem(
                            "SAVED_VOCABULARY",
                            "Xem lại " + vocab.savedSenses() + " từ đã lưu",
                            "Củng cố các từ vựng bạn đã đánh dấu.",
                            "/learn/saved-words",
                            priority));
        }

        return List.copyOf(items);
    }
}
