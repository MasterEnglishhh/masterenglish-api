package com.masterenglish.api.modules.content.repository;

import com.masterenglish.api.modules.content.entity.RevisionDependency;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RevisionDependencyRepository
        extends JpaRepository<RevisionDependency, RevisionDependency.RevisionDependencyId> {
    List<RevisionDependency> findByIdParentRevisionId(java.util.UUID parentRevisionId);
}
