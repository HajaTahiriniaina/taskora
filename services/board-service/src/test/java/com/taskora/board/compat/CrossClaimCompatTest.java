package com.taskora.board.compat;

import static com.taskora.board.compat.CompatStack.BOARD;
import static com.taskora.board.compat.CompatStack.SYMFONY;
import static com.taskora.board.compat.CompatStack.json;
import static com.taskora.board.compat.CompatStack.send;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.taskora.board.compat.CompatStack.Account;
import io.restassured.response.Response;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * SC-BOARD-030 (rejeu croisé), U12 — un message de consommation d'invitations peut être livré à
 * Symfony ou à board-service : les deux implémentations sont idempotentes et équivalentes ; chacune
 * n'accepte que le secret de son appelant ({@code INTERNAL_API_SECRET} pour Symfony, {@code
 * INTERNAL_SECRET_IDENTITY_TO_BOARD} pour board-service).
 */
@Tag("compat")
class CrossClaimCompatTest {
  static Account alice;

  @BeforeAll
  static void accounts() {
    CompatStack.requireBothUp();
    alice = CompatStack.register("Alice Claim");
  }

  private static Response claim(String base, String secret, String body) {
    return given()
        .baseUri(base)
        .contentType("application/json")
        .header("X-Internal-Secret", secret)
        .body(body)
        .post("/internal/invitations/claim");
  }

  @Test
  @DisplayName(
      "SC-BOARD-030 — invitation consommée par l'un, rejeu chez l'autre → 200 claimed 0 ; adhésion unique")
  void crossReplay() {
    String email = CompatStack.uniqueEmail("invite");
    int board =
        json(send(BOARD, alice, "POST", "/api/boards", "{\"title\":\"Claim croisé\"}"))
            .get("id")
            .asInt();
    assertThat(
            send(
                    BOARD,
                    alice,
                    "POST",
                    "/api/boards/" + board + "/members",
                    "{\"email\":\"" + email + "\"}")
                .statusCode())
        .isEqualTo(202);
    Response reg =
        given()
            .baseUri(SYMFONY)
            .contentType("application/json")
            .body(
                "{\"name\":\"Invité\",\"email\":\""
                    + email
                    + "\",\"password\":\""
                    + CompatStack.PASSWORD
                    + "\"}")
            .post("/api/auth/register");
    assertThat(reg.statusCode()).isEqualTo(201);
    int userId = json(reg).get("user").get("id").asInt();
    String body =
        "{\"eventId\":\""
            + UUID.randomUUID()
            + "\",\"userId\":"
            + userId
            + ",\"email\":\""
            + email
            + "\"}";
    Response viaBoard = claim(BOARD, CompatStack.secret("INTERNAL_SECRET_IDENTITY_TO_BOARD"), body);
    Response viaSymfony = claim(SYMFONY, CompatStack.secret("INTERNAL_API_SECRET"), body);
    assertThat(viaBoard.statusCode()).isEqualTo(200);
    assertThat(viaSymfony.statusCode()).isEqualTo(200);
    assertThat(json(viaBoard).toString()).isEqualTo("{\"claimed\":0,\"boardIds\":[]}");
    assertThat(json(viaSymfony)).isEqualTo(json(viaBoard));
    assertThat(
            json(send(BOARD, alice, "GET", "/api/boards/" + board, null)).get("members").toString())
        .contains("\"id\":" + userId);
  }

  @Test
  @DisplayName(
      "U12 — chaque claim refuse le secret de l'autre appelant (401 Accès interne refusé.)")
  void secretsPerCaller() {
    String body =
        "{\"eventId\":\"" + UUID.randomUUID() + "\",\"userId\":1,\"email\":\"x@exemple.test\"}";
    Response board = claim(BOARD, CompatStack.secret("INTERNAL_API_SECRET"), body);
    Response symfony =
        claim(SYMFONY, CompatStack.secret("INTERNAL_SECRET_IDENTITY_TO_BOARD"), body);
    assertThat(board.statusCode()).isEqualTo(401);
    assertThat(symfony.statusCode()).isEqualTo(401);
    assertThat(json(board)).isEqualTo(json(symfony));
    assertThat(
            claim(BOARD, CompatStack.secret("INTERNAL_SECRET_BOARD_TO_SYMFONY"), body).statusCode())
        .isEqualTo(401);
  }
}
