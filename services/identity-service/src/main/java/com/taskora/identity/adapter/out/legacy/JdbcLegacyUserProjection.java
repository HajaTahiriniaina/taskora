package com.taskora.identity.adapter.out.legacy;

import com.taskora.identity.application.port.out.LegacyUserProjectionPort;
import com.taskora.identity.domain.exception.EmailAlreadyUsedException;
import com.taskora.identity.domain.model.User;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Projection dans taskora.user (U1-B, A9), dans la transaction de l'écriture identity (même
 * DataSource). Id explicite, roles « [] », created_at jamais modifié, jamais de DELETE.
 *
 * <p>UPDATE par id puis, si la ligne n'existe pas, INSERT avec l'id explicite : un « INSERT … ON
 * DUPLICATE KEY UPDATE » écraserait une autre ligne qui aurait le même e-mail (index unique) au
 * lieu d'échouer. Une violation d'unicité sur l'e-mail → EmailAlreadyUsedException (409, rollback,
 * A7).
 */
public class JdbcLegacyUserProjection implements LegacyUserProjectionPort {
  private final JdbcClient jdbc;
  private final String updateSql;
  private final String insertSql;

  public JdbcLegacyUserProjection(JdbcClient jdbc, LegacySchema schema) {
    this.jdbc = jdbc;
    this.updateSql =
        "UPDATE "
            + schema.userTable()
            + " SET email = :email, name = :name, password = :password,"
            + " avatar_color = :avatarColor, roles = :roles WHERE id = :id";
    this.insertSql =
        "INSERT INTO "
            + schema.userTable()
            + " (id, email, name, password, avatar_color, roles, created_at)"
            + " VALUES (:id, :email, :name, :password, :avatarColor, :roles, :createdAt)";
  }

  @Override
  public void upsert(User user) {
    try {
      // MariaDB Connector/J renvoie les lignes trouvées (useAffectedRows=false par défaut).
      int found = params(jdbc.sql(updateSql), user).update();
      if (found == 0) {
        params(jdbc.sql(insertSql), user).param("createdAt", user.createdAt()).update();
      }
    } catch (DuplicateKeyException e) {
      throw new EmailAlreadyUsedException();
    }
  }

  private static JdbcClient.StatementSpec params(JdbcClient.StatementSpec spec, User user) {
    return spec.param("id", user.id().value())
        .param("email", user.email().value())
        .param("name", user.name().value())
        .param("password", user.password().value())
        .param("avatarColor", user.avatarColor().value())
        .param("roles", rolesJson(user));
  }

  /** Les rôles stockés (toujours [] aujourd'hui), au format json_encode de PHP. */
  private static String rolesJson(User user) {
    StringBuilder json = new StringBuilder("[");
    for (String role : user.roles().stored()) {
      if (json.length() > 1) {
        json.append(',');
      }
      json.append('"').append(role.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
    }
    return json.append(']').toString();
  }
}
