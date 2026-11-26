CREATE TABLE user_account (
  id            INT          NOT NULL AUTO_INCREMENT,
  email         VARCHAR(180) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  name          VARCHAR(120) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  password_hash VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin        NOT NULL,
  avatar_color  VARCHAR(20)  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  roles         JSON         NOT NULL,
  created_at    DATETIME     NOT NULL COMMENT 'heure locale Europe/Paris, comme Symfony',
  updated_at    DATETIME(6)  NULL     COMMENT 'UTC, propre à identity',
  PRIMARY KEY (id),
  CONSTRAINT uq_user_account_email UNIQUE (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
