-- Verrou de planification (T5, B-ADR-9), créé dès T0 pour figer le schéma board (board-migration.md §2).
CREATE TABLE scheduler_lock (
  name       VARCHAR(64)  NOT NULL,
  lock_until DATETIME(6)  NOT NULL COMMENT 'UTC',
  locked_at  DATETIME(6)  NOT NULL COMMENT 'UTC',
  locked_by  VARCHAR(255) NOT NULL COMMENT 'nom d''instance, sans donnée personnelle',
  PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
