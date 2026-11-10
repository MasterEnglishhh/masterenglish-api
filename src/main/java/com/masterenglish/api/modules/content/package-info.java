@org.springframework.modulith.ApplicationModule(
        id = "content",
        displayName = "Content & Revisions",
        allowedDependencies = {
            "identity::user-lock",
            "common::api",
            "common::entity",
            "common::storage",
            "common::error",
            "common::security",
            "common::pagination"
        })
package com.masterenglish.api.modules.content;
