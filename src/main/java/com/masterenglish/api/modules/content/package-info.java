@org.springframework.modulith.ApplicationModule(
        id = "content",
        displayName = "Content & Revisions",
        allowedDependencies = {
            "common::api",
            "common::events",
            "common::entity",
            "common::storage",
            "common::error",
            "common::security",
            "common::pagination",
            "common::validation",
            "common::time"
        })
package com.masterenglish.api.modules.content;
