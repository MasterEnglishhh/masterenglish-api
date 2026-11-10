@org.springframework.modulith.ApplicationModule(
        id = "admin",
        displayName = "Admin Operations & System Policy",
        allowedDependencies = {
            "identity::admin",
            "identity::user-lock",
            "content::admin",
            "content::authoring",
            "content::query",
            "vocabulary::query",
            "common",
            "common::api",
            "common::entity",
            "common::error",
            "common::security",
            "common::pagination"
        })
package com.masterenglish.api.modules.admin;
