@org.springframework.modulith.ApplicationModule(
        id = "learning",
        displayName = "Learning & Spaced Repetition (SRS)",
        allowedDependencies = {
            "vocabulary::query",
            "identity::user-lock",
            "common::api",
            "common::entity",
            "common::error",
            "common::security",
            "common::pagination",
            "common::time"
        })
package com.masterenglish.api.modules.learning;
