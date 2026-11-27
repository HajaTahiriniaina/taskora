# Phase 1 : migration d'identity-service — plan d'exécution

Auteur : Paul. Statut : **proposé**, en attente des décisions marquées « DÉCISION UTILISATEUR ».
Spécification fonctionnelle : [identity.md](identity.md). Architecture : `docs/architecture/backend-hexagonal.md`. Circuit : `docs/team/workflow.md`.
Branche proposée : `feat/phase1-identity-service`.

## 1. Objectif et périmètre

Remplacer, pour les routes d'identité, le backend Symfony par `services/identity-service` (Spring Boot 4.2, Java 25, hexagonal), **sans changement visible** pour le front et sans interrompre les autres routes, qui restent servies par Symfony.

| Dans le périmètre | Hors périmètre (reste dans Symfony en phase 1) |
|---|---|
| `POST /api/auth/register`, `POST /api/auth/login`, `GET /api/me`, `PATCH /api/me` | Toutes les autres routes `/api/**` et `/uploads/**` |
| Émission et validation des JWT | `GET /api/health` (reste dans Symfony ; identity expose `/actuator/health`) |
| Reprise des comptes de la table `user` | Commandes `app:seed-demo`, `app:import-trello` (lisent les utilisateurs existants) |
| Consommation des invitations à l'inscription (via un adaptateur transitoire) | Gateway (dernière étape de la migration) |

### Scénarios couverts
| Priorité | Scénarios |
|---|---|
| critique (14) | SC-IDENT-001, 002, 003, 005, 007, 008, 010, 011, 012, 014, 017, 018, 023, 024 |
| important (8) | SC-IDENT-004, 009, 013, 015, 019, 020, 025, 026 |
| secondaire (5) | SC-IDENT-006, 016, 021, 022, 027 (027 seulement si la limitation de débit est décidée) |
Scénarios d'autres domaines à rejouer en non-régression après la bascule (jetons émis par identity, lus par Symfony) : SC-BOARD-002, SC-BOARD-009, SC-BOARD-011, SC-NOTIF-001, SC-PUSH-008.

Critère de sortie de la phase : les 14 scénarios critiques `validé` dans [traceability.md](traceability.md), tests de compatibilité (« miroir ») verts contre Symfony **et** identity-service, rapport BackReviewer sans point bloquant, retour arrière testé.

## 2. Décisions à prendre avant le premier test

### 2.1 À trancher par l'utilisateur
| # | Question | Options | Recommandation de Paul |
|---|---|---|---|
| **U1** | Propriété des données utilisateur pendant la coexistence | **A.** identity-service écrit directement dans la table existante `taskora.user` (aucune copie, mais viole « une base par service » jusqu'à la phase board) ; **B.** schéma propre `identity` + reprise des données + projection transactionnelle vers `taskora.user` (adaptateur transitoire) que Symfony continue de lire | **B** : conforme à l'architecture, et garde la table historique à jour, ce qui rend le **retour arrière sans perte** |
| **U2** | Consommation des invitations à l'inscription (aujourd'hui dans la même transaction) | **a.** appel interne synchrone à un nouvel endpoint Symfony (`POST /internal/invitations/claim`, réseau Docker interne, secret partagé), rejoué via l'outbox en cas d'échec ; **b.** événement `UserRegistered` sur RabbitMQ + consommateur Symfony Messenger transitoire ; **c.** écriture directe dans `invitation`/`board_member` (rejetée : viole la propriété des données) | **a** + publication de `UserRegistered` dans l'outbox (pour board-service plus tard) : garde le comportement visible de SC-IDENT-005 (tableaux visibles dès l'arrivée sur `/boards`) sans ajouter RabbitMQ dès la phase 1 |
| **U3** | Écarts à corriger dès la phase 1 | E9 limitation des tentatives de connexion (429) ; E10 révocation des jetons au changement de mot de passe | Ne rien changer en phase 1 (compatibilité stricte) ; traiter E9 avec la gateway |
| **U4** | Fenêtre de bascule | courte coupure des écritures (inscription, profil) pendant la reprise finale vs reprise à chaud avec rattrapage | Coupure de moins de 5 minutes, annoncée (volume de comptes faible) |

### 2.2 À trancher par BackAli (ADR dans `docs/architecture/backend-hexagonal.md`)
| # | Sujet | Attendu |
|---|---|---|
| A1 | **Signature JWT en coexistence** | Même paire de clés que Symfony : clé privée PEM chiffrée du volume `jwt_keys` montée **en lecture seule**, phrase secrète par variable d'environnement (`JWT_PASSPHRASE`, jamais commitée). Vérifier l'algorithme réel d'après le type de clé (sans afficher la clé). Revendications **identiques** à Lexik : `username` (= e-mail), `roles`, `iat`, `exp = iat + 604800` ; ajout autorisé de `uid` (identifiant numérique, utile aux futurs services) ; pas de `kid` exigé à la validation. Symfony doit accepter les jetons d'identity et inversement (SC-IDENT-010). Plus tard : JWKS interne pour la gateway et les services. |
| A2 | **Hachage** | Encodeur délégant qui reconnaît le préfixe du hash : `$2y$`/`$2a$`/`$2b$` → BCrypt, `$argon2id$` → Argon2 ; nouveaux hashes en **bcrypt `$2y$` coût 13** (vérifiables par PHP `password_verify` en cas de retour arrière) ; hash inconnu → échec d'authentification (jamais 500) ; pas de re-hachage à la connexion en phase 1. Compter d'abord la répartition des préfixes en base (agrégat, sans afficher les hashes). |
| A3 | **Identifiants** | Conserver les `INT` existants : id identity = id historique ; la séquence reprend à `MAX(id) + 1` ; la projection (U1-B) insère avec l'id explicite. |
| A4 | **Format des erreurs** | `{"error": "…"}` pour les erreurs métier (messages exacts d'identity.md) ; `{"code":401,"message":"…"}` pour les erreurs d'authentification, à l'identique de Lexik (confirmé par test miroir). Un `@ControllerAdvice` unique ; jamais de trace en 5xx. |
| A5 | **Spring Data REST** | Aucun repository exporté dans identity-service (pas de collection d'utilisateurs : risque d'énumération). Lecture `/api/me` par contrôleur et cas d'utilisation. |
| A6 | **Modèle hexagonal** | `domain` : `User`, `Email` (normalisation), `PersonName`, `AvatarColor`, `PasswordPolicy`, `HashedPassword` ; `application/port/in` : `RegisterUserUseCase`, `AuthenticateUseCase`, `GetProfileUseCase`, `UpdateProfileUseCase` ; `port/out` : `UserRepositoryPort`, `PasswordHasherPort`, `TokenIssuerPort`, `InvitationClaimPort`, `EventPublisherPort` (outbox), `LegacyUserProjectionPort` (si U1-B) ; adapters `in/web`, `out/persistence`, `out/security` (JWT, hachage), `out/legacy` (projection, appel Symfony). |
| A7 | **Concurrence et unicité** | Contrainte unique sur l'e-mail normalisé ; violation traduite en 409 (SC-IDENT-002). |
| A8 | **Mise à jour atomique du profil** | Validation complète avant écriture (SC-IDENT-019). |

### 2.3 Contrat OpenAPI attendu : `docs/contracts/openapi/identity-service.yaml`
Publié par BackAli **avant** les tests ; OpenAPI 3.1.
- `servers` : chemin `/api` (routes identiques au contrat actuel).
- Chemins : `POST /auth/register`, `POST /auth/login`, `GET /me`, `PATCH /me` avec, pour chaque réponse, le statut et un **exemple par message d'erreur** d'identity.md § 2.
- Schémas : `User {id:int64, name, email, avatarColor}`, `RegisterRequest`, `LoginRequest`, `UpdateMeRequest` (tous champs optionnels), `AuthResponse {token, user}`, `Error {error}`, `AuthError {code, message}`.
- `securitySchemes.bearerAuth` (`http`, `bearer`, `bearerFormat: JWT`) et description des revendications.
- Extension `x-scenarios` sur chaque opération (ex. `[SC-IDENT-001, SC-IDENT-002]`).
- Contrats internes à part (`identity-service.internal.yaml` ou section balisée) : endpoint Symfony transitoire de consommation des invitations (U2-a), événements `UserRegistered {userId, email, name, avatarColor, occurredAt, eventId}` et `UserProfileUpdated` (schéma JSON), pour board-service et notification-service.

### 2.4 Reprise des données de la table `user`
Source : `user (id INT, email VARCHAR(180) UNIQUE, name VARCHAR(120), password VARCHAR(255), avatar_color VARCHAR(20), roles JSON, created_at DATETIME)` (migration `Version20261125070204`).
1. **Schéma cible** (Flyway `V1__create_user_account.sql` dans identity-service) : mêmes colonnes et longueurs, e-mail unique, `password_hash` VARCHAR(255) inchangé.
2. **Script de reprise** idempotent (`V2__import_legacy_users.sql` ou commande dédiée, au choix de BackAli) : copie de tous les champs **sans retraitement des hashes** ; `email` en `LOWER(TRIM())` ; reprise de l'auto-incrément.
3. **Contrôles avant / après** (rapport sans données personnelles) : nombre de lignes égal ; aucun doublon d'e-mail après normalisation ; répartition des préfixes de hash identique ; somme de contrôle sur `(id, email, password)` identique ; `MAX(id)` identique.
4. **Jeu d'essai** versionné pour les tests : comptes de test générés une seule fois avec des mots de passe de test (hashes `$2y$` coût 13 et 4, `$2a$`, `$2b$`, `$argon2id$`) ; aucun compte réel, aucun identifiant de démonstration.
5. **Retour arrière** : la route nginx revient vers Symfony ; avec U1-B la table `taskora.user` est à jour (projection) et les hashes `$2y$` sont lisibles par PHP : aucune perte. Le retour arrière est **répété en recette** avant la bascule.

## 3. Ordre de travail (Testeur → Codeur → Reviewer)

L'agent appelant (fil principal) exécute ces étapes dans l'ordre. Chaque étape reçoit : `docs/specs/identity.md`, `docs/specs/phase-1-identity.md`, `docs/contracts/openapi/identity-service.yaml`, et le commit des tests de référence.

### Étape 0 — BackAli (cadrage, sans code)
1. Intégrer les décisions U1–U4 de l'utilisateur ; rédiger les ADR A1–A8.
2. Publier `docs/contracts/openapi/identity-service.yaml` (§ 2.3).
3. Rédiger `docs/ci/requests/backend.md` (section identity, § 5).
4. Rendre l'ordre de travail au format de `backali.md`.

### Étape 1 — BackTesteur (tests rouges, commit `test(identity): …`)
Emplacement : `services/identity-service/src/test/java/com/taskora/identity/**` ; chaque test nommé d'après son scénario.
| Lot | Contenu | Scénarios |
|---|---|---|
| Architecture | ArchUnit : 5 règles de l'architecture § 3 | — (exigence d'architecture) |
| Domaine (unitaire) | normalisation de l'e-mail et du nom, troncatures, politique de mot de passe (8 caractères Unicode), couleur d'avatar, mise à jour atomique du profil | 001, 003, 004, 015, 016, 018, 019, 020 |
| Hachage (unitaire) | encodeur délégant sur le jeu d'essai, hash inconnu → refus | 023 |
| JWT (unitaire + intégration) | revendications, durée, algorithme ; **validation croisée** : un jeton produit par Symfony avec la paire de clés de test est accepté, et un jeton d'identity est conforme au format Lexik | 010, 012 |
| API (MockMvc + validation OpenAPI) | toutes les routes, tous les statuts et messages ; corps de 401 identiques | 001–004, 007–009, 011, 014–020 |
| Sécurité | sans jeton, jeton expiré / altéré / `alg:none` / HS256 avec clé publique / autre clé ; compte disparu ; absence de secrets dans réponses et journaux | 011, 012, 013, 024 |
| Intégration (Testcontainers MariaDB) | persistance, unicité sous concurrence, script de reprise sur un schéma historique peuplé, projection `taskora.user` (U1-B), adaptateur d'invitations (WireMock simulant Symfony, rejeu outbox) | 002, 005, 006, 023 |
| Compatibilité (tag `compat`) | mêmes requêtes contre Symfony et identity-service sur la stack compose ; comparaison statut + corps | 001–003, 007–009, 014–018 |
| Symfony transitoire (si U2-a) | test fonctionnel PHPUnit de l'endpoint interne de consommation des invitations (idempotent, refusé sans secret) | 005 |
Tous les tests doivent être **rouges** au commit (sauf ceux qui ciblent Symfony en mode `compat`, verts d'emblée).

### Étape 2 — BackCodeur (commit `feat(identity): …`, sans toucher aux tests)
1. `services/pom.xml` (parent) et module `services/identity-service` ; dépendances : Spring Web MVC, Security (resource server), Data JPA, Flyway, MariaDB, validation, Actuator, bibliothèque JWT (Nimbus), Argon2 (BouncyCastle).
2. Domaine et cas d'utilisation (A6), adapters web, persistance, sécurité (A1, A2), outbox, projection historique (U1-B), client de l'endpoint Symfony (U2-a).
3. Côté Symfony (si U2-a) : endpoint interne de consommation des invitations, accessible uniquement sur le réseau interne avec un secret partagé (variable d'environnement), logique reprise de `AuthController::register`.
4. Migrations Flyway et script de reprise (§ 2.4).
5. Dockerfile du service (fourni à Manuel ou écrit selon sa demande).
6. Si un test paraît faux : le signaler dans le rapport, ne pas le modifier.

### Étape 3 — BackReviewer (rapport `docs/reviews/<AAAA-MM-JJ>-identity-service.md`)
- Contrôles outillés : Spotless, Checkstyle (Google), Checker Framework (Nullness), OpenRewrite `rewrite:dryRun`, SonarQube, OWASP Dependency-Check.
- Intégrité : `git diff <commit test>..HEAD -- services/identity-service/src/test` vide ; aucun `@Disabled`, assertion retirée ou seuil abaissé.
- Revue sécurité ciblée : gestion de la clé privée, comparaison à temps constant, absence de mot de passe / hash / jeton dans les journaux, CORS, en-têtes.
- Tout point bloquant renvoie à l'étape 2 (3 boucles maximum, puis remontée à Paul).

### Étape 4 — Recette BackAli, puis validation Paul
- BackAli : tests verts, rapport sans bloquant, intégrité des tests, scénarios couverts.
- Paul : vérifie les quatre conditions de validation (paul.md), met à jour `traceability.md`, rejoue les scénarios de non-régression des autres domaines, répète le retour arrière.

### Parallélisable
- Dès la publication du contrat (étape 0) : FrontTesteur (tests e2e d'identité, § 4) et Manuel (Docker / CI, § 5) travaillent en parallèle des étapes 1–2.

## 4. Impact front (FrontPierre)

Aucun changement fonctionnel attendu : mêmes routes, mêmes corps, même stockage du jeton.
1. **Tests e2e d'identité** (FrontTesteur, `frontend/e2e/identity.spec.ts`) : SC-IDENT-001, 002, 003, 007, 008, 017, 018, 025, 026, exécutables **contre les deux backends** (variable d'URL), servant de non-régression à la bascule.
2. **Tests de composants** : intercepteur (401 → déconnexion, aucun toast sur `/auth/`), gardes, `AuthService.isExpired` avec un jeton émis par identity-service (même `exp`).
3. **Proxy de développement** (`frontend/proxy.conf.mjs`, cible lue depuis `API_BIND`/`API_PORT` du `.env`) : ajouter des entrées `/api/auth` et `/api/me` vers identity-service (`IDENTITY_BIND`/`IDENTITY_PORT`), avant l’entrée `/api`.
4. **Robustesse** : l'écran de connexion se fonde sur le statut (401) et non sur le corps : à conserver. Si la limitation de débit (U3/E9) est décidée, ajouter le cas 429 (« Trop de tentatives, réessayez dans quelques minutes. ») au formulaire de connexion.
5. **Accessibilité** (non bloquant) : `role="alert"` sur les messages d'erreur des formulaires d'authentification.
6. Toute demande d'endpoint passe par `docs/contracts/requests/<NNN>-<sujet>.md`.

## 5. Besoins CI et Docker (Manuel)

À confirmer par la demande de BackAli dans `docs/ci/requests/backend.md`.

### Docker (`docker-compose.yml`, sans casser la stack actuelle)
1. Service `identity` : image multi-stage (`maven:3-eclipse-temurin-25` → `eclipse-temurin:25-jre`), utilisateur non root, healthcheck `/actuator/health`, port non exposé publiquement (accès par nginx).
2. **Clés JWT** : montage **en lecture seule** du volume `jwt_keys` (généré aujourd'hui par le conteneur `api`) ; `JWT_PASSPHRASE` transmise par la même variable que Symfony. Ordre de démarrage : `identity` attend que les clés existent (dépendance sur `api` sain, ou tâche d'initialisation des clés dédiée).
3. **Base** : schéma et utilisateur MariaDB dédiés à identity (droits limités à son schéma ; plus, si U1-B, `SELECT/INSERT/UPDATE` sur `taskora.user` uniquement), créés par un script d'initialisation idempotent.
4. **Routage nginx** (`frontend/nginx.conf`) : `location /api/auth/` et `location = /api/me` vers `identity`, le reste inchangé ; amont paramétrable par variable d'environnement pour **revenir à Symfony sans reconstruire** (retour arrière).
5. Secret partagé de l'endpoint interne Symfony (U2-a) : variable d'environnement, valeur factice dans `.env.example`.
6. Symfony reste opérationnel (profil `legacy` ou service `api` inchangé).
7. RabbitMQ : non requis en phase 1 si U2-a ; requis si U2-b.

### GitHub Actions
| Job | Contenu | Déclencheur |
|---|---|---|
| `identity-service` | `mvn -pl identity-service -am verify` : unitaires, ArchUnit, intégration Testcontainers (Docker sur le runner), Spotless check, Checkstyle, Checker Framework ; rapports JUnit et couverture en artefacts | PR touchant `services/**` |
| `identity-security` | OWASP Dependency-Check (cache de la base NVD), échec sur CVSS ≥ 7 | PR + hebdomadaire |
| `identity-compat` | stack compose complète (Symfony + identity + MariaDB + nginx), exécution des tests `@Tag("compat")` contre les deux backends | PR touchant `services/identity-service/**` ou `backend/**` |
| `frontend-e2e-identity` | Playwright `identity.spec.ts` sur la stack compose | PR touchant `frontend/**` ou `services/identity-service/**` |
| `sonar` | analyse SonarQube (profil `quality`) | PR (si jeton disponible) |
- Paire de clés JWT de **test** générée à l'exécution du job (jamais commitée) ; aucun secret réel en CI.
- `permissions:` minimales, actions épinglées par SHA, cache Maven.

## 6. Risques et points d'attention
| Risque | Parade |
|---|---|
| Revendications JWT différentes → Symfony refuse les jetons d'identity (tout le front cassé) | Test croisé SC-IDENT-010 dans `identity-compat`, bloquant |
| Hash non reconnu (argon2, coût différent) → comptes bloqués | Répartition des préfixes mesurée avant le code ; SC-IDENT-023 sur tous les préfixes présents |
| Divergence des données utilisateur entre schémas | Projection dans la même transaction (U1-B) ; contrôle de cohérence quotidien pendant la coexistence |
| Invitations non consommées si Symfony est indisponible | Outbox et rejeu idempotent (U2-a) ; test d'intégration avec panne simulée |
| Clé privée JWT exposée par un nouveau conteneur | Montage lecture seule, fichiers non copiés dans l'image, revue BackReviewer |
| Login plus lent (coût bcrypt côté JVM) | Mesure en recette selon la décision PERF-IDENT-1 (`identity.md` §6.1) : p95 et p50 ≤ 1,15 × Symfony, p95 ≤ 1 000 ms ; coût 13 inchangé |
