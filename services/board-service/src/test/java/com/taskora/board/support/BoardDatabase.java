package com.taskora.board.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.mariadb.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * MariaDB des tests d'intégration (réponse B4 de Manuel, docs/ci/requests/backend.md §B6) : même
 * image et même digest que compose, schéma {@code taskora} généré depuis les migrations Doctrine
 * réelles ({@code schema/taskora-schema.sql}, {@code 05-…}), puis le VRAI script d'init livré
 * ({@code docker/mariadb/initdb.d/10-taskora-init.sh}, {@code 10-…}) qui crée le compte {@code
 * board} avec exactement les droits de board-migration.md §3. Mots de passe aléatoires, jamais
 * affichés. Aucun accès à la base de travail ni à une stack compose.
 *
 * <p>Le compte root ne sert qu'à préparer et contrôler les données de test ; board-service se
 * connecte avec le compte {@code board}, qui n'a pas le droit {@code DROP} : aucun test ne compte
 * sur un {@code DROP} ou un {@code clean} Flyway sous ce compte.
 */
public final class BoardDatabase {
  /**
   * Image de compose ({@code mariadb:11.8@sha256:e1d5…}) : le digest seul est passé à
   * Testcontainers (son analyseur refuse « étiquette + digest ») ; c'est la même image.
   */
  public static final String IMAGE =
      "mariadb@sha256:e1d5495f445654134f226f55a41ce0da227116f6f27f283101f405c794d841fc";

  /** Mode 0755 (rwxr-xr-x) du script d'init copié, écrit en décimal. */
  private static final int INIT_SCRIPT_MODE = 493;

  public static final String BOARD_USER = "board";
  public static final String BOARD_PASSWORD = randomSecret();
  private static final String ROOT_PASSWORD = randomSecret();
  private static final String IDENTITY_PASSWORD = randomSecret();
  private static final String APP_PASSWORD = randomSecret();

  /** Tables du schéma taskora vidées entre deux tests (ordre indifférent : FK désactivées). */
  public static final List<String> TASKORA_TABLES =
      List.of(
          "activity",
          "attachment",
          "board",
          "board_list",
          "board_member",
          "card",
          "card_assignee",
          "card_label",
          "checklist_item",
          "comment",
          "custom_field",
          "custom_field_value",
          "invitation",
          "label",
          "notification",
          "push_subscription",
          "trello_import_file",
          "user");

  private static MariaDBContainer container;
  private static HikariDataSource rootPool;
  private static String initialTaskoraFingerprint;

  private BoardDatabase() {}

  private static String randomSecret() {
    byte[] b = new byte[24];
    new SecureRandom().nextBytes(b);
    return "t" + HexFormat.of().formatHex(b);
  }

  @SuppressWarnings("resource")
  public static synchronized MariaDBContainer container() {
    if (container == null) {
      container =
          new MariaDBContainer(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("mariadb"))
              .withDatabaseName("taskora")
              .withUsername("root")
              .withPassword(ROOT_PASSWORD)
              .withEnv("MARIADB_DATABASE", "taskora")
              .withEnv("MARIADB_ROOT_PASSWORD", ROOT_PASSWORD)
              // Compte applicatif de Symfony : le script d'init lui accorde la base taskora_test
              // (GRANT … TO 'taskora') et échoue (1133) s'il n'existe pas.
              .withEnv("MARIADB_USER", "taskora")
              .withEnv("MARIADB_PASSWORD", APP_PASSWORD)
              .withEnv("IDENTITY_DB_PASSWORD", IDENTITY_PASSWORD)
              .withEnv("BOARD_DB_PASSWORD", BOARD_PASSWORD)
              .withEnv("BOARD_GRANT_COMMENT_FILES", "off")
              .withCommand(
                  "--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci")
              .withCopyFileToContainer(
                  MountableFile.forClasspathResource("schema/taskora-schema.sql"),
                  "/docker-entrypoint-initdb.d/05-taskora-schema.sql")
              .withCopyFileToContainer(
                  MountableFile.forHostPath(
                      "../../docker/mariadb/initdb.d/10-taskora-init.sh", INIT_SCRIPT_MODE),
                  "/docker-entrypoint-initdb.d/10-taskora-init.sh");
      container.start();
      initialTaskoraFingerprint = taskoraSchemaFingerprint();
    }
    return container;
  }

  public static String jdbcUrl(String schema) {
    MariaDBContainer c = container();
    return "jdbc:mariadb://" + c.getHost() + ":" + c.getMappedPort(3306) + "/" + schema;
  }

  /** Propriétés Spring : board-service connecté avec le compte board, base par défaut taskora. */
  public static void register(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> jdbcUrl("taskora"));
    registry.add("spring.datasource.username", () -> BOARD_USER);
    registry.add("spring.datasource.password", () -> BOARD_PASSWORD);
  }

  /** Connexion directe sous le compte board (tests de droits et de concurrence). */
  public static Connection boardConnection() throws SQLException {
    return DriverManager.getConnection(jdbcUrl("taskora"), BOARD_USER, BOARD_PASSWORD);
  }

  /** Connexion root (préparation et contrôle des données de test uniquement). */
  public static Connection rootConnection() throws SQLException {
    return rootDataSource().getConnection();
  }

  private static synchronized HikariDataSource rootDataSource() {
    if (rootPool == null || rootPool.isClosed()) {
      container();
      HikariConfig config = new HikariConfig();
      config.setJdbcUrl(jdbcUrl("taskora"));
      config.setUsername("root");
      config.setPassword(ROOT_PASSWORD);
      config.setPoolName("test-root");
      config.setMaximumPoolSize(4);
      config.setMinimumIdle(1);
      config.setConnectionTimeout(30_000);
      config.addDataSourceProperty("connectTimeout", "30000");
      rootPool = new HikariDataSource(config);
    }
    return rootPool;
  }

  public static synchronized void closeRootPool() {
    if (rootPool != null) {
      rootPool.close();
      rootPool = null;
    }
  }

  // ------------------------------------------------------------------ données

  /** Vide les tables de taskora et l'outbox, puis charge le jeu de référence. */
  public static void loadReference() {
    reset();
    try (Connection c = rootConnection()) {
      ScriptUtils.executeSqlScript(c, new ClassPathResource("fixtures/board-reference.sql"));
    } catch (SQLException e) {
      throw new IllegalStateException("chargement du jeu de référence impossible", e);
    }
  }

  /** Vide toutes les tables de taskora (TRUNCATE sous root) et l'outbox de board-service. */
  public static void reset() {
    List<String> statements = new ArrayList<>();
    statements.add("SET FOREIGN_KEY_CHECKS = 0");
    for (String t : TASKORA_TABLES) {
      statements.add("TRUNCATE TABLE taskora.`" + t + "`");
    }
    statements.add("SET FOREIGN_KEY_CHECKS = 1");
    rootExecute(statements);
    try {
      rootExecute(List.of("DELETE FROM board.outbox_message"));
    } catch (IllegalStateException ignored) {
      // board.outbox_message est créée par la migration Flyway V1 (BackCodeur)
    }
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

  public static void rootExecute(String sql, Object... args) {
    try (Connection c = rootConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        ps.setObject(i + 1, args[i]);
      }
      ps.execute();
    } catch (SQLException e) {
      throw new IllegalStateException("SQL de test en échec : " + e.getMessage(), e);
    }
  }

  public static List<Map<String, Object>> query(String sql, Object... args) {
    try (Connection c = rootConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        ps.setObject(i + 1, args[i]);
      }
      try (ResultSet rs = ps.executeQuery()) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int n = rs.getMetaData().getColumnCount();
        while (rs.next()) {
          Map<String, Object> row = new LinkedHashMap<>();
          for (int i = 1; i <= n; i++) {
            row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
          }
          rows.add(row);
        }
        return rows;
      }
    } catch (SQLException e) {
      throw new IllegalStateException("SQL de test en échec : " + e.getMessage(), e);
    }
  }

  public static long count(String sql, Object... args) {
    Object v = query(sql, args).getFirst().values().iterator().next();
    return v == null ? 0 : ((Number) v).longValue();
  }

  /** Valeur unique (texte) ou null. */
  public static String scalar(String sql, Object... args) {
    List<Map<String, Object>> rows = query(sql, args);
    if (rows.isEmpty()) {
      return null;
    }
    Object v = rows.getFirst().values().iterator().next();
    return v == null ? null : v.toString();
  }

  /**
   * Empreinte des données (CHECKSUM TABLE) des tables du périmètre et de l'outbox : sert à prouver
   * « aucune écriture » (SC-BOARD-021) et la lecture seule de verify.
   */
  public static Map<String, Object> dataFingerprint() {
    Map<String, Object> out = new LinkedHashMap<>();
    StringBuilder tables = new StringBuilder();
    for (String t : TASKORA_TABLES) {
      tables.append(tables.isEmpty() ? "" : ", ").append("taskora.`").append(t).append('`');
    }
    for (Map<String, Object> row : query("CHECKSUM TABLE " + tables)) {
      out.put(String.valueOf(row.get("Table")), row.get("Checksum"));
    }
    out.put("board.outbox_message", outboxCount());
    return out;
  }

  /** Nombre de lignes de l'outbox, ou -1 si la table n'existe pas encore (Flyway V1). */
  public static long outboxCount() {
    try {
      return count("SELECT COUNT(*) FROM board.outbox_message");
    } catch (IllegalStateException e) {
      return -1;
    }
  }

  /** Empreinte de la structure de taskora (colonnes, index, contraintes, tables). */
  public static String taskoraSchemaFingerprint() {
    StringBuilder sb = new StringBuilder();
    for (String sql :
        List.of(
            "SELECT TABLE_NAME, ENGINE, TABLE_COLLATION FROM information_schema.TABLES"
                + " WHERE TABLE_SCHEMA = 'taskora' ORDER BY TABLE_NAME",
            "SELECT TABLE_NAME, COLUMN_NAME, ORDINAL_POSITION, COLUMN_TYPE, IS_NULLABLE,"
                + " COLUMN_DEFAULT, COLLATION_NAME, EXTRA FROM information_schema.COLUMNS"
                + " WHERE TABLE_SCHEMA = 'taskora' ORDER BY TABLE_NAME, ORDINAL_POSITION",
            "SELECT TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX, COLUMN_NAME, NON_UNIQUE"
                + " FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = 'taskora'"
                + " ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX",
            "SELECT CONSTRAINT_NAME, TABLE_NAME, REFERENCED_TABLE_NAME, UPDATE_RULE, DELETE_RULE"
                + " FROM information_schema.REFERENTIAL_CONSTRAINTS"
                + " WHERE CONSTRAINT_SCHEMA = 'taskora' ORDER BY CONSTRAINT_NAME",
            "SELECT TRIGGER_NAME FROM information_schema.TRIGGERS"
                + " WHERE TRIGGER_SCHEMA = 'taskora' ORDER BY TRIGGER_NAME")) {
      for (Map<String, Object> row : query(sql)) {
        sb.append(row.values()).append('\n');
      }
    }
    return sb.toString();
  }

  /** Empreinte de taskora prise juste après la création du conteneur, avant tout démarrage. */
  public static String initialTaskoraFingerprint() {
    container();
    return initialTaskoraFingerprint;
  }
}
