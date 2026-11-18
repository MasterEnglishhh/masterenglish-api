package com.masterenglish.api.infrastructure.persistence.content;

import com.masterenglish.api.modules.content.entity.ContentRevision;
import com.masterenglish.api.modules.content.repository.fragment.ContentRevisionLocking;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

class JpaContentRevisionLocking implements ContentRevisionLocking {
    private final EntityManager entityManager;

    JpaContentRevisionLocking(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public void refreshForUpdate(ContentRevision revision) {
        entityManager.refresh(revision, LockModeType.PESSIMISTIC_WRITE);
    }
}
