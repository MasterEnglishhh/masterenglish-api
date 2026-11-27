@org.springframework.modulith.ApplicationModule(
        id = "dashboard",
        displayName = "Learner Dashboard",
        allowedDependencies = {
            "common::events",
            "common::api",
            "common::error",
            "common::security",
            "common::time"
        })
package com.masterenglish.api.modules.dashboard;
