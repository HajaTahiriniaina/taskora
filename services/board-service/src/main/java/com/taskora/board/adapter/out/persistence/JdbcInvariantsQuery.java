package com.taskora.board.adapter.out.persistence;

import com.taskora.board.application.port.out.InvariantsQueryPort;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Compteurs V1 à V8 de {@code verify} (spec §4.6) : requêtes {@code SELECT COUNT(*)} seulement,
 * aucune donnée personnelle, aucune écriture.
 */
public class JdbcInvariantsQuery implements InvariantsQueryPort {
  private static final Duration STALE = Duration.ofMinutes(1);

  /** Compteur → requête de comptage (constantes). */
  private static final Map<String, String> COUNTERS = new LinkedHashMap<>();

  static {
    COUNTERS.put(
        "V1.open_card_positions",
        "SELECT COUNT(*) FROM (SELECT c.list_id FROM card c"
            + " WHERE c.archived_at IS NULL AND c.deleted_at IS NULL GROUP BY c.list_id"
            + " HAVING MIN(c.position) <> 0 OR MAX(c.position) <> COUNT(*) - 1"
            + " OR COUNT(DISTINCT c.position) <> COUNT(*)) v");
    COUNTERS.put(
        "V2.closed_card_positions",
        "SELECT COUNT(*) FROM card c JOIN board_list l ON l.id = c.list_id"
            + " WHERE (c.archived_at IS NOT NULL OR c.deleted_at IS NOT NULL)"
            + " AND c.position <> -1 AND l.deleted_at IS NULL");
    COUNTERS.put(
        "V3.card_seq_behind",
        "SELECT COUNT(*) FROM board b WHERE b.card_seq <"
            + " (SELECT COALESCE(MAX(c.number), 0) FROM card c WHERE c.board_id = b.id)");
    COUNTERS.put(
        "V3.duplicate_numbers",
        "SELECT COUNT(*) FROM (SELECT board_id, number FROM card"
            + " GROUP BY board_id, number HAVING COUNT(*) > 1) d");
    COUNTERS.put(
        "V4.owner",
        "SELECT COUNT(*) FROM board b WHERE (SELECT COUNT(*) FROM board_member m"
            + " WHERE m.board_id = b.id AND m.role = 'owner') <> 1"
            + " OR NOT EXISTS (SELECT 1 FROM board_member m WHERE m.board_id = b.id"
            + " AND m.user_id = b.owner_id AND m.role = 'owner')");
    COUNTERS.put(
        "V5.assignee_not_member",
        "SELECT COUNT(*) FROM card_assignee ca JOIN card c ON c.id = ca.card_id"
            + " LEFT JOIN board_member m ON m.board_id = c.board_id AND m.user_id = ca.user_id"
            + " WHERE m.id IS NULL");
    COUNTERS.put(
        "V6.label_other_board",
        "SELECT COUNT(*) FROM card_label cl JOIN card c ON c.id = cl.card_id"
            + " JOIN label l ON l.id = cl.label_id WHERE l.board_id <> c.board_id");
    COUNTERS.put(
        "V6.field_value_other_board",
        "SELECT COUNT(*) FROM custom_field_value v JOIN card c ON c.id = v.card_id"
            + " JOIN custom_field f ON f.id = v.field_id WHERE f.board_id <> c.board_id");
    COUNTERS.put(
        "V6.cover_other_board",
        "SELECT COUNT(*) FROM card c JOIN attachment a ON a.id = c.cover_attachment_id"
            + " JOIN card ac ON ac.id = a.card_id WHERE ac.board_id <> c.board_id");
    COUNTERS.put(
        "V7.card_list_board",
        "SELECT COUNT(*) FROM card c JOIN board_list l ON l.id = c.list_id"
            + " WHERE c.board_id <> l.board_id");
    COUNTERS.put(
        "V8.outbox_failed", "SELECT COUNT(*) FROM board.outbox_message WHERE status = 'FAILED'");
  }

  private final JdbcClient jdbc;
  private final Clock clock;

  public JdbcInvariantsQuery(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Override
  public Map<String, Long> countViolations() {
    Map<String, Long> out = new LinkedHashMap<>();
    COUNTERS.forEach((name, sql) -> out.put(name, jdbc.sql(sql).query(Long.class).single()));
    LocalDateTime staleBefore =
        LocalDateTime.ofInstant(clock.instant().minus(STALE), ZoneOffset.UTC);
    out.put(
        "V8.outbox_pending_stale",
        jdbc.sql(
                "SELECT COUNT(*) FROM board.outbox_message"
                    + " WHERE status = 'PENDING' AND available_at < ?")
            .param(staleBefore)
            .query(Long.class)
            .single());
    return out;
  }
}
