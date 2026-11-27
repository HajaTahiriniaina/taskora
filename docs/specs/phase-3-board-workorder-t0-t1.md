# Ordre de travail — Phase 3, tranches T0 (socle) et T1 (tableaux, membres, invitations)

```
Fonctionnalité : board-service, socle + tableaux/membres/invitations/corbeille des tableaux
Spec : docs/specs/phase-3-board-service.md (§2–§7, §10, §11), docs/specs/boards-members.md, docs/specs/archives-trash.md, docs/specs/decisions.md (U6–U13)
Contrats : docs/contracts/openapi/board-service.yaml (T0+T1), board-service.internal.yaml, symfony.internal.yaml
Architecture : docs/architecture/backend-hexagonal.md §10 (B-ADR-1 à 12, A9 révisée), docs/architecture/board-migration.md
CI : docs/ci/requests/backend.md, partie « board-service (phase 3) » (Manuel)
Service(s) : board-service (nouveau, services/board-service) + backend Symfony (U8-a, /internal/notifications) + identity-service (INVITATION_CLAIM_URL)
Branche : feat/board-service (aucun push sans accord de l'utilisateur)
0. Manuel        — §6.1 (réponse B4, puis stack) ; en parallèle de l'étape 1 ; mesure board-facts.sql avant RB0 sur accord explicite de l'utilisateur (U11)
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
6. Corps vide = `{}` ; corps non JSON ou JSON valide **non objet** (tableau compris : `[]`, `[1]`, `"x"`, `42`, `true`, `null`) → 400 `JSON invalide.` (**U13**, E16 étendu à board-service).
7. E22/E23/E28 corrigés en T1 (messages exacts dans le contrat) ; E24 et E13 conservés.
8. `PATCH /api/boards/{id}` répond avec `listCount`/`cardCount` à 0 (reproduit, FA11).
9. Ordres : accueil `created_at DESC, id DESC`, membres et étiquettes par `id`, listes `position, id`, corbeille `deleted_at DESC, id DESC` (à figer par le miroir SC-BOARD-029 ; si Symfony diffère sur des données sans égalité, c'est le miroir qui a raison et BackAli corrige le contrat).
10. Notifications : messages composés par board-service, livrés par l'outbox à `POST /internal/notifications` (Symfony).
11. **U12** : secrets internes par appelant (B-ADR-5 §6) : board-service vérifie `INTERNAL_SECRET_IDENTITY_TO_BOARD` et envoie `INTERNAL_SECRET_BOARD_TO_SYMFONY` ; Symfony vérifie `INTERNAL_API_SECRET` sur le claim seulement et `INTERNAL_SECRET_BOARD_TO_SYMFONY` sur ses nouveaux endpoints ; identity envoie `INVITATION_CLAIM_SECRET`.
12. **U10** : 503 de B-ADR-6 accepté (T4, hors de cet ordre de travail) ; SC-COMMENT-013 à ajuster par Paul avant T4.
13. **U11** : `board-facts.sql` lancé par Manuel seulement, sur accord explicite de l'utilisateur au moment de l'exécution, après la démo, avant RB0 de T1 puis avant T2.

### 1.4 Divergences voulues (exclues du miroir, constante `INTENTIONAL_DIVERGENCES`)
Une entrée par cas, avec sa référence en commentaire : U13 (corps JSON non objet : `[]`, `[1]`, `"x"`) ; E12 (id non numérique) ; E22 (types texte : `title`, `description`, `email`) ; E23 (`withDefaults`, `showCardNumbers`) ; E28 (membre visé inexistant, invitation d'un autre tableau) ; adhésions/invitations concurrentes (409 au lieu de 500). **E1 n'est pas exclu** : U8-a aligne Symfony, le miroir compare donc les cas de non-membre (statut et corps).

## 2. Étape 1 — BackTesteur (Java, tests rouges, commit `test(board): socle T0 et tranche T1`)

### 2.1 Module
- `services/board-service/pom.xml` (parent `services/pom.xml`, module ajouté) : `spring-boot-starter-webmvc`, `spring-boot-starter-jdbc`, `spring-boot-starter-security`, `spring-boot-starter-actuator`, `spring-boot-starter-validation`, `spring-boot-starter-flyway` + `flyway-mysql`, `spring-boot-starter-restclient`, `mariadb-java-client`, `spring-security-oauth2-jose` (Nimbus) (noms Boot 4, comme identity : D5 du §10) ; tests : `spring-boot-starter-test`, `spring-boot-starter-webmvc-test`, `spring-boot-starter-security-test`, `testcontainers-mariadb`, `testcontainers-junit-jupiter`, `archunit-junit5`, `rest-assured`, `openapi-request-validator-restassured` (contre `docs/contracts/openapi/board-service.yaml`), `wiremock-standalone`, **`net.ttddyy:datasource-proxy`** (version à vérifier sur Maven Central et à inscrire au §7 de l'architecture). **Interdits** : `spring-boot-starter-data-jpa`, `spring-boot-starter-data-rest`, `hibernate-*`.
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
| SC-BOARD-024 | `ErrorFormatIT` | API | non JSON → `JSON invalide.` ; corps vide sur `favorite` = `{}` ; **U13** : `[]`, `[1]`, `"x"`, `42`, `true`, `null` sur `POST /boards`, `PATCH /boards/{id}`, `POST …/members`, `PATCH …/members/{userId}` → 400 `JSON invalide.` sans écriture (et, pour un non-membre, 404 d'abord) ; `abc`, `0`, `-1`, `99999999999` → 404 `Ressource introuvable.` ; 500 simulé → `Erreur interne du serveur.` sans trace ; trim PHP et troncature 160/180 en points de code (emoji) |
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
| A18, U12 (secrets) | `InternalSecretRequiredTest`, `InternalSecretPerCallerIT` | config, sécurité | `INTERNAL_SECRET_IDENTITY_TO_BOARD` ou `INTERNAL_SECRET_BOARD_TO_SYMFONY` vide, ou les deux égaux → le contexte ne démarre pas (message citant la variable, jamais la valeur) ; `/internal/invitations/claim` refuse (401 `Accès interne refusé.`) `INTERNAL_SECRET_BOARD_TO_SYMFONY`, `INTERNAL_API_SECRET` et un secret aléatoire, accepte seulement `INTERNAL_SECRET_IDENTITY_TO_BOARD` ; `SymfonyNotificationClient` envoie `INTERNAL_SECRET_BOARD_TO_SYMFONY` (WireMock vérifie l'en-tête) ; aucune variable `INTERNAL_API_SECRET` lue par board-service |
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
| `tests/Controller/InternalSecretPerCallerTest.php` | U12 | `/internal/notifications` : refuse `INTERNAL_API_SECRET` (secret d'identity) et un secret aléatoire, accepte `INTERNAL_SECRET_BOARD_TO_SYMFONY` ; `/internal/invitations/claim` : refuse `INTERNAL_SECRET_BOARD_TO_SYMFONY`, accepte `INTERNAL_API_SECRET` ; les deux variables égales ou l'une vide → 401 partout où elle protège |
| `tests/Migrations/InternalEventReceiptMigrationTest.php` | R1, R10 | `migrate` puis `doctrine:schema:validate` vert ; `down()` réversible ; aucune autre table modifiée |
| Non-régression | — | suite existante verte (claim, etc.) |
Commit de référence `<sha-test-backend>`.

### 4.2 BackCodeur-sur-Symfony (commit `feat(backend): accès 404 (U8-a) et notifications internes`)
- `BoardAccess` : non-membre → `NotFoundHttpException` ; message selon le contexte (`member()` pour `/api/boards/{id}/**` → `Tableau introuvable.` ; nouvelle méthode pour les ressources enfants → `Ressource introuvable.`) ; tableau dans la corbeille : `Ce tableau est dans la corbeille.` **seulement** pour un membre.
- `ApiController::find()` : le contrôle « dans la corbeille » passe **après** le contrôle d'accès (nouvelle méthode `findForMember()` utilisée par les 31 appels) ; `TrashController::trashed()` et `ownedTrashedBoard()` : non-membre → 404 du §5.3.
- `InternalNotificationController` (`POST /internal/notifications`) + entité/migration `internal_event_receipt` écrite à la main (board-migration.md §2.1) ; secret **`INTERNAL_SECRET_BOARD_TO_SYMFONY`** (U12), vérifié par un service commun `InternalCaller` (`hash_equals`, refus si vide ou égal à l'autre secret) ; `InternalInvitationController` garde `INTERNAL_API_SECRET` seul.
- Interdit : modifier `backend/tests/**`, générer un diff Doctrine, stager `backend/src/Service/TrelloImporter.php` s'il porte une modification sans rapport.

## 5. identity-service (`INVITATION_CLAIM_URL`)
### 5.1 BackTesteur (commit `test(identity): URL de consommation des invitations`)
`InvitationClaimUrlConfigTest` : défaut URL = `SYMFONY_INTERNAL_URL`, défaut secret = `INTERNAL_API_SECRET` ; `INVITATION_CLAIM_URL` et `INVITATION_CLAIM_SECRET` explicites utilisés **ensemble** par `SymfonyInvitationClaimClient` (WireMock sur deux ports, chacun n'acceptant que son secret : un message envoyé à la mauvaise cible avec le mauvais secret → 401 → `FAILED`) ; secret de claim vide → refus de démarrer ; aucun autre comportement modifié (suite existante verte).
### 5.2 BackCodeur (commit `feat(identity): URL de consommation des invitations configurable`)
Propriétés `identity.invitations.claim-url` (`INVITATION_CLAIM_URL`, défaut `${SYMFONY_INTERNAL_URL}`) et `identity.invitations.claim-secret` (`INVITATION_CLAIM_SECRET`, défaut `${INTERNAL_API_SECRET}`, U12), lues au moment de l'envoi ; rien d'autre. Changement minimal : **la bascule d'identity n'est pas retardée** ; si ce commit arrive après la bascule d'identity, il suit la procédure normale (revue, CI).

## 6. Manuel
### 6.1 Avant et pendant l'étape 1
1. Répondre à la question **B4** (schéma `taskora` des IT généré depuis les migrations Doctrine, contrôlé en CI) dans `docs/ci/requests/backend.md` §B6.
2. Stack : trois secrets internes distincts par appelant (U12, demande CI §B3 point 5 bis) ; service `board` (profil `board`), `docker-compose.board-dev.yml` (127.0.0.1:8083), init `board` + droits exacts (board-migration.md §3), variables nginx `BOARD_T1_UPSTREAM`…`T5` avec les expressions du §5.2, `INVITATION_CLAIM_URL` pour identity, `.env.example`. Le service `board` reste non démarrable tant que l'image n'existe pas (profil).
### 6.2 Dès le commit de l'étape 1
Workflows `board-service.yml`, `board-security.yml`, `board-compat.yml`, zones `changes`, `ci-gate` (demande §B1–B5). Les jobs sont rouges jusqu'à l'étape 2 : c'est attendu.
### 6.3 Demande à FrontPierre (via Paul)
e2e `@board` T1 (`frontend/e2e/board-*.spec.ts`) : accueil, création, coquille, favori, membres (ajout, invitation, rôle, retrait, départ), corbeille des tableaux, inscription d'un invité (SC-IDENT-005), toast « Tableau introuvable. » pour un non-membre ; matrice `BOARD_T1_UPSTREAM` = `api:80` / `board:8080`.

## 7. Étape 3 — BackReviewer (rapport `docs/reviews/<date>-board-t0-t1.md`)
Périmètre : `services/board-service/**`, `services/pom.xml`, `backend/src/**` et `backend/migrations/**` touchés, `services/identity-service/src/main/**` touchés.
1. **Intégrité des tests** : `git diff <sha-test-board>..HEAD -- services/board-service/src/test`, `git diff <sha-test-backend>..HEAD -- backend/tests backend/phpunit.dist.xml backend/.env.test`, idem identity : **vides**, sauf les commits de BackTesteur autorisés au §10 (D1 schéma régénéré, D7 test du push), dont le contenu est contrôlé selon le §10. Aucun `@Disabled`, `assumeTrue`, tag exclu, seuil baissé, exclusion JaCoCo/Sonar ajoutée.
2. Outils : `verify`, `-Pquality` (après `clean`), `rewrite:dryRun`, Sonar si disponible, `dependency-check` si la clé NVD est disponible en CI, Trivy de l'image.
3. Contrôles ciblés : aucun DDL ni `doctrine:migrations:diff` ; droits du compte `board` = §3 ; pas de clé privée ni passphrase dans board-service ; pas de JPA ni Spring Data REST ; politique d'accès avant le corps dans **chaque** cas d'utilisation ; `UPDATE` partiels ; ordre de verrouillage ; aucun appel réseau dans une transaction ; journaux sans données personnelles ; SQL sans concaténation de valeur (seuls des noms validés `[a-z_]+`) ; `INTENTIONAL_DIVERGENCES` = §1.4 exactement.
4. Symfony : 404 du §5.3 sur toutes les routes (y compris pièces jointes), aucune régression de la suite existante, migration additive à la main.
5. Classement bloquant / majeur / mineur ; tout bloquant renvoie à BackCodeur (3 boucles max).

## 8. Répétition du retour arrière de T1 (RB0–RB9)
Procédure : board-migration.md §7. Stack neuve `-p taskora-rehearsal` (ports libres en 127.0.0.1, secrets éphémères jamais affichés), données synthétiques via l'API Symfony, **jamais** `taskora` ni `taskora-e2e`. Prérequis : étapes 1–3 vertes, CI `board-*` et `backend-phpunit` verts, e2e `@board` T1 disponibles, U8-a déployé dans l'image Symfony de la stack. Compte rendu chiffré `docs/reviews/<date>-board-t1-rollback-rehearsal.md` (BackAli, Manuel). Points propres à T1 : RB2/RB4 commutent **aussi** `INVITATION_CLAIM_URL` et `INVITATION_CLAIM_SECRET` (U12) ; RB3 vérifie qu'un appel au claim de board-service avec `INTERNAL_API_SECRET` est refusé (401) ; RB5 inscrit un invité avec le claim servi par Symfony puis RB7 avec board-service ; `verify` en RB1, RB6 et après RB7.

## 9. Recette BackAli et critères de sortie T0 + T1 (spec §11.1)
1. Scénarios critiques T0/T1 `validé`, importants au moins `vert` (matrice `docs/specs/traceability.md`, mise à jour par Paul).
2. Miroir T1 vert contre les deux amonts en CI (`board-compat`), divergences limitées au §1.4.
3. e2e `@board` T1 verts sur les deux amonts.
4. Rapport BackReviewer sans bloquant ; intégrité des tests prouvée.
5. Répétition RB0–RB9 réussie ; `verify` vert ; outbox sans `FAILED`.
6. PHPUnit verts (U8-a, `/internal/notifications`) ; identity pointe vers board-service en recette, SC-IDENT-005 vert.
7. Faits F0–F13 mesurés (board-migration.md §1.4) **avant** RB0, par Manuel, sur accord explicite de l'utilisateur donné au moment de l'exécution (U11) ; seconde mesure avant T2 ; un écart à FA5/FA6 renvoie à BackAli.
8. Latence : suivi (U5) ; L5 (0 × 5xx) exigé.
Bascule réelle de T1 : seulement après accord explicite de l'utilisateur, hors heures de pointe, annoncée (U9-a), U8-a déployé **avant** ou **dans la même opération**.

## 10. Décisions après l'étape 1 (BackAli, 27/11/2026)
Contexte : étape 1 terminée par BackTesteur (`7cc304c` board-service, `1bec312` PHPUnit, `a183598` identity). Résultats rouges attendus : board unitaires 233 (206 rouges), IT 371 (362 rouges, faute d'`application.yml` et de Flyway), PHPUnit 108 (85 rouges), identity 9 (7 rouges). Commits de référence pour BackReviewer : `<sha-test-board>` = `7cc304c`, `<sha-test-backend>` = `1bec312`, `<sha-test-identity>` = `a183598` (plus les commits autorisés par D1 et D7 ci-dessous). Aucun point ci-dessous ne revient à l'utilisateur : chacun est tranché par la spec, une décision existante ou le code Symfony.

| # | Question | Décision | Qui fait quoi |
|---|---|---|---|
| D1 | `taskora-schema.sql` dérivera après la migration `internal_event_receipt` | **Reste sous la règle des tests** (pas d'exception au hook ni à l'intégrité). Raisons : c'est la référence sur laquelle tournent toutes les IT (un Codeur qui le touche pourrait affaiblir une contrainte) ; une exception rendrait la règle « `src/test/**` intouchable » moins simple à contrôler ; le coût est un commit d'une commande. | **BackCodeur** : commit `feat(backend)` avec la migration ; ne lance **pas** `taskora-schema.sh` (le script écrit dans `src/test/**`). **BackTesteur** : juste après, `docker/mariadb/tools/taskora-schema.sh` puis commit `test(board): schéma taskora régénéré` de ce seul fichier. **BackReviewer** : `taskora-schema.sh --check` = 0 ; le diff du fichier ne contient que l'en-tête (nombre de migrations, dernière version) et `DROP`/`CREATE TABLE internal_event_receipt` ; auteur du commit = BackTesteur. Entre les deux commits, `--check` est rouge en CI : attendu. |
| D2 | Recette B6 : `MARIADB_USER` et digest | Noté, rien à trancher. Les IT fixent `MARIADB_USER=taskora` et passent l'image par digest seul (`mariadb@sha256:e1d5…` + `asCompatibleSubstituteFor("mariadb")`, Testcontainers refuse « étiquette + digest ») ; Manuel corrige B6 et l'init en parallèle. | **Manuel** : B6 et `10-taskora-init.sh`. Le digest est dupliqué dans `BoardDatabase.IMAGE` : toute montée du digest de `docker-compose.yml` demande un commit de BackTesteur (Manuel peut ajouter un contrôle d'égalité dans `board-build`, facultatif). |
| D3 | `listCount` de `POST /api/boards` : Symfony 4, contrat « compteurs à 0 » | **Symfony a raison, le contrat était faux** (`Presenter::board` reçoit les listes créées avec 0 carte : `listCount` = nombre de listes, `cardCount` = 0). Ce n'est pas une décision produit : on reproduit l'existant, comme pour tout le contrat. La règle « compteurs à 0 » (FA11) ne concerne **que** `PATCH /api/boards/{id}`. | **BackAli** : contrat corrigé (`createBoard`). **BackTesteur** : rien (`CreateBoardIT` attend déjà 4, et 0 avec `withDefaults:false`). **BackCodeur** : `listCount` = nombre de listes créées, `cardCount` 0, chaque liste `cardCount: 0`. |
| D4 | Bornes SC-BOARD-029 et résolution du jeton | **Confirmé** : les bornes ≤ 4 / ≤ 9 portent sur le cas d'utilisation ; la résolution de l'utilisateur par le filtre (B-ADR-2) ajoute exactement 1 requête (bornes mesurées 5 et 10, comme `BoardQueryCountIT`). Écrit dans B-ADR-8 point 4. | **BackCodeur** : 1 seul `SELECT` sur `taskora.user` dans le filtre ; toute autre lecture compte dans la borne du cas d'utilisation ; même nombre de requêtes pour non-membre et inexistant. |
| D5 | Démarreurs Spring Boot 4 | **Accepté** : `spring-boot-starter-webmvc`, `spring-boot-starter-flyway`, `spring-boot-starter-restclient` (modularisation de Boot 4, mêmes noms qu'identity). | **BackAli** : §2.1 corrigé. Rien d'autre. |
| D6 | Message « dans la corbeille » sur `/api/boards/{T}/archived` (membre) | **Le test a raison** : §5.3 (« Membre, tableau dans la corbeille → `Ce tableau est dans la corbeille.` ») s'applique à toutes les routes `/api/boards/{id}/**` ; U8-a impose les messages du §5.3. Aujourd'hui seules `archived`, `archived-lists` et `trello-files` (qui passent par `ApiController::find()`) répondent `Cet élément est dans la corbeille.` ; les autres routes du tableau répondent déjà `Ce tableau…`. Uniformisation couverte par U8-a, pas une nouvelle décision produit. | **BackAli** : §5.3 précisé (ambiguïté « comme aujourd'hui » retirée). **BackTesteur** : rien. **BackCodeur (Symfony)** : `findForMember()` appliqué au tableau sur ces trois routes → membre : `Ce tableau est dans la corbeille.` ; non-membre : `Tableau introuvable.` ; ressources enfants inchangées (`Cet élément est dans la corbeille.` pour un membre). board-service fera de même en T2. |
| D7 | Clés VAPID absentes : `testPushQueued` en `markTestIncomplete` | **Refusé en l'état** : un test incomplet est un affaiblissement (§7 point 1). De plus le test est faux même avec VAPID : `PushFlushSubscriber` vide la file sur `kernel.terminate`, que le client de test déclenche ; la file lue après la requête est donc toujours `[]` (et l'assertion « aucun push au rejeu » de `testReplayIsIdempotent` ne prouve rien). | **BackTesteur**, avant BackCodeur, commit `test(backend): push des notifications internes vérifiable` : (1) poser dans `ApiTestCase` une paire VAPID **générée à l'exécution** (`Minishlink\WebPush\VAPID::createVapidKeys()`), comme les secrets factices, restaurée en `tearDown`, jamais commitée ; (2) capturer la file **avant** le vidage (écouteur `KernelEvents::TERMINATE` de priorité > 0 ajouté au répartiteur du conteneur de test, ou double de `PushSender` dans le conteneur de test) ; (3) supprimer `markTestIncomplete` ; (4) appliquer la même capture au test de rejeu (file vide attendue). Aucun appel réseau : Dora n'a pas d'abonnement push. **BackReviewer** : vérifie que ce commit ne retire aucune assertion. **BackCodeur** : rien de plus (`PushSender::queue` existant). |
| D8 | `backend/.env.test` non lu ; secrets factices posés par les tests | **Accepté** : c'est plus explicite (chaque test choisit ses valeurs, y compris vide et égales pour U12) et ne dépend pas du chargement de dotenv. Conditions : valeurs visiblement factices, restaurées en `tearDown` ; aucun secret ajouté à `backend/.env.test` ; le code de production ne doit pas compter sur `.env.test`. | **Manuel** : la phrase de B3 point 5 bis « valeurs factices distinctes dans `backend/.env.test` » est caduque pour les nouveaux secrets ; garder l'`unset` des variables réelles dans `backend-phpunit`. |
| D9 | `datasource-proxy` 1.11.1 au §7 | **Fait** : vérifiée sur Maven Central (dernière release), ajoutée au §7 de `backend-hexagonal.md` (et nom exact du validateur OpenAPI, `openapi-request-validator-*` 3.0.0). | — |
| D10 | `MirrorScopeTest` non tagué `compat` ; secrets lus par les tests `compat` | **Accepté** : `MirrorScopeTest` est statique (contrat + constantes du miroir, sans stack), il doit tourner dans `verify` comme celui d'identity. Les tests `compat` lisent `INTERNAL_API_SECRET`, `INTERNAL_SECRET_IDENTITY_TO_BOARD`, `INTERNAL_SECRET_BOARD_TO_SYMFONY` dans l'environnement et échouent explicitement (nom de la variable, jamais la valeur) s'ils manquent. | **Manuel** (`board-compat`) : exporter les trois secrets éphémères (les mêmes que ceux de la stack, masqués) dans l'environnement de l'étape Maven `-Pcompat`. |

### Fusion `b5a8b49` (docs/ci/requests/backend.md)
Vérifiée : par rapport au côté `feat/board-service` (`e56b2d0`), la fusion n'ajoute que les suites de la recette identity venues de `main` (N2, N7, ligne OWASP et m10 mises à jour) ; par rapport à `main` (`9946d1e`), elle ne retire aucune ligne. La demande board-service (B1–B6) est reprise mot pour mot. **OK**, rien à corriger.

### Consignes pour BackCodeur (en plus des §3, §4.2, §5.2)
- Attendre le commit D7 de BackTesteur avant de lancer la suite PHPUnit de référence.
- D1 : ne jamais exécuter `taskora-schema.sh` ; signaler dans le rapport le commit de la migration pour que BackTesteur régénère le schéma.
- D3, D4, D6 : comportements ci-dessus.
