package com.masterenglish.api.modules.identity.repository;

import com.masterenglish.api.modules.identity.entity.AuthRateLimit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthRateLimitRepository extends JpaRepository<AuthRateLimit, UUID> {

    Optional<AuthRateLimit> findByScopeAndSubjectHash(String scope, String subjectHash);
}
