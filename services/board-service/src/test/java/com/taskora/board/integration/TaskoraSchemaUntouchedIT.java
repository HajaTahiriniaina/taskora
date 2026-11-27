package com.taskora.board.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskora.board.support.ApiTestBase;
import com.taskora.board.support.BoardDatabase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;

/**
 * R1, R10, B-ADR-1 point 4 — le démarrage de board-service (Flyway compris) ne modifie pas la
 * structure de {@code taskora} (empreinte information_schema prise à la création du conteneur,
 * avant tout démarrage) ; Flyway ne crée que des objets du schéma {@code board} (V1 outbox, V2
 * verrou), sans DDL sur taskora.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class TaskoraSchemaUntouchedIT extends ApiTestBase {

  @Test
  @DisplayName(
      "R1 — empreinte de taskora (tables, colonnes, index, clés, déclencheurs) identique avant et après démarrage")
  void taskoraUnchanged() {
    assertThat(BoardDatabase.taskoraSchemaFingerprint())
        .isEqualTo(BoardDatabase.initialTaskoraFingerprint());
    assertThat(
            BoardDatabase.count(
                "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = 'taskora'"))
        .as("19 tables Doctrine + doctrine_migration_versions")
        .isEqualTo(20);
    assertThat(
            BoardDatabase.count(
                "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = 'taskora'"
                    + " AND TABLE_NAME = 'internal_event_receipt'"))
        .as("internal_event_receipt (migration Version20261128000100) fait partie de taskora")
        .isEqualTo(1);
  }

  @Test
  @DisplayName(
      "B-ADR-1 — Flyway ne crée que board.* : historique, outbox_message (V1) et scheduler_lock (V2)")
  void flywayOnlyInBoardSchema() {
    List<Object> tables =
        BoardDatabase.query(
                "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = 'board'"
                    + " AND TABLE_NAME NOT LIKE 'it\\_%' ORDER BY TABLE_NAME")
            .stream()
            .map(r -> r.get("TABLE_NAME"))
            .toList();
    assertThat(tables).containsExactly("flyway_schema_history", "outbox_message", "scheduler_lock");
    List<Map<String, Object>> history =
        BoardDatabase.query(
            "SELECT version, success FROM board.flyway_schema_history ORDER BY installed_rank");
    assertThat(history).extracting(r -> String.valueOf(r.get("version"))).containsExactly("1", "2");
  }

  @Test
  @DisplayName("board-migration.md §2 — colonnes et index de board.outbox_message conformes à V1")
  void outboxSchema() {
    assertThat(
            BoardDatabase.query(
                    "SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = 'board'"
                        + " AND TABLE_NAME = 'outbox_message' ORDER BY ORDINAL_POSITION")
                .stream()
                .map(r -> r.get("COLUMN_NAME"))
                .toList())
        .containsExactly(
            "id",
            "event_id",
            "type",
            "destination",
            "aggregate_id",
            "payload",
            "occurred_at",
            "available_at",
            "status",
            "attempts",
            "last_error",
            "delivered_at");
    assertThat(
            BoardDatabase.query(
                    "SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = 'board'"
                        + " AND TABLE_NAME = 'outbox_message'")
                .stream()
                .map(r -> r.get("INDEX_NAME"))
                .toList())
        .containsExactlyInAnyOrder("PRIMARY", "uq_outbox_event_id", "ix_outbox_due");
  }
}
