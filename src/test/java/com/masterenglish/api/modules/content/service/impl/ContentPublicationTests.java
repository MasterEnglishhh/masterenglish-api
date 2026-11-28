package com.masterenglish.api.modules.content.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masterenglish.api.common.error.BusinessException;
import com.masterenglish.api.common.error.ErrorCode;
import com.masterenglish.api.common.events.ContentEvents;
import com.masterenglish.api.modules.content.entity.ContentItem;
import com.masterenglish.api.modules.content.entity.ContentRevision;
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
                        events,
                        new ObjectMapper());

        // QUESTION, QUESTION_GROUP, PRACTICE_SET are validated now; these types still have no
        // validator.
        for (String type : new String[] {"UNKNOWN_TYPE_1", "UNKNOWN_TYPE_2"}) {
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

            assertThatThrownBy(
                            () ->
                                    publication.prepare(
                                            item,
                                            revision,
                                            UUID.randomUUID(),
                                            (groupId, childItem, childRevision) -> {}))
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
}
