@org.springframework.modulith.ApplicationModule(
        id = "identity",
        displayName = "Identity and account",
        allowedDependencies = {
            "common::entity",
            "common::error",
            "common::security",
            "common::api",
            "common::events"
        })
package com.masterenglish.api.modules.identity;
