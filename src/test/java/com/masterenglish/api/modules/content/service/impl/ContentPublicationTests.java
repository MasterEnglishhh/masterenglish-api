package com.masterenglish.api.modules.content.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.events.ContentEvents;
import com.masterenglish.api.modules.content.entity.ContentItem;
import com.masterenglish.api.modules.content.entity.ContentRevision;
import com.masterenglish.api.modules.content.repository.ContentRevisionRepository;
import com.masterenglish.api.modules.content.repository.ContentSourceLinkRepository;
import com.masterenglish.api.modules.content.repository.ContentSourceRepository;
import com.masterenglish.api.modules.content.repository.RevisionDependencyRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContentPublicationTests {

    @Test
    void typesWithoutARegisteredValidatorAreRejectedBeforeValidation() {
        ContentEvents events = mock(ContentEvents.class);
        ContentPublication publication =
                new ContentPublication(
                        mock(ContentSnapshots.class),
                        mock(ContentSourceLinkRepository.class),
                        mock(ContentSourceRepository.class),
                        mock(RevisionDependencyRepository.class),
                        mock(ContentRevisionRepository.class),
                        events,
                        new ObjectMapper());

        // QUESTION is validated now (practice module); these types still have no validator.
        for (String type : new String[] {"QUESTION_GROUP", "PRACTICE_SET"}) {
            UUID itemId = UUID.randomUUID();
            ContentItem item = new ContentItem(itemId, type, UUID.randomUUID(), null, null);
            ContentRevision revision =
                    new ContentRevision(
                            UUID.randomUUID(),
                            itemId,
                            1,
                            (short) 1,
                            "{}",
                            "DRAFT",
                            null,
                            UUID.randomUUID());

            assertThatThrownBy(() -> publication.prepare(item, revision))
                    .as("type %s", type)
                    .isInstanceOfSatisfying(
                            BusinessException.class,
                            ex ->
                                    assertThat(ex.getErrorCode())
                                            .isEqualTo(ErrorCode.VALIDATION_ERROR))
                    .hasMessage("Unsupported content type: " + type);
        }
        verifyNoInteractions(events);
    }

    @Test
    void grammarLessonWithMediaRefsIsRejectedBeforeSourcesAreCaptured() {
        ContentRevisionRepository revisions = mock(ContentRevisionRepository.class);
        ContentSourceLinkRepository sourceLinks = mock(ContentSourceLinkRepository.class);
        ContentPublication publication =
                new ContentPublication(
                        mock(ContentSnapshots.class),
                        sourceLinks,
                        mock(ContentSourceRepository.class),
                        mock(RevisionDependencyRepository.class),
                        revisions,
                        mock(ContentEvents.class),
                        new ObjectMapper());
        UUID itemId = UUID.randomUUID();
        ContentItem item = new ContentItem(itemId, "GRAMMAR_LESSON", UUID.randomUUID(), null, null);
        ContentRevision revision =
                new ContentRevision(
                        UUID.randomUUID(),
                        itemId,
                        1,
                        (short) 1,
                        "{}",
                        "DRAFT",
                        null,
                        UUID.randomUUID());
        when(revisions.countMediaRefsByRevisionId(revision.getId())).thenReturn(1L);

        assertThatThrownBy(() -> publication.prepare(item, revision))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR))
                .hasMessage("GRAMMAR_LESSON v1 must not reference media.");
        // Kiểm tra media đứng trước bước gắn nguồn, nên không có truy vấn nguồn nào xảy ra.
        verifyNoInteractions(sourceLinks);
    }
}
