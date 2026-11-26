package com.taskora.identity.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Valide chaque réponse de test contre le contrat public
 * docs/contracts/openapi/identity-service.yaml (chemin relatif au module : ../../docs/...).
 */
public final class OpenApiValidation {
  public static final Path CONTRACT = Path.of("../../docs/contracts/openapi/identity-service.yaml");
  private static OpenApiInteractionValidator validator;

  private OpenApiValidation() {}

  private static synchronized OpenApiInteractionValidator validator() {
    if (validator == null) {
      assertThat(Files.exists(CONTRACT))
          .as("contrat OpenAPI introuvable : " + CONTRACT.toAbsolutePath())
          .isTrue();
      validator =
          OpenApiInteractionValidator.createForSpecificationUrl(CONTRACT.toUri().toString())
              .withBasePathOverride("/api")
              .build();
    }
    return validator;
  }

  /** Réponse HTTP réelle (serveur sur port aléatoire) conforme au contrat pour cette opération. */
  public static void assertResponseConforms(
      String method, String path, int status, String contentType, String body) {
    SimpleResponse.Builder b = SimpleResponse.Builder.status(status);
    if (contentType != null) {
      b.withContentType(contentType);
    }
    b.withBody(body);
    ValidationReport report =
        validator().validateResponse(path, Request.Method.valueOf(method), b.build());
    assertThat(report.getMessages())
        .as("réponse %s %s non conforme au contrat", method, path)
        .isEmpty();
  }

  /** La réponse (statut, en-têtes, corps) est conforme au contrat pour cette opération. */
  public static void assertResponseConforms(MvcResult result) {
    MockHttpServletResponse r = result.getResponse();
    SimpleResponse.Builder b = SimpleResponse.Builder.status(r.getStatus());
    if (r.getContentType() != null) {
      b.withContentType(r.getContentType());
    }
    for (String name : r.getHeaderNames()) {
      b.withHeader(name, r.getHeaders(name));
    }
    try {
      b.withBody(r.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    } catch (java.io.UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    }
    String path = result.getRequest().getRequestURI();
    Request.Method method = Request.Method.valueOf(result.getRequest().getMethod());
    ValidationReport report = validator().validateResponse(path, method, b.build());
    assertThat(report.getMessages())
        .as("réponse %s %s non conforme au contrat", method, path)
        .isEmpty();
  }
}
