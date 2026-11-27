package com.taskora.board.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskora.board.support.ApiTestBase;
import com.taskora.board.support.BoardDatabase;
import com.taskora.board.support.Fixture;
import com.taskora.board.support.OpenApiContracts;
import io.restassured.response.Response;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.parser.OpenAPIV3Parser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * SC-BOARD-021, SC-BOARD-006 (surchargé E1), SC-BOARD-010, SC-BOARD-034 — matrice générée depuis le
 * contrat : chaque opération T1 × {owner, admin, member, non-membre, anonyme} × {ressource d'Alice,
 * id inexistant} × {corps valide, invalide, non JSON}. Un non-membre reçoit exactement la réponse
 * d'une ressource inexistante (statut, corps, TOUS les en-têtes), sans aucune écriture ni message
 * d'outbox ; l'accès est contrôlé avant l'analyse du corps.
 */
class AccessMatrixIT extends ApiTestBase {

  /** Opération du contrat. */
  record Op(String method, String template, String operationId) {
    boolean hasBoardId() {
      return template.contains("{id}");
    }

    boolean isRestore() {
      return "restoreBoard".equals(operationId);
    }

    /** Chemin concret (/api…) pour un identifiant de tableau. */
    String path(int boardId) {
      return "/api"
          + template
              .replace("{id}", String.valueOf(boardId))
              .replace("{userId}", String.valueOf(Fixture.MARC))
              .replace("{invId}", String.valueOf(Fixture.INVITATION_INES_LAUNCH));
    }

    /** Ressource d'Alice : 103 (dans la corbeille) pour la restauration, 101 sinon. */
    int aliceBoard() {
      return isRestore() ? Fixture.OLD_TRASHED : Fixture.LAUNCH;
    }

    String body(String variant) {
      return switch (variant) {
        case "valide" ->
            switch (operationId) {
              case "createBoard", "updateBoard" -> "{\"title\":\"Matrice\"}";
              case "addMember" -> "{\"email\":\"dora@exemple.test\"}";
              case "changeMemberRole" -> "{\"role\":\"admin\"}";
              default -> "{}";
            };
        case "invalide" ->
            switch (operationId) {
              case "createBoard" -> "{\"title\":\"\"}";
              case "updateBoard" -> "{\"title\":42}";
              case "addMember" -> "{\"email\":\"pas-un-email\"}";
              case "changeMemberRole" -> "{\"role\":\"owner\"}";
              default -> "[]";
            };
        default -> "{";
      };
    }

    /** Message du 404 d'un non-membre (spec §5.3). */
    String notFoundMessage() {
      return isRestore() ? "Ce tableau n’est pas dans la corbeille." : "Tableau introuvable.";
    }
  }

  static List<Op> operations() {
    OpenAPI api = new OpenAPIV3Parser().read(OpenApiContracts.PUBLIC.toUri().toString());
    List<Op> ops = new ArrayList<>();
    for (Map.Entry<String, PathItem> e : api.getPaths().entrySet()) {
      e.getValue()
          .readOperationsMap()
          .forEach((m, o) -> ops.add(new Op(m.name(), e.getKey(), o.getOperationId())));
    }
    return ops;
  }

  @Test
  @DisplayName("SC-BOARD-021 — la matrice couvre les 12 opérations T1 du contrat")
  void matrixCoversContract() {
    assertThat(operations())
        .extracting(Op::operationId)
        .containsExactlyInAnyOrder(
            "listMyBoards",
            "createBoard",
            "getBoard",
            "updateBoard",
            "trashBoard",
            "toggleFavorite",
            "addMember",
            "changeMemberRole",
            "removeMember",
            "cancelInvitation",
            "listTrashedBoards",
            "restoreBoard");
  }

  static Stream<Arguments> nonMemberCases() {
    List<Arguments> out = new ArrayList<>();
    for (Op op : operations()) {
      if (op.hasBoardId()) {
        for (String body : List.of("valide", "invalide", "non JSON")) {
          out.add(Arguments.of(op.operationId(), op, body));
        }
      }
    }
    return out.stream();
  }

  @ParameterizedTest(name = "{0} — corps {2}")
  @MethodSource("nonMemberCases")
  @DisplayName(
      "SC-BOARD-021 / 006 — non-membre ≡ inexistant (statut, corps, en-têtes), 404 §5.3, 0 écriture, 0 outbox")
  void nonMemberIndistinguishable(String operationId, Op op, String variant) {
    Map<String, Object> before = BoardDatabase.dataFingerprint();
    Response onAlice = send(op.method(), nadia(), op.path(op.aliceBoard()), op.body(variant));
    Response onMissing =
        send(op.method(), nadia(), op.path(Fixture.MISSING_BOARD), op.body(variant));
    Response ownerOnMissing =
        send(op.method(), alice(), op.path(Fixture.MISSING_BOARD), op.body(variant));

    assertError(onAlice, 404, op.notFoundMessage());
    assertIndistinguishable(onAlice, onMissing, operationId + " non-membre / inexistant");
    assertIndistinguishable(
        onAlice, ownerOnMissing, operationId + " non-membre / inexistant (propriétaire)");
    assertThat(BoardDatabase.dataFingerprint())
        .as("aucune écriture, aucun message d'outbox")
        .isEqualTo(before);
  }

  @Test
  @DisplayName(
      "SC-BOARD-021 — tableau d'Alice dans la corbeille : non-membre → Tableau introuvable., membre → Ce tableau est dans la corbeille.")
  void trashedBoardForNonMemberAndMember() {
    Response nonMember = get(nadia(), "/api/boards/" + Fixture.OLD_TRASHED);
    assertError(nonMember, 404, "Tableau introuvable.");
    assertIndistinguishable(
        nonMember, get(nadia(), "/api/boards/" + Fixture.MISSING_BOARD), "corbeille / inexistant");
    assertError(
        get(carla(), "/api/boards/" + Fixture.OLD_TRASHED),
        404,
        "Ce tableau est dans la corbeille.");
    assertError(
        patch(nadia(), "/api/boards/" + Fixture.OLD_TRASHED, "{\"title\":\"x\"}"),
        404,
        "Tableau introuvable.");
  }

  /** Attendu, corps valide, pour owner (Alice), admin (Bruno), member (Carla). */
  static Stream<Arguments> roleCases() {
    String managerOnly = "Action réservée aux administrateurs du tableau.";
    return Stream.of(
        Arguments.of("listMyBoards", "owner", 200, null),
        Arguments.of("listMyBoards", "admin", 200, null),
        Arguments.of("listMyBoards", "member", 200, null),
        Arguments.of("createBoard", "owner", 201, null),
        Arguments.of("createBoard", "member", 201, null),
        Arguments.of("getBoard", "owner", 200, null),
        Arguments.of("getBoard", "admin", 200, null),
        Arguments.of("getBoard", "member", 200, null),
        Arguments.of("updateBoard", "owner", 200, null),
        Arguments.of("updateBoard", "admin", 200, null),
        Arguments.of("updateBoard", "member", 200, null),
        Arguments.of("trashBoard", "owner", 204, null),
        Arguments.of("trashBoard", "admin", 403, "Seul le propriétaire peut supprimer le tableau."),
        Arguments.of(
            "trashBoard", "member", 403, "Seul le propriétaire peut supprimer le tableau."),
        Arguments.of("toggleFavorite", "owner", 200, null),
        Arguments.of("toggleFavorite", "admin", 200, null),
        Arguments.of("toggleFavorite", "member", 200, null),
        Arguments.of("addMember", "owner", 201, null),
        Arguments.of("addMember", "admin", 201, null),
        Arguments.of("addMember", "member", 403, managerOnly),
        Arguments.of("changeMemberRole", "owner", 200, null),
        Arguments.of("changeMemberRole", "admin", 200, null),
        Arguments.of("changeMemberRole", "member", 403, managerOnly),
        Arguments.of("removeMember", "owner", 204, null),
        Arguments.of("removeMember", "admin", 204, null),
        Arguments.of("removeMember", "member", 403, managerOnly),
        Arguments.of("cancelInvitation", "owner", 204, null),
        Arguments.of("cancelInvitation", "admin", 204, null),
        Arguments.of("cancelInvitation", "member", 403, managerOnly),
        Arguments.of("listTrashedBoards", "owner", 200, null),
        Arguments.of("listTrashedBoards", "member", 200, null),
        Arguments.of("restoreBoard", "owner", 200, null),
        Arguments.of(
            "restoreBoard",
            "admin",
            403,
            "Seul le propriétaire du tableau peut le restaurer ou le supprimer."),
        Arguments.of(
            "restoreBoard",
            "member",
            403,
            "Seul le propriétaire du tableau peut le restaurer ou le supprimer."));
  }

  private static String token(String role) {
    return switch (role) {
      case "owner" -> alice();
      case "admin" -> bruno();
      default -> carla();
    };
  }

  private static Op op(String operationId) {
    return operations().stream()
        .filter(o -> o.operationId().equals(operationId))
        .findFirst()
        .orElseThrow();
  }

  @ParameterizedTest(name = "{0} par {1} → {2}")
  @MethodSource("roleCases")
  @DisplayName("SC-BOARD-010 / 021 — rôles (corps valide) : statuts et messages 403 actuels")
  void roles(String operationId, String role, int status, String message) {
    Op op = op(operationId);
    Response r = send(op.method(), token(role), op.path(op.aliceBoard()), op.body("valide"));
    if (message == null) {
      assertStatus(r, status);
    } else {
      assertError(r, status, message);
    }
  }

  @ParameterizedTest(name = "{0} — corps {1}")
  @MethodSource("managerOnlyWithBadBodies")
  @DisplayName(
      "SC-BOARD-021 — accès avant le corps : simple membre + corps invalide ou non JSON → 403, jamais 400")
  void accessBeforeBody(String operationId, String variant) {
    Op op = op(operationId);
    Map<String, Object> before = BoardDatabase.dataFingerprint();
    assertError(
        send(op.method(), carla(), op.path(op.aliceBoard()), op.body(variant)),
        403,
        "Action réservée aux administrateurs du tableau.");
    assertThat(BoardDatabase.dataFingerprint()).isEqualTo(before);
  }

  static Stream<Arguments> managerOnlyWithBadBodies() {
    List<Arguments> out = new ArrayList<>();
    for (String id : List.of("addMember", "changeMemberRole")) {
      for (String v : List.of("invalide", "non JSON")) {
        out.add(Arguments.of(id, v));
      }
    }
    return out.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("allOperations")
  @DisplayName("SC-BOARD-021 — anonyme → 401 JWT Token not found, 0 écriture")
  void anonymous(String operationId) {
    Op op = op(operationId);
    Map<String, Object> before = BoardDatabase.dataFingerprint();
    assertAuthError(
        send(op.method(), null, op.path(op.aliceBoard()), op.body("valide")),
        "JWT Token not found");
    assertThat(BoardDatabase.dataFingerprint()).isEqualTo(before);
  }

  static Stream<String> allOperations() {
    return operations().stream().map(Op::operationId);
  }

  @Test
  @DisplayName(
      "SC-BOARD-021 — membre retiré pendant sa session → réponse du non-membre dès la requête suivante")
  void removedMemberGetsNonMemberAnswer() {
    String marc = marc();
    assertStatus(get(marc, "/api/boards/101"), 200);
    assertStatus(delete(alice(), "/api/boards/101/members/" + Fixture.MARC), 204);
    Response after = get(marc, "/api/boards/101");
    assertError(after, 404, "Tableau introuvable.");
    assertIndistinguishable(
        after, get(marc, "/api/boards/" + Fixture.MISSING_BOARD), "retiré / inexistant");
    assertThat(json(get(marc, "/api/boards"))).isEmpty();
  }

  @Test
  @DisplayName("SC-BOARD-021 — IDOR : le tableau d'Ève n'est pas lisible ni modifiable par Alice")
  void idor() {
    Map<String, Object> before = BoardDatabase.dataFingerprint();
    assertError(get(alice(), "/api/boards/" + Fixture.EVE_BOARD), 404, "Tableau introuvable.");
    assertError(
        delete(
            alice(),
            "/api/boards/" + Fixture.EVE_BOARD + "/invitations/" + Fixture.INVITATION_EVE_BOARD),
        404,
        "Tableau introuvable.");
    assertError(
        post(
            alice(),
            "/api/boards/" + Fixture.EVE_BOARD + "/members",
            "{\"email\":\"dora@exemple.test\"}"),
        404,
        "Tableau introuvable.");
    assertThat(BoardDatabase.dataFingerprint()).isEqualTo(before);
  }
}
