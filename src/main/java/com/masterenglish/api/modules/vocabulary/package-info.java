@org.springframework.modulith.ApplicationModule(
        id = "vocabulary",
        displayName = "Vocabulary Catalog & Flashcards",
        allowedDependencies = {
            "common::events",
            "common::api",
            "common::entity",
            "common::error",
            "common::security",
            "common::pagination"
        })
package com.masterenglish.api.modules.vocabulary;
