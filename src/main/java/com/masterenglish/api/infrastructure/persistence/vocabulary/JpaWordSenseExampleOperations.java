package com.masterenglish.api.infrastructure.persistence.vocabulary;

import com.masterenglish.api.modules.vocabulary.entity.WordSenseExample;
import com.masterenglish.api.modules.vocabulary.repository.fragment.WordSenseExampleOperations;
import jakarta.persistence.EntityManager;

class JpaWordSenseExampleOperations implements WordSenseExampleOperations {
    private final EntityManager entityManager;

    JpaWordSenseExampleOperations(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public void detach(WordSenseExample example) {
        entityManager.detach(example);
    }
}
