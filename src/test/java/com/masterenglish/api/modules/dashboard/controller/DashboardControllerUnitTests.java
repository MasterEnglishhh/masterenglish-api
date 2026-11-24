package com.masterenglish.api.modules.dashboard.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.masterenglish.api.common.error.GlobalExceptionHandler;
import com.masterenglish.api.common.security.AuthenticatedUser;
import com.masterenglish.api.modules.dashboard.dto.response.DashboardResponse;
import com.masterenglish.api.modules.dashboard.dto.response.DashboardResponse.LearningGoalSummary;
import com.masterenglish.api.modules.dashboard.dto.response.DashboardResponse.NotificationSummary;
import com.masterenglish.api.modules.dashboard.dto.response.DashboardResponse.OverviewSummary;
import com.masterenglish.api.modules.dashboard.dto.response.DashboardResponse.PracticeStatisticsSummary;
import com.masterenglish.api.modules.dashboard.dto.response.DashboardResponse.RecommendationItem;
import com.masterenglish.api.modules.dashboard.dto.response.DashboardResponse.TodayReviewSummary;
import com.masterenglish.api.modules.dashboard.dto.response.DashboardResponse.VocabularyProgressSummary;
import com.masterenglish.api.modules.dashboard.dto.response.DashboardResponse.WeaknessAnalysisSummary;
import com.masterenglish.api.modules.dashboard.service.DashboardService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

class DashboardControllerUnitTests {

    private final UUID learnerId = UUID.randomUUID();
    private DashboardService serviceMock;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        serviceMock = mock(DashboardService.class);
        HandlerMethodArgumentResolver currentUserResolver =
                new HandlerMethodArgumentResolver() {
                    @Override
                    public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.hasParameterAnnotation(
                                        com.masterenglish.api.common.security.CurrentUser.class)
                                && AuthenticatedUser.class.isAssignableFrom(
                                        parameter.getParameterType());
                    }

                    @Override
                    public Object resolveArgument(
                            MethodParameter parameter,
                            ModelAndViewContainer mavContainer,
                            NativeWebRequest webRequest,
                            WebDataBinderFactory binderFactory) {
                        return new AuthenticatedUser() {
                            @Override
                            public UUID getId() {
                                return learnerId;
                            }

                            @Override
                            public String getEmail() {
                                return "learner@example.test";
                            }
                        };
                    }
                };
        mockMvc =
                MockMvcBuilders.standaloneSetup(new DashboardController(serviceMock))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .setCustomArgumentResolvers(currentUserResolver)
                        .build();
    }

    @Test
    @DisplayName("GET /api/v1/dashboard trả đầy đủ các nhóm dữ liệu dashboard")
    void getDashboardReturnsAggregatedPayload() throws Exception {
        var response =
                new DashboardResponse(
                        new OverviewSummary(
                                LocalDate.of(2026, 11, 25), "Asia/Ho_Chi_Minh", 3, true, 5),
                        new VocabularyProgressSummary(12, 4, 3, 7, 25, 10),
                        new TodayReviewSummary(3, 7, List.of()),
                        new LearningGoalSummary(
                                false, null, null, null, null, 3, true, null, false),
                        new PracticeStatisticsSummary(2, 1, 5, 4, 0, 80, false, List.of()),
                        new WeaknessAnalysisSummary(
                                "INSUFFICIENT_DATA", 30, 20, 70, 12, false, List.of()),
                        List.of(
                                new RecommendationItem(
                                        "FLASHCARD_REVIEW",
                                        "Ôn tập 3 thẻ Flashcard đến hạn",
                                        "Hoàn thành hàng ôn SRS hôm nay.",
                                        "/learn/review",
                                        1)),
                        new NotificationSummary(2));
        when(serviceMock.getLearnerDashboard(learnerId)).thenReturn(response);

        mockMvc.perform(get("/api/v1/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overview.streakDays").value(3))
                .andExpect(jsonPath("$.vocabularyProgress.studiedSenses").value(12))
                .andExpect(jsonPath("$.todayReview.dueNow").value(3))
                .andExpect(jsonPath("$.learningGoal.studyTimeTrackingAvailable").value(false))
                .andExpect(jsonPath("$.practiceStatistics.completedGrammarLessons").value(2))
                .andExpect(jsonPath("$.weaknessAnalysis.status").value("INSUFFICIENT_DATA"))
                .andExpect(jsonPath("$.recommendations[0].type").value("FLASHCARD_REVIEW"))
                .andExpect(jsonPath("$.notificationSummary.unreadCount").value(2));
    }
}
