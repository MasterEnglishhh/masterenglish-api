package com.masterenglish.api.modules.vocabulary.seed;

import java.util.UUID;

public interface VocabularySeedService {
    void seedVocabularyData(UUID adminUserId);

    void seedTopicVocabularyData(UUID adminUserId);
}
