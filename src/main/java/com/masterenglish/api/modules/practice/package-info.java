@org.springframework.modulith.ApplicationModule(
        id = "practice",
        displayName = "Practice Engine (shared quizzes and attempts)",
        allowedDependencies = {
            "common::events",
            "common::error",
            "common::security",
            "common::api",
            "common::pagination"
        })
package com.masterenglish.api.modules.practice;
