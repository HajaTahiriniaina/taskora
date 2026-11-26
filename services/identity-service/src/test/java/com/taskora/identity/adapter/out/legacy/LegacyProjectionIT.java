package com.taskora.identity.adapter.out.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskora.identity.support.ApiTestBase;
import com.taskora.identity.support.MariaDbContainerSupport;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** U1-B / A9 : projection dans taskora.user, même transaction, jamais de DELETE. */
class LegacyProjectionIT extends ApiTestBase {
  private static final Instant SUMMER = Instant.parse("2026-07-01T10:00:00Z"); // 12:00 à Paris (UTC+2)

  @AfterEach
  void stopGeneralLog() {
    MariaDbContainerSupport.rootExecute(List.of("SET GLOBAL general_log = 0"));
  }

  /** Le pilote renvoie DATETIME en java.sql.Timestamp sur la connexion root. */
  private static LocalDateTime localDateTime(Object value) {
    return value instanceof java.sql.Timestamp t ? t.toLocalDateTime() : (LocalDateTime) value;
  }

  private static Map<String, Object> identityRow(String email) {
    return query("SELECT * FROM identity.user_account WHERE email = ?", email).getFirst();
  }

  private static Map<String, Object> legacyRow(String email) {
    return query("SELECT * FROM taskora.`user` WHERE email = ?", email).getFirst();
  }

  @Test
  @DisplayName("SC-IDENT-001 U1-B — inscription : ligne taskora.user identique (id explicite, hash, roles [], created_at heure de Paris)")
  void registrationProjected() throws Exception {
    CLOCK.set(SUMMER);
    registeredToken("Camille", "camille@exemple.fr", "motdepasse");
    Map<String, Object> id = identityRow("camille@exemple.fr");
    Map<String, Object> legacy = legacyRow("camille@exemple.fr");
    assertThat(legacy.get("id")).isEqualTo(id.get("id"));
    assertThat(legacy.get("name")).isEqualTo("Camille");
    assertThat(legacy.get("password")).isEqualTo(id.get("password_hash"));
    assertThat(legacy.get("avatar_color")).isEqualTo(id.get("avatar_color"));
    assertThat(legacy.get("roles")).isEqualTo("[]");
    assertThat(localDateTime(legacy.get("created_at"))).isEqualTo(LocalDateTime.of(2026, 7, 1, 12, 0, 0));
    assertThat(localDateTime(id.get("created_at"))).isEqualTo(LocalDateTime.of(2026, 7, 1, 12, 0, 0));
  }

  @Test
  @DisplayName("SC-IDENT-001 U1-B — l'id identity suit les ids déjà présents dans taskora.user (pas de ré-identification)")
  void idsDoNotCollide() throws Exception {
    MariaDbContainerSupport.rootExecute(List.of(
        "INSERT INTO taskora.`user` (id, email, name, password, avatar_color, roles, created_at) "
            + "VALUES (1, 'ancien@exemple.test', 'Ancien', '$2y$04$x', '#6366f1', '[]', '2026-01-01 00:00:00')",
        "INSERT INTO identity.user_account (id, email, name, password_hash, avatar_color, roles, created_at) "
            + "VALUES (1, 'ancien@exemple.test', 'Ancien', '$2y$04$x', '#6366f1', '[]', '2026-01-01 00:00:00')"));
    registeredToken("Camille", "camille@exemple.fr", "motdepasse");
    assertThat(legacyRow("ancien@exemple.test").get("name")).isEqualTo("Ancien");
    assertThat(legacyRow("camille@exemple.fr").get("id")).isEqualTo(identityRow("camille@exemple.fr").get("id"));
    assertThat(((Number) identityRow("camille@exemple.fr").get("id")).intValue()).isEqualTo(2);
  }

  @Test
  @DisplayName("SC-IDENT-015/017 U1-B — profil et mot de passe projetés ; created_at jamais modifié")
  void profileProjected() throws Exception {
    CLOCK.set(SUMMER);
    String token = registeredToken("Camille", "camille@exemple.fr", "ancienmdp1");
    CLOCK.set(Instant.parse("2026-07-05T10:00:00Z")); // jeton encore valide (7 jours), autre jour
    assertThat(patchMe(token, "{\"name\":\"Camille M.\",\"avatarColor\":\"#0EA5E9\",\"currentPassword\":\"ancienmdp1\",\"newPassword\":\"nouveaumdp1\"}")
            .getResponse().getStatus()).isEqualTo(200);
    Map<String, Object> legacy = legacyRow("camille@exemple.fr");
    assertThat(legacy.get("name")).isEqualTo("Camille M.");
    assertThat(legacy.get("avatar_color")).isEqualTo("#0EA5E9");
    assertThat(legacy.get("password")).isEqualTo(identityRow("camille@exemple.fr").get("password_hash"));
    assertThat(localDateTime(legacy.get("created_at"))).isEqualTo(LocalDateTime.of(2026, 7, 1, 12, 0, 0));
  }

  @Test
  @DisplayName("SC-IDENT-002 A7 — échec forcé de la projection (e-mail déjà dans taskora.user) → 409, rien dans identity")
  void projectionFailureRollsBack() throws Exception {
    MariaDbContainerSupport.rootExecute(List.of(
        "INSERT INTO taskora.`user` (id, email, name, password, avatar_color, roles, created_at) "
            + "VALUES (50, 'camille@exemple.fr', 'Symfony', '$2y$04$x', '#6366f1', '[]', '2026-01-01 00:00:00')"));
    assertError(register("Camille", "camille@exemple.fr", "motdepasse"), 409,
        "Un compte existe déjà avec cette adresse e-mail.");
    assertThat(count("SELECT COUNT(*) FROM identity.user_account")).isZero();
    assertThat(count("SELECT COUNT(*) FROM identity.outbox_message")).isZero();
    assertThat(legacyRow("camille@exemple.fr").get("name")).isEqualTo("Symfony");
    assertThat(count("SELECT COUNT(*) FROM taskora.`user`")).isEqualTo(1);
  }

  @Test
  @DisplayName("A9 — aucune requête DELETE sur taskora.user (journal SQL) et le compte identity n'a pas le droit DELETE")
  void neverDeletes() throws Exception {
    MariaDbContainerSupport.rootExecute(List.of(
        "TRUNCATE TABLE mysql.general_log", "SET GLOBAL log_output = 'TABLE'", "SET GLOBAL general_log = 1"));
    String token = registeredToken("Camille", "camille@exemple.fr", "ancienmdp1");
    patchMe(token, "{\"name\":\"Camille M.\",\"currentPassword\":\"ancienmdp1\",\"newPassword\":\"nouveaumdp1\"}");
    register("Camille", "camille@exemple.fr", "motdepasse"); // 409
    patchMe(token, "{\"currentPassword\":\"faux\",\"newPassword\":\"nouveaumdp1\"}"); // 400
    MariaDbContainerSupport.rootExecute(List.of("SET GLOBAL general_log = 0"));
    long deletes = count("SELECT COUNT(*) FROM mysql.general_log WHERE user_host LIKE 'identity%' "
        + "AND LOWER(CONVERT(argument USING utf8mb4)) REGEXP 'delete[[:space:]].*user'");
    assertThat(deletes).isZero();
    assertThat(count("SELECT COUNT(*) FROM information_schema.table_privileges WHERE grantee LIKE '%identity%' "
        + "AND table_schema = 'taskora' AND privilege_type = 'DELETE'")).isZero();
  }
}
