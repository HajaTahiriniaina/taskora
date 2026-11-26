package com.taskora.identity.compat;

import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** SC-IDENT-010 (A1) : jetons croisés entre Symfony et identity-service (même paire de clés). */
@Tag("compat")
class CrossTokenCompatibilityTest {

  @BeforeAll
  static void identityMustBeUp() {
    CompatStack.requireIdentityUp();
  }

  @Test
  @DisplayName("SC-IDENT-010 — jeton de login Symfony → GET /api/me sur identity : 200")
  void symfonyTokenOnIdentity() {
    String email = CompatStack.uniqueEmail();
    CompatStack.register(
        CompatStack.IDENTITY, email, "motdepasse"); // compte projeté dans taskora.user
    Response login =
        CompatStack.post(
            CompatStack.SYMFONY,
            "/api/auth/login",
            "{\"email\":\"" + email + "\",\"password\":\"motdepasse\"}");
    assertThat(login.statusCode()).as(login.asString()).isEqualTo(200);
    Response me =
        CompatStack.get(CompatStack.IDENTITY, "/api/me", login.jsonPath().getString("token"));
    assertThat(me.statusCode()).as(me.asString()).isEqualTo(200);
    assertThat(me.jsonPath().getString("email")).isEqualTo(email);
  }

  @Test
  @DisplayName("SC-IDENT-010 — jeton identity (avec uid) → GET /api/boards sur Symfony : 200")
  void identityTokenOnSymfony() {
    String token =
        CompatStack.register(CompatStack.IDENTITY, CompatStack.uniqueEmail(), "motdepasse");
    Response boards = CompatStack.get(CompatStack.SYMFONY, "/api/boards", token);
    assertThat(boards.statusCode()).as(boards.asString()).isEqualTo(200);
    Response me = CompatStack.get(CompatStack.SYMFONY, "/api/me", token);
    assertThat(me.statusCode()).isEqualTo(200);
  }
}
