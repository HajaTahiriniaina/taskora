# Cohabitation et bascule de board-service (phase 3)

Responsable : BackAli. Date : 27/11/2026. Branche : `feat/board-service`.
Décisions appliquées : U6-A, U7-a, U8-a, U9-a ; écarts E2, E8, E12, E22, E23, E25–E28, E31, E32 **corrigés**, E13, E24, E29, E30 **conservés** ([decisions.md](../specs/decisions.md)).
Spécification : [phase-3-board-service.md](../specs/phase-3-board-service.md) (§2–§7, §10). ADR : [backend-hexagonal.md](backend-hexagonal.md) §9, B-ADR-1 à B-ADR-12 et A9 révisée.
Modèle : [identity-migration.md](identity-migration.md). Différence majeure : **aucune copie de données** (tables partagées en place, U6-A), donc ni gel des écritures ni reprise ; la bascule est une commutation nginx par tranche (U9-a).
Ce document ne contient ni secret, ni valeur de `.env`, ni donnée personnelle.

## 1. Faits (étape 0)

### 1.1 Méthode
Contrainte de l'utilisateur : la base de travail `taskora` contient des données personnelles et sert à une démonstration client. **Elle n'a pas été interrogée.** Les faits ci-dessous viennent :
1. de la lecture du code Symfony (`backend/src`, `backend/config`, `backend/docker`) et des 12 migrations Doctrine ;
2. d'un conteneur PHP jetable lancé depuis l'image existante `taskora-compat-api` **sans réseau** (`--network none`, aucune variable d'environnement, aucune base) pour exécuter la conversion réelle de Doctrine DBAL (`datetime_immutable`) sous le fuseau PHP du conteneur ;
3. d'une base MariaDB jetable (`taskora-b2-db`, même image et même digest que compose, réseau Docker `--internal` dédié, mots de passe éphémères jamais affichés) où les **migrations Doctrine réelles** ont été appliquées par l'image Symfony, puis : variables du serveur, exécution à blanc de [board-facts.sql](board-facts.sql), contrôle des droits MariaDB minimaux sur des lignes synthétiques (`@exemple.test`). Conteneur et réseau supprimés ensuite (aucune ressource `taskora-b2` restante). Les stacks `taskora` et `taskora-e2e` n'ont pas été touchées.

### 1.2 Faits établis

| # | Fait | Source | Conséquence |
|---|---|---|---|
| FA1 | **E31 confirmé** : Doctrine écrit l'heure murale de l'objet reçu sans conversion (`2026-12-01T16:00:00.000Z` → `2026-12-01 16:00:00`) et relit dans le fuseau PHP `Europe/Paris` → `2026-12-01T16:00:00+01:00`, soit **−3 600 s** l'hiver et **−7 200 s** l'été (`2026-07-01T15:00:00.000Z` → `15:00:00+02:00`). Une valeur avec décalage (`17:00+01:00`) est conservée (Δ 0) | conteneur PHP jetable (2) | B-ADR-11 : board-service lit et écrit `card.due_date` en UTC |
| FA2 | Le front envoie toujours l'échéance en UTC (`new Date(value).toISOString()`, `card-modal.component.ts`) ; l'import Trello écrit `new \DateTimeImmutable($c['due'])` (chaîne ISO `Z` de Trello) | code | toutes les échéances écrites par l'application sont en **heure murale UTC** ; seul un client tiers envoyant un décalage écrirait l'heure de Paris (F9 le mesurera) |
| FA3 | Filtre `due=overdue/day/week/month` : `:now` = `new \DateTimeImmutable()` lié sans type explicite → formaté en heure de Paris, comparé à des valeurs UTC → filtres décalés de 1 à 2 h | code (`CardRepository` l. 249–263) | divergence voulue E31 dans les tests miroir des filtres (T2) |
| FA4 | Toutes les autres colonnes `DATETIME` sont écrites en heure murale **`Europe/Paris`** (`docker/php.ini` `date.timezone`), exposées en ATOM avec le décalage de Paris | code | R4 : board-service écrit et lit en `Europe/Paris` (horloge injectée) |
| FA5 | Serveur : MariaDB **11.8.9**, `sql_mode` = `STRICT_TRANS_TABLES,ERROR_FOR_DIVISION_BY_ZERO,NO_AUTO_CREATE_USER,NO_ENGINE_SUBSTITUTION` (défaut, non surchargé par compose ni par Doctrine), `time_zone` = `SYSTEM` (= `UTC`), isolation `REPEATABLE-READ`, `innodb_lock_wait_timeout` 50 s, détection d'interblocage active, tables de fuseaux chargées (498 noms) | base jetable (3) | B-ADR-1 : pas de surcharge du `sql_mode` ; contrôle au démarrage ; les colonnes `DATETIME` ne dépendent pas de `time_zone` (board-service n'utilise jamais `NOW()` SQL) |
| FA6 | Collations : toutes les tables et colonnes texte en `utf8mb4_unicode_ci` ; colonnes JSON (`activity.data`, `custom_field.options`, `custom_field_value.value`, `user.roles`) = `LONGTEXT utf8mb4_bin` avec `CHECK (json_valid)` | migrations + F10 à blanc | égalités de texte (e-mails, `q`) faites **en SQL** ; JSON comparé octet par octet (R5) |
| FA7 | Numérotation : `UPDATE board SET card_seq = LAST_INSERT_ID(card_seq + 1) WHERE id = ?` puis `SELECT LAST_INSERT_ID()` ; index unique `uniq_card_board_number (board_id, number)` → aucun doublon possible ; `app:seed-demo` fixe `card_seq` explicitement | code, migrations ; requête rejouée sous le compte restreint `board` | B-ADR-4 : même requête, dans la transaction |
| FA8 | Positions : **aucun index unique** sur `card(list_id, position)`, `board_list(board_id, position)`, `checklist_item`, `custom_field` (seul un index non unique `idx_card_list_position`) ; aucun verrou côté Symfony ; création de liste = `max(toutes les listes, archivées et corbeille comprises) + 1` ; restauration de liste = `max(listes ouvertes) + 1` ; checklist et champs = **nombre** d'éléments (E25) | code, migrations | doublons hérités **possibles** : à mesurer (F3–F5) ; tri `position, id` partout (R8) |
| FA9 | Ordres non déterministes chez Symfony : `Board::members` et `Board::labels` sans `OrderBy` (ordre de l'index `board_id`, donc de fait par `id`) ; listes par `position` seul ; accueil par `created_at DESC` seul ; corbeille par `deleted_at DESC` seul | entités, `BoardRepository` | R8 : membres et étiquettes par `id`, listes par `position, id`, accueil `created_at DESC, id DESC`, corbeille `deleted_at DESC, id DESC` (à figer par SC-BOARD-029) |
| FA10 | **Droits minimaux vérifiés** : un compte `board` sans aucun droit sur `attachment` ni `notification` peut `DELETE FROM board` ; les cascades InnoDB suppriment cartes, listes, adhésions, pièces jointes et notifications. Un `INSERT` sur `board_member` (clé étrangère vers `user`) passe sans droit sur `user`. Un droit `SELECT (id, email, name, avatar_color, created_at)` **par colonne** sur `user` suffit et **refuse** `password` (erreur 1143). La recherche `email = 'B@EXEMPLE.TEST '` trouve `b@exemple.test` (collation : casse et espaces finaux ignorés) | base jetable (3) | R2 précisé (§3) ; la question « à confirmer » de la spec est close (un IT la garde) |
| FA11 | `PATCH /api/boards/{id}` répond `boardSummary` **sans compteurs** (`listCount: 0`, `cardCount: 0`) ; `description` : `""` et `"0"` → `null` (PHP « falsy ») en modification, stockée telle quelle à la création | code | reproduit (contrat, `x-compat`) |
| FA12 | `/api/trash/boards/{id}` et `/restore` n'ont **pas** de contrainte `\d+` ; non-membre d'un tableau dans la corbeille → 403 chez Symfony | code | E12 et E1 : 404 dans board-service et, avec U8-a, dans Symfony |
| FA13 | Notification `member_removed` émise **sans** tableau (titre push « Taskora », URL `/boards`) ; `Notifier` ignore l'acteur et les doublons ; `board_invite` de la consommation d'invitation a pour acteur l'inviteur (peut être nul → « Quelqu'un ») | code | reproduit par `/internal/notifications` (contrat Symfony) |
| FA14 | `CommentAttachments` interroge le stockage (`info(key)` : existence, type, taille) **pendant** la transaction du commentaire ; un objet introuvable est ignoré en silence | code | B-ADR-6 |
| FA15 | Accès : `BoardAccess::member()` → 404 `Tableau introuvable.` / 404 `Ce tableau est dans la corbeille.` (même pour un non-membre, fuite) / 403 non-membre ; `ApiController::find()` lève « dans la corbeille » **avant** le contrôle d'accès ; 37 appels d'accès et 31 appels `find()` dans 8 contrôleurs (dont pièces jointes et fichiers Trello, qui restent dans Symfony) | code | U8-a : périmètre précis de la modification Symfony (ordre de travail §5) |
| FA16 | Purge : boucle de `docker/entrypoint.sh` (5 min après le démarrage, puis toutes les 6 h) sans variable de désactivation | code | T5 : variable `TRASH_PURGE_ENABLED` (Manuel) |

### 1.3 Faits restant à mesurer (base de travail, lecture seule, agrégats)
- **Fichier** : [board-facts.sql](board-facts.sql) (F0–F13). Exécuté à blanc sur le schéma réel : toutes les requêtes passent.
- **Qui (décision U11)** : **Manuel seulement**, et **uniquement avec l'accord explicite de l'utilisateur donné au moment de la lancer** (un accord antérieur ou général ne suffit pas). Aucun autre agent ne l'exécute sur `taskora`.
- **Quand (U11)** : après la démonstration client ; une première fois **avant la répétition RB0 de T1** (§7), une seconde fois **avant la bascule de T2** (positions). Agrégats seulement, session en lecture seule.
- **Comment** : commande en tête du fichier (session `READ ONLY`, compte applicatif, mot de passe lu dans l'environnement du conteneur). Sortie : nombres et noms de colonnes uniquement.
- **Restitution** : recopier les nombres dans le tableau §1.4 (commit `docs(board): faits mesurés`), sans la sortie brute.

### 1.4 Résultats mesurés (à remplir)
Mesure 1 (avant RB0 de T1) et mesure 2 (avant T2) : par Manuel, sur accord explicite de l'utilisateur au moment de chaque exécution (U11). Date et accord consignés ici (date seulement, aucune donnée).
| Mesure | Date | Accord de l'utilisateur consigné |
|---|---|---|
| 1 — avant RB0 de T1 | _à faire_ | _à faire_ |
| 2 — avant T2 | _à faire_ | _à faire_ |

| Réf. | Mesure | Valeur | Attendu / décision liée |
|---|---|---|---|
| F0 | version, `sql_mode`, `time_zone` | _à mesurer_ | identiques à FA5, sinon revoir B-ADR-1 |
| F1, F2 | volumes, tailles max | _à mesurer_ | dimensionne les IT N+1 (B-ADR-8) et SC-TRASH-014 |
| F3, F4 | positions des cartes (doublons, trous, ≠ −1) | _à mesurer_ | « état initial » de `verify` (V1, V2) ; si > 0 : écarts hérités tolérés, jamais augmentés |
| F5 | doublons de positions (listes, checklist, champs) | _à mesurer_ | E25 / E29 : tri `position, id` |
| F6 | `card_seq` en retard / en avance, doublons de numéros | _à mesurer_ | retard attendu = 0 (E14 corrigé) ; > 0 → correction par Symfony avant T2 |
| F7 | invariants V4–V7 | _à mesurer_ | 0 attendu ; sinon état initial |
| F8 | rôles, invitations d'un compte existant | _à mesurer_ | > 0 = invitations non consommées (rattrapage par la consommation) |
| F9 | échéances : heures stockées, minutes rondes | _à mesurer_ | pics décalés de −1 h (hiver) / −2 h (été) par rapport aux heures choisies = E31 confirmé sur les données |
| F10 | collations différentes de `utf8mb4_unicode_ci` | _à mesurer_ | seulement les 4 colonnes JSON en `utf8mb4_bin` |
| F11 | types JSON, non-ASCII brut, `/` non échappé | _à mesurer_ | 0 non-ASCII brut et 0 `/` non échappé = encodage PHP (R5) |
| F12 | corbeille et archives, purge en retard | _à mesurer_ | 0 élément en retard de purge |
| F13 | historique dans l'heure ambiguë d'octobre | _à mesurer_ | > 0 → cas de test SC-BOARD-028 |

## 2. Schéma `board` (Flyway, `services/board-service/src/main/resources/db/migration`)

Flyway ne gère **que** le schéma `board` (`spring.flyway.schemas=board`, `create-schemas=false` : schéma créé par l'init root de Manuel). Il ne touche jamais `taskora` (R1).

`V1__create_outbox_message.sql` (même modèle que l'ADR A11, destinations différentes)
```sql
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
```
`V2__create_scheduler_lock.sql` (T5, B-ADR-9 ; créé dès T0 pour figer le schéma)
```sql
CREATE TABLE scheduler_lock (
  name       VARCHAR(64)  NOT NULL,
  lock_until DATETIME(6)  NOT NULL COMMENT 'UTC',
  locked_at  DATETIME(6)  NOT NULL COMMENT 'UTC',
  locked_by  VARCHAR(255) NOT NULL COMMENT 'nom d''instance, sans donnée personnelle',
  PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```
Pas de clé étrangère entre `board` et `taskora`. Aucune table de domaine dans `board` pendant la phase 3 (U6-A).

### 2.1 Ajout additif côté Symfony (R1, décidé par BackAli)
Une seule migration Doctrine **écrite à la main** (pas de `doctrine:migrations:diff`, R10) : `Version2026112800xxxx` crée `internal_event_receipt (event_id CHAR(36) NOT NULL PRIMARY KEY, type VARCHAR(64) NOT NULL, received_at DATETIME NOT NULL)` pour l'idempotence de `POST /internal/notifications` (et plus tard `/internal/storage/release`). Entité Doctrine correspondante pour que `doctrine:schema:validate` reste vert. Méthode `down()` fournie. Aucune autre modification du schéma `taskora` en phase 3.

## 3. Comptes MariaDB (init SQL idempotent, Manuel, exécuté en root)
Extension de `docker/mariadb/initdb.d/10-taskora-init.sh` (même mécanique que pour `identity` : `REVOKE ALL` puis `GRANT` exacts, droits sur `taskora` accordés par `db-init` une fois les migrations Symfony passées).
```sql
CREATE DATABASE IF NOT EXISTS board CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'board'@'%' IDENTIFIED BY '<BOARD_DB_PASSWORD>';
ALTER USER 'board'@'%' IDENTIFIED BY '<BOARD_DB_PASSWORD>';
REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'board'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON board.* TO 'board'@'%';
-- Tables du tableau (R2) : DML uniquement, jamais de DDL sur taskora.
GRANT SELECT, INSERT, UPDATE, DELETE ON taskora.board              TO 'board'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON taskora.board_list         TO 'board'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON taskora.board_member       TO 'board'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON taskora.invitation         TO 'board'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON taskora.card               TO 'board'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON taskora.card_assignee      TO 'board'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON taskora.card_label         TO 'board'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON taskora.label              TO 'board'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON taskora.custom_field       TO 'board'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON taskora.custom_field_value TO 'board'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON taskora.checklist_item     TO 'board'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON taskora.comment            TO 'board'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON taskora.activity           TO 'board'@'%';
-- Lectures croisées (aucune écriture) ; jamais la colonne password (FA10).
GRANT SELECT (id, email, name, avatar_color, created_at) ON taskora.`user` TO 'board'@'%';
GRANT SELECT ON taskora.attachment         TO 'board'@'%';
GRANT SELECT ON taskora.trello_import_file TO 'board'@'%';
-- T4 seulement (B-ADR-6), ajouté par la variable BOARD_GRANT_COMMENT_FILES=on :
-- GRANT INSERT ON taskora.attachment TO 'board'@'%';
FLUSH PRIVILEGES;
```
- Aucun droit sur `notification`, `push_subscription`, `trello_import_file` en écriture, ni `DELETE` sur `user` ou `attachment` : les cascades InnoDB s'appliquent sans droit (FA10).
- Mot de passe : variable `BOARD_DB_PASSWORD` (jamais affichée) ; valeur factice dans `.env.example`.
- Contrôle (sans hash) : `information_schema.TABLE_PRIVILEGES`, `COLUMN_PRIVILEGES`, `SCHEMA_PRIVILEGES` ; un test d'intégration (`BoardDbPrivilegesIT`) rejoue FA10 et vérifie qu'un `ALTER TABLE taskora.card …` sous le compte `board` est refusé.

## 4. Règles de cohabitation R1–R10 (mise en œuvre)

| # | Règle (spec §4.3) | Mise en œuvre | Vérification |
|---|---|---|---|
| R1 | Symfony seul propriétaire du DDL de `taskora` | compte `board` sans droit DDL sur `taskora` (§3) ; Flyway limité à `board` ; seul ajout : §2.1 | `BoardDbPrivilegesIT` ; IT « schéma `taskora` identique avant et après démarrage de board-service » (empreinte `information_schema.COLUMNS` + `STATISTICS`) ; revue `git diff backend/migrations` |
| R2 | Droits minimaux | §3 | `BoardDbPrivilegesIT` |
| R3 | Identifiants `INT` existants, exposés tels quels | `BoardId`, `ListId`, `CardId`, `UserId`… = `int` ; insertions par `AUTO_INCREMENT` (jamais d'id explicite) | tests miroir |
| R4 | Dates en heure murale `Europe/Paris`, sauf `card.due_date` en UTC (E31) | `LegacyClock` (B-ADR-11) | SC-BOARD-028, SC-CARD-042 |
| R5 | Encodage JSON identique à PHP | `PhpJsonEncoder` (domaine) : `/` → `\/`, non-ASCII → `\uXXXX` (paires de substitution), flottants entiers sans `.0`, `true`/`false`/`null` littéraux | SC-CARD-044, SC-ACTIVITY-008 ; F11 |
| R6 | Numérotation par l'incrément atomique | requête FA7 dans la transaction de création | SC-CARD-002, 003, 032 |
| R7 | board-service seul écrivain de positions après T2 ; verrous par liste en ordre croissant | B-ADR-4 | SC-CARD-033, 034 |
| R8 | Ordre déterministe | §1.2 FA9 ; toutes les requêtes de lecture finissent par `, id` | SC-BOARD-029, SC-LIST-014 |
| R9 | Aucun état mis en cache entre deux requêtes | pas de JPA ni de cache de second niveau (B-ADR-1) ; chaque cas d'utilisation relit en base | revue ; SC-BOARD-023 |
| R10 | Pas de `doctrine:migrations:diff` sur les tables partagées | migration §2.1 écrite à la main ; consigne dans `backend/README` (BackCodeur) | revue BackReviewer |

## 5. Tranches et routage

### 5.1 Contenu
| Tranche | Routes basculées (amont `BOARD_Tn_UPSTREAM`) | Tables écrites | Sorties | Prérequis |
|---|---|---|---|---|
| T0 | aucune | `board.*` | — | U6–U9 décidés (fait) ; contrats publiés (fait) |
| T1 | `GET/POST /api/boards` ; `GET/PATCH/DELETE /api/boards/{id}` ; `POST /api/boards/{id}/favorite` ; `POST /api/boards/{id}/members` ; `PATCH/DELETE /api/boards/{id}/members/{userId}` ; `DELETE /api/boards/{id}/invitations/{invId}` ; `GET /api/trash/boards` ; `POST /api/trash/boards/{id}/restore` ; interne `POST /internal/invitations/claim` (URL d'identity) | `board`, `board_member`, `invitation`, `board_list` et `label` (contenu par défaut), `card_assignee` (désassignation au retrait) | `NotificationRequested` (`board_invite`, `member_joined`, `member_removed`) ; `BoardCreated`, `MemberAdded`, `MemberRemoved`, `MemberRoleChanged`, `BoardTrashed`, `BoardRestored` en `HELD` | T0 vert ; U8-a déployé dans Symfony **avant** la bascule |
| T2 | listes, cartes, archives, corbeille douce, historique (spec §2.1) | `board_list`, `card`, `card_assignee`, `card_label`, `activity` | `card_assigned`, `card_moved`, `card_due` ; miniature synchrone | T1 basculée et stable 48 h |
| T3 | étiquettes, champs, checklists | `label`, `card_label`, `custom_field(_value)`, `checklist_item`, `activity` | — | T2 basculée |
| T4 | commentaires | `comment`, `activity`, `attachment` (INSERT, B-ADR-6) | `card_comment` ; inspection synchrone du stockage | T2 basculée |
| T5 | `DELETE /api/trash/{cards,lists,boards}/{id}` ; purge planifiée | suppressions (cascades) | `StorageKeysReleased` | T2 basculée |

### 5.2 Routage nginx (Manuel)
Une variable par tranche, `BOARD_T1_UPSTREAM` … `BOARD_T5_UPSTREAM` (`api:80` par défaut, `board:8080` après bascule), emplacements par expression régulière déclarés **avant** `location /api/`. Les identifiants sont capturés par `[^/]+` (et non `\d+`) pour que les identifiants non numériques suivent la tranche (E12 : 404 `Ressource introuvable.` une fois basculée).

| Tranche | Expressions (`location ~ …`) |
|---|---|
| T1 | `^/api/boards$` ; `^/api/boards/[^/]+$` ; `^/api/boards/[^/]+/(favorite\|members)$` ; `^/api/boards/[^/]+/members/[^/]+$` ; `^/api/boards/[^/]+/invitations/[^/]+$` ; `^/api/trash/boards$` ; `^/api/trash/boards/[^/]+/restore$` |
| T2 | `^/api/boards/[^/]+/(lists\|lists/reorder\|cards\|archived\|archived-lists\|trash)$` ; `^/api/lists/[^/]+(/(cards\|archive\|restore))?$` ; `^/api/cards/[^/]+$` ; `^/api/cards/[^/]+/(move\|archive\|restore\|assignees\|activity)$` ; `^/api/trash/(cards\|lists)/[^/]+/restore$` |
| T3 | `^/api/boards/[^/]+/(labels\|custom-fields)$` ; `^/api/labels/[^/]+$` ; `^/api/custom-fields/[^/]+$` ; `^/api/cards/[^/]+/custom-fields/[^/]+$` ; `^/api/cards/[^/]+/checklist$` ; `^/api/checklist/[^/]+$` |
| T4 | `^/api/cards/[^/]+/comments$` ; `^/api/comments/[^/]+$` |
| T5 | `^/api/trash/(cards\|lists\|boards)/[^/]+$` |
Restent **toujours** sur Symfony (aucune expression ne doit les capturer, test de routage obligatoire) : `/api/cards/{id}/attachments`, `/api/attachments/**`, `/api/uploads`, `/uploads/**`, `/api/boards/{id}/trello-files/**`, `/api/import/**`, `/api/notifications/**`, `/api/push/**`, `/api/health`, `/api/auth/**`, `/api/me` (identity).
- Commutation : changer la variable dans `.env`, puis `docker compose up -d web` (nouveau conteneur, sans reconstruction, comme `IDENTITY_UPSTREAM`). Les requêtes en cours se terminent sur l'ancien conteneur (arrêt gracieux de nginx).
- Marqueur de routage pour les tests (SC-BOARD-022) : board-service ajoute `X-Taskora-Backend: board` **seulement** si `BOARD_ROUTING_MARKER=on` (CI et recette ; absent en exploitation).
- Avant toute recréation du conteneur `web` : export du journal d'accès (constat N2 de la phase 1).

### 5.3 Consommation des invitations (T1)
identity-service reçoit une nouvelle variable `INVITATION_CLAIM_URL` (défaut : `SYMFONY_INTERNAL_URL`, comportement actuel inchangé). Bascule T1 : `INVITATION_CLAIM_URL=http://board:8080` puis `docker compose up -d identity`. Les deux implémentations de `/internal/invitations/claim` sont équivalentes et idempotentes : un message d'outbox en attente peut être livré à l'une ou à l'autre (SC-BOARD-030). **Secrets par appelant (U12, B-ADR-5 §6)** : identity envoie `INVITATION_CLAIM_SECRET`, qui vaut `INTERNAL_API_SECRET` (accepté par Symfony seulement) avant la bascule et `INTERNAL_SECRET_IDENTITY_TO_BOARD` (accepté par board-service seulement) après ; URL et secret sont commutés **ensemble** (`docker compose up -d identity`).

## 6. Bascule et retour arrière par tranche (U9-a : sans gel)

### 6.1 Bascule (chaque tranche)
Prérequis : critères du §11.1 de la spec, dont la répétition RB0–RB9 (§7) réussie et `verify` vert.
1. **T-24 h** : annonce (aucune coupure attendue).
2. **T-10 min** : board-service déployé, sain, **non routé** pour la tranche ; `java -jar board-service.jar --board.verify=baseline` → rapport « état initial » consigné.
3. **T0** : commutation `BOARD_Tn_UPSTREAM=board:8080` (+ T1 : `INVITATION_CLAIM_URL` **et** `INVITATION_CLAIM_SECRET` d'identity ; + T5 : `TRASH_PURGE_ENABLED=off` côté Symfony et `BOARD_PURGE_ENABLED=on` côté board-service **dans la même opération**).
4. **T0+1** : contrôles à chaud : parcours de la tranche avec un compte de recette, requêtes marquées (routage), `--board.verify=check`.
5. **T0+5 → T+48 h** : surveillance (§9).

### 6.2 Retour arrière (RB, chaque tranche)
1. Commutation inverse de la variable (et des réglages associés de l'étape 3).
2. **Rien à recopier** : tables partagées. board-service reste démarré jusqu'à `PENDING = 0` dans son outbox (destinations Symfony). S'il doit s'arrêter avant : `--board.outbox-drain` (relais seul, sans HTTP public).
3. `--board.verify=check` vert.
4. Avant une nouvelle bascule : refaire 6.1 depuis l'étape 2.

| Tranche | Spécificités du retour arrière |
|---|---|
| T1 | `INVITATION_CLAIM_URL` et `INVITATION_CLAIM_SECRET` d'identity remis à Symfony / `INTERNAL_API_SECRET`. Effet visible : aucun (U8-a aligne déjà Symfony sur les 404). Les jetons restent valides des deux côtés |
| T2 | Les positions restent contiguës (board-service ne laisse jamais d'état intermédiaire) ; Symfony reprend sans verrou (risque d'avant la migration). Échéances : Symfony réaffiche avec son décalage actuel (E31, rien de pire qu'avant) |
| T3 | Valeurs JSON écrites en encodage PHP (R5) : relues à l'identique |
| T4 | Pièces jointes créées par board-service identiques à celles de Symfony (mêmes colonnes) |
| T5 | Purge Symfony réactivée et purge board-service désactivée dans la même opération (SC-TRASH-012) ; messages `StorageKeysReleased` en attente livrés à Symfony |

## 7. Répétition en recette (RB0–RB9, critère de sortie de chaque tranche)
Stack **neuve** `-p taskora-rehearsal` (ports libres sur 127.0.0.1, fichier de secrets éphémère hors dépôt, jamais affiché), données **synthétiques** `@exemple.test` remplies par l'API Symfony (jamais une copie de `taskora`), `down -v` à la fin. Ne jamais viser `taskora` ni `taskora-e2e`.

| # | Étape | Qui | Attendu (T1) |
|---|---|---|---|
| RB0 | Stack neuve + jeu de référence (3 tableaux dont un dans la corbeille, 4 rôles, invitations dont une sur un tableau dans la corbeille, listes ouvertes/archivées/corbeille, fichiers Trello en attente) | Manuel (stack), BackAli (données via l'API) | stack saine |
| RB1 | `--board.verify=baseline` | BackAli | rapport consigné |
| RB2 | Bascule T1 (+ `INVITATION_CLAIM_URL`) | Manuel | table de routage conforme (marqueur) ; T2–T5 encore sur Symfony |
| RB3 | e2e `@board` T1 via board-service, avec écritures (création, membres, invitation puis inscription d'un invité via identity, favori, corbeille, restauration) | FrontTesteur (suite), BackAli (exécution) | verts |
| RB4 | Retour arrière | Manuel | commutation ≤ 5 s ; outbox `PENDING = 0`, `FAILED = 0` |
| RB5 | Via Symfony : lecture **et** écriture des tableaux créés en RB3 (carte créée dans un tableau neuf : numéro 1) ; inscription d'un invité (claim servi par Symfony) | BackAli | verts |
| RB6 | `--board.verify=check` | BackAli | vert, aucun écart nouveau |
| RB7 | Re-bascule, e2e `@board` T1, lecture des écritures de RB5 | Manuel, BackAli | verts |
| RB8 | Journaux `board`, `api`, `web`, `identity` | BackAli | 0 jeton, secret, e-mail en `INFO` ; 0 × 5xx (L5) |
| RB9 | Nettoyage | Manuel | aucune ressource `taskora-rehearsal` |
Compte rendu : `docs/reviews/<date>-board-t1-rollback-rehearsal.md` (nombres uniquement).

## 8. Commande `verify` (lecture seule, rapport chiffré)
`java -jar board-service.jar --board.verify=baseline|check` (cas d'utilisation `VerifyBoardInvariantsUseCase`, adapter `adapter.in.cli`). Aucune écriture en base ; `baseline` écrit l'état initial dans un fichier JSON de nombres (`--board.verify.baseline-file`, monté hors image) ; `check` compare à ce fichier. Code de sortie : 0 = vert ; 2 = invariant violé ou écart en hausse ; 1 = erreur technique.

| # | Invariant | Forme SQL (identique à board-facts.sql) |
|---|---|---|
| V1 | positions des cartes ouvertes `0..n-1` par liste | F3 `lists_not_contiguous` |
| V2 | archivées / corbeille à −1 | F4 |
| V3 | `card_seq ≥ MAX(number)`, pas de doublon | F6 `boards_seq_behind`, `duplicate_numbers` |
| V4 | un seul `owner`, égal à `board.owner_id` | F7 |
| V5 | aucun assigné non membre | F7 `assignees_not_members` |
| V6 | étiquettes, valeurs, couverture du bon tableau / de la bonne carte | F7 |
| V7 | `card.board_id` = tableau de la liste | F7 `card_board_mismatch` |
| V8 | outbox : `PENDING` de plus d'une minute, `FAILED` | `SELECT status, COUNT(*) FROM board.outbox_message …` |
Le rapport n'affiche que des nombres. Les écarts hérités (état initial) sont tolérés tant qu'ils n'augmentent pas ; V3, V4, V5, V7 doivent être à 0 avant la bascule de T2.

## 9. Surveillance (bascule → +48 h, puis continue)
| Signal | Source | Seuil d'alerte | Réaction |
|---|---|---|---|
| 5xx de la tranche | journal nginx (amont `board`) | ≥ 1 | analyse ; retour arrière si répété |
| 404 / 403 inhabituels | journal nginx, comparaison à la semaine précédente | × 2 | vérifier la politique d'accès (§5.3 de la spec) |
| Outbox `PENDING` > 1 min, `FAILED` | métriques `board_outbox_pending_oldest_seconds`, `board_outbox_failed_total` (actuator, port de gestion) | > 60 s ; ≥ 1 | Symfony joignable ? secret ? |
| Interblocages rejoués / abandonnés | `board_tx_retry_total`, `board_tx_retry_exhausted_total` | abandonné ≥ 1 | analyse des verrous (B-ADR-4) |
| `verify` | lancé à T+1 h, T+24 h, T+48 h | code ≠ 0 | retour arrière |
| Latence p95 | journal nginx | suivi seulement (U5) | — |
Les journaux ne contiennent jamais de jeton, secret, mot de passe, contenu de commentaire ou de description ; les e-mails ne sont pas journalisés en `INFO` (SC-BOARD-026).

## 10. Fin de la cohabitation (hors phase 3)
Après attachment-, notification- et import-service : lectures croisées remplacées, clés étrangères inter-domaines retirées, tables déplacées dans `board` (`RENAME TABLE`), projection `taskora.user` (A9) et endpoints internes Symfony supprimés.
