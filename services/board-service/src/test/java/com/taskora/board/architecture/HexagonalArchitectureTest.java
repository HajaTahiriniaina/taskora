package com.taskora.board.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Les cinq règles hexagonales (backend-hexagonal.md §3), la règle 4 adaptée par B-ADR-1 (pas de JPA
 * : {@code JdbcClient} et {@code java.sql} confinés dans {@code adapter.out.persistence}) et
 * B-ADR-8 (pas de Spring Data REST).
 */
@AnalyzeClasses(
    packages = "com.taskora.board",
    importOptions = ImportOption.DoNotIncludeTests.class)
class HexagonalArchitectureTest {
  private static final String ROOT = "com.taskora.board";

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
              "jakarta..",
              "com.fasterxml..",
              "tools.jackson..",
              "java.sql..",
              "javax.sql..")
          .as("Règle 1 — domain sans Spring, Jakarta, Jackson ni JDBC");

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
  static final ArchRule applicationWithoutJdbc =
      noClasses()
          .that()
          .resideInAPackage(ROOT + ".application..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("java.sql..", "javax.sql..")
          .as("Règle 2 (B-ADR-1) — application sans JDBC");

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
  static final ArchRule jdbcConfined =
      noClasses()
          .that()
          .resideOutsideOfPackages(ROOT + ".adapter.out.persistence..", ROOT + ".config..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("org.springframework.jdbc..", "java.sql..", "javax.sql..")
          .as(
              "Règle 4 (B-ADR-1) — JdbcClient, java.sql et javax.sql seulement dans"
                  + " adapter.out.persistence (config : assemblage)");

  @ArchTest
  static final ArchRule noJdbcApiInConfig =
      noClasses()
          .that()
          .resideInAPackage(ROOT + ".config..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("java.sql..")
          .as("Règle 4 (B-ADR-1) — aucune requête SQL dans config");

  @ArchTest
  static final ArchRule noJpa =
      noClasses()
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("jakarta.persistence..", "org.hibernate..")
          .as("B-ADR-1 — ni JPA ni Hibernate");

  @ArchTest
  static final ArchRule noDataRest =
      noClasses()
          .should()
          .dependOnClassesThat()
          .resideInAPackage("org.springframework.data.rest..")
          .orShould()
          .beAnnotatedWith("org.springframework.data.rest.core.annotation.RepositoryRestResource")
          .as("B-ADR-8 — aucune ressource Spring Data REST");

  @ArchTest
  static final ArchRule noCycles =
      slices().matching(ROOT + ".(*)..").should().beFreeOfCycles().as("Règle 5 — aucun cycle");

  @ArchTest
  static final ArchRule noAdapterCycles =
      slices()
          .matching(ROOT + ".adapter.(**)")
          .should()
          .beFreeOfCycles()
          .as("Règle 5 — aucun cycle entre sous-paquets d'adapter");

  @ParameterizedTest(name = "{0} absent du classpath")
  @ValueSource(
      strings = {
        "jakarta.persistence.Entity",
        "org.hibernate.Session",
        "org.springframework.data.rest.core.config.RepositoryRestConfiguration",
        "org.springframework.data.jpa.repository.JpaRepository"
      })
  void forbiddenLibrariesAbsent(String className) {
    assertThatThrownBy(() -> Class.forName(className)).isInstanceOf(ClassNotFoundException.class);
  }
}
