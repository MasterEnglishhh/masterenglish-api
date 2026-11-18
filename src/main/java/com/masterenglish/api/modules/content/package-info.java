@org.springframework.modulith.ApplicationModule(
        id = "content",
        displayName = "Content & Revisions",
        allowedDependencies = {
            "common::events",
            "common::entity",
            "common::storage",
            "common::error",
            "common::security",
            "common::pagination"
        })
package com.masterenglish.api.modules.content;
