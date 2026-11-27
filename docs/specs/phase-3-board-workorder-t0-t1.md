# Ordre de travail — Phase 3, tranches T0 (socle) et T1 (tableaux, membres, invitations)

```
Fonctionnalité : board-service, socle + tableaux/membres/invitations/corbeille des tableaux
Spec : docs/specs/phase-3-board-service.md (§2–§7, §10, §11), docs/specs/boards-members.md, docs/specs/archives-trash.md, docs/specs/decisions.md
Contrats : docs/contracts/openapi/board-service.yaml (T0+T1), board-service.internal.yaml, symfony.internal.yaml
Architecture : docs/architecture/backend-hexagonal.md §10 (B-ADR-1 à 12, A9 révisée), docs/architecture/board-migration.md
CI : docs/ci/requests/backend.md, partie « board-service (phase 3) » (Manuel)
Service(s) : board-service (nouveau, services/board-service) + backend Symfony (U8-a, /internal/notifications) + identity-service (INVITATION_CLAIM_URL)
Branche : feat/board-service (aucun push sans accord de l'utilisateur)
0. Manuel        — §6.1 (réponse B4, puis stack) ; en parallèle de l'étape 1
1. BackTesteur   — §2 (Java) puis §4.1 (PHPUnit) et §5.1 (identity) : tests rouges
2. BackCodeur    — §3 (board-service), §4.2 (Symfony), §5.2 (identity) : vert sans toucher aux tests
3. BackReviewer  — §7 ; boucle Codeur ↔ Reviewer (3 boucles max, puis remontée à BackAli)
4. Manuel        — §6.2 (workflows CI) dès que l'étape 1 est commitée
5. FrontTesteur  — e2e @board T1 (demande à FrontPierre, §6.3)
6. BackAli+Manuel — répétition RB0–RB9 de T1 (§8)
7. BackAli       — recette (§9), puis Paul — validation
```
Auteur : BackAli, 27/11/2026. Exécuté par le fil principal (BackAli sans outil Agent dans ce run : le fil principal lance chaque étape dans l'ordre). Ce fichier suffit à chaque agent : lisez-le en entier, puis les documents référencés pour le détail.

## 1. Règles communes

### 1.1 Propriété des fichiers
| Chemin | Propriétaire | Remarque |
|---|---|---|
| `services/board-service/pom.xml`, ajout du module dans `services/pom.xml` | **BackTesteur** crée (étape 1) ; BackCodeur peut **ajouter** des dépendances | BackCodeur ne touche ni aux seuils JaCoCo, ni à Surefire/Failsafe (inclusions, tags `compat`, `concurrency`), ni au profil `compat` |
| Squelette de compilation (§2.2) dans `services/board-service/src/main/java/**` | **BackTesteur** crée les signatures (corps `throw new UnsupportedOperationException("BackCodeur")`) ; BackCodeur les implémente | aucune signature publique changée sans l'écrire dans le rapport (BackAli tranche) |
| `services/board-service/src/test/**` | BackTesteur uniquement (hook `protect-tests.sh`) | |
| `backend/tests/**`, `backend/phpunit.dist.xml`, `backend/.env.test` | BackTesteur uniquement | **non protégés par le hook** : BackReviewer contrôle à la main |
| `services/identity-service/src/test/**` | BackTesteur uniquement | une seule addition (§5.1) |
| Reste de `services/board-service/src/main/**`, `services/board-service/Dockerfile`, `backend/src/**`, `backend/config/**`, `backend/migrations/**` (migration §4.2 seulement), `services/identity-service/src/main/**` (§5.2 seulement) | BackCodeur | |
| `docker-compose*.yml`, `docker/mariadb/**`, `frontend/nginx.conf`, `.env.example`, `.github/**`, `backend/docker/entrypoint.sh` | Manuel | |
| `frontend/e2e/board-*.spec.ts` | FrontTesteur | |
| `docs/reviews/**` | BackReviewer (revues), BackAli (recette, répétition) | |

### 1.2 Interdits
- Ne jamais lire, afficher ni commiter `.env` ou `backend/.env` ; aucune clé, passphrase, mot de passe ou secret réel dans le dépôt, les tests, les journaux, les rapports.
- **Ne jamais interroger ni modifier la base `taskora` de la stack de travail ni aucune stack `taskora-e2e`** (données personnelles, démonstration client, autre agent). Les tests utilisent Testcontainers ou une stack dédiée `-p taskora-ci-board` / `-p taskora-rehearsal` avec des données synthétiques `@exemple.test`, puis `down -v`.
- board-service n'exécute **aucun DDL** sur `taskora` (B-ADR-1) ; aucun `doctrine:migrations:diff` (R10).
- Maven depuis `services/` : `./mvnw -B -ntp …` (sans Java 25 local : image `maven:3.10.2-eclipse-temurin-25`, socket Docker pour Testcontainers, comme en phase 1).
- Jamais de tests sur des classes compilées avec `-Pquality` ; toujours `clean` entre les deux (A12).

### 1.3 Faits et décisions qui priment sur la spec
Détail : board-migration.md §1 et ADR B-ADR-1 à 12.
1. **E31 confirmé** (FA1–FA3) : `card.due_date` est en heure murale UTC dans les données ; board-service la traitera en UTC (T2, pas en T1).
2. Toutes les autres dates : heure murale `Europe/Paris`, ATOM avec décalage, secondes entières (B-ADR-11).
3. Droits MariaDB minimaux **vérifiés** (FA10) : cascades sans droit sur les tables filles ; `SELECT` par colonne sur `user` sans `password`.
4. Pas de JPA : `JdbcClient` (B-ADR-1). Pas de Spring Data REST (B-ADR-8).
5. Accès : 404 indiscernable pour un non-membre, contrôle **avant** l'analyse du corps (B-ADR-3) ; E12 → 404 `Ressource introuvable.`
6. Corps vide = `{}` ; JSON scalaire → 400 `JSON invalide.` ; tableau JSON = objet sans clé connue (reproduit).
7. E22/E23/E28 corrigés en T1 (messages exacts dans le contrat) ; E24 et E13 conservés.
8. `PATCH /api/boards/{id}` répond avec `listCount`/`cardCount` à 0 (reproduit, FA11).
9. Ordres : accueil `created_at DESC, id DESC`, membres et étiquettes par `id`, listes `position, id`, corbeille `deleted_at DESC, id DESC` (à figer par le miroir SC-BOARD-029 ; si Symfony diffère sur des données sans égalité, c'est le miroir qui a raison et BackAli corrige le contrat).
10. Notifications : messages composés par board-service, livrés par l'outbox à `POST /internal/notifications` (Symfony).

### 1.4 Divergences voulues (exclues du miroir, constante `INTENTIONAL_DIVERGENCES`)
Une entrée par cas, avec sa référence en commentaire : E12 (id non numérique) ; E22 (types texte : `title`, `description`, `email`) ; E23 (`withDefaults`, `showCardNumbers`) ; E28 (membre visé inexistant, invitation d'un autre tableau) ; adhésions/invitations concurrentes (409 au lieu de 500). **E1 n'est pas exclu** : U8-a aligne Symfony, le miroir compare donc les cas de non-membre (statut et corps).

## 2. Étape 1 — BackTesteur (Java, tests rouges, commit `test(board): socle T0 et tranche T1`)

### 2.1 Module
- `services/board-service/pom.xml` (parent `services/pom.xml`, module ajouté) : `spring-boot-starter-web`, `spring-boot-starter-jdbc`, `spring-boot-starter-security`, `spring-boot-starter-actuator`, `spring-boot-starter-validation`, `flyway-core` + `flyway-mysql`, `mariadb-java-client`, `spring-security-oauth2-jose` (Nimbus) ; tests : `spring-boot-starter-test`, `testcontainers-mariadb`, `testcontainers-junit-jupiter`, `archunit-junit5`, `rest-assured`, `swagger-request-validator-restassured` (contre `docs/contracts/openapi/board-service.yaml`), `wiremock-standalone`, **`net.ttddyy:datasource-proxy`** (version à vérifier sur Maven Central et à inscrire au §7 de l'architecture). **Interdits** : `spring-boot-starter-data-jpa`, `spring-boot-starter-data-rest`, `hibernate-*`.
- Surefire `*Test` ; Failsafe `*IT` ; tag `compat` exclu par défaut (profil `-Pcompat`) ; tag `concurrency` inclus ; JaCoCo A12 ; profils `quality` et `security-scan` hérités.

### 2.2 Squelette de compilation (signatures seulement)
Paquets et types de B-ADR-12. Corps : `throw new UnsupportedOperationException("BackCodeur")`. Records de domaine et de commande avec leurs champs ; exceptions de domaine avec message exact ; `ErrorMessages` (constantes de tous les messages du contrat T1). `BoardServiceApplication` démarre (contexte) une fois BackCodeur passé.

### 2.3 Tests à écrire (rouges), par scénario
Nommage : `@DisplayName("SC-… — …")`. Toute réponse d'API est validée contre `board-service.yaml` (`OpenApiValidationFilter`).

| Scénario(s) | Classe(s) | Type | Points obligatoires |
|---|---|---|---|
| Architecture | `HexagonalArchitectureTest` | ArchUnit | 5 règles du §3 ; `JdbcClient`/`java.sql` seulement dans `adapter.out.persistence` ; pas de `jakarta.persistence`, `org.hibernate`, `org.springframework.data.rest` sur le classpath ; `domain`/`application` sans Spring |
| SC-BOARD-020 | `JwtResolutionTest` (unitaire), `JwtSecurityIT` (API), `TokenVectors` (paire générée à l'exécution, chiffrée comme Lexik) | unitaire, sécurité | jeton identity (`uid`) et Symfony (sans `uid`) → même liste ; expiré, `alg:none`, HS256 avec la clé publique, autre clé, `iat` futur, en-tête absent, `uid`/`username` incohérents, compte disparu, `username` absent → corps Lexik exacts + `WWW-Authenticate: Bearer` ; le contexte ne contient **ni** clé privée **ni** passphrase (`BoardSecretsConfigTest`) |
| SC-BOARD-021 | `AccessMatrixIT` | sécurité | matrice générée depuis le contrat : chaque opération T1 × {owner, admin, member, non-membre, anonyme} × {ressource d'Alice, id inexistant} × {corps valide, invalide, non JSON} ; non-membre ≡ inexistant (statut, corps, **tous** les en-têtes) ; **0 écriture** (empreinte des tables) et 0 message d'outbox ; membre retiré → 404 dès la requête suivante |
| SC-BOARD-021 (politique) | `BoardAccessPolicyTest` | unitaire | toutes les branches de B-ADR-3 |
| SC-BOARD-024 | `ErrorFormatIT` | API | non JSON → `JSON invalide.` ; corps vide sur `favorite` = `{}` ; `[]` = objet vide ; `abc`, `0`, `-1`, `99999999999` → 404 `Ressource introuvable.` ; 500 simulé → `Erreur interne du serveur.` sans trace ; trim PHP et troncature 160/180 en points de code (emoji) |
| SC-BOARD-025 | `NotificationOutboxIT` (WireMock `/internal/notifications`) | intégration | ajout d'un membre : `board_invite` + `member_joined` aux bons destinataires, textes exacts, jamais l'acteur ; Symfony KO 2 min (WireMock 503) → réponse 201 puis livraison ; rejeu même `eventId` ; requête en 409 → aucun message ; WireMock 401 → `FAILED`, journal `ERROR`, pas de boucle ; corps conforme au schéma `NotificationRequested` |
| SC-BOARD-026 | `LogHygieneIT` | sécurité | journaux capturés d'un parcours T1 : 0 jeton, secret, mot de passe ; 0 e-mail en `INFO` |
| SC-BOARD-027 | `ProfileFreshnessIT` | intégration | changement de `name`/`avatar_color` dans `taskora.user` entre deux requêtes → visible dans `members` |
| SC-BOARD-028 | `LegacyClockTest`, `DateCompatibilityIT` | unitaire, intégration | horloge injectée, fuseaux de test, heure ambiguë d'octobre, heure inexistante de mars ; `joinedAt`, `createdAt`, `deletedAt`, `purgeAt` = `deletedAt` + 30 j (Paris) ; écriture board-service relue en SQL brut = heure murale Paris |
| R1/R2 (FA10) | `BoardDbPrivilegesIT`, `TaskoraSchemaUntouchedIT` | intégration | compte `board` créé avec **exactement** board-migration.md §3 ; `ALTER TABLE taskora.card` refusé ; `SELECT password` refusé ; cascade `DELETE FROM board` vers `attachment`/`notification` ; empreinte `information_schema` de `taskora` identique avant/après démarrage ; Flyway ne crée que `board.*` |
| SC-BOARD-001 | `CreateBoardIT`, `DefaultBoardContentTest` | API, unitaire | contenu par défaut exact (titres, couleurs, positions) ; `withDefaults:false` ; `background` hors liste ignoré ; `description` stockée brute ; owner ; 201 conforme ; une transaction (panne injectée → rien) |
| SC-BOARD-002, 008 | `ListMyBoardsIT` | API | tri `created_at DESC, id DESC` (égalités de `created_at`) ; compteurs (listes archivées/corbeille, cartes archivées/corbeille exclues) ; favori par membre ; tableaux dans la corbeille absents |
| SC-BOARD-003, 004, 018 | `GetBoardIT` | API | coquille complète ; listes ouvertes `position, id` ; `trelloFiles` (statuts ≠ `done`) ; invitations visibles par un simple membre ; inexistant → `Tableau introuvable.` ; corbeille → `Ce tableau est dans la corbeille.` (membre) |
| SC-BOARD-005 | `UpdateBoardIT` | API | champs absents inchangés ; `description` `""`/`"0"` → `null` ; compteurs à 0 dans la réponse ; E22/E23 (§1.4) sans écriture partielle |
| SC-BOARD-006 (surchargé E1) | dans `AccessMatrixIT` | sécurité | 404 `Tableau introuvable.` |
| SC-BOARD-007, SC-TRASH-007 (lecture, restauration) | `TrashBoardIT` | API | propriétaire seul (403 message exact) ; liste de corbeille du propriétaire seulement, tri `deleted_at DESC, id DESC`, `deletedBy`, `purgeAt` ; restauration : non-membre → 404 `Ce tableau n’est pas dans la corbeille.`, admin → 403 ; tableau non supprimé → 404 |
| SC-BOARD-009–013 | `AddMemberIT`, `InvitationEmailTest` | API, unitaire | e-mail trim → troncature 180 → minuscules → `FILTER_VALIDATE_EMAIL` (golden d'identity réutilisé) ; collation (casse, espaces finaux) pour trouver le compte ; `role` → `member` par défaut ; 201/202/409 exacts ; simple membre → 403 ; E24 conservé (invitation `admin` → `member` à la consommation) |
| SC-BOARD-014, 032 | `ChangeMemberRoleIT`, `MembershipRulesTest`, `OwnerInvariantConcurrencyIT` (`@Tag("concurrency")`) | API, unitaire, concurrence | ordre des contrôles du contrat ; `owner` → 400 ; propriétaire visé → 403 ; auto-rétrogradation ; 100 opérations concurrentes → exactement un `owner` (V4) |
| SC-BOARD-015, 016, 033 | `RemoveMemberIT`, `RemoveVsAssignConcurrencyIT` (`concurrency`) | API, concurrence | désassignation de toutes les cartes du tableau ; `member_removed` sauf départ volontaire ; propriétaire → 403 ; course retrait / assignation simulée en SQL (T2 n'existe pas encore : l'assignation concurrente est simulée par une transaction JDBC qui prend `LOCK IN SHARE MODE` puis insère) → V5 vert sur 100 exécutions |
| SC-BOARD-017, 031 | `MembershipConcurrencyIT` (`concurrency`) | concurrence | N ajouts / N invitations simultanés (casses différentes) → 1 ligne, N−1 × 409, 0 × 500 |
| SC-BOARD-012, 035 | `CancelInvitationIT`, `NoAccountEnumerationIT` | API, sécurité | E28 : réponses identiques pour « existant non membre » et « inexistant » |
| SC-BOARD-029 | `BoardQueryCountIT` | intégration | nombre de requêtes SQL **identique** pour 1 tableau et 200 tableaux / 300 listes (`GET /api/boards` ≤ 4 ; `GET /api/boards/{id}` ≤ 9) |
| SC-BOARD-030, SC-IDENT-005 | `ClaimInvitationsIT` | API, intégration | contrat identique à `identity-service.internal.yaml` (validation du schéma) ; 2 tableaux dont un dans la corbeille ; rejeu → `claimed:0` ; secret absent/faux → 401 ; utilisateur absent → 404 ; e-mail différent → 409 ; déjà membre → invitation supprimée sans notification ; `board_invite` par l'outbox avec l'inviteur comme acteur (ou « Quelqu'un ») |
| A18 (secret) | `InternalSecretRequiredTest` | config | `INTERNAL_API_SECRET` vide → le contexte ne démarre pas (message sans valeur) |
| `verify` | `VerifyInvariantsIT` | intégration | V1–V8 sur jeux sains et corrompus ; `baseline` puis `check` ; code de sortie 0/2/1 ; sortie = nombres seulement |
| Outbox | `OutboxRelayIT` | intégration | `SKIP LOCKED` avec deux relais ; backoff ; classement 200/400/401/404/409/5xx/délai ; purge des `DELIVERED` > 30 j |
| Miroir (`@Tag("compat")`) | `BoardMirrorCompatibilityTest`, `MirrorScopeTest`, `CrossWritesCompatTest` (SC-BOARD-023), `CrossClaimCompatTest` (SC-BOARD-030 rejeu croisé), `RoutingCompatTest` (SC-BOARD-022) | compatibilité | mêmes requêtes contre Symfony (8081) et board-service (8083) sur la **même base** de la stack `taskora-ci-board` ; JSON sémantique ; `INTENTIONAL_DIVERGENCES` (§1.4) ; `MirrorScopeTest` vérifie que chaque opération T1 du contrat est couverte par le miroir ou listée en divergence |

- Fixture versionnée `src/test/resources/fixtures/board-reference.sql` : données **synthétiques** du §9.2 de la spec restreintes à T1 (3 tableaux dont un importé et un dans la corbeille, 4 rôles, invitations, listes ouvertes/archivées/corbeille, cartes pour les compteurs, fichiers Trello en attente, horodatages égaux pour éprouver les tris), comptes `@exemple.test`, hashes factices.
- Schéma `taskora` des IT : fichier généré depuis les migrations Doctrine selon la réponse de Manuel à B4 (§6.1) ; tant qu'elle n'est pas là, BackTesteur s'arrête après l'écriture des tests et le signale.
- Sortie attendue : `./mvnw -B -ntp -pl board-service -am verify` **compile** et échoue (tests rouges, aucune erreur de compilation). Rapport : liste des classes, nombre de tests par scénario, commit de référence `<sha-test-board>`.

## 3. Étape 2 — BackCodeur (board-service, commit `feat(board): socle T0 et tranche T1`)
Implémenter le squelette jusqu'au vert, **sans toucher aux tests**. Contraintes :
1. Hexagone de B-ADR-12 ; cas d'utilisation Java purs ; transactions par `UnitOfWorkPort` ; reprise sur interblocage (B-ADR-4 §4).
2. `JdbcClient` partout, SQL paramétré, `UPDATE` des seules colonnes modifiées ; requêtes de lecture groupées (B-ADR-8) ; aucun cache.
3. Politique d'accès avant l'analyse du corps (`RawJsonBody` + `RequestBodyParser`) ; `PathIdConverter` (E12).
4. Verrous : adhésion visée `FOR UPDATE` ; index uniques traduits en 409 ; mise à jour conditionnelle `role <> 'owner'`.
5. Outbox (`board.outbox_message`, Flyway V1, V2) ; livraison asynchrone après validation + relais 5 s ; `SymfonyNotificationClient` (`RestClient`, délais 1 s / 3 s).
6. `LegacyClock` (Paris) ; `PhpString` et validation d'e-mail portées d'identity (copie, pas de dépendance entre modules).
7. Sécurité : filtre Lexik dupliqué d'identity (B-ADR-2), clé publique seule ; `InternalSecretFilter` sur `/internal/**` ; CSRF désactivé (API sans état, même justification qu'A16) ; démarrage refusé si secret vide ou `sql_mode` sans `STRICT_TRANS_TABLES`.
8. `BoardCliRunner` : `--board.verify=baseline|check`, `--board.outbox-drain`.
9. Marqueur `X-Taskora-Backend: board` seulement si `BOARD_ROUTING_MARKER=on`.
10. Journaux : jamais de jeton, secret, e-mail en `INFO`, titre ou description ; niveaux SQL du pilote à `OFF` pour les erreurs qui citent des valeurs (comme A18).
11. `Dockerfile` multi-étapes (digests épinglés, non root, 8080/8091), copié du modèle d'identity.
- Vert attendu : `verify` complet (hors `compat`), `-Pquality` à 0 violation, `rewrite:dryRun` vide. Rapport : écarts, tests jugés faux (sans les modifier), versions ajoutées.

## 4. Backend Symfony (U8-a et `/internal/notifications`)

### 4.1 BackTesteur (PHPUnit, rouges, commit `test(backend): accès 404 et notifications internes`)
| Test | Scénarios | Points |
|---|---|---|
| `tests/Controller/BoardAccess404Test.php` | SC-BOARD-021 (U8-a), SC-BOARD-006, SC-BOARD-034 | même matrice que `AccessMatrixIT` sur les routes **T1 et T2–T5 encore servies par Symfony**, y compris pièces jointes et fichiers Trello : non-membre → 404 avec le message du §5.3 de la spec (`Tableau introuvable.` pour `/api/boards/{id}/**`, `Ressource introuvable.` pour les ressources enfants, `Cet élément n’est pas dans la corbeille.` / `Ce tableau n’est pas dans la corbeille.` pour `/api/trash/**`) ; non-membre + élément dans la corbeille → message du non-membre (plus de fuite « dans la corbeille ») ; membre : messages actuels ; gestion → 403 inchangé |
| `tests/Controller/InternalNotificationsTest.php` | SC-BOARD-025 | contrat `symfony.internal.yaml` ; secret absent/faux/vide côté serveur → 401 ; jamais l'acteur ; doublons ; destinataire inconnu ignoré ; `boardId`/`cardId` inexistant → `created:0` ; rejeu `eventId` → `created:0` sans push ; push mis en file avec titre/URL/étiquette actuels ; transaction (panne → ni notification ni reçu) |
| `tests/Migrations/InternalEventReceiptMigrationTest.php` | R1, R10 | `migrate` puis `doctrine:schema:validate` vert ; `down()` réversible ; aucune autre table modifiée |
| Non-régression | — | suite existante verte (claim, etc.) |
Commit de référence `<sha-test-backend>`.

### 4.2 BackCodeur-sur-Symfony (commit `feat(backend): accès 404 (U8-a) et notifications internes`)
- `BoardAccess` : non-membre → `NotFoundHttpException` ; message selon le contexte (`member()` pour `/api/boards/{id}/**` → `Tableau introuvable.` ; nouvelle méthode pour les ressources enfants → `Ressource introuvable.`) ; tableau dans la corbeille : `Ce tableau est dans la corbeille.` **seulement** pour un membre.
- `ApiController::find()` : le contrôle « dans la corbeille » passe **après** le contrôle d'accès (nouvelle méthode `findForMember()` utilisée par les 31 appels) ; `TrashController::trashed()` et `ownedTrashedBoard()` : non-membre → 404 du §5.3.
- `InternalNotificationController` (`POST /internal/notifications`) + entité/migration `internal_event_receipt` écrite à la main (board-migration.md §2.1) ; secret comme `InternalInvitationController`.
- Interdit : modifier `backend/tests/**`, générer un diff Doctrine, stager `backend/src/Service/TrelloImporter.php` s'il porte une modification sans rapport.

## 5. identity-service (`INVITATION_CLAIM_URL`)
### 5.1 BackTesteur (commit `test(identity): URL de consommation des invitations`)
`InvitationClaimUrlConfigTest` : défaut = `SYMFONY_INTERNAL_URL` ; valeur explicite utilisée par `SymfonyInvitationClaimClient` (WireMock sur deux ports) ; aucun autre comportement modifié (suite existante verte).
### 5.2 BackCodeur (commit `feat(identity): URL de consommation des invitations configurable`)
Propriété `identity.invitations.claim-url` (`INVITATION_CLAIM_URL`, défaut `${SYMFONY_INTERNAL_URL}`) ; rien d'autre. Changement minimal : **la bascule d'identity n'est pas retardée** ; si ce commit arrive après la bascule d'identity, il suit la procédure normale (revue, CI).

## 6. Manuel
### 6.1 Avant et pendant l'étape 1
1. Répondre à la question **B4** (schéma `taskora` des IT généré depuis les migrations Doctrine, contrôlé en CI) dans `docs/ci/requests/backend.md` §B6.
2. Stack : service `board` (profil `board`), `docker-compose.board-dev.yml` (127.0.0.1:8083), init `board` + droits exacts (board-migration.md §3), variables nginx `BOARD_T1_UPSTREAM`…`T5` avec les expressions du §5.2, `INVITATION_CLAIM_URL` pour identity, `.env.example`. Le service `board` reste non démarrable tant que l'image n'existe pas (profil).
### 6.2 Dès le commit de l'étape 1
Workflows `board-service.yml`, `board-security.yml`, `board-compat.yml`, zones `changes`, `ci-gate` (demande §B1–B5). Les jobs sont rouges jusqu'à l'étape 2 : c'est attendu.
### 6.3 Demande à FrontPierre (via Paul)
e2e `@board` T1 (`frontend/e2e/board-*.spec.ts`) : accueil, création, coquille, favori, membres (ajout, invitation, rôle, retrait, départ), corbeille des tableaux, inscription d'un invité (SC-IDENT-005), toast « Tableau introuvable. » pour un non-membre ; matrice `BOARD_T1_UPSTREAM` = `api:80` / `board:8080`.

## 7. Étape 3 — BackReviewer (rapport `docs/reviews/<date>-board-t0-t1.md`)
Périmètre : `services/board-service/**`, `services/pom.xml`, `backend/src/**` et `backend/migrations/**` touchés, `services/identity-service/src/main/**` touchés.
1. **Intégrité des tests** : `git diff <sha-test-board>..HEAD -- services/board-service/src/test`, `git diff <sha-test-backend>..HEAD -- backend/tests backend/phpunit.dist.xml backend/.env.test`, idem identity : **vides**. Aucun `@Disabled`, `assumeTrue`, tag exclu, seuil baissé, exclusion JaCoCo/Sonar ajoutée.
2. Outils : `verify`, `-Pquality` (après `clean`), `rewrite:dryRun`, Sonar si disponible, `dependency-check` si la clé NVD est disponible en CI, Trivy de l'image.
3. Contrôles ciblés : aucun DDL ni `doctrine:migrations:diff` ; droits du compte `board` = §3 ; pas de clé privée ni passphrase dans board-service ; pas de JPA ni Spring Data REST ; politique d'accès avant le corps dans **chaque** cas d'utilisation ; `UPDATE` partiels ; ordre de verrouillage ; aucun appel réseau dans une transaction ; journaux sans données personnelles ; SQL sans concaténation de valeur (seuls des noms validés `[a-z_]+`) ; `INTENTIONAL_DIVERGENCES` = §1.4 exactement.
4. Symfony : 404 du §5.3 sur toutes les routes (y compris pièces jointes), aucune régression de la suite existante, migration additive à la main.
5. Classement bloquant / majeur / mineur ; tout bloquant renvoie à BackCodeur (3 boucles max).

## 8. Répétition du retour arrière de T1 (RB0–RB9)
Procédure : board-migration.md §7. Stack neuve `-p taskora-rehearsal` (ports libres en 127.0.0.1, secrets éphémères jamais affichés), données synthétiques via l'API Symfony, **jamais** `taskora` ni `taskora-e2e`. Prérequis : étapes 1–3 vertes, CI `board-*` et `backend-phpunit` verts, e2e `@board` T1 disponibles, U8-a déployé dans l'image Symfony de la stack. Compte rendu chiffré `docs/reviews/<date>-board-t1-rollback-rehearsal.md` (BackAli, Manuel). Points propres à T1 : RB2/RB4 commutent **aussi** `INVITATION_CLAIM_URL` ; RB5 inscrit un invité avec le claim servi par Symfony puis RB7 avec board-service ; `verify` en RB1, RB6 et après RB7.

## 9. Recette BackAli et critères de sortie T0 + T1 (spec §11.1)
1. Scénarios critiques T0/T1 `validé`, importants au moins `vert` (matrice `docs/specs/traceability.md`, mise à jour par Paul).
2. Miroir T1 vert contre les deux amonts en CI (`board-compat`), divergences limitées au §1.4.
3. e2e `@board` T1 verts sur les deux amonts.
4. Rapport BackReviewer sans bloquant ; intégrité des tests prouvée.
5. Répétition RB0–RB9 réussie ; `verify` vert ; outbox sans `FAILED`.
6. PHPUnit verts (U8-a, `/internal/notifications`) ; identity pointe vers board-service en recette, SC-IDENT-005 vert.
7. Faits F0–F13 mesurés (board-migration.md §1.4) **avant** RB0 ; un écart à FA5/FA6 renvoie à BackAli.
8. Latence : suivi (U5) ; L5 (0 × 5xx) exigé.
Bascule réelle de T1 : seulement après accord explicite de l'utilisateur, hors heures de pointe, annoncée (U9-a), U8-a déployé **avant** ou **dans la même opération**.
