# Revue BackReviewer — identity-service (phase 1)

- Date : 26/11/2026
- Branche : `feat/identity-service`
- Commits revus :
  - `eefa634` : tests rouges (BackTesteur) ;
  - `98bcc4e` : code de production (BackCodeur) ;
  - `aa27c2f` : infra et CI (Manuel), revue limitée à la sécurité ;
  - `6d86299` : 7 corrections de tests approuvées, **référence d'intégrité**.
- Périmètre : `services/identity-service`, endpoint Symfony transitoire, parties sécurité de l'infra.
- Aucun secret, hash, jeton ni donnée personnelle dans ce rapport.

## 1. Verdict : **REFUSÉ**

Le code fonctionnel est de bonne qualité :
- tous les tests passent ;
- les seuils JaCoCo sont atteints ;
- l'architecture hexagonale est respectée ;
- les écarts E15–E21 sont corrigés ;
- la revue de sécurité ne trouve aucune faille exploitable.

Le refus vient de la chaîne qualité exigée par l'ADR A12 :
- Spotless est inutilisable avec google-java-format 1.37.0 ; avec 2.0.0, 63 fichiers sont mal formatés ;
- Checkstyle relève 164 violations ;
- le Nullness Checker ne démarre pas (dépendance manquante), puis relève 9 erreurs ;
- OpenRewrite ne se résout pas, puis propose des changements sur du code neuf ;
- OWASP n'a pas pu tourner ;
- Sonar relève une vulnérabilité CRITICAL non revue ;
- `verify` n'a jamais été vert de bout en bout en local, à cause d'erreurs d'environnement.

## 2. Résultats des outils

Toutes les commandes Maven ont été lancées depuis `services/` dans `eclipse-temurin:25-jdk`, avec `./mvnw -B -ntp -pl identity-service -am`.

| # | Outil | Commande | Statut | Problèmes |
|---|---|---|---|---|
| 1 | Tests + JaCoCo | `verify` | ⚠️ **ÉCHEC d'environnement** (2 passages) | Surefire : 566/566 verts. Failsafe : 231/233 ; 2 erreurs dans `MeApiIT`, des tests différents à chaque passage, toutes `SQLTimeoutException: Socket timeout when connecting to host.docker.internal:<port>` (connexion JDBC directe du helper de test). `MeApiIT` lancé seul : 64/64 verts. JaCoCo : « All coverage checks have been met ». |
| 2 | Spotless (gjf 1.37.0) | `spotless:check` | ❌ **inexécutable** | `InvocationTargetException` sur JDK 25. Les drapeaux `--add-exports jdk.compiler/...` passés par `MAVEN_OPTS` ne changent rien. |
| 2b | Spotless (gjf **2.0.0**) | `spotless:check -Dgoogle-java-format.version=2.0.0` | ❌ écarts | Fonctionne avec Spotless 3.12.0 sur JDK 25. `spotless:apply` sur une copie : **63 fichiers** (19 `src/main`, 44 `src/test`), +1436/−608. |
| 3a | Checkstyle (google_checks) | `-Pquality verify` | ❌ | **164 violations**, toutes dans `src/main` : 131 MissingJavadocMethod, 22 LineLength, 5 SummaryJavadoc, 2 VariableDeclarationUsageDistance, 1 GoogleMethodName, 1 AbbreviationAsWordInName, 1 JavadocParagraph, 1 MissingSwitchDefault. |
| 3b | Checker Framework Nullness 4.4.0 | `-Pquality clean verify -DskipTests` | ❌ **configuration** | `AnnotationBuilder: fromClass can't load class org.checkerframework.dataflow.qual.Deterministic` : `checker-qual` absent du classpath. Sans `clean`, la compilation est sautée et le checker ne tourne pas. Sur une copie avec `checker-qual:4.4.0` (scope `provided`) : JDK 25 compatible, **9 erreurs**, détail en B3. |
| 4 | OpenRewrite | `rewrite:dryRun` | ❌ **non résolu** | `rewrite-spring` 6.38.0, `rewrite-migrate-java` 3.43.0 et `rewrite-static-analysis` 2.42.0 sont référencés par `rewrite-recipe-bom` 3.38.0 mais **absents de Maven Central** (404). Avec les versions recommandées (§2.1), la recette `UpgradeSpringBoot_4_2` n'existe pas ; avec `UpgradeSpringBoot_4_0` : **10 fichiers modifiés** (1 pom, 4 `src/main`, 5 `src/test`), détail en B4. |
| 5 | OWASP dependency-check 13.0.0 | `-Psecurity-scan verify -DskipTests` | ⛔ **non exécuté** | `NvdApiException: Invalid API Key, length of 0` puis `NoDataException: No documents exist`. La version 13 refuse de fonctionner sans `NVD_API_KEY`. Aucune clé n'a été créée (service externe). |
| 6 | Sonar (SonarQube local 26.10, profil `quality`) | `verify sonar:sonar -DskipTests` | ⚠️ gate **PASSED** (première analyse), mais A12 non respectée | 5 bugs (MAJOR), 7 vulnérabilités (1 **CRITICAL** S4502, 6 MAJOR S2077), 0 hotspot, 0 code smell, 0 % duplication, 3447 lignes. Note de sécurité E, de fiabilité C. Couverture non transmise : tests sautés pour cette analyse ; la mesure de référence est JaCoCo (§5). Jeton admin local révoqué après usage ; SonarQube arrêté (`stop`, pas de `down -v`). |
| 7a | PHPUnit 13.4.8 | `docker compose run --rm --no-deps -e APP_ENV=test … vendor/bin/phpunit` | ✅ | 13 tests, 60 assertions, OK. |
| 7b | composer audit | même conteneur | ⚠️ | **10 avis** (sans sévérité) sur 7 paquets Symfony **8.1.11**, tous corrigés en **8.1.12** (publiés le 25/11/2026) : `framework-bundle` ×3, `routing` ×2, `http-kernel`, `security-core`, `security-http`, `doctrine-bridge`, `html-sanitizer`. Aucun paquet abandonné. Antérieurs à cette branche. |

### 2.1 Versions recommandées
- **google-java-format : `2.0.0`**, en gardant Spotless 3.12.0. Validé sur JDK 25 : exécution correcte, seuls de vrais écarts de format sont signalés. La 1.37.0 est cassée sur JDK 25, avec ou sans `--add-exports`.
- **OpenRewrite**, avec le plugin `rewrite-maven-plugin` 6.46.1 :
  - versions : `rewrite-spring` **6.37.1**, `rewrite-migrate-java` **3.42.1**, `rewrite-static-analysis` **2.41.1** (dernières publiées, résolues et exécutées) ;
  - variante cohérente avec un BOM publié : `rewrite-recipe-bom` 3.37.0, soit 6.37.0, 3.42.0 et 2.41.0 ;
  - recettes : `org.openrewrite.java.spring.boot4.UpgradeSpringBoot_4_0`, `org.openrewrite.java.migrate.UpgradeToJava25`, `org.openrewrite.staticanalysis.CommonStaticAnalysis`. `UpgradeSpringBoot_4_2` n'existe pas dans 6.37.1.
- **Nullness** : ajouter `org.checkerframework:checker-qual:${checker.version}` en scope `provided`. Checker Framework 4.4.0 fonctionne sur JDK 25 avec les `-J--add-exports` déjà présents.

## 3. Points bloquants

| Réf. | Propriétaire | Fichier:ligne | Constat | Correction attendue |
|---|---|---|---|---|
| **B1** | **BackAli** (table §7), puis **BackCodeur** et **BackTesteur** | `services/pom.xml:45` | gjf 1.37.0 inutilisable sur JDK 25 : Spotless ne peut pas tourner. | 1) BackAli passe `google-java-format.version` à `2.0.0` et met à jour architecture §7. 2) BackCodeur lance `spotless:apply` sur `src/main` (19 fichiers). 3) BackTesteur le lance sur `src/test` (44 fichiers ; changement de forme seulement, sans toucher aux assertions). |
| **B2** | **BackCodeur** | `src/main/**` | 164 violations Checkstyle (§2, ligne 3a). | Javadoc des méthodes publiques (`config/UseCaseConfig.java`, `config/ClockConfig.java`, `config/SecurityConfig.java:24`, constructeurs d'adapters…) ; lignes de plus de 100 caractères (`SecurityConfig.java:17,25,28,40`, `ErrorMessages.java:6,8`, `PhpEmailValidator.java:15,17,19`, `JdbcLegacyUserSource.java:119`…) ; renommer `ErrorMessages.notAString` (`ErrorMessages.java:27`) ; ajouter un `default` au `switch` (`OutboxDeliveryService.java:75`). Une grande partie se règle après `spotless:apply` (B1). |
| **B3** | **BackCodeur** (ajout de dépendance autorisé par §1.1) | `services/identity-service/pom.xml` ; puis le code | Nullness Checker non exécutable : `checker-qual` manque. Une fois ajouté, 9 erreurs. | Ajouter `checker-qual` (`provided`). Corriger : `PhpJsonBody.java:60,69` (retour nullable : annoter `@Nullable`) ; `MeController.java:47` (`isEmpty()` sur un récepteur nullable) ; `LegacyImportRunner.java:46` (argument nullable de `String.join`) ; `UserAccountEntity.java:46,48` et `OutboxMessageEntity.java:56,58` (champs JPA non initialisés : `@Nullable` ou `@SuppressWarnings("initialization.fields.uninitialized")` justifié sur le constructeur JPA) ; `SymfonyCompatiblePasswordHasher.java:32` (`dummyHash` : `@MonotonicNonNull` ou calcul dans le constructeur, voir m7). |
| **B4** | **BackAli** (versions et recettes), **BackCodeur** (`src/main`), **BackTesteur** (`src/test` et Surefire) | `services/pom.xml:52-54`, `:320` | Versions OpenRewrite introuvables ; recette 4_2 inexistante. Après correction, changements proposés sur du code neuf. | BackAli : versions du §2.1, recette `UpgradeSpringBoot_4_0`, table §7. BackCodeur : <br>• `application.yml:24-30` : Boot 4 a renommé `server.error.*` en `spring.web.error.*`, donc la configuration actuelle est **sans effet** (les valeurs par défaut sont sûres, mais l'A4 n'est pas appliquée explicitement) ; <br>• `OutboxJpaRepository.java` : `@Query(nativeQuery)` → `@NativeQuery` ; <br>• `LexikKeyLoader.java` : `if/else instanceof` → `switch` ; <br>• `LegacyImportService.java` : `String.valueOf` inutile sur des `String`. <br>BackTesteur : `MirrorCompatibilityTest`, `MirrorScopeTest`, `RegistrationInvitationClaimIT`, `RegisterUserServiceTest` et `EmailTest` (EqualsAvoidsNull, dépendances) ; `AddMockitoJavaAgentToMavenSurefirePlugin` sur `identity-service/pom.xml` (configuration Surefire : décision BackAli). Attendu : un `rewrite.patch` vide. |
| **B5** | **Manuel** | `.github/workflows/identity-security.yml:49-55` ; environnement local | OWASP non exécuté : dependency-check 13 échoue sans clé NVD, en local **et en CI** si le secret est absent. Le message « mise à jour lente » est trompeur, car le job échoue. | Fournir le secret `NVD_API_KEY` (décision de l'utilisateur, service externe), corriger le message, puis livrer un rapport OWASP avec CVSS < 7 avant la bascule. |
| **B6** | **BackAli** (revue Sonar), **BackCodeur** si refus | `config/SecurityConfig.java:31` ; `adapter/out/legacy/JdbcLegacyUserSource.java:63,119,129,142,164,169` | A12 exige 0 vulnérabilité critique non revue. S4502 (CSRF désactivé) est CRITICAL. Les 6 S2077 (SQL dynamique) sont des faux positifs : seul un schéma validé `[a-z_]+` et des constantes sont concaténés, toutes les valeurs passent en paramètres. | Revoir et marquer dans Sonar : S4502 « Safe » (API sans état, jeton en en-tête `Authorization`, aucun cookie, A1/A4) et S2077 « Safe » (`LegacySchema`), avec la justification. Activer `sonar.qualitygate.wait` sur Sonar way en CI (déjà prévu). |
| **B7** | **Manuel** (preuve CI), **BackTesteur** (robustesse) | `support/ApiTestBase.java:~190-210` | `verify` n'a jamais été vert de bout en bout en local : timeouts de connexion JDBC vers Testcontainers via `host.docker.internal` (Docker Desktop), aléatoires, avec un nouveau socket par `query()`. Ce n'est pas un défaut du produit : `MeApiIT` seul est vert. | Manuel : fournir un run `identity-service` vert sur GitHub Actions (hôte Docker natif). BackTesteur (non bloquant en soi) : réutiliser une connexion ou une `DataSource` dans `ApiTestBase.query`. |

Il n'y a **aucun point bloquant de sécurité dans le code**.

## 4. Revue manuelle

### 4.1 Hexagonal ✅
- ArchUnit (`HexagonalArchitectureTest`, 10 règles) : vert.
- Aucun import Spring, JPA, Jackson, Hibernate, adapter ou config dans `domain` et `application` (vérifié par grep).
- Les services applicatifs sont du Java pur, instanciés par `UseCaseConfig`.
- `@Entity` seulement dans `adapter.out.persistence` (`UserAccountEntity`, `OutboxMessageEntity`).
- Pas de `spring-boot-starter-data-rest` ni de `@RepositoryRestResource` (A5).
- Le filtre JWT passe par le port `ResolveAuthenticatedUserUseCase` : aucun adapter n'en appelle un autre.

### 4.2 Sécurité
- **JWT** (`NimbusTokenVerifier`) ✅ :
  - précédence §8.1 respectée : expiré avant tout, puis `exp` absent, `iat` futur, `alg`, signature, `nbf`, `username` ;
  - `alg` comparé à `RS256` **avant** la vérification cryptographique : `none`, `HS*` et les autres sont refusés ;
  - tolérance 0 s ;
  - extraction Lexik exacte (`LexikTokenExtractor`) ;
  - `WWW-Authenticate: Bearer` ;
  - pas de `BearerTokenAuthenticationFilter`.
  - Le `shouldNotFilter` sur `/api/auth/` est sans risque : la règle `authenticated()` couvre le reste, et le pare-feu strict de Spring refuse `..` et `;`.
- **Comparaisons à temps constant** ✅ :
  - `BCrypt.checkpw` et Argon2 de Spring ;
  - `hash_equals` côté Symfony ;
  - vérification factice pour un e-mail inconnu (`LoginTimingIT` vert).
  - Un mot de passe vide n'atteint jamais le service : le parseur renvoie 400, donc pas d'oracle d'existence.
- **Hachage** ✅ :
  - `$2y$`, coût 13 configurable (`identity.password.bcrypt-cost`, bornes 4–31) ;
  - pré-hachage `base64(sha512)` au-delà de 72 octets ou avec NUL, puis troncature à 72 octets (A2) ;
  - E15 appliquée dans le domaine (`PlainPassword.requireMaximumLength`) **avant** l'unicité et le hachage ; garde défensive dans le hasher ;
  - vérification : préfixe inconnu, hash corrompu, mot de passe vide ou de plus de 4096 octets → `false`, jamais d'exception.
- **Journaux (A13)** ✅ :
  - seuls des identifiants numériques, des noms de classes d'exception et des `eventId` sont journalisés ;
  - le 500 est assaini (classe seule, pas de message) ;
  - `SecretsDoNotLeakIT` est vert ;
  - aucun « Duplicate entry » ni e-mail dans les journaux des deux exécutions complètes, `ConcurrentRegistrationIT` compris.
- **Pas de DELETE sur `taskora.user`** ✅ :
  - le seul `DELETE` est la purge de `identity.outbox_message` ;
  - les droits MariaDB n'accordent que `SELECT, INSERT, UPDATE` sur `taskora.user` (`docker/mariadb/initdb.d/10-taskora-init.sh:70`, conforme à identity-migration §2).
- **Injection SQL** ✅ : `LegacySchema` est validé `[a-z_]+` ; seuls des constantes et ce nom sont concaténés ; toutes les valeurs sont paramétrées (`JdbcClient`, JPA).
- **Client Symfony** ✅ :
  - connexion 1 s, lecture 3 s (`SymfonyInvitationClaimClient.java:31`) ;
  - pas de redirection suivie (`HttpClient` par défaut) ;
  - 401, 400 et 409 → échec définitif ; 404, 408, 429, 5xx et erreurs réseau → nouvel essai.
- **Endpoint `/internal`** ✅ :
  - `hash_equals` ; secret vide ou absent → 401 (`InternalInvitationController.php:81-85`) ;
  - idempotent, une transaction ;
  - non routé par nginx ; port `api` publié sur `127.0.0.1` seulement.
- **CI** ✅ :
  - pas de `pull_request_target` ;
  - `permissions: {}` au niveau du workflow, permissions minimales par job ;
  - actions épinglées par SHA ;
  - `persist-credentials: false` partout ;
  - secrets éphémères masqués (`::add-mask::`) ;
  - upload SARIF et `checks: write` réservés au dépôt lui-même ;
  - aucune expression `${{ github.event.* }}` injectée dans un `run`.
- **Conteneur identity** ✅ :
  - utilisateur 10001 ;
  - `read_only` et `tmpfs /tmp` ;
  - `no-new-privileges`, `cap_drop: ALL` ;
  - réseau `backend` interne, sans port publié (port 8082 en `127.0.0.1` par l'override de développement seulement) ;
  - clés montées `:ro` ;
  - management sur 8091, jamais publié ;
  - healthcheck sans curl.

### 4.3 Conformité au contrat ✅
- Chemins, statuts et messages de `identity-service.yaml` et `identity-service.internal.yaml` retrouvés dans `ErrorMessages`, le domaine et `InternalInvitationController`.
- Chaque réponse des API IT est validée contre l'OpenAPI (`OpenApiValidation`).
- E15–E21 sont corrigés, pas reproduits :
  - aucune conversion PHP (`PhpJsonBody` : objet exigé, chaîne exigée) ;
  - corps vide ou fait d'espaces refusé ;
  - `PasswordTooLongException` → 400 ;
  - couleur validée par `\A#[0-9a-fA-F]{6}\z` ;
  - login en français, `Content-Type` non contrôlé ;
  - `"0"` traité comme un vrai mot de passe (`MeController.java:47`).
- `INTENTIONAL_DIVERGENCES` (`MirrorCompatibilityTest.java:35-50`) : 7 entrées, chacune référencée E15 à E21, aucune autre.

### 4.4 Couverture des scénarios ✅
Les tests ont été rapprochés des rapports Surefire et Failsafe (2e passage) et de `traceability.md`.

| Scénarios | Tests (classes) | Résultat |
|---|---|---|
| 001, 002, 003, 004 | Email/PersonName/PhpString/PhpEmailValidator/PlainPassword, RegisterUserServiceTest, RegisterApiIT, UserPersistenceIT, ConcurrentRegistrationIT, LegacyProjectionIT | verts |
| 005 | RegisterUserServiceTest, OutboxDeliveryServiceTest, RegistrationInvitationClaimIT, OutboxRelayIT, PHPUnit InternalInvitationControllerTest | verts |
| 006 | PHPUnit `testInviterGone`, RegistrationInvitationClaimIT | verts |
| 007, 008, 009 | AuthenticateServiceTest, LoginApiIT, LoginTimingIT, JsonLoginRequestParserTest | verts |
| 010–013 | NimbusTokenIssuer/Verifier, LexikKeyLoaderTest, RolesTest, AuthenticatedUserResolverTest, JwtSecurityApiIT | verts |
| 014–021 | UserProfileUpdateTest, ProfileServiceTest, MeApiIT (64/64 en isolé), LoginApiIT, LegacyProjectionIT | verts |
| 022 | ManagementPortIT | vert |
| 023 | SymfonyCompatiblePasswordHasherTest, LegacyImportServiceTest, LegacyImportIT | verts |
| 024 | PlainPasswordTest, LexikKeyLoaderTest, RegisterApiIT, SecretsDoNotLeakIT | verts |
| 028–034 | PlainPasswordTest, PhpJsonBodyTest, JsonLoginRequestParserTest, AvatarColorTest, UserProfileUpdateTest, Register/Login/MeApiIT | verts |

Les tests `compat` (Mirror, CrossToken, CrossPassword) n'ont pas été lancés : il faut la stack jetable de Manuel. Ils restent à prouver par `identity-compat` en CI.

### 4.5 Écarts de BackCodeur par rapport à la spec

| Écart | Décision | Motif |
|---|---|---|
| Projection : UPDATE puis INSERT avec l'id explicite, au lieu de `ON DUPLICATE KEY UPDATE` (`JdbcLegacyUserProjection.java:37-47`) | **ACCEPTÉ** | Plus sûr qu'A9 : un ODKU se déclencherait aussi sur l'index unique `email` et écraserait la ligne d'**un autre** compte. Ici, une collision sur l'e-mail ou l'id donne `DuplicateKeyException` → 409 et rollback. Le comptage des lignes trouvées (`useAffectedRows=false`, valeur par défaut du pilote MariaDB) est correct. `LegacyProjectionIT` est vert. **BackAli** met à jour A9. |
| `JpaUnitOfWork` rejoue jusqu'à 3 fois sur un interblocage InnoDB (`JpaUnitOfWork.java:30-41`) | **ACCEPTÉ** | Le travail est rejoué en entier dans une nouvelle transaction (UUID générés hors de la closure, aucune fuite d'état). Le rejeu tombe sur l'unicité → 409, ce qui satisfait SC-IDENT-002 (0 × 500). Réserve mineure m5. |
| Logger Hibernate `jdbc.error` réglé sur ERROR (`application.yml:48-49`) | **ACCEPTÉ** | Hibernate n'écrit le message SQL (qui contient la valeur en conflit) que si WARN est actif. Vérifié : aucun e-mail dans les journaux des tests de concurrence. Recommandation m4 : passer à `OFF`, plus robuste. |
| Reprise sans `ALTER TABLE … AUTO_INCREMENT` (`JdbcLegacyUserSource.java:74-75`) | **ACCEPTÉ** | InnoDB/MariaDB avancent le compteur au-delà d'un id inséré explicitement ; un `ALTER` validerait la transaction implicitement. Le contrôle `autoIncrementAboveMaxId` de `verify` et `LegacyImportIT` (`AUTO_INCREMENT = max+1`) sont verts. **BackAli** met à jour identity-migration §3. |
| Dockerfile construit avec `eclipse-temurin:25-jdk` et `mvnw` (`Dockerfile:8-16`) | **ACCEPTÉ** | Cohérent avec §7 (image de build Temurin 25, wrapper épinglé sur Maven 3.10.2), et l'image `maven:3.10.2-eclipse-temurin-25` manque sur certaines plateformes. Recommandation m10 : épingler les images par digest. |

## 5. Mesures

- JaCoCo, rapport fusionné du 2e passage :
  - domaine : lignes 100 %, branches 100 % ;
  - application : lignes 99,5 %, branches 95,0 % ;
  - module : lignes 90,2 %, branches 78,2 % ;
  - seuils A12 respectés.
- Tests :
  - Surefire : 566 ;
  - Failsafe : 233 (231 verts dans l'exécution complète, 233 en comptant `MeApiIT` relancé seul) ;
  - PHPUnit : 13.

## 6. Points mineurs

- **m1 (BackCodeur)** `config/UseCaseConfig.java:113-117` : si `INTERNAL_API_SECRET` est vide, identity démarre quand même. Chaque claim reçoit alors 401, passe en `FAILED` définitif, et les invitations sont perdues sans intervention. Refuser le démarrage (fail-fast) quand le secret est vide.
- **m2 (BackCodeur + Manuel)** `adapter/in/web/RequestBodies.java:13` lit le corps sans limite, alors que nginx accepte 105 Mo (`frontend/nginx.conf:24`), ce qui ouvre une saturation mémoire. Plafonner la lecture (par exemple 64 Kio → 400) et poser un `client_max_body_size` réduit sur `/api/auth/` et `/api/me`.
- **m3 (BackCodeur)** `JdbcLegacyUserSource.java:62-72` : l'import utilise un `ON DUPLICATE KEY UPDATE`, qui peut écraser une ligne « cible seule » de même e-mail sous un autre id. C'est le risque évité dans la projection ; il est peu probable ici. Ajouter un contrôle préalable « même e-mail, id différent » qui fait échouer l'import.
- **m4 (BackCodeur)** `application.yml:48-49` : passer `org.hibernate.orm.jdbc.error` et `SqlExceptionHelper` à `OFF`, plus robuste qu'`ERROR`.
- **m5 (BackCodeur)** `JpaUnitOfWork.java:34` : `PessimisticLockingFailureException` couvre aussi l'attente de verrou, ce qui peut faire jusqu'à 3 × `innodb_lock_wait_timeout`. Limiter le rejeu à `DeadlockLoserDataAccessException`, ou documenter.
- **m6 (BackCodeur)** Bugs Sonar MAJOR :
  - S2259 aux lignes 112, 119 et 176 de `JdbcLegacyUserSource.java` (`single()` nullable : `Objects.requireNonNull`) ;
  - S3655 à `User.java:90` (`get()` sur un Optional : utiliser `orElseThrow()`) ;
  - S5998 à `PhpEmailValidator.java:19` (répétition d'expression régulière). Le risque est faible, car l'entrée est bornée à 320 octets avant l'expression régulière ; à documenter.
- **m7 (BackCodeur)** `SymfonyCompatiblePasswordHasher.java:69-83` : le hash factice est calculé à la première connexion sur un e-mail inconnu, pas au démarrage comme le prévoit A2. Cette première réponse est plus lente une fois. Le calculer dans le constructeur, ce qui règle aussi B3.
- **m8 (BackCodeur)** `config/UseCaseConfig.java:62` : la passphrase reste en `String` dans `IdentityProperties`. L'effacement du `char[]` est donc symbolique. Acceptable ; à documenter.
- **m9 (Manuel)** `docker-compose.yml` : `database` est publié sur toutes les interfaces (`${DB_PORT:-3307}:3306`), avec des mots de passe par défaut, dont le nouveau compte `identity` (`identity_change_me`). C'était déjà le cas avant cette branche. Lier à `127.0.0.1`.
- **m10 (Manuel)** Images non épinglées par digest (`eclipse-temurin`, `mariadb`, `sonarqube`) ; pas de `mem_limit` ni de `pids_limit` sur `identity`.
- **m11 (Manuel)** `backend/.env.dist` contenait une passphrase JWT réelle jusqu'à `aa27c2f`, et elle reste dans l'historique git. La changer partout où elle a servi.
- **m12 (BackAli)** composer audit : passer Symfony 8.1.11 → **8.1.12** (10 avis du 25/11/2026, dont `security-core`, `security-http`, `http-kernel` et `routing`). Ce n'est pas introduit par la branche, mais il faut le planifier avant la bascule.
- **m13 (BackTesteur)** `docs/specs/traceability.md` : les lignes SC-IDENT sont encore « test écrit (rouge) ». Les passer au vert.
- **m14 (BackTesteur)** Le message du commit `6d86299` annonce « 5 test defects » alors qu'il contient les 7 corrections approuvées. Cosmétique.
- **m15 (Manuel)** `identity-service.yml`, job `identity-sonar` : `SONAR_TOKEN` est exposé au build d'une PR de branche interne. C'est acceptable pour un dépôt privé ; à garder en tête.

## 7. Contrôle d'intégrité des tests ✅

- `git diff eefa634..6d86299 -- 'services/**/src/test/**' backend/tests` touche 5 fichiers (+32/−13) et contient **exactement les 7 corrections approuvées** :
  1. `RegisterUserServiceTest.duplicateRace` : `when().thenThrow()` → `doThrow().when()`. L'assertion et le `verifyNoInteractions` sont inchangés.
  2. `UserPersistenceIT` : `success` comparé à `Boolean.TRUE` au lieu de `intValue() == 1`. Force équivalente.
  3. `LegacyProjectionIT` : helper `Timestamp → LocalDateTime`. Les valeurs attendues sont inchangées.
  4. `LegacyProjectionIT.profileProjected` : horloge déplacée au 05/07 (dans les 7 jours du jeton, autre jour que la création). Les assertions sont inchangées.
  5. `ApiTestBase` : `bcrypt-cost` et `internal-url` passent en `@TestPropertySource` pour que les `@DynamicPropertySource` des sous-classes l'emportent. Le crochet `symfonyUrl()` supprimé n'était surchargé nulle part (grep).
  6. PHPUnit : `setUseSchemaEditorApi(true)` et vidage manuel de toutes les tables de la base `*_test`. La garde « base `*_test` uniquement » est conservée.
  7. PHPUnit : `INTERNAL_API_SECRET` forcé dans `setUp`. `testEmptyServerSecretAlwaysRefuses` remet `''` et reste effectif.
- **Aucun affaiblissement** :
  - aucune assertion supprimée ni relâchée ;
  - aucun `@Disabled`, `assume*`, `markTestSkipped` ni `@Ignore` dans `services/identity-service/src/test` et `backend/tests` (grep vide).
- `98bcc4e` ne touche **aucun** fichier de test, ni `services/pom.xml`, ni `services/identity-service/pom.xml`, ni `backend/phpunit.dist.xml`, `backend/.env.test` ou `backend/composer.json`. Les seuils JaCoCo (0,80 / 0,90 / 0,80, `services/pom.xml:64-67`) n'ont pas changé depuis `eefa634`.
- `aa27c2f` ne touche aucun fichier de test.
- HEAD = `6d86299`, donc aucun code ultérieur à comparer à la référence.
