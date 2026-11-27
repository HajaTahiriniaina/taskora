package com.taskora.board.compat;

import static com.taskora.board.compat.CompatStack.WEB;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.taskora.board.compat.CompatStack.Account;
import io.restassured.response.Response;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * SC-BOARD-022 — routage par tranche via nginx : avec {@code BOARD_ROUTING_MARKER=on}, les routes
 * de T1 portent {@code X-Taskora-Backend: board} si et seulement si {@code BOARD_T1_UPSTREAM} vise
 * board-service ({@code -Dcompat.board.t1=board|api}) ; les routes « toujours Symfony » et celles
 * de T2–T5 ne le portent jamais tant que leur tranche n'est pas basculée.
 */
@Tag("compat")
class RoutingCompatTest {
  private static final String MARKER = "X-Taskora-Backend";
  private static final boolean T1_ON_BOARD =
      "board".equals(System.getProperty("compat.board.t1", "api"));
  static Account alice;
  static int board;

  @BeforeAll
  static void account() {
    CompatStack.requireBothUp();
    alice = CompatStack.register("Alice Routage");
    board =
        CompatStack.json(
                CompatStack.send(
                    CompatStack.SYMFONY, alice, "POST", "/api/boards", "{\"title\":\"Routage\"}"))
            .get("id")
            .asInt();
  }

  private static Response via(String method, String path) {
    return given()
        .baseUri(WEB)
        .urlEncodingEnabled(false)
        .header("Authorization", "Bearer " + alice.token())
        .request(method, path);
  }

  @Test
  @DisplayName(
      "SC-BOARD-022 — routes T1 (identifiants numériques ou non) → board-service si T1 basculée, sinon Symfony")
  void t1Routes() {
    for (String[] r :
        List.of(
            new String[] {"GET", "/api/boards"},
            new String[] {"GET", "/api/boards/" + board},
            new String[] {"GET", "/api/boards/abc"},
            new String[] {"POST", "/api/boards/" + board + "/favorite"},
            new String[] {"DELETE", "/api/boards/" + board + "/invitations/999999"},
            new String[] {"PATCH", "/api/boards/" + board + "/members/999999"},
            new String[] {"GET", "/api/trash/boards"},
            new String[] {"POST", "/api/trash/boards/999999/restore"})) {
      Response resp = via(r[0], r[1]);
      assertThat(resp.getHeader(MARKER))
          .as("%s %s", r[0], r[1])
          .isEqualTo(T1_ON_BOARD ? "board" : null);
    }
  }

  @Test
  @DisplayName(
      "SC-BOARD-022 — routes jamais servies par board-service en phase 3 et routes T2–T5 : toujours Symfony")
  void alwaysSymfony() {
    for (String[] r :
        List.of(
            new String[] {"GET", "/api/cards/999999/attachments"},
            new String[] {"GET", "/api/boards/" + board + "/trello-files"},
            new String[] {"GET", "/api/notifications"},
            new String[] {"GET", "/api/health"},
            new String[] {"GET", "/api/boards/" + board + "/archived"},
            new String[] {"GET", "/api/lists/999999/cards"},
            new String[] {"GET", "/api/cards/999999"},
            new String[] {"DELETE", "/api/trash/boards/999999"})) {
      assertThat(via(r[0], r[1]).getHeader(MARKER)).as("%s %s", r[0], r[1]).isNull();
    }
  }
}
