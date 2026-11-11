package com.masterenglish.api;

import com.masterenglish.api.modules.vocabulary.seed.ProductionVocabularyImportService;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.Profiles;

/** Explicit one-shot operation; normal API startup never loads starter content. */
final class ContentImportCommand {
    private ContentImportCommand() {}

    static void run(String[] args) {
        var application = new SpringApplication(MasterenglishApiApplication.class);
        application.setAdditionalProfiles("content-import");
        application.addInitializers(
                context -> {
                    var environment = context.getEnvironment();
                    if (environment.acceptsProfiles(Profiles.of("local"))) {
                        throw new IllegalArgumentException(
                                "Production import must not use the local profile.");
                    }
                    // Security's servlet beans still load, but this container exposes no API
                    // listener.
                    environment
                            .getPropertySources()
                            .addFirst(
                                    new MapPropertySource(
                                            "contentImportListener",
                                            Map.of(
                                                    "server.address",
                                                    "127.0.0.1",
                                                    "server.port",
                                                    "0")));
                    required(
                            environment.getProperty(
                                    "app.vocabulary.production-import.actor-email"));
                    required(environment.getProperty("app.vocabulary.production-import.manifest"));
                });
        try (var context = application.run(args)) {
            var environment = context.getEnvironment();
            var result =
                    context.getBean(ProductionVocabularyImportService.class)
                            .importStarter(
                                    environment.getRequiredProperty(
                                            "app.vocabulary.production-import.actor-email"),
                                    Path.of(
                                            environment.getRequiredProperty(
                                                    "app.vocabulary.production-import.manifest")));
            System.out.printf(
                    "CONTENT_IMPORT_OK words=%d decks=%d audioImported=%d%n",
                    result.words(), result.decks(), result.audioImported());
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException("Cannot read starter bundle.", exception);
        }
    }

    private static void required(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Content import requires actor-email and manifest.");
        }
    }
}
