@org.springframework.modulith.ApplicationModule(
        id = "practice",
        displayName = "Practice Engine (shared quizzes and attempts)",
        allowedDependencies = {
            "common::events",
            "common::api",
            "common::error",
            "common::security",
            "common::pagination",
            "common::time"
        })
package com.masterenglish.api.modules.practice;
