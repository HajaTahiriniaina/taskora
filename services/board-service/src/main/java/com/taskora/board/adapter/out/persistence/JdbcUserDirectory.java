package com.taskora.board.adapter.out.persistence;

import com.taskora.board.application.port.out.UserDirectoryPort;
import com.taskora.board.domain.model.UserId;
import com.taskora.board.domain.model.UserSummary;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Lecture de {@code taskora.user} (colonnes autorisées seulement, jamais {@code password}) ; les
 * comparaisons d'e-mail sont faites par MariaDB avec la collation de la colonne (FA6, FA10).
 */
public class JdbcUserDirectory implements UserDirectoryPort {
  private static final String COLUMNS = "SELECT id, email, name, avatar_color";

  private final JdbcClient jdbc;

  public JdbcUserDirectory(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<UserSummary> findById(UserId id) {
    return jdbc.sql(COLUMNS + " FROM `user` WHERE id = ?")
        .param(id.value())
        .query((rs, n) -> summary(rs))
        .optional();
  }

  @Override
  public Optional<UserSummary> findByEmail(String email) {
    return jdbc.sql(COLUMNS + " FROM `user` WHERE email = ?")
        .param(email)
        .query((rs, n) -> summary(rs))
        .optional();
  }

  /** Une seule requête : compte et égalité de l'e-mail selon la collation de la colonne. */
  @Override
  public Optional<MatchedUser> findByIdMatching(UserId id, String username) {
    return jdbc.sql(COLUMNS + ", email = ? AS matches FROM `user` WHERE id = ?")
        .param(username)
        .param(id.value())
        .query((rs, n) -> new MatchedUser(summary(rs), rs.getBoolean("matches")))
        .optional();
  }

  static UserSummary summary(ResultSet rs) throws SQLException {
    return new UserSummary(
        new UserId(rs.getInt("id")),
        SqlRows.text(rs, "name"),
        SqlRows.text(rs, "email"),
        SqlRows.text(rs, "avatar_color"));
  }
}
