package com.masterenglish.api.modules.content.repository;

import com.masterenglish.api.modules.content.entity.ContentSourceLink;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ContentSourceLinkRepository
        extends JpaRepository<ContentSourceLink, ContentSourceLink.ContentSourceLinkId> {
    List<ContentSourceLink> findByIdRevisionId(UUID revisionId);
}
