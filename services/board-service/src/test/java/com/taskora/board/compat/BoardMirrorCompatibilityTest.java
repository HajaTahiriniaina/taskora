package com.taskora.board.compat;

import static com.taskora.board.compat.CompatStack.BOARD;
import static com.taskora.board.compat.CompatStack.SYMFONY;
import static com.taskora.board.compat.CompatStack.json;
import static com.taskora.board.compat.CompatStack.send;
import static org.assertj.core.api.Assertions.assertThat;

import com.taskora.board.compat.CompatStack.Account;
import io.restassured.response.Response;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * SC-BOARD-029 (miroir T1), SC-BOARD-021 (E1 comparé grâce à U8-a), SC-BOARD-034 — mêmes requêtes
 * contre Symfony et board-service sur la MÊME base (stack taskora-ci-board) ; comparaison du statut
 * et du JSON sémantique. Les écritures sont jouées sur des tableaux jumeaux (un par amont), créés à
 * l'identique ; identifiants et horodatages des jumeaux sont normalisés.
 *
 * <p>Divergences voulues exclues (ordre de travail §1.4), chacune avec sa référence : voir {@link
 * #INTENTIONAL_DIVERGENCES}. E1 n'est PAS exclu (U8-a aligne Symfony).
 */
@Tag("compat")
class BoardMirrorCompatibilityTest {

  /** Cas exclus du miroir → référence de la décision (ordre de travail T0/T1 §1.4). */
  static final Map<String, String> INTENTIONAL_DIVERGENCES = new LinkedHashMap<>();

  static {
    // U13 (E16 étendu) : corps JSON valide non objet → 400 chez board-service, accepté comme {} par
    // Symfony.
    INTENTIONAL_DIVERGENCES.put("U13-corps-non-objet", "U13");
    // E12 : identifiant de chemin non numérique → 404 Ressource introuvable. (Symfony : route
    // absente).
    INTENTIONAL_DIVERGENCES.put("E12-id-non-numerique", "E12");
    // E22 : title, description, email non chaînes → 400 (Symfony convertissait ou répondait 500).
    INTENTIONAL_DIVERGENCES.put("E22-types-texte", "E22");
    // E23 : withDefaults, showCardNumbers non booléens → 400 (Symfony convertissait).
    INTENTIONAL_DIVERGENCES.put("E23-types-booleens", "E23");
    // E28 : membre visé inexistant / non membre → 404 Membre introuvable. ; invitation d'un autre
    // tableau → 404.
    INTENTIONAL_DIVERGENCES.put("E28-enumeration-comptes", "E28");
    // Adhésions et invitations concurrentes : 409 au lieu de 500.
    INTENTIONAL_DIVERGENCES.put("CONCURRENCE-409", "CONCURRENCE");
  }

  /** Opérations T1 couvertes par le miroir (vérifié par MirrorScopeTest contre le contrat). */
  static final Set<String> MIRRORED_OPERATIONS =
      Set.of(
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

  /** Corps envoyés par le miroir (vérifiés par MirrorScopeTest : objets, types conformes). */
  static final List<String> MIRROR_BODIES =
      List.of(
          "{\"title\":\"Miroir\",\"background\":\"ocean\"}",
          "{\"title\":\"Miroir vide\",\"withDefaults\":false}",
          "{\"title\":\"Miroir fond\",\"background\":\"rainbow\"}",
          "{\"title\":\"\"}",
          "{\"title\":\"Renommé\",\"description\":\"0\",\"showCardNumbers\":false}",
          "{\"email\":\"%s\",\"role\":\"admin\"}",
          "{\"email\":\"%s\"}",
          "{\"email\":\"pas-un-email\"}",
          "{\"role\":\"admin\"}",
          "{\"role\":\"owner\"}");

  static Account alice;
  static Account bob;
  static Account carla;
  static Account outsider;
  static int shared;

  @BeforeAll
  static void dataset() {
    CompatStack.requireBothUp();
    alice = CompatStack.register("Alice Miroir");
    bob = CompatStack.register("Bob Miroir");
    carla = CompatStack.register("Carla Miroir");
    outsider = CompatStack.register("Olivier Dehors");
    shared = createOn(SYMFONY, "{\"title\":\"Partagé\",\"background\":\"forest\"}");
    send(
        SYMFONY,
        alice,
        "POST",
        "/api/boards/" + shared + "/members",
        "{\"email\":\"" + bob.email() + "\",\"role\":\"admin\"}");
    send(
        SYMFONY,
        alice,
        "POST",
        "/api/boards/" + shared + "/members",
        "{\"email\":\"" + carla.email() + "\"}");
    send(
        SYMFONY,
        alice,
        "POST",
        "/api/boards/" + shared + "/members",
        "{\"email\":\"" + CompatStack.uniqueEmail("attente") + "\"}");
    send(SYMFONY, carla, "POST", "/api/boards/" + shared + "/favorite", null);
    JsonNode lists = json(send(SYMFONY, alice, "GET", "/api/boards/" + shared, null)).get("lists");
    int first = lists.get(0).get("id").asInt();
    send(SYMFONY, alice, "POST", "/api/lists/" + first + "/cards", "{\"title\":\"Carte 1\"}");
    send(SYMFONY, alice, "POST", "/api/lists/" + first + "/cards", "{\"title\":\"Carte 2\"}");
    send(SYMFONY, alice, "POST", "/api/lists/" + lists.get(1).get("id").asInt() + "/archive", null);
    send(SYMFONY, alice, "DELETE", "/api/lists/" + lists.get(2).get("id").asInt(), null);
  }

  private static int createOn(String base, String body) {
    Response r = send(base, alice, "POST", "/api/boards", body);
    assertThat(r.statusCode()).as("%s : %s", base, r.asString()).isEqualTo(201);
    return json(r).get("id").asInt();
  }

  /** Statut et JSON sémantique identiques. */
  private static void same(Account who, String method, String path, String body) {
    Response s = send(SYMFONY, who, method, path, body);
    Response b = send(BOARD, who, method, path, body);
    assertThat(b.statusCode())
        .as("%s %s : statut (Symfony %s)", method, path, s.asString())
        .isEqualTo(s.statusCode());
    assertThat(json(b)).as("%s %s : corps", method, path).isEqualTo(json(s));
  }

  /** Remplace les identifiants des jumeaux et les horodatages par des marqueurs. */
  private static JsonNode normalized(JsonNode n, int twinId) {
    if (n == null) {
      return null;
    }
    String text = n.toString().replaceAll("\"id\":" + twinId + "(?=[,}])", "\"id\":\"<jumeau>\"");
    JsonNode out =
        CompatStack.JSON.readTree(text.replaceAll("\"\\d{4}-\\d{2}-\\d{2}T[^\"]+\"", "\"<date>\""));
    if (out instanceof ObjectNode o && o.has("lists")) {
      o.get("lists").forEach(l -> ((ObjectNode) l).put("id", 0));
      o.get("labels").forEach(l -> ((ObjectNode) l).put("id", 0));
      o.get("invitations").forEach(l -> ((ObjectNode) l).put("id", 0));
    }
    return out;
  }

  /** Même écriture sur deux jumeaux (un par amont) : statuts et JSON normalisés identiques. */
  private static void twin(String create, Account who, String method, String suffix, String body) {
    int onSymfony = createOn(SYMFONY, create);
    int onBoard = createOn(BOARD, create);
    for (Account a : new Account[] {bob, carla}) {
      for (int id : new int[] {onSymfony, onBoard}) {
        send(
            SYMFONY,
            alice,
            "POST",
            "/api/boards/" + id + "/members",
            "{\"email\":\""
                + a.email()
                + "\",\"role\":\""
                + (a == bob ? "admin" : "member")
                + "\"}");
      }
    }
    Response s = send(SYMFONY, who, method, "/api/boards/" + onSymfony + suffix, body);
    Response b = send(BOARD, who, method, "/api/boards/" + onBoard + suffix, body);
    assertThat(b.statusCode())
        .as("%s %s : statut (Symfony %s)", method, suffix, s.asString())
        .isEqualTo(s.statusCode());
    assertThat(normalized(json(b), onBoard))
        .as("%s %s : corps", method, suffix)
        .isEqualTo(normalized(json(s), onSymfony));
  }

  @Test
  @DisplayName(
      "SC-BOARD-029 — GET /api/boards et GET /api/boards/{id} identiques (propriétaire, admin, membre)")
  void reads() {
    for (Account a : new Account[] {alice, bob, carla}) {
      same(a, "GET", "/api/boards", null);
      same(a, "GET", "/api/boards/" + shared, null);
    }
    same(alice, "GET", "/api/trash/boards", null);
  }

  @Test
  @DisplayName("SC-BOARD-021 / 006 (U8-a) — non-membre et inexistant : mêmes 404 des deux côtés")
  void nonMember() {
    same(outsider, "GET", "/api/boards/" + shared, null);
    same(outsider, "PATCH", "/api/boards/" + shared, "{\"title\":\"x\"}");
    same(outsider, "DELETE", "/api/boards/" + shared, null);
    same(outsider, "POST", "/api/boards/" + shared + "/favorite", null);
    same(alice, "GET", "/api/boards/999999", null);
    same(outsider, "POST", "/api/trash/boards/" + shared + "/restore", null);
  }

  @Test
  @DisplayName(
      "SC-BOARD-001 — création : contenu par défaut, withDefaults:false, fond ignoré, titre obligatoire")
  void creations() {
    for (String body : MIRROR_BODIES.subList(0, 3)) {
      Response s = send(SYMFONY, alice, "POST", "/api/boards", body);
      Response b = send(BOARD, alice, "POST", "/api/boards", body);
      assertThat(b.statusCode()).isEqualTo(s.statusCode());
      assertThat(normalized(json(b), json(b).get("id").asInt()))
          .as(body)
          .isEqualTo(normalized(json(s), json(s).get("id").asInt()));
    }
    same(alice, "POST", "/api/boards", MIRROR_BODIES.get(3));
    same(alice, "POST", "/api/boards", "pas du json");
  }

  @Test
  @DisplayName(
      "SC-BOARD-005, 007, 008 — modification, favori, mise à la corbeille (admin 403, propriétaire 204)")
  void boardWrites() {
    twin("{\"title\":\"Jumeau\"}", carla, "PATCH", "", MIRROR_BODIES.get(4));
    twin("{\"title\":\"Jumeau\"}", carla, "POST", "/favorite", null);
    twin("{\"title\":\"Jumeau\"}", bob, "DELETE", "", null);
    twin("{\"title\":\"Jumeau\"}", alice, "DELETE", "", null);
  }

  @Test
  @DisplayName(
      "SC-BOARD-009 à 016 — membres : ajout, invitation, rôle, retrait, départ, annulation")
  void memberWrites() {
    Account dora = CompatStack.register("Dora Miroir");
    twin(
        "{\"title\":\"Membres\"}",
        alice,
        "POST",
        "/members",
        MIRROR_BODIES.get(5).formatted(dora.email()));
    twin(
        "{\"title\":\"Membres\"}",
        bob,
        "POST",
        "/members",
        MIRROR_BODIES.get(6).formatted(CompatStack.uniqueEmail("nouveau")));
    twin(
        "{\"title\":\"Membres\"}",
        carla,
        "POST",
        "/members",
        MIRROR_BODIES.get(6).formatted(dora.email()));
    twin("{\"title\":\"Membres\"}", alice, "POST", "/members", MIRROR_BODIES.get(7));
    twin(
        "{\"title\":\"Membres\"}",
        alice,
        "POST",
        "/members",
        MIRROR_BODIES.get(6).formatted(bob.email()));
    twin("{\"title\":\"Membres\"}", bob, "PATCH", "/members/" + carla.id(), MIRROR_BODIES.get(8));
    twin("{\"title\":\"Membres\"}", bob, "PATCH", "/members/" + alice.id(), MIRROR_BODIES.get(9));
    twin("{\"title\":\"Membres\"}", bob, "PATCH", "/members/" + carla.id(), MIRROR_BODIES.get(9));
    twin("{\"title\":\"Membres\"}", bob, "DELETE", "/members/" + carla.id(), null);
    twin("{\"title\":\"Membres\"}", carla, "DELETE", "/members/" + carla.id(), null);
    twin("{\"title\":\"Membres\"}", bob, "DELETE", "/members/" + alice.id(), null);
    twin("{\"title\":\"Membres\"}", carla, "DELETE", "/members/" + bob.id(), null);
  }

  @Test
  @DisplayName(
      "SC-BOARD-012 — annulation d'une invitation du tableau (204) et par un simple membre (403)")
  void cancelInvitation() {
    for (Account who : new Account[] {alice, carla}) {
      int onSymfony = createOn(SYMFONY, "{\"title\":\"Invitations\"}");
      int onBoard = createOn(BOARD, "{\"title\":\"Invitations\"}");
      send(
          SYMFONY,
          alice,
          "POST",
          "/api/boards/" + onSymfony + "/members",
          "{\"email\":\"" + carla.email() + "\"}");
      send(
          SYMFONY,
          alice,
          "POST",
          "/api/boards/" + onBoard + "/members",
          "{\"email\":\"" + carla.email() + "\"}");
      String email = CompatStack.uniqueEmail("annule");
      send(
          SYMFONY,
          alice,
          "POST",
          "/api/boards/" + onSymfony + "/members",
          "{\"email\":\"" + email + "\"}");
      send(
          SYMFONY,
          alice,
          "POST",
          "/api/boards/" + onBoard + "/members",
          "{\"email\":\"" + email + "\"}");
      int invS =
          json(send(SYMFONY, alice, "GET", "/api/boards/" + onSymfony, null))
              .get("invitations")
              .get(0)
              .get("id")
              .asInt();
      int invB =
          json(send(SYMFONY, alice, "GET", "/api/boards/" + onBoard, null))
              .get("invitations")
              .get(0)
              .get("id")
              .asInt();
      Response s =
          send(SYMFONY, who, "DELETE", "/api/boards/" + onSymfony + "/invitations/" + invS, null);
      Response b =
          send(BOARD, who, "DELETE", "/api/boards/" + onBoard + "/invitations/" + invB, null);
      assertThat(b.statusCode()).isEqualTo(s.statusCode());
      assertThat(json(b)).isEqualTo(json(s));
    }
  }

  @Test
  @DisplayName(
      "SC-BOARD-034 / SC-TRASH-007 — corbeille : restauration admin 403, propriétaire 200, tableau ouvert 404")
  void trash() {
    for (Account who : new Account[] {bob, alice}) {
      int onSymfony = createOn(SYMFONY, "{\"title\":\"Corbeille\"}");
      int onBoard = createOn(BOARD, "{\"title\":\"Corbeille\"}");
      for (int id : new int[] {onSymfony, onBoard}) {
        send(
            SYMFONY,
            alice,
            "POST",
            "/api/boards/" + id + "/members",
            "{\"email\":\"" + bob.email() + "\",\"role\":\"admin\"}");
        send(SYMFONY, alice, "DELETE", "/api/boards/" + id, null);
      }
      Response s = send(SYMFONY, who, "POST", "/api/trash/boards/" + onSymfony + "/restore", null);
      Response b = send(BOARD, who, "POST", "/api/trash/boards/" + onBoard + "/restore", null);
      assertThat(b.statusCode()).isEqualTo(s.statusCode());
      assertThat(normalized(json(b), onBoard)).isEqualTo(normalized(json(s), onSymfony));
    }
    same(alice, "POST", "/api/trash/boards/" + shared + "/restore", null);
  }
}
