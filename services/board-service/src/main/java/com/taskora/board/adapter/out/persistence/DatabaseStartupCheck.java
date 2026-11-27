package com.taskora.board.adapter.out.persistence;

import java.util.Arrays;
import java.util.Map;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Contrôles au démarrage (B-ADR-1 point 5) : {@code sql_mode} du serveur (non surchargé) contient
 * {@code STRICT_TRANS_TABLES} ; la base par défaut de la connexion est le schéma historique. Sinon
 * le service refuse de démarrer, avec un message sans donnée.
 */
public class DatabaseStartupCheck {
  private final JdbcClient jdbc;
  private final String legacySchema;

  public DatabaseStartupCheck(JdbcClient jdbc, String legacySchema) {
    this.jdbc = jdbc;
    this.legacySchema = legacySchema;
  }

  /** Lève une exception si la base ne convient pas. */
  public void verify() {
    Map<String, @Nullable Object> row =
        jdbc.sql("SELECT @@sql_mode AS sql_mode, DATABASE() AS db").query().singleRow();
    String sqlMode = String.valueOf(row.get("sql_mode"));
    if (Arrays.stream(sqlMode.split(",")).noneMatch("STRICT_TRANS_TABLES"::equals)) {
      throw new IllegalStateException(
          "sql_mode du serveur sans STRICT_TRANS_TABLES : démarrage refusé (B-ADR-1).");
    }
    if (!legacySchema.equals(row.get("db"))) {
      throw new IllegalStateException(
          "BOARD_DB_URL doit désigner le schéma historique LEGACY_SCHEMA comme base par défaut.");
    }
  }
}
