@org.springframework.modulith.ApplicationModule(
        id = "notifications",
        displayName = "Notifications & Study Reminders",
        allowedDependencies = {
            "common::events",
            "common::api",
            "common::entity",
            "common::error",
            "common::security",
            "common::pagination",
            "common::time"
        })
package com.masterenglish.api.modules.notifications;
