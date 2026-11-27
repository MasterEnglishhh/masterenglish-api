package com.masterenglish.api.modules.content.service.impl;

import com.masterenglish.api.common.events.ContentEvents.AudioUrlRequested;
import com.masterenglish.api.common.events.ContentEvents.ManifestValidationRequested;
import com.masterenglish.api.common.events.ContentEvents.PronunciationImportRequested;
import com.masterenglish.api.common.events.ContentEvents.PublishedRevisionRequested;
import com.masterenglish.api.common.events.ContentEvents.SeedPublicationRequested;
import com.masterenglish.api.common.events.ContentEvents.SenseAudioUrlRequested;
import com.masterenglish.api.common.events.ContentEvents.SnapshotRequested;
import com.masterenglish.api.common.events.ContentEvents.SourceCreationRequested;
import com.masterenglish.api.common.events.ContentEvents.SourceExistsRequested;
import com.masterenglish.api.modules.content.service.ContentAuthoringService;
import com.masterenglish.api.modules.content.service.ContentQueryService;
import com.masterenglish.api.modules.content.service.PronunciationImportService;
import com.masterenglish.api.modules.content.service.PronunciationService;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
class ContentEventHandler {
    private final ContentAuthoringService authoring;
    private final ContentQueryService queries;
    private final PronunciationService pronunciation;
    private final ObjectProvider<PronunciationImportService> importer;

    ContentEventHandler(
            ContentAuthoringService authoring,
            ContentQueryService queries,
            PronunciationService pronunciation,
            ObjectProvider<PronunciationImportService> importer) {
        this.authoring = authoring;
        this.queries = queries;
        this.pronunciation = pronunciation;
        this.importer = importer;
    }

    @EventListener
    void on(SnapshotRequested event) {
        event.reply()
                .complete(
                        queries.snapshot(
                                event.itemId(), event.revisionId(), event.type(), event.lock()));
    }

    @EventListener
    void on(AudioUrlRequested event) {
        event.reply().complete(pronunciation.audioUrl(event.wordId(), event.revisionId()));
    }

    @EventListener
    void on(SenseAudioUrlRequested event) {
        event.reply()
                .complete(
                        pronunciation.audioUrl(
                                event.wordId(), event.revisionId(), event.senseId()));
    }

    @EventListener
    void on(SourceExistsRequested event) {
        event.reply().complete(authoring.hasSource(event.sourceId()));
    }

    @EventListener
    void on(SourceCreationRequested event) {
        var source = event.source();
        authoring.createSource(
                source.id(),
                source.title(),
                source.type(),
                source.url(),
                source.author(),
                source.licenseCode(),
                source.licenseUrl(),
                source.attribution(),
                source.permissionNote(),
                source.actor());
        event.reply().complete(null);
    }

    @EventListener
    void on(PublishedRevisionRequested event) {
        event.reply()
                .complete(
                        authoring.findPublishedRevisionId(event.namespace(), event.externalKey()));
    }

    @EventListener
    void on(SeedPublicationRequested event) {
        var draft = event.draft();
        event.reply()
                .complete(
                        authoring.createAndPublish(
                                draft.itemId(),
                                draft.type(),
                                draft.actor(),
                                draft.namespace(),
                                draft.externalKey(),
                                draft.snapshot(),
                                draft.changeSummary(),
                                draft.sourceId()));
    }

    @EventListener
    void on(ManifestValidationRequested event) {
        try {
            event.reply().complete(importer.getObject().validateManifest(event.manifest()));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @EventListener
    void on(PronunciationImportRequested event) {
        try {
            event.reply()
                    .complete(
                            importer.getObject()
                                    .importPortableManifest(
                                            event.manifest(), event.actor(), event.revisions()));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
