package com.taskora.board.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;

/** Lecture des colonnes {@code NOT NULL} : une valeur absente est une incohérence de schéma. */
public final class SqlRows {
  private SqlRows() {}

  /** Colonne texte {@code NOT NULL}. */
  public static String text(ResultSet rs, String column) throws SQLException {
    String value = rs.getString(column);
    if (value == null) {
      throw new IllegalStateException("colonne NOT NULL vide : " + column);
    }
    return value;
  }

  /** Colonne {@code DATETIME NOT NULL} (heure murale). */
  public static LocalDateTime wall(ResultSet rs, String column) throws SQLException {
    LocalDateTime value = rs.getObject(column, LocalDateTime.class);
    if (value == null) {
      throw new IllegalStateException("colonne NOT NULL vide : " + column);
    }
    return value;
  }
}
