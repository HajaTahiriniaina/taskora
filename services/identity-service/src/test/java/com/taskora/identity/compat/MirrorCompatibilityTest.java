package com.taskora.identity.compat;

import static org.assertj.core.api.Assertions.assertThat;

import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests miroir : la même requête est envoyée à Symfony et à identity-service, on compare le statut
 * et le JSON analysé (après neutralisation des valeurs propres à chaque compte : jeton, id, e-mail
 * unique, couleur tirée au hasard). Périmètre : SC-IDENT-001–003, 007, 008, 014–018, HORS
 * divergences voulues E15–E21 (docs/specs/decisions.md), listées dans INTENTIONAL_DIVERGENCES.
 */
@Tag("compat")
class MirrorCompatibilityTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /**
   * Divergences voulues : AUCUNE requête de ces catégories n'est envoyée au miroir. Toute entrée
   * doit porter une référence E15–E21 (décisions de l'utilisateur du 26/11/2026).
   */
  static final Map<String, String> INTENTIONAL_DIVERGENCES =
      Map.ofEntries(
          // E15 : mot de passe > 4096 octets (inscription, newPassword) → 400 au lieu de 500
          Map.entry("E15-password-over-4096-bytes", "E15"),
          // E16 : corps JSON valide mais non objet ([] , "x", 42, true) → 400 « JSON invalide. »
          Map.entry("E16-non-object-json-body", "E16"),
          // E17 : champ présent non chaîne → 400 « Le champ "x" doit être une chaîne de caractères.
          // »
          Map.entry("E17-non-string-field", "E17"),
          // E18 : avatarColor invalide → 400 « Couleur d'avatar invalide. » au lieu d'être ignorée
          Map.entry("E18-invalid-avatar-color", "E18"),
          // E19 : tous les 400 du login (messages français, Content-Type non contrôlé) —
          // SC-IDENT-009 n'est plus miroir
          Map.entry("E19-login-400-and-content-type", "E19"),
          // E20 : corps vide → 400 « JSON invalide. » sur register, login, PATCH /me
          Map.entry("E20-empty-body", "E20"),
          // E21 : newPassword "0" contrôlé comme un vrai mot de passe
          Map.entry("E21-new-password-zero", "E21"));

  /**
   * Un cas miroir : préparation éventuelle d'un compte, puis une requête identique des deux côtés.
   */
  record Case(String id, String method, String path, String body, boolean needsAccount) {}

  static final List<Case> MIRROR =
      List.of(
          new Case(
              "SC-IDENT-001 inscription nominale",
              "POST",
              "/api/auth/register",
              "{\"name\":\"  Compat Martin \",\"email\":\" ${EMAIL_UPPER} \",\"password\":\"motdepasse\"}",
              false),
          new Case(
              "SC-IDENT-002 e-mail déjà utilisé",
              "POST",
              "/api/auth/register",
              "{\"name\":\"Autre\",\"email\":\"${EMAIL_UPPER}\",\"password\":\"motdepasse\"}",
              true),
          new Case(
              "SC-IDENT-003 name vide",
              "POST",
              "/api/auth/register",
              "{\"name\":\"\",\"email\":\"${EMAIL}\",\"password\":\"motdepasse\"}",
              false),
          new Case(
              "SC-IDENT-003 name d'espaces",
              "POST",
              "/api/auth/register",
              "{\"name\":\"   \",\"email\":\"${EMAIL}\",\"password\":\"motdepasse\"}",
              false),
          new Case(
              "SC-IDENT-003 email absent",
              "POST",
              "/api/auth/register",
              "{\"name\":\"Compat\",\"password\":\"motdepasse\"}",
              false),
          new Case(
              "SC-IDENT-003 email invalide",
              "POST",
              "/api/auth/register",
              "{\"name\":\"Compat\",\"email\":\"pas-un-email\",\"password\":\"motdepasse\"}",
              false),
          new Case(
              "SC-IDENT-003 mot de passe de 7",
              "POST",
              "/api/auth/register",
              "{\"name\":\"Compat\",\"email\":\"${EMAIL}\",\"password\":\"1234567\"}",
              false),
          new Case(
              "SC-IDENT-003 ééééééé",
              "POST",
              "/api/auth/register",
              "{\"name\":\"Compat\",\"email\":\"${EMAIL}\",\"password\":\"ééééééé\"}",
              false),
          new Case("SC-IDENT-003 corps non JSON", "POST", "/api/auth/register", "texte", false),
          new Case(
              "SC-IDENT-007 connexion nominale",
              "POST",
              "/api/auth/login",
              "{\"email\":\"${EMAIL}\",\"password\":\"motdepasse\"}",
              true),
          new Case(
              "SC-IDENT-007 autre casse",
              "POST",
              "/api/auth/login",
              "{\"email\":\"${EMAIL_UPPER}\",\"password\":\"motdepasse\"}",
              true),
          new Case(
              "SC-IDENT-007 espace final",
              "POST",
              "/api/auth/login",
              "{\"email\":\"${EMAIL}  \",\"password\":\"motdepasse\"}",
              true),
          new Case(
              "SC-IDENT-007 espace initial",
              "POST",
              "/api/auth/login",
              "{\"email\":\" ${EMAIL}\",\"password\":\"motdepasse\"}",
              true),
          new Case(
              "SC-IDENT-008 mauvais mot de passe",
              "POST",
              "/api/auth/login",
              "{\"email\":\"${EMAIL}\",\"password\":\"mauvaismdp\"}",
              true),
          new Case(
              "SC-IDENT-008 e-mail inconnu",
              "POST",
              "/api/auth/login",
              "{\"email\":\"inconnu-${EMAIL}\",\"password\":\"motdepasse\"}",
              false),
          new Case("SC-IDENT-014 lire son profil", "GET", "/api/me", null, true),
          new Case(
              "SC-IDENT-015 renommer", "PATCH", "/api/me", "{\"name\":\"  Compat M. \"}", true),
          new Case("SC-IDENT-015 nom vide", "PATCH", "/api/me", "{\"name\":\"\"}", true),
          new Case("SC-IDENT-015 nom null", "PATCH", "/api/me", "{\"name\":null}", true),
          new Case(
              "SC-IDENT-015 nom de 150",
              "PATCH",
              "/api/me",
              "{\"name\":\"" + "n".repeat(150) + "\"}",
              true),
          new Case(
              "SC-IDENT-016 couleur valide",
              "PATCH",
              "/api/me",
              "{\"avatarColor\":\"#0EA5E9\"}",
              true),
          new Case(
              "SC-IDENT-017 changer de mot de passe",
              "PATCH",
              "/api/me",
              "{\"currentPassword\":\"motdepasse\",\"newPassword\":\"nouveaumdp1\"}",
              true),
          new Case(
              "SC-IDENT-018 mot de passe actuel faux",
              "PATCH",
              "/api/me",
              "{\"currentPassword\":\"faux\",\"newPassword\":\"nouveaumdp1\"}",
              true),
          new Case(
              "SC-IDENT-018 nouveau de 7",
              "PATCH",
              "/api/me",
              "{\"currentPassword\":\"motdepasse\",\"newPassword\":\"1234567\"}",
              true),
          new Case(
              "SC-IDENT-018 newPassword vide", "PATCH", "/api/me", "{\"newPassword\":\"\"}", true),
          new Case(
              "SC-IDENT-018 currentPassword absent",
              "PATCH",
              "/api/me",
              "{\"newPassword\":\"nouveaumdp1\"}",
              true),
          new Case(
              "SC-IDENT-020 e-mail non modifiable",
              "PATCH",
              "/api/me",
              "{\"email\":\"autre@exemple.test\"}",
              true));

  @BeforeAll
  static void identityMustBeUp() {
    CompatStack.requireIdentityUp();
  }

  static Stream<Arguments> cases() {
    return MIRROR.stream().map(c -> Arguments.of(c.id(), c));
  }

  private static Response run(String base, Case c, String email) {
    String token = null;
    if (c.needsAccount()) {
      token = CompatStack.register(base, email, "motdepasse");
    }
    String body =
        c.body() == null
            ? null
            : c.body()
                .replace("${EMAIL_UPPER}", email.toUpperCase(java.util.Locale.ROOT))
                .replace("${EMAIL}", email);
    BiFunction<String, String, Response> send =
        switch (c.method()) {
          case "GET" -> (b, t) -> CompatStack.get(base, c.path(), t);
          case "PATCH" -> (b, t) -> CompatStack.patch(base, c.path(), b, t);
          default -> (b, t) -> CompatStack.post(base, c.path(), b);
        };
    return send.apply(body, token);
  }

  /** Neutralise les valeurs propres à chaque backend. */
  private static JsonNode normalize(String raw, String email) {
    JsonNode n = JSON.readTree(raw.isBlank() ? "null" : raw);
    if (n.isObject()) {
      ObjectNode o = (ObjectNode) n;
      if (o.has("token")) {
        assertThat(o.get("token").asString()).matches("[\\w-]+\\.[\\w-]+\\.[\\w-]+");
        o.put("token", "<jwt>");
      }
      ObjectNode user =
          o.has("user") ? (ObjectNode) o.get("user") : (o.has("avatarColor") ? o : null);
      if (user != null) {
        user.put("id", 0);
        if (user.get("email").asString().equalsIgnoreCase(email)) {
          user.put("email", "<email>");
        }
        String color = user.get("avatarColor").asString();
        if (color.matches("#[0-9a-f]{6}") && !"#0ea5e9".equals(color)) {
          user.put("avatarColor", "<palette>");
        }
      }
    }
    return n;
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  @DisplayName("SC-IDENT-001–003, 007, 008, 014–018, 020 — même statut et même JSON que Symfony")
  void sameAsSymfony(String id, Case c) {
    String idEmail = CompatStack.uniqueEmail();
    Response identity =
        run(
            CompatStack.IDENTITY,
            c,
            idEmail); // identity d'abord : rien n'est écrit chez Symfony si identity échoue
    String sfEmail = CompatStack.uniqueEmail();
    Response symfony = run(CompatStack.SYMFONY, c, sfEmail);
    assertThat(identity.statusCode())
        .as("%s statut (Symfony : %s)", id, symfony.asString())
        .isEqualTo(symfony.statusCode());
    assertThat(normalize(identity.asString(), idEmail))
        .as(id)
        .isEqualTo(normalize(symfony.asString(), sfEmail));
    if (symfony.statusCode() == 401) {
      assertThat(identity.header("WWW-Authenticate")).isEqualTo(symfony.header("WWW-Authenticate"));
    }
  }
}
