package com.masterenglish.api.modules.content.repository;

import com.masterenglish.api.modules.content.entity.ContentSourceLink;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContentSourceLinkRepository
        extends JpaRepository<ContentSourceLink, ContentSourceLink.ContentSourceLinkId> {
    List<ContentSourceLink> findByIdRevisionId(java.util.UUID revisionId);
}
