package com.urlshortener.integration;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ArchUnit tests enforce architectural boundaries at compile/test time.
 *
 * <p>Rules enforced: 1. Domain layer has zero infrastructure dependencies 2. API controllers never
 * call repositories directly (must go through use cases) 3. Domain models never import Spring
 * annotations (framework-agnostic) 4. Infrastructure never imports API layer 5. Use cases never
 * import Spring MVC/web annotations
 */
@DisplayName("Architecture rules")
class ArchitectureTest {

  private static JavaClasses classes;

  @BeforeAll
  static void loadClasses() {
    classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.urlshortener");
  }

  @Test
  @DisplayName("layered architecture: API → Application → Domain ← Infrastructure")
  void layeredArchitecture_respected() {
    layeredArchitecture()
        .consideringAllDependencies()
        .layer("API")
        .definedBy("com.urlshortener.api..")
        .layer("Application")
        .definedBy("com.urlshortener.application..")
        .layer("Domain")
        .definedBy("com.urlshortener.domain..")
        .layer("Infrastructure")
        .definedBy("com.urlshortener.infrastructure..")
        .layer("Config")
        .definedBy("com.urlshortener.config..")
        .layer("Common")
        .definedBy("com.urlshortener.common..")
        .whereLayer("API")
        .mayOnlyBeAccessedByLayers("Config")
        .whereLayer("Application")
        .mayOnlyBeAccessedByLayers("API", "Config")
        .whereLayer("Domain")
        .mayOnlyBeAccessedByLayers("API", "Application", "Infrastructure", "Config")
        .whereLayer("Infrastructure")
        .mayOnlyBeAccessedByLayers("API", "Application", "Config")
        .check(classes);
  }

  @Test
  @DisplayName("domain model classes do not depend on Spring framework")
  void domainModels_noSpringDependency() {
    noClasses()
        .that()
        .resideInAPackage("com.urlshortener.domain.model..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework.web..",
            "org.springframework.security..",
            "org.springframework.kafka..",
            "org.springframework.data.redis..")
        .check(classes);
  }

  @Test
  @DisplayName("controllers must not access repositories directly")
  void controllers_doNotAccessRepositories() {
    noClasses()
        .that()
        .resideInAPackage("com.urlshortener.api..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("com.urlshortener.domain.repository..")
        .check(classes);
  }

  @Test
  @DisplayName("use cases must not import Spring MVC annotations")
  void useCases_noSpringMvcDependency() {
    noClasses()
        .that()
        .resideInAPackage("com.urlshortener.application.usecase..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("org.springframework.web.bind.annotation..")
        .check(classes);
  }

  @Test
  @DisplayName("infrastructure must not import API DTOs (reverse dependency)")
  void infrastructure_doesNotDependOnApiDtos() {
    noClasses()
        .that()
        .resideInAPackage("com.urlshortener.infrastructure..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("com.urlshortener.api..")
        .check(classes);
  }

  @Test
  @DisplayName("domain services must not import infrastructure")
  void domainServices_noInfrastructureDependency() {
    noClasses()
        .that()
        .resideInAPackage("com.urlshortener.domain.service..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("com.urlshortener.infrastructure..", "com.urlshortener.config..")
        .check(classes);
  }

  @Test
  @DisplayName("exception classes reside in common.exception package")
  void exceptions_reside_in_correct_package() {
    classes()
        .that()
        .areAssignableTo(com.urlshortener.common.exception.DomainException.class)
        .should()
        .resideInAPackage("com.urlshortener.common.exception..")
        .check(classes);
  }

  @Test
  @DisplayName("all Spring @Service classes reside in domain, application, or infrastructure")
  void services_residInCorrectPackages() {
    classes()
        .that()
        .areAnnotatedWith(org.springframework.stereotype.Service.class)
        .should()
        .resideInAnyPackage(
            "com.urlshortener.domain..",
            "com.urlshortener.application..",
            "com.urlshortener.infrastructure..",
            "com.urlshortener.observability..")
        .check(classes);
  }

  @Test
  @DisplayName("all @RestController classes reside in api package")
  void controllers_resideInApiPackage() {
    classes()
        .that()
        .areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
        .should()
        .resideInAPackage("com.urlshortener.api..")
        .check(classes);
  }
}
