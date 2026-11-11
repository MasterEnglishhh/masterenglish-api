package com.masterenglish.api.modules.vocabulary.seed;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Explicit local fixture loading only; never enabled by production startup. */
@Component
@Profile("local")
@ConditionalOnProperty(name = "app.vocabulary.seed", havingValue = "true")
public class LocalVocabularySeedRunner implements ApplicationRunner {
    private final VocabularySeedService seed;
    private final UUID actor;

    public LocalVocabularySeedRunner(
            VocabularySeedService seed, @Value("${app.vocabulary.seed-actor}") UUID actor) {
        this.seed = seed;
        this.actor = actor;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        seed.seedTopicVocabularyData(actor);
    }
}
