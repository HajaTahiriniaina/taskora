CREATE TABLE outbox_message (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  event_id     CHAR(36)     NOT NULL,
  type         VARCHAR(64)  NOT NULL,
  destination  VARCHAR(32)  NOT NULL,
  aggregate_id INT          NOT NULL,
  payload      JSON         NOT NULL,
  occurred_at  DATETIME(6)  NOT NULL,
  available_at DATETIME(6)  NOT NULL,
  status       VARCHAR(16)  NOT NULL,
  attempts     INT          NOT NULL DEFAULT 0,
  last_error   VARCHAR(255) NULL,
  delivered_at DATETIME(6)  NULL,
  PRIMARY KEY (id),
  CONSTRAINT uq_outbox_event_id UNIQUE (event_id),
  INDEX ix_outbox_due (destination, status, available_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
