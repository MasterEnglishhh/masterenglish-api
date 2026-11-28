@org.springframework.modulith.ApplicationModule(
        id = "admin",
        displayName = "Admin Operations & System Policy",
        allowedDependencies = {
            "common::events",
            "common::api",
            "common::entity",
            "common::error",
            "common::security",
            "common::pagination",
            "common::validation"
        })
package com.masterenglish.api.modules.admin;
