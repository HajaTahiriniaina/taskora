package com.taskora.board.compat;

import static com.taskora.board.compat.CompatStack.BOARD;
import static com.taskora.board.compat.CompatStack.SYMFONY;
import static com.taskora.board.compat.CompatStack.json;
import static com.taskora.board.compat.CompatStack.send;
import static org.assertj.core.api.Assertions.assertThat;

import com.taskora.board.compat.CompatStack.Account;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * SC-BOARD-023, SC-BOARD-034 — écritures croisées sur les tables partagées : un tableau créé par
 * board-service reçoit une carte créée par Symfony (numéro 1) ; un tableau créé par Symfony
 * apparaît aussitôt, avec ses compteurs, dans l'accueil servi par board-service ; un tableau mis à
 * la corbeille par board-service rend ses listes et cartes (Symfony) 404 pour ses membres.
 */
@Tag("compat")
class CrossWritesCompatTest {
  static Account alice;
  static Account bob;

  @BeforeAll
  static void accounts() {
    CompatStack.requireBothUp();
    alice = CompatStack.register("Alice Croisée");
    bob = CompatStack.register("Bob Croisé");
  }

  @Test
  @DisplayName(
      "SC-BOARD-023 — tableau créé par board-service, carte créée par Symfony : numéro 1, compteurs à jour des deux côtés")
  void boardServiceThenSymfony() {
    Response created = send(BOARD, alice, "POST", "/api/boards", "{\"title\":\"Croisé\"}");
    assertThat(created.statusCode()).isEqualTo(201);
    int board = json(created).get("id").asInt();
    int list = json(created).get("lists").get(0).get("id").asInt();
    Response card =
        send(SYMFONY, alice, "POST", "/api/lists/" + list + "/cards", "{\"title\":\"Première\"}");
    assertThat(card.statusCode()).as(card.asString()).isEqualTo(201);
    assertThat(json(card).get("number").asInt()).isEqualTo(1);
    JsonNode viaBoard = json(send(BOARD, alice, "GET", "/api/boards/" + board, null));
    assertThat(viaBoard.get("cardCount").asInt()).isEqualTo(1);
    assertThat(json(send(SYMFONY, alice, "GET", "/api/boards/" + board, null))).isEqualTo(viaBoard);
  }

  @Test
  @DisplayName(
      "SC-BOARD-023 — tableau créé par Symfony : visible immédiatement dans GET /api/boards (board-service), sans cache")
  void symfonyThenBoardService() {
    int before = json(send(BOARD, alice, "GET", "/api/boards", null)).size();
    Response created = send(SYMFONY, alice, "POST", "/api/boards", "{\"title\":\"Par Symfony\"}");
    int board = json(created).get("id").asInt();
    JsonNode home = json(send(BOARD, alice, "GET", "/api/boards", null));
    assertThat(home.size()).isEqualTo(before + 1);
    assertThat(home.get(0).get("id").asInt()).isEqualTo(board);
    assertThat(home.get(0).get("listCount").asInt()).isEqualTo(4);
  }

  @Test
  @DisplayName(
      "SC-BOARD-034 — corbeille via board-service : routes Symfony des listes et cartes → 404 Ce tableau est dans la corbeille. ; restauration → tout réapparaît")
  void transitiveTrash() {
    Response created =
        send(BOARD, alice, "POST", "/api/boards", "{\"title\":\"Corbeille croisée\"}");
    int board = json(created).get("id").asInt();
    int list = json(created).get("lists").get(0).get("id").asInt();
    send(
        BOARD,
        alice,
        "POST",
        "/api/boards/" + board + "/members",
        "{\"email\":\"" + bob.email() + "\"}");
    int card =
        json(send(SYMFONY, bob, "POST", "/api/lists/" + list + "/cards", "{\"title\":\"x\"}"))
            .get("id")
            .asInt();
    assertThat(send(BOARD, alice, "DELETE", "/api/boards/" + board, null).statusCode())
        .isEqualTo(204);
    for (String path :
        new String[] {
          "/api/lists/" + list + "/cards",
          "/api/cards/" + card,
          "/api/boards/" + board + "/archived"
        }) {
      Response r = send(SYMFONY, bob, "GET", path, null);
      assertThat(r.statusCode()).as(path).isEqualTo(404);
      assertThat(json(r).get("error").asString())
          .as(path)
          .isEqualTo("Ce tableau est dans la corbeille.");
    }
    assertThat(
            send(BOARD, alice, "POST", "/api/trash/boards/" + board + "/restore", null)
                .statusCode())
        .isEqualTo(200);
    assertThat(send(SYMFONY, bob, "GET", "/api/cards/" + card, null).statusCode()).isEqualTo(200);
  }
}
