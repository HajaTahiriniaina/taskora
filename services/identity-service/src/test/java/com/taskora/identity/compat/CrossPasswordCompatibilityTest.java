package com.taskora.identity.compat;

import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * SC-IDENT-023 (A2, A9) : hashes produits par identity lisibles par Symfony (retour arrière sans
 * perte).
 */
@Tag("compat")
class CrossPasswordCompatibilityTest {

  @BeforeAll
  static void identityMustBeUp() {
    CompatStack.requireIdentityUp();
  }

  private static int symfonyLogin(String email, String password) {
    return CompatStack.post(
            CompatStack.SYMFONY,
            "/api/auth/login",
            "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
        .statusCode();
  }

  @Test
  @DisplayName(
      "SC-IDENT-023 — compte inscrit via identity → login Symfony 200 (projection + hash $2y$)")
  void registeredOnIdentityLogsIntoSymfony() {
    String email = CompatStack.uniqueEmail();
    CompatStack.register(CompatStack.IDENTITY, email, "motdepasse");
    assertThat(symfonyLogin(email, "motdepasse")).isEqualTo(200);
  }

  @Test
  @DisplayName(
      "SC-IDENT-023 — mot de passe long (100 octets) inscrit via identity → login Symfony 200 (pré-hachage)")
  void longPassword() {
    String email = CompatStack.uniqueEmail();
    String pw = "p".repeat(100);
    CompatStack.register(CompatStack.IDENTITY, email, pw);
    assertThat(symfonyLogin(email, pw)).isEqualTo(200);
  }

  @Test
  @DisplayName(
      "SC-IDENT-023 — changement de mot de passe via identity → login Symfony avec le nouveau, pas l'ancien")
  void passwordChangeVisibleToSymfony() {
    String email = CompatStack.uniqueEmail();
    String token = CompatStack.register(CompatStack.IDENTITY, email, "ancienmdp1");
    Response p =
        CompatStack.patch(
            CompatStack.IDENTITY,
            "/api/me",
            "{\"currentPassword\":\"ancienmdp1\",\"newPassword\":\"nouveaumdp1\"}",
            token);
    assertThat(p.statusCode()).isEqualTo(200);
    assertThat(symfonyLogin(email, "nouveaumdp1")).isEqualTo(200);
    assertThat(symfonyLogin(email, "ancienmdp1")).isEqualTo(401);
  }
}
