package com.taskora.board.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import com.atlassian.oai.validator.restassured.OpenApiValidationFilter;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Contrats OpenAPI (chemins relatifs au module : ../../docs/contracts/openapi/…). Chaque réponse
 * d'API des IT est validée contre le contrat ; la validation des REQUÊTES est désactivée, car les
 * tests envoient volontairement des requêtes hors contrat (corps non JSON, identifiants {@code
 * abc}…).
 */
public final class OpenApiContracts {
  public static final Path PUBLIC = Path.of("../../docs/contracts/openapi/board-service.yaml");
  public static final Path INTERNAL =
      Path.of("../../docs/contracts/openapi/board-service.internal.yaml");
  public static final Path SYMFONY_INTERNAL =
      Path.of("../../docs/contracts/openapi/symfony.internal.yaml");

  private static OpenApiValidationFilter publicFilter;
  private static OpenApiValidationFilter internalFilter;
  private static OpenApiInteractionValidator symfonyValidator;

  private OpenApiContracts() {}

  private static OpenApiInteractionValidator validator(Path contract, String basePath) {
    assertThat(Files.exists(contract))
        .as("contrat OpenAPI introuvable : " + contract.toAbsolutePath())
        .isTrue();
    OpenApiInteractionValidator.Builder b =
        OpenApiInteractionValidator.createForSpecificationUrl(contract.toUri().toString())
            .withLevelResolver(
                LevelResolver.create()
                    .withLevel("validation.request", ValidationReport.Level.IGNORE)
                    .build());
    if (basePath != null) {
      b.withBasePathOverride(basePath);
    }
    return b.build();
  }

  /** Filtre RestAssured : réponses conformes à board-service.yaml (serveur /api). */
  public static synchronized OpenApiValidationFilter publicApi() {
    if (publicFilter == null) {
      publicFilter = new OpenApiValidationFilter(validator(PUBLIC, "/api"));
    }
    return publicFilter;
  }

  /** Filtre RestAssured : réponses conformes à board-service.internal.yaml. */
  public static synchronized OpenApiValidationFilter internalApi() {
    if (internalFilter == null) {
      internalFilter = new OpenApiValidationFilter(validator(INTERNAL, "/"));
    }
    return internalFilter;
  }

  /**
   * Corps envoyé par board-service à {@code POST /internal/notifications} conforme au schéma {@code
   * NotificationRequested} (symfony.internal.yaml → board-service.internal.yaml).
   */
  public static void assertNotificationRequestConforms(String body, String secret) {
    synchronized (OpenApiContracts.class) {
      if (symfonyValidator == null) {
        symfonyValidator =
            OpenApiInteractionValidator.createForSpecificationUrl(
                    SYMFONY_INTERNAL.toUri().toString())
                .withBasePathOverride("/")
                .build();
      }
    }
    SimpleRequest request =
        SimpleRequest.Builder.post("/internal/notifications")
            .withContentType("application/json")
            .withHeader("X-Internal-Secret", secret)
            .withBody(body)
            .build();
    ValidationReport report = symfonyValidator.validateRequest(request);
    assertThat(report.getMessages())
        .as("corps de /internal/notifications non conforme : %s", body)
        .isEmpty();
  }

  /** Méthode du contrat (pour les rapports). */
  public static Request.Method method(String m) {
    return Request.Method.valueOf(m);
  }
}
