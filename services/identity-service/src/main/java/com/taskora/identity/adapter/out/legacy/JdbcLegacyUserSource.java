package com.taskora.identity.adapter.out.legacy;

import com.taskora.identity.application.port.in.ImportReport;
import com.taskora.identity.application.port.out.LegacyUserSourcePort;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reprise idempotente de taskora.user vers identity.user_account (A14, identity-migration.md §3-4).
 * Jamais de DELETE ; rapports sans donnée personnelle (nombres et empreintes).
 */
public class JdbcLegacyUserSource implements LegacyUserSourcePort {
  private static final String TARGET = "user_account";

  private final JdbcClient jdbc;
  private final String source;

  public JdbcLegacyUserSource(JdbcClient jdbc, LegacySchema schema) {
    this.jdbc = jdbc;
    this.source = schema.userTable();
  }

  @Override
  public int countNormalizedEmailDuplicates() {
    return count(
        "SELECT COUNT(*) FROM (SELECT LOWER(TRIM(email)) AS e FROM "
            + source
            + " GROUP BY e HAVING COUNT(*) > 1) d");
  }

  @Override
  public ImportReport copyAllIntoIdentity() {
    int sourceRows = count("SELECT COUNT(*) FROM " + source);
    int inserted =
        count(
            "SELECT COUNT(*) FROM "
                + source
                + " u WHERE NOT EXISTS (SELECT 1 FROM "
                + TARGET
                + " a WHERE a.id = u.id)");
    int unchanged =
        count(
            "SELECT COUNT(*) FROM "
                + source
                + " u JOIN "
                + TARGET
                + " a ON a.id = u.id"
                + " WHERE BINARY a.email = BINARY LOWER(TRIM(u.email))"
                + " AND BINARY a.name = BINARY u.name"
                + " AND BINARY a.password_hash = BINARY u.password"
                + " AND BINARY a.avatar_color = BINARY u.avatar_color"
                + " AND BINARY a.roles = BINARY u.roles"
                + " AND a.created_at = u.created_at");
    int targetOnly =
        count(
            "SELECT COUNT(*) FROM "
                + TARGET
                + " a WHERE NOT EXISTS (SELECT 1 FROM "
                + source
                + " u WHERE u.id = a.id)");
    jdbc.sql(
            "INSERT INTO "
                + TARGET
                + " (id, email, name, password_hash, avatar_color, roles, created_at)"
                + " SELECT u.id, LOWER(TRIM(u.email)), u.name, u.password, u.avatar_color,"
                + " u.roles, u.created_at FROM "
                + source
                + " u ORDER BY u.id"
                + " ON DUPLICATE KEY UPDATE email = VALUES(email), name = VALUES(name),"
                + " password_hash = VALUES(password_hash), avatar_color = VALUES(avatar_color),"
                + " roles = VALUES(roles)")
        .update();
    // InnoDB porte AUTO_INCREMENT au-delà du plus grand id inséré explicitement (MAX(id) + 1) :
    // pas d'ALTER TABLE, qui validerait implicitement la transaction.
    return new ImportReport(
        sourceRows, inserted, sourceRows - inserted - unchanged, unchanged, targetOnly);
  }

  @Override
  public Map<String, String> sourceMetrics() {
    Map<String, String> metrics =
        metrics(source, "LOWER(TRIM(email))", "password", "CONCAT_WS('|', id, LOWER(TRIM(email)),"
            + " password, name, avatar_color, roles, created_at)");
    // Valeurs attendues des contrôles croisés, mesurées côté cible.
    metrics.put("idSymmetricDifference", "0");
    metrics.put("autoIncrementAboveMaxId", "true");
    return metrics;
  }

  @Override
  public Map<String, String> targetMetrics() {
    Map<String, String> metrics =
        metrics(TARGET, "email", "password_hash", "CONCAT_WS('|', id, email, password_hash, name,"
            + " avatar_color, roles, created_at)");
    int onlyInSource =
        count(
            "SELECT COUNT(*) FROM "
                + source
                + " u WHERE NOT EXISTS (SELECT 1 FROM "
                + TARGET
                + " a WHERE a.id = u.id)");
    int onlyInTarget =
        count(
            "SELECT COUNT(*) FROM "
                + TARGET
                + " a WHERE NOT EXISTS (SELECT 1 FROM "
                + source
                + " u WHERE u.id = a.id)");
    metrics.put("idSymmetricDifference", String.valueOf(onlyInSource + onlyInTarget));
    long nextId =
        jdbc.sql(
                "SELECT COALESCE(AUTO_INCREMENT, 1) FROM information_schema.tables"
                    + " WHERE table_schema = DATABASE() AND table_name = '"
                    + TARGET
                    + "'")
            .query(Long.class)
            .single();
    long sourceMax = jdbc.sql("SELECT COALESCE(MAX(id), 0) FROM " + source).query(Long.class).single();
    metrics.put("autoIncrementAboveMaxId", String.valueOf(nextId > sourceMax));
    return metrics;
  }

  private Map<String, String> metrics(String table, String email, String hash, String row) {
    Map<String, String> metrics = new LinkedHashMap<>();
    metrics.put("count", String.valueOf(count("SELECT COUNT(*) FROM " + table)));
    metrics.put(
        "maxId",
        jdbc.sql("SELECT COALESCE(MAX(id), 0) FROM " + table).query(String.class).single());
    metrics.put(
        "normalizedEmailDuplicates",
        String.valueOf(
            count(
                "SELECT COUNT(*) FROM (SELECT "
                    + email
                    + " AS e FROM "
                    + table
                    + " GROUP BY e HAVING COUNT(*) > 1) d")));
    metrics.put(
        "hashPrefixes",
        jdbc.sql(
                "SELECT COALESCE(GROUP_CONCAT(CONCAT(p, '/', c, '=', n) ORDER BY p, c"
                    + " SEPARATOR ','), '') FROM (SELECT CASE"
                    + " WHEN "
                    + hash
                    + " LIKE '$argon2id$%' THEN '$argon2id$'"
                    + " WHEN "
                    + hash
                    + " LIKE '$argon2i$%' THEN '$argon2i$'"
                    + " ELSE LEFT("
                    + hash
                    + ", 4) END AS p,"
                    + " CASE WHEN "
                    + hash
                    + " LIKE '$2%' THEN SUBSTRING("
                    + hash
                    + ", 5, 2) ELSE '' END AS c, COUNT(*) AS n FROM "
                    + table
                    + " GROUP BY p, c) h")
            .query(String.class)
            .single());
    metrics.put(
        "crc32Sum",
        jdbc.sql("SELECT COALESCE(SUM(CRC32(" + row + ")), 0) FROM " + table)
            .query(String.class)
            .single());
    metrics.put(
        "crc32Xor",
        jdbc.sql("SELECT COALESCE(BIT_XOR(CRC32(" + row + ")), 0) FROM " + table)
            .query(String.class)
            .single());
    return metrics;
  }

  private int count(String sql) {
    return jdbc.sql(sql).query(Integer.class).single();
  }
}
