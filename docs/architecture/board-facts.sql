-- Taskora — étape 0 de board-service : faits à mesurer sur la base de travail `taskora`.
-- Référence : docs/architecture/board-migration.md §1.3. Auteur : BackAli (27/11/2026).
--
-- RÈGLES (à respecter par la personne qui exécute) :
--   * LECTURE SEULE : la session est déclarée en lecture seule (ci-dessous) ; aucune écriture, aucun DDL.
--   * AGRÉGATS SEULEMENT : chaque requête renvoie des nombres ou des noms de colonnes/collations ;
--     jamais un e-mail, un nom, un titre, un contenu ni une date individuelle.
--   * Qui / quand : l'utilisateur (ou Manuel avec son accord explicite), APRÈS la démonstration client,
--     jamais pendant. Aucun agent ne lance ce fichier de lui-même sur `taskora`.
--   * Commande (le mot de passe vient de l'environnement du conteneur, il n'est jamais affiché) :
--       docker compose -p taskora exec -T database sh -c \
--         'MYSQL_PWD="$MARIADB_PASSWORD" mariadb --batch -u "$MARIADB_USER" "$MARIADB_DATABASE"' \
--         < docs/architecture/board-facts.sql > /tmp/board-facts.tsv
--     puis recopier les nombres dans board-migration.md §1.4 (et supprimer /tmp/board-facts.tsv).
--   * Durée attendue : quelques secondes (index existants) ; aucun verrou d'écriture pris
--     (lectures cohérentes InnoDB, REPEATABLE READ, transaction en lecture seule).

SET SESSION TRANSACTION READ ONLY;
START TRANSACTION READ ONLY;

-- F0 — Serveur et session
SELECT 'F0' AS fact, VERSION() AS version, @@sql_mode AS sql_mode, @@time_zone AS time_zone,
       @@system_time_zone AS system_tz, @@collation_server AS collation_server,
       @@transaction_isolation AS isolation, @@innodb_lock_wait_timeout AS lock_wait_s;

-- F1 — Volumes par table (exacts)
SELECT 'F1' AS fact, t, n FROM (
  SELECT 'user' t, COUNT(*) n FROM `user`
  UNION ALL SELECT 'board', COUNT(*) FROM board
  UNION ALL SELECT 'board_member', COUNT(*) FROM board_member
  UNION ALL SELECT 'invitation', COUNT(*) FROM invitation
  UNION ALL SELECT 'board_list', COUNT(*) FROM board_list
  UNION ALL SELECT 'card', COUNT(*) FROM card
  UNION ALL SELECT 'card_assignee', COUNT(*) FROM card_assignee
  UNION ALL SELECT 'card_label', COUNT(*) FROM card_label
  UNION ALL SELECT 'label', COUNT(*) FROM label
  UNION ALL SELECT 'custom_field', COUNT(*) FROM custom_field
  UNION ALL SELECT 'custom_field_value', COUNT(*) FROM custom_field_value
  UNION ALL SELECT 'checklist_item', COUNT(*) FROM checklist_item
  UNION ALL SELECT 'comment', COUNT(*) FROM comment
  UNION ALL SELECT 'activity', COUNT(*) FROM activity
  UNION ALL SELECT 'attachment', COUNT(*) FROM attachment
  UNION ALL SELECT 'notification', COUNT(*) FROM notification
  UNION ALL SELECT 'trello_import_file', COUNT(*) FROM trello_import_file
) v;

-- F2 — Tailles maximales (dimensionnement des tests N+1 et de la purge)
SELECT 'F2' AS fact,
  (SELECT COALESCE(MAX(n),0) FROM (SELECT COUNT(*) n FROM board_member GROUP BY user_id) x) AS max_boards_per_user,
  (SELECT COALESCE(MAX(n),0) FROM (SELECT COUNT(*) n FROM board_member GROUP BY board_id) x) AS max_members_per_board,
  (SELECT COALESCE(MAX(n),0) FROM (SELECT COUNT(*) n FROM board_list GROUP BY board_id) x) AS max_lists_per_board,
  (SELECT COALESCE(MAX(n),0) FROM (SELECT COUNT(*) n FROM card GROUP BY board_id) x) AS max_cards_per_board,
  (SELECT COALESCE(MAX(n),0) FROM (SELECT COUNT(*) n FROM card GROUP BY list_id) x) AS max_cards_per_list,
  (SELECT COALESCE(MAX(n),0) FROM (SELECT COUNT(*) n FROM activity GROUP BY card_id) x) AS max_activity_per_card,
  (SELECT COALESCE(MAX(n),0) FROM (SELECT COUNT(*) n FROM invitation GROUP BY board_id) x) AS max_invitations_per_board;

-- F3 — Positions des cartes ouvertes (V1) : listes avec doublon, listes non contiguës
SELECT 'F3' AS fact,
  (SELECT COUNT(DISTINCT list_id) FROM (SELECT list_id FROM card WHERE archived_at IS NULL AND deleted_at IS NULL
      GROUP BY list_id, position HAVING COUNT(*) > 1) d) AS lists_with_duplicate_positions,
  (SELECT COUNT(*) FROM (SELECT list_id FROM card WHERE archived_at IS NULL AND deleted_at IS NULL
      GROUP BY list_id HAVING MIN(position) <> 0 OR MAX(position) <> COUNT(*) - 1 OR COUNT(DISTINCT position) <> COUNT(*)) nc)
      AS lists_not_contiguous,
  (SELECT COUNT(*) FROM card WHERE archived_at IS NULL AND deleted_at IS NULL AND position < 0) AS open_cards_negative_position;

-- F4 — Cartes archivées ou dans la corbeille dont la position n'est pas -1 (V2)
SELECT 'F4' AS fact,
  SUM(archived_at IS NOT NULL AND position <> -1) AS archived_not_minus1,
  SUM(deleted_at IS NOT NULL AND position <> -1) AS trashed_not_minus1
FROM card;

-- F5 — Positions des listes ouvertes (doublons par tableau), checklists et champs (E25)
SELECT 'F5' AS fact,
  (SELECT COUNT(DISTINCT board_id) FROM (SELECT board_id FROM board_list WHERE archived_at IS NULL AND deleted_at IS NULL
      GROUP BY board_id, position HAVING COUNT(*) > 1) d) AS boards_with_dup_list_positions,
  (SELECT COUNT(DISTINCT card_id) FROM (SELECT card_id FROM checklist_item GROUP BY card_id, position HAVING COUNT(*) > 1) d)
      AS cards_with_dup_checklist_positions,
  (SELECT COUNT(DISTINCT board_id) FROM (SELECT board_id FROM custom_field GROUP BY board_id, position HAVING COUNT(*) > 1) d)
      AS boards_with_dup_field_positions;

-- F6 — Numérotation (V3) : card_seq contre MAX(number)
SELECT 'F6' AS fact,
  SUM(b.card_seq < COALESCE(m.mx, 0)) AS boards_seq_behind,
  SUM(b.card_seq > COALESCE(m.mx, 0)) AS boards_seq_ahead,
  COALESCE(MAX(b.card_seq - COALESCE(m.mx, 0)), 0) AS max_gap,
  (SELECT COUNT(*) FROM (SELECT 1 FROM card GROUP BY board_id, number HAVING COUNT(*) > 1) d) AS duplicate_numbers
FROM board b LEFT JOIN (SELECT board_id, MAX(number) mx FROM card GROUP BY board_id) m ON m.board_id = b.id;

-- F7 — Invariants de structure (V4, V5, V6, V7)
SELECT 'F7' AS fact,
  (SELECT COUNT(*) FROM board b WHERE (SELECT COUNT(*) FROM board_member m WHERE m.board_id = b.id AND m.role = 'owner') <> 1)
      AS boards_owner_count_not_1,
  (SELECT COUNT(*) FROM board b JOIN board_member m ON m.board_id = b.id AND m.role = 'owner' WHERE m.user_id <> b.owner_id)
      AS owner_member_mismatch,
  (SELECT COUNT(*) FROM board_member WHERE role NOT IN ('owner', 'admin', 'member')) AS unknown_roles,
  (SELECT COUNT(*) FROM card_assignee ca JOIN card c ON c.id = ca.card_id
      LEFT JOIN board_member m ON m.board_id = c.board_id AND m.user_id = ca.user_id WHERE m.id IS NULL) AS assignees_not_members,
  (SELECT COUNT(*) FROM card_label cl JOIN card c ON c.id = cl.card_id JOIN label l ON l.id = cl.label_id
      WHERE l.board_id <> c.board_id) AS labels_other_board,
  (SELECT COUNT(*) FROM custom_field_value v JOIN card c ON c.id = v.card_id JOIN custom_field f ON f.id = v.field_id
      WHERE f.board_id <> c.board_id) AS values_other_board,
  (SELECT COUNT(*) FROM card c JOIN attachment a ON a.id = c.cover_attachment_id WHERE a.card_id <> c.id) AS cover_other_card,
  (SELECT COUNT(*) FROM card c JOIN board_list l ON l.id = c.list_id WHERE l.board_id <> c.board_id) AS card_board_mismatch;

-- F8 — Rôles et invitations
SELECT 'F8' AS fact, role, COUNT(*) AS n FROM board_member GROUP BY role;
SELECT 'F8b' AS fact,
  (SELECT COUNT(*) FROM invitation i JOIN `user` u ON u.email = i.email) AS invitations_matching_existing_account,
  (SELECT COUNT(*) FROM invitation WHERE invited_by_id IS NULL) AS invitations_without_inviter;

-- F9 — Échéances (E31) : répartition par heure murale stockée, secondes, minutes rondes
SELECT 'F9' AS fact, COUNT(due_date) AS with_due,
  SUM(SECOND(due_date) <> 0) AS with_seconds,
  SUM(MINUTE(due_date) = 0) AS on_the_hour,
  SUM(MINUTE(due_date) IN (0, 15, 30, 45)) AS on_quarter
FROM card;
SELECT 'F9b' AS fact, HOUR(due_date) AS stored_hour, COUNT(*) AS n FROM card WHERE due_date IS NOT NULL
GROUP BY HOUR(due_date) ORDER BY stored_hour;
SELECT 'F9c' AS fact, (MONTH(due_date) BETWEEN 4 AND 10) AS summer_ish, HOUR(due_date) AS stored_hour, COUNT(*) AS n
FROM card WHERE due_date IS NOT NULL AND MINUTE(due_date) = 0 GROUP BY summer_ish, stored_hour ORDER BY summer_ish, stored_hour;

-- F10 — Collations et types des colonnes du périmètre (attendu : utf8mb4_unicode_ci, JSON = utf8mb4_bin)
SELECT 'F10' AS fact, TABLE_NAME, COLUMN_NAME, DATA_TYPE, COLLATION_NAME
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('user','board','board_member','invitation','board_list','card','card_assignee','card_label','label',
                     'custom_field','custom_field_value','checklist_item','comment','activity','attachment','trello_import_file')
  AND COLLATION_NAME IS NOT NULL AND COLLATION_NAME <> 'utf8mb4_unicode_ci'
ORDER BY TABLE_NAME, COLUMN_NAME;
SELECT 'F10b' AS fact, TABLE_NAME, TABLE_COLLATION, ENGINE FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND (TABLE_COLLATION <> 'utf8mb4_unicode_ci' OR ENGINE <> 'InnoDB');

-- F11 — Encodages JSON (R5) : types et échappements PHP
SELECT 'F11' AS fact, JSON_TYPE(value) AS json_type, COUNT(*) AS n FROM custom_field_value GROUP BY JSON_TYPE(value);
SELECT 'F11b' AS fact,
  (SELECT COUNT(*) FROM custom_field_value WHERE value REGEXP '[^ -~]') AS values_raw_non_ascii,
  (SELECT COUNT(*) FROM activity WHERE data REGEXP '[^ -~]') AS activity_raw_non_ascii,
  (SELECT COUNT(*) FROM custom_field WHERE options REGEXP '[^ -~]') AS options_raw_non_ascii,
  (SELECT COUNT(*) FROM activity WHERE data LIKE '%/%' AND data NOT LIKE '%\\\\/%') AS activity_unescaped_slash;
SELECT 'F11c' AS fact, type, COUNT(*) AS n FROM activity GROUP BY type ORDER BY type;

-- F12 — Corbeille et archives (purge en service ?)
SELECT 'F12' AS fact,
  (SELECT COUNT(*) FROM board WHERE deleted_at IS NOT NULL) AS boards_trashed,
  (SELECT COUNT(*) FROM board_list WHERE deleted_at IS NOT NULL) AS lists_trashed,
  (SELECT COUNT(*) FROM card WHERE deleted_at IS NOT NULL) AS cards_trashed,
  (SELECT COUNT(*) FROM board WHERE deleted_at < NOW() - INTERVAL 31 DAY) AS boards_overdue_purge,
  (SELECT COUNT(*) FROM card WHERE deleted_at < NOW() - INTERVAL 31 DAY) AS cards_overdue_purge,
  (SELECT COUNT(*) FROM board_list WHERE archived_at IS NOT NULL) AS lists_archived,
  (SELECT COUNT(*) FROM card WHERE archived_at IS NOT NULL) AS cards_archived;

-- F13 — Dates écrites en heure de Paris : entrées d'historique dans l'heure ambiguë d'octobre (ordre SC-BOARD-028)
SELECT 'F13' AS fact, COUNT(*) AS activity_in_ambiguous_hour FROM activity
WHERE MONTH(created_at) = 10 AND DAYOFWEEK(created_at) = 1 AND DAYOFMONTH(created_at) > 24 AND HOUR(created_at) = 2;

COMMIT;
