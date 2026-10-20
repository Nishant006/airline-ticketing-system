package com.airline.reservation;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;

@AnalyzeClasses(
    packages = "com.airline.reservation",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
  @ArchTest
  static final ArchRule CONTROLLERS_USE_SERVICES_AND_DTOS =
      noClasses()
          .that()
          .resideInAPackage("..controller..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..repository..", "..entity..");

  @ArchTest
  static final ArchRule PERSISTENCE_DOES_NOT_DEPEND_ON_TRANSPORT_OR_SERVICES =
      noClasses()
          .that()
          .resideInAnyPackage("..repository..", "..entity..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..controller..", "..service..");

  @ArchTest
  static final ArchRule DTOS_DO_NOT_DEPEND_ON_PERSISTENCE_OR_SERVICES =
      noClasses()
          .that()
          .resideInAPackage("..dto..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..repository..", "..service..", "jakarta.persistence..");

  @ArchTest
  static final ArchRule SERVICES_DO_NOT_DEPEND_ON_HTTP_OR_SESSION_STATE =
      noClasses()
          .that()
          .resideInAPackage("..service..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("jakarta.servlet..", "org.springframework.web..");

  @ArchTest
  static final ArchRule DTO_REQUEST_AND_RESPONSE_PACKAGES_ARE_INDEPENDENT =
      slices().matching("com.airline.reservation.dto.(*)..").should().notDependOnEachOther();

  @ArchTest
  static final ArchRule DTOS_DO_NOT_EXPOSE_JPA_ENTITIES =
      noClasses()
          .that()
          .resideInAPackage("..dto..")
          .should()
          .dependOnClassesThat()
          .areAnnotatedWith(Entity.class);

  @ArchTest
  static final ArchRule SERVICE_CONTRACTS_ARE_INTERFACES =
      classes()
          .that()
          .resideInAPackage("com.airline.reservation.service")
          .and()
          .haveSimpleNameEndingWith("Service")
          .should()
          .beInterfaces();

  @ArchTest
  static final ArchRule APPLICATION_CONTRACTS_ARE_PERSISTENCE_AND_TRANSPORT_INDEPENDENT =
      noClasses()
          .that()
          .resideInAnyPackage("com.airline.reservation.service", "..model..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "..entity..",
              "..repository..",
              "..dto..",
              "jakarta.persistence..",
              "org.springframework.data..",
              "org.hibernate.exception..");

  @ArchTest
  static final ArchRule SERVICES_DO_NOT_USE_API_DTOS =
      noClasses()
          .that()
          .resideInAPackage("..service..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..dto..");

  @ArchTest
  static final ArchRule COLLABORATORS_DEPEND_ON_SERVICE_CONTRACTS =
      noClasses()
          .that()
          .resideInAnyPackage("..controller..", "..service.impl..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..service.impl..");

  @ArchTest
  static final ArchRule PERSISTENCE_DOES_NOT_DEPEND_ON_API_DTOS =
      noClasses()
          .that()
          .resideInAPackage("..repository..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..dto..");

  @ArchTest
  static final ArchRule MODELS_HAVE_NO_JSON_OR_SPRING_DEPENDENCIES =
      noClasses()
          .that()
          .resideInAPackage("..model..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.fasterxml.jackson..",
              "tools.jackson..",
              "org.springframework..",
              "jakarta.persistence..");
}
