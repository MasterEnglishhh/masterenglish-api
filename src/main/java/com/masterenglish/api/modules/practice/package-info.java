@org.springframework.modulith.ApplicationModule(
        id = "practice",
        displayName = "Practice Engine (shared quizzes and attempts)",
        allowedDependencies = {
            "common::events",
            "common::error",
            "common::security",
            "common::api",
            "common::pagination",
            "common::time"
        })
package com.masterenglish.api.modules.practice;
