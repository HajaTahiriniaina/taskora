package com.taskora.identity.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.mariadb.MariaDBContainer;

/**
 * MariaDB 11.8 partagée par les tests d'intégration (Testcontainers), configurée comme la base réelle
 * (utf8mb4_unicode_ci) : schémas identity et taskora, table taskora.user exactement comme en §8.3,
 * utilisateur identity avec les droits exacts d'identity-migration.md §2 (jamais DELETE sur
 * taskora.user). Aucun accès à la base de développement.
 */
public final class MariaDbContainerSupport {
  public static final String IDENTITY_USER = "identity";
  public static final String IDENTITY_PASSWORD = "test-" + UUID.randomUUID();
  private static final String ROOT_PASSWORD = "test-root-" + UUID.randomUUID();

  /** DDL réelle de taskora.user (SHOW CREATE TABLE sur MariaDB 11.8.9, sans données). */
  public static final String TASKORA_USER_DDL =
      """
      CREATE TABLE taskora.`user` (
        `id` int(11) NOT NULL AUTO_INCREMENT,
        `email` varchar(180) NOT NULL,
        `name` varchar(120) NOT NULL,
        `password` varchar(255) NOT NULL,
        `avatar_color` varchar(20) NOT NULL,
        `roles` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL CHECK (json_valid(`roles`)),
        `created_at` datetime NOT NULL,
        PRIMARY KEY (`id`),
        UNIQUE KEY `UNIQ_8D93D649E7927C74` (`email`)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
      """;

  private static MariaDBContainer container;

  private MariaDbContainerSupport() {}

  @SuppressWarnings("resource")
  public static synchronized MariaDBContainer container() {
    if (container == null) {
      container =
          new MariaDBContainer("mariadb:11.8")
              .withDatabaseName("identity")
              .withUsername("root")
              .withPassword(ROOT_PASSWORD)
              .withCommand(
                  "--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");
      container.start();
      initSchemas();
    }
    return container;
  }

  private static void initSchemas() {
    rootExecute(
        List.of(
            "CREATE DATABASE IF NOT EXISTS identity CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci",
            "CREATE DATABASE IF NOT EXISTS taskora CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci",
            TASKORA_USER_DDL,
            "CREATE USER IF NOT EXISTS 'identity'@'%' IDENTIFIED BY '" + IDENTITY_PASSWORD + "'",
            "GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON identity.* TO 'identity'@'%'",
            "GRANT SELECT, INSERT, UPDATE ON taskora.`user` TO 'identity'@'%'",
            "FLUSH PRIVILEGES"));
  }

  public static String jdbcUrl(String schema) {
    MariaDBContainer c = container();
    return "jdbc:mariadb://" + c.getHost() + ":" + c.getMappedPort(3306) + "/" + schema;
  }

  /** Connexion root (préparation et contrôle des données de test uniquement). */
  public static Connection rootConnection() throws SQLException {
    return DriverManager.getConnection(jdbcUrl("identity"), "root", ROOT_PASSWORD);
  }

  public static void rootExecute(List<String> statements) {
    try (Connection c = rootConnection();
        Statement s = c.createStatement()) {
      for (String sql : statements) {
        s.execute(sql);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("SQL de test en échec : " + e.getMessage(), e);
    }
  }

  /** Vide les tables entre deux tests (tolère les tables pas encore créées par Flyway). */
  public static void reset() {
    for (String sql :
        List.of(
            "DELETE FROM identity.outbox_message",
            "DELETE FROM identity.user_account",
            "ALTER TABLE identity.user_account AUTO_INCREMENT = 1",
            "DELETE FROM taskora.`user`",
            "ALTER TABLE taskora.`user` AUTO_INCREMENT = 1")) {
      try {
        rootExecute(List.of(sql));
      } catch (IllegalStateException ignored) {
        // table absente tant que les migrations Flyway n'existent pas (BackCodeur)
      }
    }
  }

  /** Propriétés Spring pour une application connectée avec le compte identity. */
  public static void register(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> jdbcUrl("identity"));
    registry.add("spring.datasource.username", () -> IDENTITY_USER);
    registry.add("spring.datasource.password", () -> IDENTITY_PASSWORD);
    registry.add("identity.legacy.schema", () -> "taskora");
    registry.add("identity.legacy.zone", () -> "Europe/Paris");
  }
}
