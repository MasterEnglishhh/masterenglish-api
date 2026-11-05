package com.masterenglish.api.modules.content.pronunciation;

import java.nio.file.Path;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
@ConditionalOnProperty(name = "app.vocabulary.pronunciation.import-path")
public class LocalPronunciationImportRunner implements ApplicationRunner {
    private final LocalPronunciationImporter importer;
    private final Path manifest;
    private final UUID actor;

    public LocalPronunciationImportRunner(
            LocalPronunciationImporter importer,
            @Value("${app.vocabulary.pronunciation.import-path}") String manifest,
            @Value("${app.vocabulary.pronunciation.actor}") UUID actor) {
        this.importer = importer;
        this.manifest = Path.of(manifest);
        this.actor = actor;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        int count = importer.importManifest(manifest, actor);
        org.slf4j.LoggerFactory.getLogger(getClass())
                .info("Pronunciation import completed: {} new files.", count);
    }
}
