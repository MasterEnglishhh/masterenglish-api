package com.masterenglish.api;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureTests {

    @Test
    void discoversBusinessModulesAndEnforcesTheirBoundaries() {
        ApplicationModules modules = ApplicationModules.of(MasterenglishApiApplication.class);
        assertThat(modules.getModuleByName("identity")).isPresent();
        assertThat(modules.getModuleByName("common")).isPresent();
        assertThat(modules.getModuleByName("modules")).isEmpty();
        modules.verify();
    }
}
