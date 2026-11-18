package com.masterenglish.api.modules.content.repository;

import com.masterenglish.api.modules.content.entity.RevisionDependency;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RevisionDependencyRepository
        extends JpaRepository<RevisionDependency, RevisionDependency.RevisionDependencyId> {
    List<RevisionDependency> findByIdParentRevisionId(UUID parentRevisionId);
}
