@org.springframework.modulith.ApplicationModule(
        id = "grammar",
        displayName = "Grammar Lessons",
        allowedDependencies = {
            "common::api",
            "common::error",
            "common::events",
            "common::pagination"
        })
package com.masterenglish.api.modules.grammar;
