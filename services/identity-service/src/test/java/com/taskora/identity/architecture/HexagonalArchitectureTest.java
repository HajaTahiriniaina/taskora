package com.taskora.identity.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Les 5 règles hexagonales (backend-hexagonal.md §3) et l'ADR A5. */
@AnalyzeClasses(
    packages = "com.taskora.identity",
    importOptions = ImportOption.DoNotIncludeTests.class)
class HexagonalArchitectureTest {
  private static final String ROOT = "com.taskora.identity";

  @ArchTest
  static final ArchRule domainIsPure =
      classes()
          .that()
          .resideInAPackage(ROOT + ".domain..")
          .should()
          .onlyDependOnClassesThat()
          .resideInAnyPackage(ROOT + ".domain..", "java..")
          .as("Règle 1 — domain ne dépend que de domain et du JDK");

  @ArchTest
  static final ArchRule domainWithoutFrameworks =
      noClasses()
          .that()
          .resideInAPackage(ROOT + ".domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..",
              "jakarta.persistence..",
              "com.fasterxml..",
              "tools.jackson..")
          .as("Règle 1 — domain sans Spring, JPA ni Jackson");

  @ArchTest
  static final ArchRule applicationDependsOnDomainOnly =
      classes()
          .that()
          .resideInAPackage(ROOT + ".application..")
          .should()
          .onlyDependOnClassesThat()
          .resideInAnyPackage(ROOT + ".application..", ROOT + ".domain..", "java..")
          .as(
              "Règle 2 — application ne dépend que de domain, d'elle-même et du JDK (pas de Spring)");

  @ArchTest
  static final ArchRule coreIgnoresAdapters =
      noClasses()
          .that()
          .resideInAnyPackage(ROOT + ".domain..", ROOT + ".application..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(ROOT + ".adapter..", ROOT + ".config..")
          .as("Règle 3 — domain et application ne dépendent jamais des adapters ni de config");

  @ArchTest
  static final ArchRule adaptersIndependent =
      slices()
          .matching(ROOT + ".adapter.(*).(*)..")
          .should()
          .notDependOnEachOther()
          .as("Règle 3 — aucun adapter n'en appelle un autre directement");

  @ArchTest
  static final ArchRule entitiesInPersistence =
      classes()
          .that()
          .areAnnotatedWith("jakarta.persistence.Entity")
          .should()
          .resideInAPackage(ROOT + ".adapter.out.persistence..")
          .as("Règle 4 — les entités JPA restent dans adapter.out.persistence");

  @ArchTest
  static final ArchRule jpaConfined =
      noClasses()
          .that()
          .resideOutsideOfPackages(ROOT + ".adapter.out.persistence..", ROOT + ".config..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("jakarta.persistence..")
          .as(
              "Règle 4 — aucune classe hors persistence ne dépend d'une entité JPA ou de jakarta.persistence");

  @ArchTest
  static final ArchRule noCycles =
      slices()
          .matching(ROOT + ".(*)..")
          .should()
          .beFreeOfCycles()
          .as("Règle 5 — aucun cycle entre paquets");

  @ArchTest
  static final ArchRule noAdapterCycles =
      slices()
          .matching(ROOT + ".adapter.(**)")
          .should()
          .beFreeOfCycles()
          .as("Règle 5 — aucun cycle entre sous-paquets d'adapter");

  @ArchTest
  static final ArchRule noDataRest =
      noClasses()
          .should()
          .dependOnClassesThat()
          .resideInAPackage("org.springframework.data.rest..")
          .orShould()
          .beAnnotatedWith("org.springframework.data.rest.core.annotation.RepositoryRestResource")
          .as("A5 — aucune ressource Spring Data REST");

  @Test
  @DisplayName("A5 — spring-data-rest absent du classpath")
  void dataRestNotOnClasspath() {
    assertThatThrownBy(() ->
            Class.forName("org.springframework.data.rest.core.config.RepositoryRestConfiguration"))
        .isInstanceOf(ClassNotFoundException.class);
  }
}
