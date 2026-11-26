# Reprise des données et bascule d'identity-service

Responsable : BackAli. Décisions appliquées : U1-B, U2-a, U3, U4 ([decisions.md](../specs/decisions.md)) ; ADR A3, A7, A9, A11, A14 ([backend-hexagonal.md](backend-hexagonal.md) §9).
Faits de départ (§8) : 3 comptes, tous `$2y$` coût 13, `MAX(id) = 3`, collation `utf8mb4_unicode_ci`, `created_at` en heure de Paris, 15 clés étrangères vers `taskora.user.id`.

## 1. Schéma `identity` (Flyway, `services/identity-service/src/main/resources/db/migration`)

Flyway ne gère **que** le schéma `identity` (`spring.flyway.schemas=identity`, `spring.flyway.create-schemas=false` : le schéma est créé par l'init SQL de Manuel). Il ne touche jamais `taskora`.

`V1__create_user_account.sql`
```sql
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
```

`V2__create_outbox_message.sql`
```sql
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
```
- `password_hash` en `utf8mb4_bin` : le hash est comparé octet par octet ; la longueur 255 de Symfony est conservée.
- Pas de clé étrangère entre `identity` et `taskora`.

## 2. Comptes MariaDB (init SQL idempotent, Manuel, exécuté en root)
```sql
CREATE DATABASE IF NOT EXISTS identity CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'identity'@'%' IDENTIFIED BY '<IDENTITY_DB_PASSWORD>';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON identity.* TO 'identity'@'%';
GRANT SELECT, INSERT, UPDATE ON taskora.`user` TO 'identity'@'%';   -- projection U1-B + lecture pour la reprise
```
`DELETE` n'est **jamais** accordé sur `taskora.user` (cascades). La valeur du mot de passe vient d'une variable d'environnement.

## 3. Copie idempotente (`ImportLegacyUsersUseCase`)

Lancement : `docker compose run --rm identity --identity.legacy-import=run` (ou `verify` pour les contrôles seuls).

Contrôles **bloquants** avant toute écriture (ADR A18) : 0 doublon d'e-mail normalisé dans la source, et 0 ligne source dont l'e-mail normalisé existe déjà dans la cible sous un **autre id** :
```sql
SELECT COUNT(*) FROM taskora.`user` s
JOIN identity.user_account t ON t.email = LOWER(TRIM(s.email)) AND t.id <> s.id;
```
Si l'un des deux est > 0 : échec (code ≠ 0), rien n'est écrit, seuls des nombres sont affichés. L'`ON DUPLICATE KEY UPDATE` ci-dessous ne peut alors se déclencher que sur la clé primaire.

Puis une seule transaction :
```sql
INSERT INTO identity.user_account (id, email, name, password_hash, avatar_color, roles, created_at)
SELECT u.id, LOWER(TRIM(u.email)), u.name, u.password, u.avatar_color, u.roles, u.created_at
FROM taskora.`user` u
ON DUPLICATE KEY UPDATE
  email = VALUES(email), name = VALUES(name), password_hash = VALUES(password_hash),
  avatar_color = VALUES(avatar_color), roles = VALUES(roles);
```
- **Pas d'`ALTER TABLE … AUTO_INCREMENT`** (révisé après la revue 1) : InnoDB/MariaDB avance le compteur au-delà de tout id inséré explicitement, et un `ALTER` validerait implicitement la transaction en cours. `verify` contrôle `AUTO_INCREMENT ≥ MAX(id)+1` (`autoIncrementAboveMaxId`).
- **Hashes copiés tels quels** (aucun retraitement) ; `created_at` copié sans conversion de fuseau.
- `LOWER(TRIM())` est un no-op sur les données actuelles (0 e-mail non normalisé) ; si une normalisation produit un doublon, la commande échoue **avant** toute écriture (contrôle préalable), sans afficher d'e-mail.
- Ré-exécutable : la deuxième exécution ne change rien (`rows affected = 0`), test d'intégration obligatoire.
- Jamais de `DELETE` : un compte absent de la source mais présent dans `identity` est **signalé** (compte) et conservé.

## 4. Contrôles avant / après (rapport sans donnée personnelle)

La commande `verify` calcule la même série sur la source (`taskora.user`) et la cible (`identity.user_account`), et sort en erreur (code ≠ 0) à la moindre différence :

| Contrôle | Requête (forme) |
|---|---|
| Nombre de lignes | `COUNT(*)` |
| Identifiant maximal | `MAX(id)` |
| Doublons d'e-mail normalisé | `COUNT(*)` des groupes `LOWER(TRIM(email))` ayant plus d'une ligne → 0 |
| Répartition des hashes | `LEFT(hash, 4)` (ou `$argon2id$`), coût `SUBSTRING(hash,5,2)`, `COUNT(*)` → identique |
| Empreinte | `SUM(CRC32(CONCAT_WS('|', id, LOWER(TRIM(email)), password, name, avatar_color, roles, created_at)))` et `BIT_XOR(CRC32(...))` → identiques |
| Ids | ensemble des ids identique (`COUNT` de la différence symétrique = 0) |
| Auto-incrément | `identity` ≥ `MAX(id)+1` |

Le rapport n'affiche que des nombres et des empreintes. Il est conservé dans le journal de la bascule.

## 5. Bascule (fenêtre annoncée < 5 min, U4)

Prérequis : tests critiques verts, rapport BackReviewer sans bloquant, **retour arrière répété en recette** (§6), sauvegarde `mariadb-dump` de `taskora.user` (fichier hors dépôt).

Conditions préalables à la bascule (revue 2, toutes obligatoires ; aucune n'est remplie par un run local) :
| Réf. | Condition | Responsable | Dépend de | Statut (recette BackAli, 26/11/2026) |
|---|---|---|---|---|
| **C1** | Run CI `identity-security` (OWASP dependency-check) vert : aucune dépendance CVSS ≥ 7, résumé dans `docs/ci/requests/backend.md` | Manuel | push approuvé par l'utilisateur | **remplie** : `identity-security` vert sur la PR #1 (fusion `86b3b07`), aucune dépendance CVSS ≥ 7 ; résumé chiffré encore à reporter dans `docs/ci/requests/backend.md` (Manuel) |
| **C2** | Runs CI `identity-service` (tests, qualité, image) et `identity-compat` verts ; `identity-compat` prouve les tests miroir SC-IDENT-001…018 et croisés SC-IDENT-010/023, jamais exécutés en local | Manuel | push approuvé par l'utilisateur | **remplie** : `identity-service` (build, tests, qualité, image) et `identity-compat` (32/32, dont `MirrorCompatibilityTest`) verts sur la PR #1 ; job Sonar sauté (voir C4) |
| **C3** | Passphrase JWT changée partout où elle a servi (elle figure dans l'historique git, revue 1 m11) ; nouvelle paire de clés générée, jetons existants invalidés (annonce aux utilisateurs) | utilisateur | — | **remplie en local (26/11/2026)** : nouvelle passphrase dans `.env` et `backend/.env`, paire régénérée (`--overwrite`), ancien jeton refusé (401), nouvelle connexion acceptée (200). À refaire sur tout autre environnement où l'ancienne passphrase a servi. |
| **C4** | Quality gate Sonar vert sur le serveur de CI, sur la référence de CI (exclusions limitées à A16 `e1`–`e3`) | Manuel, BackReviewer | push approuvé par l'utilisateur | **en attente de l'utilisateur** : variable `SONAR_HOST_URL` et secret `SONAR_TOKEN` à créer sur GitHub (job `identity-sonar` sauté sur la PR #1) |
La bascule (étape 1 ci-dessous) n'est pas annoncée tant que C1 à C4 ne sont pas consignées comme remplies. Détail et preuves : [recette BackAli](../reviews/2026-11-26-identity-service-recette.md).
1. **T-24 h** : annonce de la coupure des écritures d'identité (inscription, profil).
2. **T-10 min** : `identity` déployé mais **non routé** ; `verify` lancé à blanc (la cible peut être vide ou ancienne).
3. **T0 — gel** : nginx renvoie `503 {"error":"Maintenance en cours, réessayez dans quelques minutes."}` sur `POST /api/auth/register` et `PATCH /api/me` uniquement (login et lectures restent servis par Symfony, ils n'écrivent pas dans `user`).
4. **T0+1** : `--identity.legacy-import=run`, puis `verify` → doit être vert. Rouge → abandon (étape 6.1), dégel.
5. **T0+2** : bascule nginx : `location /api/auth/` et `location = /api/me` → `identity:8080` (variable d'amont, rechargement `nginx -s reload`, sans reconstruction). Le gel est levé du même coup.
6. **T0+3 — contrôles à chaud** : login d'un compte de recette, `GET /api/me` avec un jeton émis par Symfony avant la bascule, `GET /api/boards` (Symfony) avec un jeton émis par identity, inscription d'un compte de recette invité sur un tableau de recette (adhésion visible), `verify` (projection : la source `taskora.user` et la cible restent identiques).
7. **T0+5** : fin de fenêtre ; surveillance renforcée 24 h (taux de 401, 5xx, messages d'outbox `PENDING` > 1 min).

## 6. Retour arrière (répété en recette avant la bascule réelle)

### 6.1 Retour pendant la fenêtre (avant l'étape 5)
Dégel nginx, rien d'autre : Symfony n'a jamais cessé d'être l'écrivain.

### 6.2 Retour après la bascule
1. nginx : amont de `/api/auth/` et `/api/me` → `api:80` (rechargement).
2. Rien à recopier : grâce à la projection dans la même transaction, `taskora.user` contient tous les comptes et changements faits par identity (ids explicites, hashes `$2y$` coût 13 lisibles par PHP).
3. Messages d'outbox `PENDING` vers `symfony-invitations` : le relais continue de tourner (identity reste démarré) jusqu'à ce qu'ils soient livrés, puis identity peut être arrêté. Si identity est arrêté avant : relancer le relais seul (`--identity.outbox-drain`) ou consommer à la main via l'endpoint interne (idempotent).
4. Les jetons émis par identity restent valides pour Symfony (même clé, mêmes revendications).
5. Avant une nouvelle bascule : refaire §5 depuis l'étape 3 (la copie idempotente rapatrie les écritures faites par Symfony pendant le retour arrière).

### 6.3 Répétition en recette (critère de sortie, Paul)
Sur une copie de la base : bascule → inscription + changement de mot de passe via identity → retour arrière → login avec le nouveau mot de passe **via Symfony** → nouvelle inscription via Symfony → re-bascule → `verify` vert → login des deux comptes via identity. Résultat consigné dans `docs/reviews/`.

**Statut (répétition R0–R10 du 26/11/2026, stack `taskora-rehearsal`, `main` `934dd2c`) : ÉCHOUÉE**, sur la seule étape R8 (performance). Détail : [compte rendu](../reviews/2026-11-26-identity-rollback-rehearsal.md).
- **Conforme** :
  - deux bascules (16 s et 14 s, gel 503 sur les deux écritures, lectures servies), avec `verify` vert à chaque fois ;
  - retour arrière : routage en 1 s, outbox vidée (`PENDING` = 0 en 38 s, 0 `FAILED`) ;
  - mot de passe changé via identity, accepté par Symfony ; ancien mot de passe refusé ; jetons acceptés dans les deux sens ;
  - inscription via Symfony rapatriée, login des deux comptes via identity ;
  - non-régression board/notification/push ;
  - e2e `@identity` 14/14 sur les deux amonts ;
  - comptages R0 + 2 ; 0 fuite et 0 × 5xx dans les journaux.
- **Non conforme** : `POST /api/auth/login` via identity a un p95 de 641,6 et 643,5 ms, pour un seuil < 500 ms. Symfony est à 498,3 ms sur le même hôte. Renvoi à BackAli (B1) ; seuil à confirmer par Paul.
- Écart : la base de recette a été remplie par l'API avec des comptes synthétiques au lieu d'une copie de la base de travail, qui contient des données personnelles.
- Nouvelle répétition requise après le traitement de B1, au minimum R7–R9.

## 7. Jeu d'essai versionné (tests)
`services/identity-service/src/test/resources/fixtures/legacy-users.sql` : comptes **fictifs** (`@exemple.test`), mots de passe de test documentés dans le fichier, hashes générés **une fois** par PHP dans le conteneur `api` (`password_hash`, `sodium_crypto_pwhash_str`) : `$2y$` coût 13, `$2y$` coût 4, `$2a$`, `$2b$`, `$argon2id$`, `$argon2i$`, mot de passe de 100 octets (pré-hachage), mot de passe contenant un caractère accentué, hash tronqué (corrompu). Aucune donnée réelle.
