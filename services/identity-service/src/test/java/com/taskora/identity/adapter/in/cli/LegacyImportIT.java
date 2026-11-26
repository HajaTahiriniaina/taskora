package com.taskora.identity.adapter.in.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskora.identity.application.port.in.ImportLegacyUsersUseCase;
import com.taskora.identity.application.port.in.ImportReport;
import com.taskora.identity.application.port.in.VerificationReport;
import com.taskora.identity.support.ApiTestBase;
import com.taskora.identity.support.MariaDbContainerSupport;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/** SC-IDENT-023 (A14) : reprise idempotente du jeu d'essai fixtures/legacy-users.sql. */
class LegacyImportIT extends ApiTestBase {
  @Autowired ImportLegacyUsersUseCase importer;

  private static List<JsonNode> accounts;

  @BeforeEach
  void loadFixture() throws IOException {
    List<String> inserts = new ArrayList<>();
    try (InputStream in = LegacyImportIT.class.getResourceAsStream("/fixtures/legacy-users.sql")) {
      new String(in.readAllBytes(), StandardCharsets.UTF_8)
          .lines()
          .filter(l -> l.startsWith("INSERT"))
          .forEach(l -> inserts.add(l.replace("INSERT INTO `user`", "INSERT INTO taskora.`user`")));
    }
    MariaDbContainerSupport.rootExecute(inserts);
    try (InputStream in = LegacyImportIT.class.getResourceAsStream("/fixtures/legacy-users.json")) {
      accounts = new ArrayList<>();
      JSON.readTree(in).forEach(accounts::add);
    }
  }

  private static String fingerprint() {
    return String.valueOf(
        query(
                "SELECT COUNT(*) AS n, SUM(CRC32(CONCAT_WS('|', id, email, name, password_hash, avatar_color, roles, created_at))) AS s "
                    + "FROM identity.user_account")
            .getFirst());
  }

  @Test
  @DisplayName(
      "SC-IDENT-023 — run : chaque compte du jeu d'essai se connecte via l'API (100 %), le hash corrompu → 401")
  void everyAccountLogsIn() throws Exception {
    ImportReport report = importer.importAll();
    assertThat(report.sourceRows()).isEqualTo(accounts.size());
    assertThat(report.inserted()).isEqualTo(accounts.size());
    for (JsonNode a : accounts) {
      MvcResult r = login(a.get("email").asString(), a.get("password").asString());
      int expected = a.get("expectLogin").asBoolean() ? 200 : 401;
      assertThat(r.getResponse().getStatus())
          .as("%s (%s)", a.get("label").asString(), body(r))
          .isEqualTo(expected);
      if (expected == 200) {
        assertThat(json(r).get("user").get("id").asInt()).isEqualTo(a.get("id").asInt());
      }
    }
    assertThat(accounts.stream().filter(a -> a.get("expectLogin").asBoolean()).count())
        .isGreaterThanOrEqualTo(8);
  }

  @Test
  @DisplayName("SC-IDENT-023 — hashes copiés tels quels, created_at sans conversion")
  void copiedAsIs() {
    importer.importAll();
    List<Map<String, Object>> diff =
        query(
            "SELECT u.id FROM taskora.`user` u JOIN identity.user_account a ON a.id = u.id "
                + "WHERE BINARY a.password_hash <> BINARY u.password OR a.created_at <> u.created_at OR a.name <> u.name");
    assertThat(diff).isEmpty();
  }

  @Test
  @DisplayName("SC-IDENT-023 — 2e run : aucune modification (idempotent)")
  void secondRunChangesNothing() {
    importer.importAll();
    String before = fingerprint();
    ImportReport second = importer.importAll();
    assertThat(second.inserted()).isZero();
    assertThat(second.updated()).isZero();
    assertThat(second.unchanged()).isEqualTo(accounts.size());
    assertThat(fingerprint()).isEqualTo(before);
  }

  @Test
  @DisplayName(
      "SC-IDENT-023 — verify vert après run ; AUTO_INCREMENT = MAX(id) + 1 ; inscription suivante sans collision")
  void verifyAndAutoIncrement() throws Exception {
    importer.importAll();
    VerificationReport v = importer.verify();
    assertThat(v.consistent()).as("contrôles en échec : %s", v.failedChecks()).isTrue();
    assertThat(v.metrics().values()).noneMatch(m -> m.contains("@"));
    long maxId = count("SELECT MAX(id) FROM taskora.`user`");
    long next =
        count(
            "SELECT AUTO_INCREMENT FROM information_schema.tables WHERE table_schema='identity' AND table_name='user_account'");
    assertThat(next).isEqualTo(maxId + 1);
    MvcResult r = register("Nouveau", "nouveau@exemple.test", "motdepasse");
    assertThat(r.getResponse().getStatus()).isEqualTo(201);
    assertThat(json(r).get("user").get("id").asLong()).isEqualTo(maxId + 1);
  }

  @Test
  @DisplayName("SC-IDENT-023 — verify rouge si la cible diverge (ligne modifiée à la main)")
  void verifyDetectsDivergence() {
    importer.importAll();
    MariaDbContainerSupport.rootExecute(
        List.of("UPDATE identity.user_account SET name = 'Modifié' WHERE id = 1"));
    VerificationReport v = importer.verify();
    assertThat(v.consistent()).isFalse();
    assertThat(v.failedChecks()).isNotEmpty();
  }

  @Test
  @DisplayName(
      "SC-IDENT-023 — compte présent seulement dans la cible : signalé et conservé (jamais de DELETE)")
  void targetOnlyKept() {
    MariaDbContainerSupport.rootExecute(
        List.of(
            "INSERT INTO identity.user_account (id, email, name, password_hash, avatar_color, roles, created_at) "
                + "VALUES (99, 'cible-seule@exemple.test', 'Cible', '$2y$04$x', '#6366f1', '[]', '2026-01-01 00:00:00')"));
    ImportReport report = importer.importAll();
    assertThat(report.targetOnly()).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM identity.user_account WHERE id = 99")).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "SC-IDENT-023 — doublon d'e-mail normalisé dans la source → échec avant toute écriture")
  void duplicateAborts() {
    MariaDbContainerSupport.rootExecute(
        List.of(
            "INSERT INTO taskora.`user` (id, email, name, password, avatar_color, roles, created_at) "
                + "VALUES (50, ' Reprise-2y-13@exemple.test', 'Doublon', '$2y$04$x', '#6366f1', '[]', '2026-01-01 00:00:00')"));
    try {
      importer.importAll();
    } catch (UnsupportedOperationException e) {
      throw e;
    } catch (RuntimeException expected) {
      // échec attendu
    }
    assertThat(count("SELECT COUNT(*) FROM identity.user_account")).isZero();
  }
}
