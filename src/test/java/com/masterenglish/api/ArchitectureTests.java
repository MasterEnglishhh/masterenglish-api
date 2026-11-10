package com.masterenglish.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ArchitectureTests {

    @Test
    void discoversBusinessModulesAndEnforcesTheirBoundaries() {
        ApplicationModules modules = ApplicationModules.of(MasterenglishApiApplication.class);
        assertThat(modules.getModuleByName("identity")).isPresent();
        assertThat(modules.getModuleByName("common")).isPresent();
        assertThat(modules.getModuleByName("content")).isPresent();
        assertThat(modules.getModuleByName("vocabulary")).isPresent();
        assertThat(modules.getModuleByName("learning")).isPresent();
        assertThat(modules.getModuleByName("admin")).isPresent();
        assertThat(modules.getModuleByName("modules")).isEmpty();
        modules.verify();
    }
}
