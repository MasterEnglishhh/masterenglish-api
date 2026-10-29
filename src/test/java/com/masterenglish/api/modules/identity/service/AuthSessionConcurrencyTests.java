package com.masterenglish.api.modules.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.masterenglish.api.TestcontainersConfiguration;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.modules.identity.dto.LoginResult;
import com.masterenglish.api.modules.identity.dto.RefreshResult;
import com.masterenglish.api.modules.identity.entity.AuthSession;
import com.masterenglish.api.modules.identity.entity.Role;
import com.masterenglish.api.modules.identity.entity.User;
import com.masterenglish.api.modules.identity.repository.AuthSessionRepository;
import com.masterenglish.api.modules.identity.repository.RefreshTokenRepository;
import com.masterenglish.api.modules.identity.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AuthSessionConcurrencyTests {

    @Autowired private AuthSessionService authSessionService;
    @Autowired private UserRepository userRepository;
    @Autowired private AuthSessionRepository authSessionRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private TransactionTemplate transactionTemplate;

    private User testUser;

    @BeforeEach
    void setUp() {
        transactionTemplate.execute(
                status -> {
                    testUser =
                            new User(
                                    "concurrent.test@example.com",
                                    "hashed-pwd",
                                    "Concurrent",
                                    "Tester");
                    testUser.addRole(entityManager.find(Role.class, (short) 1));
                    testUser.markEmailVerified(Instant.now());
                    userRepository.saveAndFlush(testUser);
                    return null;
                });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.execute(
                status -> {
                    if (testUser != null) {
                        List<AuthSession> sessions =
                                authSessionRepository.findByUserId(testUser.getId());
                        for (AuthSession session : sessions) {
                            refreshTokenRepository.deleteAll(
                                    refreshTokenRepository.findAll().stream()
                                            .filter(
                                                    rt ->
                                                            rt.getSession()
                                                                    .getId()
                                                                    .equals(session.getId()))
                                            .toList());
                        }
                        authSessionRepository.deleteAll(sessions);
                        userRepository.deleteById(testUser.getId());
                    }
                    return null;
                });
    }

    @Test
    @DisplayName(
            "Concurrent refresh token rotations with the same token serialize without deadlock")
    void concurrentRefreshRequests_serializeCleanly() throws Exception {
        LoginResult loginResult =
                authSessionService.createSession(testUser, "Mozilla/5.0", "127.0.0.1");
        String rawRefreshToken = loginResult.rawRefreshToken();

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Throwable> exceptions = Collections.synchronizedList(new ArrayList<>());
        List<RefreshResult> successes = Collections.synchronizedList(new ArrayList<>());

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(
                    executor.submit(
                            () -> {
                                readyLatch.countDown();
                                try {
                                    startLatch.await();
                                    RefreshResult res =
                                            authSessionService.rotateRefreshToken(rawRefreshToken);
                                    successes.add(res);
                                } catch (Throwable t) {
                                    exceptions.add(t);
                                }
                            }));
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        // Exactly one rotation should succeed, and one should fail due to reuse detection
        // or both could fail if reuse was triggered immediately.
        // Crucially: no deadlock, and any exception must be UNAUTHENTICATED BusinessException.
        for (Throwable t : exceptions) {
            assertThat(t).isInstanceOf(BusinessException.class);
            BusinessException be = (BusinessException) t;
            assertThat(be.getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);
        }

        // At most 1 thread succeeded
        assertThat(successes.size()).isLessThanOrEqualTo(1);

        // Verify database state: no constraint violations, unrevoked tokens <= 1
        List<AuthSession> sessions = authSessionRepository.findByUserId(testUser.getId());
        assertThat(sessions).hasSize(1);
        UUID sessionId = sessions.get(0).getId();

        // Database constraint uq_refresh_tokens_one_unrevoked must be satisfied
        long unrevokedCount =
                refreshTokenRepository.findAll().stream()
                        .filter(
                                rt ->
                                        rt.getSession().getId().equals(sessionId)
                                                && rt.getRevokedAt() == null)
                        .count();
        assertThat(unrevokedCount).isLessThanOrEqualTo(1);
    }
}
