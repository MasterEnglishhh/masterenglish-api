package com.masterenglish.api.modules.admin.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterenglish.api.TestcontainersConfiguration;
import com.masterenglish.api.common.security.RoleCode;
import com.masterenglish.api.modules.admin.dto.system.UpdateFeatureFlagRequest;
import com.masterenglish.api.modules.admin.dto.user.LockUserRequest;
import com.masterenglish.api.modules.admin.dto.user.UpdateRolesRequest;
import com.masterenglish.api.modules.content.admin.CreateContentSourceRequest;
import com.masterenglish.api.modules.content.admin.CreateWordDraftRequest;
import com.masterenglish.api.modules.identity.entity.Role;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.repository.RoleRepository;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import com.masterenglish.api.modules.identity.security.UserPrincipal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
@org.junit.jupiter.api.condition.EnabledIf("isDockerAvailable")
class AdminApiIntegrationTests {

    static boolean isDockerAvailable() {
        try {
            org.testcontainers.DockerClientFactory.instance().client();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;

    private MockMvc mockMvc;
    private User systemAdminUser;
    private User contentAdminUser;
    private User learnerUser;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        Role adminRole = roleRepository.findByCode(RoleCode.SYSTEM_ADMIN).orElseThrow();
        Role contentRole = roleRepository.findByCode(RoleCode.CONTENT_ADMIN).orElseThrow();
        Role learnerRole = roleRepository.findByCode(RoleCode.LEARNER).orElseThrow();

        systemAdminUser = new User("sysadmin@test.com", "hash", "System", "Admin");
        systemAdminUser.addRole(adminRole);
        systemAdminUser.markEmailVerified(Instant.now());
        systemAdminUser = userRepository.saveAndFlush(systemAdminUser);

        contentAdminUser = new User("contentadmin@test.com", "hash", "Content", "Admin");
        contentAdminUser.addRole(contentRole);
        contentAdminUser.markEmailVerified(Instant.now());
        contentAdminUser = userRepository.saveAndFlush(contentAdminUser);

        learnerUser = new User("learner@test.com", "hash", "Learner", "User");
        learnerUser.addRole(learnerRole);
        learnerUser.markEmailVerified(Instant.now());
        learnerUser = userRepository.saveAndFlush(learnerUser);
    }

    @Test
    @DisplayName("Admin Overview - Thẻ KPI và Activities yêu cầu quyền Admin")
    void overview_endpoints_requireAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/admin/overview/kpis"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/admin/overview/kpis")
                        .with(SecurityMockMvcRequestPostProcessors.user(UserPrincipal.create(learnerUser))))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/admin/overview/kpis")
                        .with(SecurityMockMvcRequestPostProcessors.user(UserPrincipal.create(systemAdminUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        mockMvc.perform(get("/api/v1/admin/overview/activities")
                        .with(SecurityMockMvcRequestPostProcessors.user(UserPrincipal.create(contentAdminUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("Admin Users - SYSTEM_ADMIN có thể xem danh sách, cập nhật role và khóa/mở khóa")
    void userManagement_crud_success() throws Exception {
        // CONTENT_ADMIN cannot access user management
        mockMvc.perform(get("/api/v1/admin/users")
                        .with(SecurityMockMvcRequestPostProcessors.user(UserPrincipal.create(contentAdminUser))))
                .andExpect(status().isForbidden());

        // SYSTEM_ADMIN lists users
        mockMvc.perform(get("/api/v1/admin/users")
                        .with(SecurityMockMvcRequestPostProcessors.user(UserPrincipal.create(systemAdminUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.totalItems").isNumber());

        // Update roles
        UpdateRolesRequest rolesRequest = new UpdateRolesRequest(List.of(RoleCode.LEARNER, RoleCode.CONTENT_ADMIN));
        mockMvc.perform(put("/api/v1/admin/users/" + learnerUser.getId() + "/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rolesRequest))
                        .with(csrf())
                        .with(SecurityMockMvcRequestPostProcessors.user(UserPrincipal.create(systemAdminUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles").isArray());

        // Lock user
        LockUserRequest lockRequest = new LockUserRequest("Vi phạm quy tắc", Instant.now().plusSeconds(86400));
        mockMvc.perform(post("/api/v1/admin/users/" + learnerUser.getId() + "/lock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(lockRequest))
                        .with(csrf())
                        .with(SecurityMockMvcRequestPostProcessors.user(UserPrincipal.create(systemAdminUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LOCKED"));

        // Unlock user
        mockMvc.perform(post("/api/v1/admin/users/" + learnerUser.getId() + "/unlock")
                        .with(csrf())
                        .with(SecurityMockMvcRequestPostProcessors.user(UserPrincipal.create(systemAdminUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("Admin Content - CONTENT_ADMIN có thể tạo nguồn và tạo bản thảo từ vựng")
    void content_crud_success() throws Exception {
        // Create Content Source
        CreateContentSourceRequest sourceReq = new CreateContentSourceRequest(
                "Oxford Learner's Dictionary", "EXTERNAL", "https://oxford.com",
                "Oxford", "CC-BY", null, "Attribution Oxford", null);

        mockMvc.perform(post("/api/v1/admin/content/sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sourceReq))
                        .with(csrf())
                        .with(SecurityMockMvcRequestPostProcessors.user(UserPrincipal.create(contentAdminUser))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.title").value("Oxford Learner's Dictionary"));

        // Create Word Draft
        CreateWordDraftRequest wordReq = new CreateWordDraftRequest(
                "resilience", "NOUN", "B2", "Khả năng phục hồi",
                "The capacity to recover quickly from difficulties", "/rɪˈzɪliəns/",
                List.of("Courage and resilience are essential."),
                List.of("Lòng dũng cảm và sự kiên cường là cần thiết."),
                null, "Thêm từ vựng mới");

        mockMvc.perform(post("/api/v1/admin/content/words")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(wordReq))
                        .with(csrf())
                        .with(SecurityMockMvcRequestPostProcessors.user(UserPrincipal.create(contentAdminUser))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("resilience"))
                .andExpect(jsonPath("$.status").value("DRAFT"));

        // List Content Items
        mockMvc.perform(get("/api/v1/admin/content/items")
                        .with(SecurityMockMvcRequestPostProcessors.user(UserPrincipal.create(contentAdminUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    @DisplayName("Admin System - SYSTEM_ADMIN có thể xem/đổi Feature Flags và tra cứu Audit Logs")
    void system_featuresAndAudit_success() throws Exception {
        // List feature flags
        mockMvc.perform(get("/api/v1/admin/system/feature-flags")
                        .with(SecurityMockMvcRequestPostProcessors.user(UserPrincipal.create(systemAdminUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        // Update feature flag
        UpdateFeatureFlagRequest flagReq = new UpdateFeatureFlagRequest(false);
        mockMvc.perform(put("/api/v1/admin/system/feature-flags/ai_tutor_chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(flagReq))
                        .with(csrf())
                        .with(SecurityMockMvcRequestPostProcessors.user(UserPrincipal.create(systemAdminUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        // Storage Quota
        mockMvc.perform(get("/api/v1/admin/system/quotas/storage")
                        .with(SecurityMockMvcRequestPostProcessors.user(UserPrincipal.create(systemAdminUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bucket").isNotEmpty());

        // Audit Logs
        mockMvc.perform(get("/api/v1/admin/system/audit-logs")
                        .with(SecurityMockMvcRequestPostProcessors.user(UserPrincipal.create(systemAdminUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }
}
