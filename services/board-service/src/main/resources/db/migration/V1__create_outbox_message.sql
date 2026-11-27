-- Outbox de board-service (B-ADR-5, board-migration.md §2) : schéma board seulement, jamais taskora (R1).
CREATE TABLE outbox_message (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  event_id     CHAR(36)     NOT NULL,
  type         VARCHAR(64)  NOT NULL,
  destination  VARCHAR(32)  NOT NULL COMMENT 'symfony-notifications | symfony-storage | broker',
  aggregate_id INT          NOT NULL COMMENT 'id du tableau',
  payload      JSON         NOT NULL,
  occurred_at  DATETIME(6)  NOT NULL COMMENT 'UTC',
  available_at DATETIME(6)  NOT NULL COMMENT 'UTC',
  status       VARCHAR(16)  NOT NULL COMMENT 'PENDING | DELIVERED | FAILED | HELD',
  attempts     INT          NOT NULL DEFAULT 0,
  last_error   VARCHAR(255) NULL COMMENT 'sans donnée personnelle',
  delivered_at DATETIME(6)  NULL,
  PRIMARY KEY (id),
  CONSTRAINT uq_outbox_event_id UNIQUE (event_id),
  INDEX ix_outbox_due (destination, status, available_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
