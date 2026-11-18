package com.masterenglish.api.modules.vocabulary.service;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface VocabularySeedService {
    void seedVocabularyData(UUID adminUserId);

    void seedTopicVocabularyData(UUID adminUserId);

    Map<UUID, String> starterWords();

    Set<UUID> starterDeckIds();
}
