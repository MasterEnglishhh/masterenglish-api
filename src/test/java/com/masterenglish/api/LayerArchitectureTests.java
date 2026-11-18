package com.masterenglish.api;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.INTERFACES;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

class LayerArchitectureTests {
    private static final ClassFileImporter IMPORTER =
            new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS);
    private static final JavaClasses MODULES =
            IMPORTER.importPackages("com.masterenglish.api.modules");

    @Test
    void controllersGoThroughServicesInsteadOfPersistenceTypes() {
        noClasses()
                .that()
                .resideInAPackage("..controller..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "..repository..", "..persistence..", "..entity..", "..service.impl..")
                .check(MODULES);
    }

    @Test
    void controllersUseServiceInterfaces() {
        noClasses()
                .that()
                .resideInAPackage("..controller..")
                .should()
                .dependOnClassesThat(resideInAPackage("..service..").and(INTERFACES.negate()))
                .check(MODULES);
    }

    @Test
    void businessServicesDoNotDependOnControllersOrPersistenceAdapters() {
        noClasses()
                .that()
                .resideInAPackage("..service..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..controller..", "..persistence..")
                .check(MODULES);
    }

    @Test
    void repositoriesAndEntitiesDoNotCallTheUpperLayers() {
        noClasses()
                .that()
                .resideInAnyPackage("..repository..", "..persistence..", "..entity..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..controller..", "..service..")
                .check(MODULES);
    }

    @Test
    void dtosDoNotExposePersistenceTypes() {
        noClasses()
                .that()
                .resideInAPackage("..dto..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "..repository..", "..persistence..", "..entity..", "..service..")
                .check(MODULES);
    }

    @Test
    void repositoryPackagesDeclareOnlyAnnotatedJpaRepositoryInterfaces() {
        classes()
                .that()
                .resideInAPackage("..repository")
                .and()
                .areTopLevelClasses()
                .should()
                .beInterfaces()
                .andShould()
                .beAssignableTo(JpaRepository.class)
                .andShould()
                .beAnnotatedWith(Repository.class)
                .check(MODULES);
    }

    @Test
    void servicesDeclareTransactionsAtMethodLevel() {
        noClasses()
                .that()
                .resideInAPackage("..service..")
                .should()
                .beAnnotatedWith(Transactional.class)
                .check(MODULES);
    }

    @Test
    void servicesDoNotExecuteSqlOrAccessEntityManagersDirectly() {
        noClasses()
                .that()
                .resideInAPackage("..service..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.springframework.jdbc..", "jakarta.persistence..")
                .check(MODULES);
    }

    @Test
    void databaseAccessGoesThroughSpringDataRepositories() {
        JavaClasses application = IMPORTER.importPackages("com.masterenglish.api");
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.springframework.jdbc..", "java.sql..", "javax.sql..")
                .check(application);
        noClasses()
                .that()
                .resideOutsideOfPackage("..infrastructure.persistence..")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("jakarta.persistence.EntityManager")
                .check(application);
    }

    @Test
    void serviceImplementationsLiveInImplPackages() {
        classes()
                .that()
                .haveSimpleNameEndingWith("Impl")
                .should()
                .resideInAPackage("..service.impl..")
                .check(MODULES);
    }

    @Test
    void businessModulesHaveNoDirectDependenciesOnEachOther() {
        SlicesRuleDefinition.slices()
                .matching("com.masterenglish.api.modules.(*)..")
                .should()
                .notDependOnEachOther()
                .check(MODULES);
    }

    @Test
    void businessModulesUseOnlyMvcPackages() {
        assertThat(MODULES.stream().filter(type -> !type.getSimpleName().equals("package-info")))
                .allSatisfy(
                        type ->
                                assertThat(type.getPackageName())
                                        .matches(
                                                "com\\.masterenglish\\.api\\.modules\\.[a-z]+\\."
                                                        + "(controller|dto\\.(request|response)|entity|"
                                                        + "repository(\\.fragment)?|service(\\.impl)?)"));
    }

    @Test
    void httpRequestAndResponseClassesLiveInTheirDtoPackages() {
        classes()
                .that()
                .haveSimpleNameEndingWith("Request")
                .should()
                .resideInAPackage("..dto.request..")
                .check(MODULES);
        classes()
                .that()
                .haveSimpleNameEndingWith("Response")
                .should()
                .resideInAPackage("..dto.response..")
                .check(MODULES);
    }
}
