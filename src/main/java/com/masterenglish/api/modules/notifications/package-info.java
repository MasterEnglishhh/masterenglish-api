@org.springframework.modulith.ApplicationModule(
        id = "notifications",
        displayName = "Notifications & Reminders",
        allowedDependencies = {
            "common::events",
            "common::api",
            "common::entity",
            "common::error",
            "common::security"
        })
package com.masterenglish.api.modules.notifications;
