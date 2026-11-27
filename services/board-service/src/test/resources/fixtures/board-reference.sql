-- Jeu de données de référence T1 (spec phase 3 §9.2, restreint à T1) — données SYNTHÉTIQUES uniquement.
-- Comptes @exemple.test, hashes factices (aucun mot de passe réel), aucune copie de la base taskora.
-- Chargé par le compte root du conteneur de test (BoardDatabase.loadReference), après vidage des tables.
-- Dates : heure murale Europe/Paris, comme Symfony (FA4). Égalités voulues pour éprouver les tris :
--   tableaux 101 et 102 créés au même instant (accueil : id ASC) ; tableaux 103 et 105 mis à la corbeille
--   au même instant (corbeille : id ASC) ; listes 202 et 205 à la même position (position, id) ;
--   champs 601 et 602 à la même position. Dates d'été (+02:00), d'hiver (+01:00) et heure ambiguë
--   d'octobre (invitation 302, 2026-10-25 02:30:00 → +02:00, décalage le plus ancien).
-- Jeu « sain » pour verify (V1–V8 = 0) : positions des cartes ouvertes contiguës, archivées/corbeille à -1,
--   card_seq = MAX(number), un seul owner = owner_id, assignés membres, étiquettes et valeurs du bon tableau.

INSERT INTO `user` (id, email, name, password, avatar_color, roles, created_at) VALUES
  (1, 'alice@exemple.test', 'Alice Martin', '$2y$13$factice.hash.de.test.aucun.mot.de.passe.reel.000001', '#6366f1', '[]', '2026-01-10 09:00:00'),
  (2, 'bruno@exemple.test', 'Bruno Admin', '$2y$13$factice.hash.de.test.aucun.mot.de.passe.reel.000002', '#ef4444', '[]', '2026-01-11 09:00:00'),
  (3, 'carla@exemple.test', 'Carla Membre', '$2y$13$factice.hash.de.test.aucun.mot.de.passe.reel.000003', '#14b8a6', '[]', '2026-01-12 09:00:00'),
  (4, 'marc@exemple.test', 'Marc Membre', '$2y$13$factice.hash.de.test.aucun.mot.de.passe.reel.000004', '#f97316', '[]', '2026-01-13 09:00:00'),
  (5, 'nadia@exemple.test', 'Nadia Externe', '$2y$13$factice.hash.de.test.aucun.mot.de.passe.reel.000005', '#ec4899', '[]', '2026-01-14 09:00:00'),
  (6, 'dora@exemple.test', 'Dora Libre', '$2y$13$factice.hash.de.test.aucun.mot.de.passe.reel.000006', '#eab308', '[]', '2026-01-15 09:00:00'),
  (7, 'eve@exemple.test', 'Ève Autre', '$2y$13$factice.hash.de.test.aucun.mot.de.passe.reel.000007', '#0ea5e9', '[]', '2026-01-16 09:00:00'),
  (8, 'Gaston.Vieux@Exemple.TEST', 'Gaston Vieux', '$2y$13$factice.hash.de.test.aucun.mot.de.passe.reel.000008', '#22c55e', '[]', '2026-01-17 09:00:00'),
  (9, 'invite@exemple.test', 'Inès Invitée', '$2y$13$factice.hash.de.test.aucun.mot.de.passe.reel.000009', '#a855f7', '[]', '2026-11-27 10:59:00');

INSERT INTO board (id, title, description, background, created_at, owner_id, card_seq, show_card_numbers, deleted_at, deleted_by_id) VALUES
  (101, 'Lancement produit', 'Plan de lancement', 'ocean', '2026-07-14 09:15:00', 1, 8, 1, NULL, NULL),
  (102, 'Import Trello', NULL, 'aurora', '2026-07-14 09:15:00', 1, 1, 0, NULL, NULL),
  (103, 'Ancien projet', NULL, 'forest', '2026-06-15 08:00:00', 1, 1, 1, '2026-11-25 09:30:00', 1),
  (104, 'Tableau d''Ève', NULL, 'sand', '2026-11-01 12:00:00', 7, 1, 1, NULL, NULL),
  (105, 'Brouillon', NULL, 'berry', '2026-05-01 08:00:00', 1, 0, 1, '2026-11-25 09:30:00', NULL);

INSERT INTO board_member (id, role, favorite, joined_at, board_id, user_id) VALUES
  (1001, 'owner', 0, '2026-07-14 09:15:00', 101, 1),
  (1002, 'admin', 0, '2026-07-15 10:00:00', 101, 2),
  (1003, 'member', 1, '2026-11-21 14:00:00', 101, 3),
  (1004, 'member', 0, '2026-11-22 09:15:00', 101, 4),
  (1005, 'owner', 1, '2026-07-14 09:15:00', 102, 1),
  (1006, 'member', 0, '2026-08-01 08:00:00', 102, 3),
  (1007, 'owner', 0, '2026-06-15 08:00:00', 103, 1),
  (1008, 'admin', 0, '2026-06-15 08:05:00', 103, 2),
  (1009, 'member', 0, '2026-06-15 08:10:00', 103, 3),
  (1010, 'owner', 0, '2026-11-01 12:00:00', 104, 7),
  (1011, 'owner', 0, '2026-05-01 08:00:00', 105, 1);

INSERT INTO board_list (id, title, position, color, board_id, archived_at, deleted_at, deleted_by_id) VALUES
  (201, 'À faire', 0, NULL, 101, NULL, NULL, NULL),
  (202, 'En cours', 1, '#6366f1', 101, NULL, NULL, NULL),
  (203, 'Archivée', 2, NULL, 101, '2026-11-10 08:00:00', NULL, NULL),
  (204, 'Supprimée', 3, NULL, 101, NULL, '2026-11-24 08:00:00', 1),
  (205, 'En revue', 1, NULL, 101, NULL, NULL, NULL),
  (206, 'Import', 0, NULL, 102, NULL, NULL, NULL),
  (207, 'Vieille liste', 0, NULL, 103, NULL, NULL, NULL),
  (208, 'Liste d''Ève', 0, NULL, 104, NULL, NULL, NULL);

INSERT INTO card (id, title, description, position, due_date, completed, cover, priority, created_at, list_id, created_by_id, number, board_id, cover_attachment_id, archived_at, deleted_at, deleted_by_id) VALUES
  (401, 'Carte A', NULL, 0, NULL, 0, NULL, 'none', '2026-11-20 10:00:00', 201, 1, 1, 101, NULL, NULL, NULL, NULL),
  (402, 'Carte B', NULL, 1, NULL, 0, NULL, 'none', '2026-11-20 10:01:00', 201, 1, 2, 101, NULL, NULL, NULL, NULL),
  (403, 'Carte C', NULL, 2, NULL, 0, NULL, 'none', '2026-11-20 10:02:00', 201, 1, 3, 101, NULL, NULL, NULL, NULL),
  (404, 'Carte archivée', NULL, -1, NULL, 0, NULL, 'none', '2026-11-20 10:03:00', 201, 1, 4, 101, NULL, '2026-11-11 08:00:00', NULL, NULL),
  (405, 'Carte supprimée', NULL, -1, NULL, 0, NULL, 'none', '2026-11-20 10:04:00', 201, 1, 5, 101, NULL, NULL, '2026-11-24 09:00:00', 1),
  (406, 'Carte D', NULL, 0, NULL, 0, NULL, 'none', '2026-11-20 10:05:00', 202, 2, 6, 101, NULL, NULL, NULL, NULL),
  (407, 'Carte E', NULL, 1, NULL, 0, NULL, 'none', '2026-11-20 10:06:00', 202, 2, 7, 101, NULL, NULL, NULL, NULL),
  (408, 'Carte de liste archivée', NULL, 0, NULL, 0, NULL, 'none', '2026-11-20 10:07:00', 203, 2, 8, 101, NULL, NULL, NULL, NULL),
  (409, 'Carte importée', NULL, 0, NULL, 0, NULL, 'none', '2026-07-14 09:15:00', 206, 1, 1, 102, NULL, NULL, NULL, NULL),
  (410, 'Vieille carte', NULL, 0, NULL, 0, NULL, 'none', '2026-06-15 08:00:00', 207, 1, 1, 103, NULL, NULL, NULL, NULL),
  (411, 'Carte d''Ève', NULL, 0, NULL, 0, NULL, 'none', '2026-11-01 12:00:00', 208, 7, 1, 104, NULL, NULL, NULL, NULL);

INSERT INTO card_assignee (card_id, user_id) VALUES (401, 3), (406, 3), (402, 4), (409, 3);

INSERT INTO label (id, name, color, board_id) VALUES
  (501, 'Urgent', '#ef4444', 101),
  (502, 'Bug', '#f97316', 101),
  (503, 'Import', '#6366f1', 102);
INSERT INTO card_label (card_id, label_id) VALUES (401, 501), (409, 503);

INSERT INTO custom_field (id, name, type, options, show_on_card, position, board_id) VALUES
  (601, 'Priorité client', 'select', '[{"id":"a1","label":"Haute"},{"id":"b2","label":"Basse"}]', 1, 0, 101),
  (602, 'Estimation', 'number', '[]', 0, 0, 101);
INSERT INTO custom_field_value (id, value, card_id, field_id) VALUES (701, '"a1"', 401, 601);

INSERT INTO checklist_item (id, content, done, position, card_id) VALUES (1201, 'Vérifier le plan', 0, 0, 401);
INSERT INTO comment (id, content, created_at, card_id, author_id, external_author) VALUES
  (1101, '<p>Bonjour</p>', '2026-11-21 15:00:00', 401, 3, NULL);
INSERT INTO activity (id, external_actor, type, data, created_at, card_id, actor_id) VALUES
  (1301, NULL, 'card_created', '{"title":"Carte A"}', '2026-11-20 10:00:00', 401, 1);

INSERT INTO invitation (id, email, created_at, board_id, invited_by_id) VALUES
  (301, 'invite@exemple.test', '2026-11-23 16:45:00', 101, 1),
  (302, 'autre@exemple.test', '2026-10-25 02:30:00', 101, 2),
  (303, 'invite@exemple.test', '2026-06-20 10:00:00', 103, 1),
  (304, 'tiers@exemple.test', '2026-11-02 08:00:00', 104, 7),
  (305, 'orphelin@exemple.test', '2026-11-03 08:00:00', 102, NULL);

INSERT INTO attachment (id, name, storage_key, mime_type, size, created_at, card_id, uploader_id, comment_id, thumb_key, preview_key) VALUES
  (901, 'plan.pdf', 'uploads/2026/11/plan-de-test.pdf', 'application/pdf', 1234, '2026-11-21 10:00:00', 401, 1, NULL, NULL, NULL);

INSERT INTO notification (id, type, message, is_read, created_at, recipient_id, actor_id, board_id, card_id) VALUES
  (951, 'board_invite', 'Alice Martin vous a ajouté au tableau « Lancement produit »', 0, '2026-11-21 14:00:00', 3, 1, 101, NULL);

INSERT INTO trello_import_file (id, trello_id, url, name, bytes, `date`, cover, status, error, board_id, card_id, uploader_id, attachment_id) VALUES
  (801, 'trello-0001', 'https://trello.exemple.test/a.png', 'a.png', 100, '2026-07-14 09:15:00', 0, 'pending', NULL, 101, 401, 1, NULL),
  (802, 'trello-0002', 'https://trello.exemple.test/b.png', 'b.png', 200, '2026-07-14 09:15:00', 0, 'failed', 'délai dépassé', 101, 401, 1, NULL),
  (803, 'trello-0003', 'https://trello.exemple.test/c.png', 'c.png', 300, '2026-07-14 09:15:00', 0, 'done', NULL, 101, 402, 1, 901),
  (804, 'trello-0004', 'https://trello.exemple.test/d.png', 'd.png', 400, '2026-07-14 09:15:00', 0, 'pending', NULL, 102, 409, 1, NULL);
