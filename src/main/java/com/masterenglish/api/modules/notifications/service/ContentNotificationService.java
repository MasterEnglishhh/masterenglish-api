package com.masterenglish.api.modules.notifications.service;

import java.util.UUID;

/** Handles learner announcements for newly published vocabulary decks (M10.07). */
public interface ContentNotificationService {

    /**
     * @return number of CONTENT_NEW notifications inserted in this run
     */
    int notifyDeckFirstPublished(UUID deckId, UUID revisionId, String deckTitle, String level);
}
