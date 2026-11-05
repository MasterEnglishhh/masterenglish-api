package com.masterenglish.api.modules.vocabulary.seed;

import java.util.UUID;

public interface VocabularySeedService {
    void seedVocabularyData(UUID adminUserId);

    void seedTopicVocabularyData(UUID adminUserId);

    java.util.Map<UUID, String> starterWords();

    java.util.Set<UUID> starterDeckIds();
}
