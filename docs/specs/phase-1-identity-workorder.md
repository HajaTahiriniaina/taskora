# Ordre de travail — Phase 1 : identity-service

```
Fonctionnalité : migration de l'identité (inscription, connexion, JWT, profil) vers Spring Boot
Spec : docs/specs/identity.md, docs/specs/phase-1-identity.md, docs/specs/decisions.md
Contrats : docs/contracts/openapi/identity-service.yaml, docs/contracts/openapi/identity-service.internal.yaml
Architecture : docs/architecture/backend-hexagonal.md (§7 versions, §8 faits vérifiés, §9 ADR A1–A14), docs/architecture/identity-migration.md
CI : docs/ci/requests/backend.md (Manuel)
Service(s) : identity-service (nouveau, services/identity-service) + backend Symfony (endpoint transitoire)
Branche : feat/identity-service (déjà créée ; aucun push sans accord de l'utilisateur)
1. BackTesteur — §2
2. BackCodeur  — §3
3. BackReviewer — §4
```
Auteur : BackAli, 26/11/2026. Ce fichier suffit à chaque agent : lisez-le en entier, puis les documents référencés pour le détail.

## 1. Règles communes

### 1.1 Propriété des fichiers
| Chemin | Propriétaire | Remarque |
|---|---|---|
| `services/pom.xml`, `services/identity-service/pom.xml`, `services/mvnw*`, `services/.mvn/**` | **BackTesteur** crée (étape 1) ; BackCodeur peut **ajouter** des dépendances ou plugins | BackCodeur ne modifie ni les seuils JaCoCo, ni la configuration Surefire/Failsafe (inclusions, exclusions, tags), ni le profil `compat` |
| Squelette de compilation (§2.3) dans `services/identity-service/src/main/java/**` | **BackTesteur** crée les signatures (corps `throw new UnsupportedOperationException("BackCodeur")`) ; BackCodeur les implémente | BackCodeur ne change **aucune signature publique** du squelette sans l'écrire dans son rapport (BackAli tranche) |
| `services/identity-service/src/test/**` | BackTesteur uniquement (hook `protect-tests.sh`) | |
| `backend/tests/**`, `backend/phpunit.dist.xml`, `backend/.env.test`, `require-dev` de `backend/composer.json` | BackTesteur uniquement | **Non protégé par le hook** : BackReviewer contrôle à la main |
| Tout le reste de `services/identity-service/src/main/**`, `services/identity-service/Dockerfile`, `backend/src/**`, `backend/config/**`, `backend/.env.dist` | BackCodeur | |
| `docker-compose.yml`, `frontend/nginx.conf`, `.env.example`, `.github/**` | Manuel (hors de cet ordre) | |
| `docs/reviews/**` | BackReviewer | |

### 1.2 Interdits
- Ne jamais lire, afficher ni commiter `.env` ou `backend/.env`. Aucune clé, passphrase ou secret réel dans le dépôt, les tests, les journaux ou les rapports.
- Les tests ne lisent pas la base réelle de développement ; seuls les tests `@Tag("compat")` parlent à la stack compose.
- Ne pas stager `backend/src/Service/TrelloImporter.php` (modification sans rapport en cours).
- Exécuter Maven depuis `services/` : `./mvnw -B -ntp …`. Sans Java 25 local : `docker run --rm -v "$PWD":/w -w /w -v "$HOME/.m2":/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock maven:3.10.2-eclipse-temurin-25 ./mvnw -B -ntp …` (le socket Docker sert à Testcontainers).

### 1.3 Faits qui changent les hypothèses de la spec (à appliquer, ils priment sur identity.md)
Détail et preuves : architecture §8.
1. JWT **RS256** confirmé ; `iat`/`exp` entiers ; en-tête `{"typ":"JWT","alg":"RS256"}` sans `kid` ; tolérance 0 s ; précédence des messages 401 du §8.1 (l'expiration l'emporte sur une signature fausse).
2. Hashes en base : **100 % `$2y$` coût 13** (3/3). Argon2 absent mais supporté en vérification.
3. **Bcrypt ne tronque pas** chez Symfony : au-delà de 72 octets (ou avec `\0`), pré-hachage `base64(sha512)` ; Java tronque ensuite l'entrée effective à 72 octets (A2). Limite **4096 octets** : chez Symfony 500 au hachage ; dans identity-service **400** (E15 corrigé, voir point 9), `false` à la vérification (login → 401).
4. Erreurs 400 du login : `{"error": "<message anglais>"}` (pas la forme Lexik) ; `Content-Type` sans `json` → 404 (§8.4).
5. Compte disparu → 401 `Invalid credentials.` ; revendication `username` absente → `Unable to find key "username" in the token payload.`
6. Connexion : la recherche de l'e-mail suit la collation `utf8mb4_unicode_ci` (casse, **accents**, **espaces finaux** ignorés ; espace initial → 401). On **ne normalise pas** l'e-mail au login (contrairement à l'hypothèse SC-IDENT-007) : la colonne d'identity a la même collation (A7).
7. `created_at` est en heure locale **Europe/Paris** (fuseau PHP).
8. La reprise est une commande ré-exécutable, pas une migration Flyway (A14).
9. **Écarts corrigés (décision utilisateur, decisions.md, ADR A15)** — identity-service **ne reproduit pas** ces comportements de Symfony :
   | Réf. | Règle identity-service | Message 400 exact | Scénario |
   |---|---|---|---|
   | E15 | mot de passe > 4096 octets UTF-8 à l'inscription ou dans `newPassword` (login : 401 inchangé) | `Le mot de passe ne doit pas dépasser 4096 octets.` | SC-IDENT-028 |
   | E16 | `register` et `PATCH /me` : JSON valide mais non objet (`[]`, `"x"`, `42`, `true`) ; corps vide = `{}` | `JSON invalide.` | SC-IDENT-029 |
   | E17 | champ connu présent non chaîne (`register` : `name`, `email`, `password`, `null` refusé ; `PATCH` : `name`, `avatarColor`, `currentPassword`, `newPassword`, `null` = absent) ; contrôle de type de tous les champs dans cet ordre **avant** les contrôles de contenu ; login inchangé | `Le champ "<nom>" doit être une chaîne de caractères.` | SC-IDENT-030 |
   | E18 | `avatarColor` ≠ `\A#[0-9a-fA-F]{6}\z` (dont `""`, `\n` final) ; après `name`, avant le mot de passe ; rien n'est appliqué | `Couleur d'avatar invalide.` | SC-IDENT-031 |
   Restent reproduits : `trim` PHP, longueurs en points de code, collation, `newPassword` `""`/`"0"` = aucun changement ; plus aucune conversion de type PHP.
   **Exclusions `compat`** : `MirrorCompatibilityTest` n'envoie **aucune** requête relevant de E15–E18 (mot de passe > 4096 octets, corps JSON non objet, champ non chaîne, `avatarColor` invalide) ; ces cas sont testés seulement contre identity-service (API IT). La liste exclue est une constante nommée `INTENTIONAL_DIVERGENCES` avec la référence E15–E18 en commentaire.

## 2. Étape 1 — BackTesteur (tests rouges, commit `test(identity): …`)

### 2.1 Scénarios à couvrir
| Priorité | Scénarios | Remarque |
|---|---|---|
| critique | SC-IDENT-001, 002, 003, 005, 007, 008, 010, 011, 012, 014, 017, 018, 023, 024 | obligatoires |
| important | SC-IDENT-004, 009, 013, 015, 019, 020, **028, 029, 030** | 025 et 026 sont des scénarios front (FrontTesteur) ; 028–030 = écarts E15–E17 corrigés |
| secondaire | SC-IDENT-006, 016, 021, **031** | 022 : identity n'expose que `/actuator/health` (test de démarrage) ; 027 hors phase (U3) |
Chaque test : `@DisplayName("SC-IDENT-0NN — …")` ; un test peut couvrir plusieurs identifiants (`SC-IDENT-003/004 — …`). Les cas listés « Limites » de identity.md sont tous testés, corrigés par §1.3.

### 2.2 Squelette Maven à créer
1. **`services/pom.xml`** (`com.taskora:taskora-services:0.1.0-SNAPSHOT`, `packaging pom`) :
   - parent `org.springframework.boot:spring-boot-starter-parent:4.2.0` ; `<modules>` : `identity-service` ;
   - propriétés : `java.version=25`, `maven.compiler.release=25`, `project.build.sourceEncoding=UTF-8`, versions épinglées du §7 de l'architecture (`spring-cloud.version=2026.0.0`, `archunit.version=1.5.1`, `rest-assured.version=6.3.0`, `swagger-request-validator.version=3.0.0`, `wiremock.version=3.13.2`, `bouncycastle.version=1.87`, `spotless.version=3.12.0`, `google-java-format.version=1.37.0`, `checkstyle-plugin.version=3.6.0`, `checkstyle.version=14.4.0`, `checker.version=4.4.0`, `rewrite-plugin.version=6.46.1`, `rewrite-recipe-bom.version=3.38.0`, `jacoco.version=0.8.16`, `sonar-plugin.version=5.9.0.7382`, `dependency-check.version=13.0.0`, `enforcer.version=3.7.0`) ;
   - `dependencyManagement` : import `org.springframework.cloud:spring-cloud-dependencies:${spring-cloud.version}` + versions des artefacts épinglés ;
   - `pluginManagement` + `plugins` : **Surefire** (inclut `**/*Test.java`, `excludedGroups=compat`), **Failsafe** (`**/*IT.java`, goals `integration-test`/`verify`, `excludedGroups=compat`), **JaCoCo** (`prepare-agent`, `prepare-agent-integration`, `merge`, `report`, `check` en phase `verify` : bundle lignes ≥ 0,80 ; paquets `com.taskora.identity.domain*` et `com.taskora.identity.application*` lignes ≥ 0,90 et branches ≥ 0,80), **Enforcer** (Java `[25,)`, Maven `[3.9,)`, `dependencyConvergence`) ;
   - profils : `quality` (Spotless check, Checkstyle `google_checks.xml` en échec, Checker Framework Nullness sur `src/main` via `annotationProcessorPaths`), `security-scan` (dependency-check, `failBuildOnCVSS=7`, formats HTML/JSON/SARIF), `compat` (Failsafe et Surefire avec `groups=compat`, `excludedGroups` vide, propriétés `compat.symfony.url`, `compat.identity.url`, `compat.web.url`), `sonar` (rien d'autre que la configuration `sonar.coverage.jacoco.xmlReportPaths`). OpenRewrite configuré en `pluginManagement` (recettes `org.openrewrite.java.spring.boot4.UpgradeSpringBoot_4_2` si disponible dans le BOM 3.38.0, sinon `org.openrewrite.java.migrate.UpgradeToJava25` et `org.openrewrite.staticanalysis.CommonStaticAnalysis` ; choisir celles qui existent et le noter dans le rapport).
2. **`services/identity-service/pom.xml`** (`identity-service`, `jar`) :
   - `compile` : `spring-boot-starter-webmvc`, `spring-boot-starter-security`, `spring-boot-starter-data-jpa`, `spring-boot-starter-flyway`, `org.flywaydb:flyway-mysql`, `spring-boot-starter-validation`, `spring-boot-starter-actuator`, `spring-boot-starter-restclient`, `com.nimbusds:nimbus-jose-jwt` (version gérée), `org.bouncycastle:bcprov-jdk18on`, `org.bouncycastle:bcpkix-jdk18on`, `spring-security-crypto` (via starter) ; `runtime` : `org.mariadb.jdbc:mariadb-java-client` ;
   - `test` : `spring-boot-starter-webmvc-test`, `spring-boot-starter-security-test`, `spring-boot-starter-data-jpa-test`, `spring-boot-testcontainers`, `org.testcontainers:testcontainers-mariadb`, `org.testcontainers:testcontainers-junit-jupiter`, `com.tngtech.archunit:archunit-junit5`, `io.rest-assured:rest-assured`, `io.rest-assured:spring-mock-mvc`, `com.atlassian.oai:swagger-request-validator-restassured` (et `-mockmvc` si disponible en 3.0.0), `org.wiremock:wiremock-standalone`, `org.awaitility:awaitility` (version gérée par Boot) ;
   - **pas** de `spring-boot-starter-data-rest` (A5) ; plugin `spring-boot-maven-plugin`.
3. **Wrapper** : `cd services && docker run --rm -v "$PWD":/w -w /w maven:3.10.2-eclipse-temurin-25 mvn -N -B wrapper:wrapper -Dtype=only-script -Dmaven=3.10.2` (crée `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`).
4. `services/.gitignore` : `target/`.
5. `services/identity-service/src/main/resources/application.yml` **minimal** (BackTesteur) : uniquement les clés lues par les tests sous forme de placeholders (`identity.jwt.*`, `identity.legacy.*`, `identity.symfony.*`, `identity.password.bcrypt-cost`) ; BackCodeur le complète.

### 2.3 Squelette de compilation (src/main, signatures seules)
Paquet racine `com.taskora.identity`. Corps : `throw new UnsupportedOperationException("BackCodeur")`. Les records sont créés complets (ce sont des contrats de données, sans logique) sauf indication.
- `IdentityServiceApplication` (`@SpringBootApplication`, `main`).
- `domain.model` :
  - `record UserId(int value)` ; `record HashedPassword(String value)` (`toString()` masqué : `HashedPassword[***]`) ; `record Roles(List<String> stored)` + `List<String> effective()` (stub).
  - `final class PhpString` : `static String trim(String)`, `static int length(String)` (points de code), `static String truncate(String, int maxCodePoints)`, `static String toLower(String)`.
  - `final class PhpEmailValidator` : `static boolean isValid(String)` (équivalent `filter_var($e, FILTER_VALIDATE_EMAIL)` sans drapeau).
  - `record Email(String value)` : `static Email fromRegistration(String raw)` (trim → obligatoire → tronqué à 180 → minuscules → format), `static Email ofStored(String value)`.
  - `record PersonName(String value)` : `static PersonName of(String raw)` (trim → obligatoire → tronqué à 120).
  - `record AvatarColor(String value)` : `static final List<String> PALETTE`, `static AvatarColor random(RandomGenerator)`, `static AvatarColor parse(String raw)` (E18 : `\A#[0-9a-fA-F]{6}\z`, sinon `ValidationException("Couleur d'avatar invalide.")`).
  - `final class PlainPassword` : `static PlainPassword of(String raw)`, `String value()`, `int codePointLength()`, `int utf8Length()`, `boolean isEmpty()`, `void requireMinimumLength()` (8, sinon `ValidationException`), `void requireMaximumLength()` (4096 octets UTF-8, sinon `PasswordTooLongException`, E15), `toString()` masqué.
  - `record NewUser(Email email, PersonName name, HashedPassword password, AvatarColor avatarColor, Roles roles, LocalDateTime createdAt)`.
  - `final class User` : accesseurs `id()`, `email()`, `name()`, `password()`, `avatarColor()`, `roles()`, `createdAt()` ; `static User rehydrate(UserId, Email, PersonName, HashedPassword, AvatarColor, Roles, LocalDateTime)` ; `ProfileUpdateResult updateProfile(ProfileUpdate update, PasswordHashing hashing)`.
  - `record ProfileUpdate(Optional<String> name, Optional<String> avatarColor, Optional<String> currentPassword, Optional<String> newPassword)` — `name` présent = clé présente et non `null` ; `newPassword` présent = non vide au sens PHP `empty` (calculé par l'adapter web).
  - `record ProfileUpdateResult(User user, Set<String> changedFields, boolean passwordChanged)`.
- `domain.service.PasswordHashing` : `HashedPassword hash(PlainPassword)`, `boolean matches(PlainPassword, HashedPassword)`.
- `domain.event` : `sealed interface DomainEvent permits UserRegistered, UserProfileUpdated` (`UUID eventId()`, `Instant occurredAt()`, `UserId userId()`) ; `record UserRegistered(UUID eventId, Instant occurredAt, UserId userId, String email, String name, String avatarColor)` ; `record UserProfileUpdated(UUID eventId, Instant occurredAt, UserId userId, String name, String avatarColor, List<String> changed)`.
- `domain.exception` (complètes, `RuntimeException`) : `ValidationException(String message)`, `EmailAlreadyUsedException`, `InvalidCurrentPasswordException`, `PasswordTooLongException`, `InvalidCredentialsException`, `TokenRejectedException(TokenRejection reason)`, `enum TokenRejection { INVALID, EXPIRED, MISSING_USERNAME, ACCOUNT_NOT_FOUND }`.
- `application.port.in` (interfaces + records) :
  - `RegisterUserUseCase` : `AuthResult register(RegisterUserCommand)` ; `record RegisterUserCommand(String name, String email, String password)` (chaînes déjà converties façon PHP, `null` = absent).
  - `AuthenticateUseCase` : `AuthResult authenticate(LoginCommand)` ; `record LoginCommand(String email, String password)`.
  - `GetProfileUseCase` : `UserView get(UserId)`.
  - `UpdateProfileUseCase` : `UserView update(UserId, ProfileUpdate)`.
  - `ResolveAuthenticatedUserUseCase` : `AuthenticatedUser resolve(String token)` (lève `TokenRejectedException`).
  - `DeliverPendingMessagesUseCase` : `int deliverDue(int batchSize)`, `boolean deliverNow(UUID eventId)`.
  - `ImportLegacyUsersUseCase` : `ImportReport importAll()`, `VerificationReport verify()`.
  - records : `AuthResult(String token, UserView user)`, `UserView(int id, String name, String email, String avatarColor)`, `AuthenticatedUser(UserId id, String email, List<String> roles)`, `ImportReport(int sourceRows, int inserted, int updated, int unchanged, int targetOnly)`, `VerificationReport(boolean consistent, List<String> failedChecks, Map<String, String> metrics)` (métriques : nombres et empreintes uniquement).
- `application.port.out` (interfaces + records) :
  - `UserRepositoryPort` : `Optional<User> findByLoginEmail(String rawEmail)`, `Optional<User> findByEmail(Email)`, `Optional<User> findById(UserId)`, `boolean existsByEmail(Email)`, `User insert(NewUser)` (lève `EmailAlreadyUsedException` sur violation d'unicité), `void update(User)`.
  - `PasswordHasherPort extends PasswordHashing` : `void verifyAgainstDummy(PlainPassword)` (temps comparable pour un e-mail inconnu).
  - `TokenIssuerPort` : `String issue(User)`.
  - `TokenVerifierPort` : `VerifiedToken verify(String token)` (lève `TokenRejectedException`) ; `record VerifiedToken(String username, Map<String, Object> claims)`.
  - `LegacyUserProjectionPort` : `void upsert(User)`.
  - `OutboxPort` : `void appendEvent(DomainEvent event)` (statut `HELD`, destination `broker`), `void appendInvitationClaim(InvitationClaim claim, Instant availableAt)`, `List<PendingClaim> lockDueClaims(Instant now, int limit)`, `Optional<PendingClaim> lockClaim(UUID eventId)`, `void markDelivered(UUID eventId, Instant at)`, `void markRetry(UUID eventId, int attempts, Instant nextAttemptAt, String errorCode)`, `void markFailed(UUID eventId, String errorCode)` ; records `InvitationClaim(UUID eventId, UserId userId, Email email)`, `PendingClaim(InvitationClaim claim, int attempts)`.
  - `InvitationClaimPort` : `ClaimOutcome claim(InvitationClaim)` ; `enum ClaimOutcome { DELIVERED, RETRYABLE_FAILURE, PERMANENT_FAILURE }`.
  - `LegacyUserSourcePort` : `int countNormalizedEmailDuplicates()`, `ImportReport copyAllIntoIdentity()`, `Map<String, String> sourceMetrics()`, `Map<String, String> targetMetrics()`.
  - `UnitOfWorkPort` : `<T> T inTransaction(Supplier<T> work)`.
- `application.service` : classes vides `RegisterUserService`, `AuthenticateService`, `ProfileService` (Get + Update), `AuthenticatedUserResolver`, `OutboxDeliveryService`, `LegacyImportService` implémentant les ports in, constructeurs avec les ports out + `Clock` (+ `RandomGenerator` pour l'inscription), méthodes en stub.
Rien d'autre dans `src/main` (pas d'adapter, pas de configuration Spring) : les tests d'adapters et d'API échouent alors parce que les beans/classes d'adapter manquent. Les classes d'adapters que les tests instancient directement (`SymfonyCompatiblePasswordHasher`, `NimbusTokenIssuer`, `NimbusTokenVerifier`, `LexikKeyLoader`, `PhpJsonBody`, `JsonLoginRequestParser`) sont aussi créées en stub avec leurs constructeurs :
  - `adapter.out.security.LexikKeyLoader(Path privateKey, Path publicKey, char[] passphrase)` : `RSAPrivateKey privateKey()`, `RSAPublicKey publicKey()` ;
  - `adapter.out.security.SymfonyCompatiblePasswordHasher(int bcryptCost)` ;
  - `adapter.out.security.NimbusTokenIssuer(LexikKeyLoader keys, Clock clock, Duration ttl)` ;
  - `adapter.out.security.NimbusTokenVerifier(RSAPublicKey key, Clock clock)` ;
  - `adapter.in.web.PhpJsonBody` (nom conservé, **sans conversion PHP** depuis E16/E17) : `static PhpJsonBody parse(byte[] body)` (vide → `{}` ; non JSON **ou JSON non objet** → `ValidationException("JSON invalide.")`), `boolean has(String key)`, `boolean isSetLikePhp(String key)` (présent et non `null`), `String requiredString(String key)` (inscription : `null` si absent ; présent non chaîne, `null` compris → `ValidationException("Le champ \"<key>\" doit être une chaîne de caractères.")`), `String optionalString(String key)` (`PATCH` : `null` si absent ou `null` ; autre non-chaîne → même exception), `boolean nonEmptyLikePhp(String key)` (chaîne ni `""` ni `"0"`). `stringLikePhp` est **supprimée** ;
  - `adapter.in.web.JsonLoginRequestParser` : `static LoginCommand parse(String contentType, byte[] body)` (lève `JsonLoginException(int status, String message)`, à créer aussi).

### 2.4 Tests à écrire (`services/identity-service/src/test/java/com/taskora/identity/...`)
Utilitaires (`support/`) : `TestKeys` (paire RSA 2048 générée en mémoire et écrite en PEM PKCS#8 **chiffré** dans un `@TempDir`, passphrase aléatoire), `TokenFactory` (jetons au format Lexik : en-tête `{"typ":"JWT","alg":"RS256"}`, `iat`/`exp` entiers, variantes `alg:none`, HS256 signé avec la clé publique PEM comme secret, autre clé, `exp` absent, `iat` futur, `nbf` futur, expiré + signature fausse, sans `username`), `MutableClock`, `MariaDbContainerSupport` (Testcontainers `mariadb:11.8`, `--character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci`, création des schémas `identity` et `taskora` + table `taskora.user` **exactement** comme en §8.3 + `invitation`/`board_member` non nécessaires), `OpenApiValidation` (filtre RestAssured/MockMvc sur `docs/contracts/openapi/identity-service.yaml`, chemin relatif `../../docs/...`), `LogCapture` (Spring `OutputCaptureExtension`).

Fixtures (`src/test/resources/`) générées **une fois** dans le conteneur `api` (lecture seule, pas d'accès base), le script de génération étant commité à côté (`fixtures/README.md` + commandes) :
- `fixtures/php-hashes.json` : `{password, hash, expectedValid}` produits par `Symfony\Component\PasswordHasher\Hasher\NativePasswordHasher` (coûts 13 et 4), `password_hash(PASSWORD_ARGON2ID|ARGON2I)`, `sodium_crypto_pwhash_str`, variantes `$2a$`/`$2b$` (même sel, préfixe réécrit), mots de passe : ASCII 8 car., accents (`ééééééé8`), emoji, exactement 72 octets, 73 octets, 100 octets, contenant `\0`, 4096 octets ; plus des hashes corrompus/inconnus (`expectedValid=false`). Commande type : `docker compose exec -T api php -r 'require "vendor/autoload.php"; …' > …`.
- `compat/php-filter-validate-email.json` : ≥ 150 candidats (`{input, valid}`) passés à `filter_var(…, FILTER_VALIDATE_EMAIL)` : cas usuels, guillemets, IP littérales, points consécutifs, tirets, TLD numériques, longueurs 64/65 de partie locale, 254/255/320 total, Unicode, espaces, `\n` final.
- `compat/php-string-functions.json` : entrées → `trim`, `mb_strlen`, `mb_substr(…,0,120)`, `mb_strtolower`, `preg_match('/^#[0-9a-fA-F]{6}$/')`.
- `fixtures/legacy-users.sql` : jeu d'essai d'identity-migration.md §7 (comptes `@exemple.test`).

| Lot | Classe (chemin relatif à `com/taskora/identity/`) | Type | Scénarios / contenu |
|---|---|---|---|
| Architecture | `architecture/HexagonalArchitectureTest` | ArchUnit (`*Test`) | 5 règles du §3 ; `domain` sans `org.springframework..`, `jakarta.persistence..`, `com.fasterxml..`, `tools.jackson..` ; `application` sans Spring ; entités `@Entity` seulement dans `adapter.out.persistence` ; aucun adapter ne dépend d'un autre adapter ; pas de cycle ; aucune classe `@RepositoryRestResource` ni `org.springframework.data.rest..` (A5) |
| Domaine | `domain/model/EmailTest` | unitaire | 001 (trim + minuscules), 003 (absent/vide → message), 004 (troncature à 180 **avant** validation) |
| ↳ | `domain/model/PhpEmailValidatorTest` | unitaire paramétré | golden `compat/php-filter-validate-email.json` : 100 % des cas identiques |
| ↳ | `domain/model/PhpStringTest` | unitaire paramétré | golden `compat/php-string-functions.json` |
| ↳ | `domain/model/PersonNameTest` | unitaire | 001, 003 (espaces seuls), 004/015 (150 → 120 points de code, emoji compté 1) |
| ↳ | `domain/model/AvatarColorTest` | unitaire | 001 (palette, `RandomGenerator` déterministe), 016 (`#0EA5E9` conservé tel quel), 031 (`red`, `#12345`, `#1234567`, `""`, `#0EA5E9\n`, ` #0EA5E9` → `ValidationException("Couleur d'avatar invalide.")`) |
| ↳ | `domain/model/PlainPasswordTest` | unitaire | 003/018 (7 → refus, 8 → ok, `ééééééé` 7 → refus), 028 (4096 octets ok, 4097 → `PasswordTooLongException`, 2049 × `é` → refus), `toString` masqué (024) |
| ↳ | `domain/model/UserProfileUpdateTest` | unitaire | 015, 016, 018 (actuel faux, actuel absent, nouveau trop court), 019 (erreur → état d'origine intact, `ProfileUpdateResult` non produit), 020 (pas de champ e-mail), ordre des contrôles, `changedFields`, `passwordChanged` |
| ↳ | `domain/model/RolesTest` | unitaire | 010 (`ROLE_USER` ajouté, sans doublon) |
| Application | `application/service/RegisterUserServiceTest` | unitaire (ports mockés) | 001, 002 (existe → 409, aucune écriture), 003 (ordre name → email → password → unicité), 005 (dans **une** unité de travail : insert + projection + 3 messages d'outbox : claim immédiat, claim de rattrapage à +60 s, `UserRegistered` ; **après** l'unité de travail : `deliverNow` du premier claim ; échec de livraison → résultat 201 quand même) ; 028 : `PasswordTooLongException` (> 4096 octets) levée après la longueur minimale et avant l'unicité, sans écriture ni hachage |
| ↳ | `application/service/AuthenticateServiceTest` | unitaire | 007, 008 (inconnu → `verifyAgainstDummy` appelé puis `InvalidCredentialsException` ; mauvais mot de passe → même exception), e-mail passé **brut** à `findByLoginEmail`, e-mail ou mot de passe > 4096 octets → refus sans appel au dépôt |
| ↳ | `application/service/ProfileServiceTest` | unitaire | 014, 015–020 ; une seule unité de travail (update + projection + événement) ; `UserProfileUpdated` seulement si `name`/`avatarColor` changent ; erreur → aucun appel d'écriture |
| ↳ | `application/service/AuthenticatedUserResolverTest` | unitaire | 013 (`ACCOUNT_NOT_FOUND`), `MISSING_USERNAME` |
| ↳ | `application/service/OutboxDeliveryServiceTest` | unitaire | 005 : `DELIVERED` → `markDelivered` ; `RETRYABLE_FAILURE` → `markRetry` avec backoff 5 s, 10 s, 20 s… plafonné à 5 min ; `PERMANENT_FAILURE` → `markFailed` |
| ↳ | `application/service/LegacyImportServiceTest` | unitaire | doublons normalisés > 0 → échec sans copie ; rapport `verify` incohérent → `consistent=false` |
| Sécurité (adapters) | `adapter/out/security/SymfonyCompatiblePasswordHasherTest` | unitaire paramétré | 023 : 100 % de `fixtures/php-hashes.json` ; nouveaux hashes `$2y$13$`, longueur 60 ; > 72 octets : hash Java vérifié par l'algorithme PHP (pré-hachage + troncature) ; vide → `false` ; > 4096 → `false` en vérification, exception en hachage ; corrompu → `false` sans exception |
| ↳ | `adapter/out/security/LexikKeyLoaderTest` | unitaire | PKCS#8 chiffré PBES2 (3DES comme Lexik, et AES-256) ; mauvaise passphrase → exception dont le message ne contient ni la passphrase ni du PEM (024) |
| ↳ | `adapter/out/security/NimbusTokenIssuerTest` | unitaire | 010 : en-tête exact, `exp − iat = 604800`, entiers, `username`, `roles`, `uid`, aucune autre revendication |
| ↳ | `adapter/out/security/NimbusTokenVerifierTest` | unitaire paramétré | 012 : tableau de précédence du §8.1 (chaque variante de `TokenFactory` → `TokenRejection` attendu) ; jeton au format Lexik valide → accepté ; `uid` absent accepté (jetons Symfony) |
| API | `adapter/in/web/RegisterApiIT` | MockMvc `@SpringBootTest` + Testcontainers MariaDB + validation OpenAPI | 001–004, 024 : statuts, corps exacts, validation OpenAPI de chaque réponse ; 028 (4096 octets → 201, 4097 → 400) ; 029 (`[]`, `"x"`, `42`, `true` → 400 `JSON invalide.` ; corps vide → 400 `name` obligatoire) ; 030 (paramétré champ × type : nombre, booléen, `null`, tableau, objet ; priorité du premier champ ; type vérifié avant contenu) |
| ↳ | `adapter/in/web/LoginApiIT` | idem | 007 (casse différente, accent `é`↔`e`, espace final → 200 ; espace initial → 401), 008 (corps identiques), 009 (tous les cas du tableau §8.4 avec messages exacts, `Content-Type` absent → 404), 017 (ancien → 401, nouveau → 200) |
| ↳ | `adapter/in/web/MeApiIT` | idem | 014, 015 (`null` ignoré, `""` → 400), 016, 017, 018 (`newPassword` `""`/`"0"` → aucun changement), 019, 020, 021 (ancien jeton toujours accepté après changement de mot de passe) ; 028 (`newPassword` 4097 octets → 400, hash inchangé) ; 029 ; 030 (`null` = absent, autres types → 400) ; 031 (chaque valeur invalide → 400, `name` envoyé en même temps non appliqué ; `{"name":"","avatarColor":"red"}` → message `name`) |
| ↳ | `adapter/in/web/JwtSecurityApiIT` | idem | 011 (sans en-tête, `Basic …`, `Bearer` seul, `Bearer a b` → `JWT Token not found`), 012 (chaque variante → message exact + `WWW-Authenticate: Bearer`), 013 (compte supprimé → `Invalid credentials.`), `/api/auth/*` accessibles sans jeton, `/actuator/health` sur le port de management uniquement (022) |
| ↳ | `adapter/in/web/PhpJsonBodyTest`, `adapter/in/web/JsonLoginRequestParserTest` | unitaire | 029 (non-objet → `JSON invalide.`, vide → `{}`), 030 (aucune conversion : non-chaîne → message exact, `null` selon le mode), `nonEmptyLikePhp`, messages du parseur de login (inchangés) |
| Intégration | `adapter/out/persistence/UserPersistenceIT` | Testcontainers | Flyway V1/V2 sur schéma vide ; collation : recherche accent/casse/espace final ; index unique (`É@x` vs `e@x`) |
| ↳ | `adapter/out/persistence/ConcurrentRegistrationIT` | Testcontainers + HTTP | 002 : 8 inscriptions parallèles même e-mail (casses différentes) → 1 × 201, 7 × 409, 0 × 500, 1 ligne dans `identity` et dans `taskora.user` |
| ↳ | `adapter/out/legacy/LegacyProjectionIT` | Testcontainers | U1-B : inscription et profil → ligne `taskora.user` identique (id explicite, hash, `roles=[]`, `created_at` en heure de Paris) ; échec forcé de la projection → rien dans `identity` ; aucune requête `DELETE` sur `taskora.user` (journal SQL) |
| ↳ | `adapter/out/legacy/RegistrationInvitationClaimIT` | Testcontainers + WireMock | 005/006 : corps envoyé = schéma `InvitationClaimRequest`, en-tête `X-Internal-Secret` ; Symfony 200 → `DELIVERED` avant la réponse 201 ; 500 ou délai > 3 s → 201 puis rejeu par le relais (Awaitility, `MutableClock`) ; 401/409 → `FAILED` ; claim de rattrapage envoyé à +60 s |
| ↳ | `adapter/out/messaging/OutboxRelayIT` | Testcontainers | `SKIP LOCKED` : deux relais concurrents ne livrent jamais deux fois le même message ; `UserRegistered`/`UserProfileUpdated` en `HELD`, charges valides contre les schémas JSON du contrat interne |
| ↳ | `adapter/in/cli/LegacyImportIT` | Testcontainers | 023 : `fixtures/legacy-users.sql` dans `taskora.user` → `run` → login de **chaque** compte du jeu d'essai via l'API (100 %) ; 2e `run` → aucune modification ; `verify` vert ; `AUTO_INCREMENT` = max+1 ; compte présent seulement dans la cible → signalé, conservé |
| ↳ | `security/SecretsDoNotLeakIT` | Testcontainers + capture des journaux | 024 : sur un parcours inscription → login → PATCH mot de passe → erreurs, aucune occurrence du mot de passe, du hash, du jeton, de la passphrase ni du PEM dans les réponses et journaux |
| ↳ | `security/LoginTimingIT` | Testcontainers | 008 : 30 essais e-mail inconnu vs mauvais mot de passe, coût 13 : écart des médianes < 50 % |
| Compatibilité | `compat/MirrorCompatibilityTest` (`@Tag("compat")`) | RestAssured sur la stack compose | mêmes requêtes vers Symfony (`compat.symfony.url`) et identity (`compat.identity.url`) : 001–003, 007–009, 014–018, **hors** divergences E15–E18 (§1.3 point 9, constante `INTENTIONAL_DIVERGENCES`) ; comparaison statut + JSON analysé ; e-mails uniques `compat-<uuid>@exemple.test` (à lancer seulement sur une stack jetable) |
| ↳ | `compat/CrossTokenCompatibilityTest` (`@Tag("compat")`) | idem | 010 : jeton de login Symfony → `GET /api/me` identity 200 ; jeton identity → `GET /api/boards` Symfony 200 |
| ↳ | `compat/CrossPasswordCompatibilityTest` (`@Tag("compat")`) | idem | 023 : compte inscrit via identity → login Symfony 200 (projection + hash `$2y$`) ; changement de mot de passe via identity → login Symfony avec le nouveau |

### 2.5 Test PHPUnit de l'endpoint Symfony transitoire
1. `backend/composer.json` `require-dev` : `phpunit/phpunit` `^13.4`, `symfony/browser-kit` `8.1.*` ; `composer update phpunit/phpunit symfony/browser-kit --with-dependencies` dans le conteneur `api` (met à jour `composer.lock`). Le `Dockerfile` (`--no-dev`) n'est pas touché.
2. `backend/phpunit.dist.xml` (bootstrap `tests/bootstrap.php`, `KERNEL_CLASS=App\Kernel`), `backend/tests/bootstrap.php`, `backend/.env.test` (valeurs **factices** uniquement : `APP_SECRET`, `INTERNAL_API_SECRET=test-internal-secret-0123456789abcdef`, `DATABASE_URL` vers la base `taskora_test` ; aucune clé AWS/VAPID).
3. `backend/tests/Controller/InternalInvitationControllerTest.php` (`WebTestCase`, schéma recréé par test) — `@testdox` préfixés `SC-IDENT-005` / `SC-IDENT-006` :
   - sans en-tête, secret faux, `INTERNAL_API_SECRET` vide côté serveur → 401 `{"error":"Accès interne refusé."}`, aucune écriture ;
   - deux invitations (« Sprint » invitée par Alice, « Roadmap ») → 200 `{"claimed":2,"boardIds":[…]}`, adhésions `member`, notifications `board_invite` « Alice vous a ajouté au tableau « Sprint » », invitations supprimées ;
   - rejeu du même appel → 200 `{"claimed":0,"boardIds":[]}`, rien de doublé ;
   - adhésion déjà existante → pas de doublon ni de notification, invitation supprimée ;
   - `invited_by` NULL → « Quelqu'un vous a ajouté au tableau « … » », acteur nul (006) ;
   - tableau dans la corbeille → adhésion créée ;
   - `userId` inconnu → 404 `Utilisateur introuvable.` ; e-mail différent → 409 ; corps non JSON → 400 `JSON invalide.` ; champ manquant → 400 ;
   - non-régression : `POST /api/auth/register` Symfony consomme toujours les invitations (même résultat qu'avant refactorisation).
   Exécution : voir `docs/ci/requests/backend.md` (job `backend-phpunit`). La base `taskora_test` est fournie par Manuel ; si elle n'existe pas encore, l'indiquer dans le rapport (pas de contournement).

### 2.6 Vérifications et commit
- `./mvnw -B -ntp -pl identity-service -am verify` : **compile**, et tous les tests sont **rouges** pour la bonne raison (`UnsupportedOperationException`, bean ou classe d'adapter manquant, 404) — aucun test vert sauf ceux qui ne testent que des fixtures. `-Pcompat` : les appels vers Symfony répondent (comparaisons rouges faute d'identity). PHPUnit : rouge (404 sur `/internal/invitations/claim`) sauf la non-régression de `register` (verte).
- Mettre à jour `docs/specs/traceability.md` (lignes IDENT : fichier/méthode, statut `rouge`).
- Commit unique : `test(identity): SC-IDENT-001…024 tests rouges, squelette Maven et ports` (stager seulement `services/**`, `backend/tests/**`, `backend/phpunit.dist.xml`, `backend/.env.test`, `backend/composer.json`, `backend/composer.lock`, `docs/specs/traceability.md`). Rapport : hash du commit (référence d'intégrité), couverture par scénario, cas non couverts et raison.

## 3. Étape 2 — BackCodeur (commit `feat(identity): …`, sans toucher aux tests)
Entrée : le hash du commit de BackTesteur. Interdit : toute écriture dans `services/**/src/test/**`, `backend/tests/**`, `backend/phpunit.dist.xml`, `backend/.env.test`, le `require-dev` de `composer.json`, les seuils et la configuration de tests des POM.

### 3.1 Domaine et application
- Implémenter le squelette §2.3 en respectant **exactement** l'ordre des contrôles et les messages de identity.md §2 (« Ordre dans identity-service ») corrigés par §1.3, y compris les écarts corrigés E15–E18 (§1.3 point 9) : ne pas les reproduire « à la Symfony ».
- `PhpString.trim` : uniquement ` `, `\t`, `\n`, `\r`, `\0`, `\x0B` ; longueurs et troncatures en **points de code** ; `toLower` = `toLowerCase(Locale.ROOT)`.
- `AvatarColor.parse` : `\A#[0-9a-fA-F]{6}\z` (pas `$`), valeur stockée telle quelle ; `PlainPassword.requireMaximumLength` appelé après `requireMinimumLength` (inscription) et après la vérification du mot de passe actuel puis la longueur minimale (`PATCH`).
- `PhpEmailValidator` : port fidèle de l'expression de `php_filter_validate_email` (ext/filter, sans `FILTER_FLAG_EMAIL_UNICODE`) + limites de longueur PHP ; doit passer 100 % du golden.
- Services applicatifs en Java pur, transactions via `UnitOfWorkPort` ; `RegisterUserService` : unité de travail (insert, projection, 3 messages d'outbox) **puis** `deliverNow` hors transaction (exceptions de livraison capturées et journalisées sans e-mail) ; `AuthenticateService` : `verifyAgainstDummy` pour un e-mail inconnu.

### 3.2 Adapters attendus
| Adapter | Classe(s) | Points imposés |
|---|---|---|
| `adapter.in.web` | `AuthController` (`POST /api/auth/register` 201, `POST /api/auth/login` 200), `MeController` (`GET`, `PATCH /api/me`), `PhpJsonBody`, `JsonLoginRequestParser`, `JsonLoginException`, `ApiErrorHandler`, `ErrorMessages`, `UserResponse`, `AuthResponse` | Lire le corps brut (`byte[]`) via `PhpJsonBody` : pas de liaison Jackson automatique sur des DTO typés (elle convertirait les types en silence) ; **aucune conversion PHP** (E17) ; contrôle de forme (E16) puis de type de tous les champs connus, dans l'ordre du contrat, avant tout appel de cas d'utilisation ; `PasswordTooLongException` → 400 (E15), plus jamais 500 ; réponses conformes au contrat ; 404/405 en `{"error"}` ; `server.error.include-stacktrace=never` |
| `adapter.in.web.security` | `SecurityConfig` (dans `config`), `LexikCompatibleJwtFilter`, `LexikTokenExtractor`, `LexikAuthenticationEntryPoint` | sans état, CSRF désactivé, `/api/auth/**` public, tout le reste de `/api/**` authentifié ; messages et précédence §8.1 ; `WWW-Authenticate: Bearer` ; pas de `BearerTokenAuthenticationFilter` |
| `adapter.in.scheduling` | `OutboxRelayScheduler` | `@Scheduled(fixedDelay = 5 s)`, lot de 20, désactivable (`identity.outbox.relay.enabled`) |
| `adapter.in.cli` | `LegacyImportRunner` | `--identity.legacy-import=run|verify`, `--identity.outbox-drain` ; code de sortie ≠ 0 si incohérent ; sortie : nombres et empreintes seulement |
| `adapter.out.persistence` | `UserAccountEntity`, `UserAccountJpaRepository`, `JpaUserRepositoryAdapter`, `OutboxMessageEntity`, `OutboxJpaRepository`, `JpaOutboxAdapter`, `JpaUnitOfWork` (`TransactionTemplate`), migrations `db/migration/V1__create_user_account.sql`, `V2__create_outbox_message.sql` (texte exact d'identity-migration.md §1) | violation `uq_user_account_email` → `EmailAlreadyUsedException` ; `lockDueClaims` en `SELECT … FOR UPDATE SKIP LOCKED` ; `findByLoginEmail` = égalité SQL simple sur la colonne |
| `adapter.out.security` | `LexikKeyLoader`, `SymfonyCompatiblePasswordHasher`, `NimbusTokenIssuer`, `NimbusTokenVerifier` | A1 et A2 à la lettre ; clé lue une fois au démarrage ; `char[]` de passphrase effacé après usage |
| `adapter.out.legacy` | `JdbcLegacyUserProjection`, `JdbcLegacyUserSource`, `SymfonyInvitationClaimClient` (`RestClient`, connexion 1 s, lecture 3 s) | A9, A10 ; schéma historique via `identity.legacy.schema` (identifiant validé `[a-z_]+`, jamais concaténé depuis une entrée utilisateur) ; jamais de `DELETE` sur la table historique |
| `config` | `IdentityProperties` (`@ConfigurationProperties`), `ClockConfig`, `UseCaseConfig` (instancie les services applicatifs), `SecurityConfig` | toutes les valeurs par variables d'environnement d'A13 |

### 3.3 Symfony (endpoint transitoire, U2-a)
- `backend/src/Service/InvitationClaimer.php` : extrait la boucle de `AuthController::register` (adhésion, notification, suppression) ; `register` l'utilise sans changer son comportement.
- `backend/src/Controller/InternalInvitationController.php` : `POST /internal/invitations/claim`, contrat `identity-service.internal.yaml` ; `hash_equals` sur `INTERNAL_API_SECRET` (paramètre `%env(default::INTERNAL_API_SECRET)%`, vide → refus) ; idempotent ; une transaction.
- `ApiExceptionListener` : couvrir aussi `/internal` (réponses JSON `{"error"}`).
- `backend/.env.dist` : ajouter `INTERNAL_API_SECRET=` (vide). Ne pas lire `backend/.env`.
- Aucune autre modification de Symfony (`GET /api/health` reste chez Symfony).

### 3.4 Livrables techniques
- `services/identity-service/src/main/resources/application.yml` complet (A13 ; `spring.flyway.schemas=identity`, `spring.jpa.hibernate.ddl-auto=validate`, `spring.jpa.open-in-view=false`, `management.server.port=8091`, `management.endpoints.web.exposure.include=health`, journalisation sans données personnelles).
- `services/identity-service/Dockerfile` (multi-étapes, non root, healthcheck) suivant `docs/ci/requests/backend.md` §3.1 ; ne pas modifier `docker-compose.yml` (Manuel).
- Vérifications avant commit : `./mvnw -B -ntp -pl identity-service -am verify` vert ; `-Pquality verify -DskipTests` vert ; PHPUnit vert ; `git diff <hash BackTesteur>..HEAD -- services/identity-service/src/test backend/tests backend/phpunit.dist.xml backend/.env.test` **vide**.
- Commit : `feat(identity): identity-service hexagonal, compatibilité Symfony et endpoint d'invitations` (sans fichier de test). Si un test paraît faux : s'arrêter, l'écrire dans le rapport (BackAli tranche), ne pas le contourner.

## 4. Étape 3 — BackReviewer (rapport `docs/reviews/2026-11-26-identity-service.md`, ou date du jour)
Référence d'intégrité : hash du commit `test(identity)` de BackTesteur.
1. **Intégrité des tests** : `git diff <hash>..HEAD --stat -- services/identity-service/src/test backend/tests backend/phpunit.dist.xml backend/.env.test` vide ; `git diff <hash>..HEAD -- services/pom.xml services/identity-service/pom.xml` sans changement des seuils JaCoCo, des inclusions/exclusions Surefire/Failsafe ni du profil `compat` ; aucun `@Disabled`, `assumeTrue` ajouté, `markTestSkipped`, exclusion JaCoCo ou Sonar. `backend/tests` n'est pas protégé par le hook : contrôle manuel obligatoire.
2. **Contrôles outillés** (depuis `services/`) : `./mvnw -B -ntp -pl identity-service -am verify` ; `-Pquality verify -DskipTests` (Spotless, Checkstyle Google, Nullness) ; `rewrite:dryRun` (patch vide attendu) ; `-Psecurity-scan verify -DskipTests` (OWASP, CVSS < 7 ; sans `NVD_API_KEY` en local, le noter) ; Sonar si un serveur est disponible, sinon le noter ; PHPUnit (`vendor/bin/phpunit` dans le conteneur `api`). Signaler toute incompatibilité d'outil avec Java 25 (Checker Framework 4.4.0, google-java-format) comme point **bloquant à arbitrer**, sans désactiver l'outil.
3. **Architecture** : ArchUnit vert ; pas d'annotation Spring dans `domain`/`application` ; pas de Spring Data REST (A5) ; aucun adapter n'appelle un autre adapter.
4. **Revue de sécurité ciblée** : chargement de la clé privée (jamais journalisée, montée en lecture seule, passphrase effacée) ; validation JWT (`alg` vérifié avant la signature, tolérance 0, pas d'acceptation HS*/none) ; comparaison à temps constant des hashes **et** du secret interne (`hash_equals`) ; aucune donnée sensible dans journaux, exceptions, `last_error` d'outbox, rapports de reprise ; schéma historique non injectable ; aucune requête `DELETE` sur `taskora.user` ; endpoint `/internal` refusé si secret vide ; en-têtes de sécurité par défaut de Spring Security conservés ; CORS non ouvert (même origine via nginx).
5. **Conformité au contrat** : chaque statut/message des deux YAML a au moins un test ; exemples OpenAPI cohérents avec les réponses réelles. **Écarts E15–E18** : vérifier qu'ils sont corrigés et non reproduits (aucune conversion PHP dans `PhpJsonBody` ni ailleurs, aucun `[]` accepté comme objet, aucun 500 pour un mot de passe long, aucune couleur invalide ignorée) ; vérifier que les tests `compat` les excluent via `INTENTIONAL_DIVERGENCES` et qu'aucun autre cas n'y a été ajouté pour masquer une divergence non décidée (toute entrée hors E15–E18 est bloquante).
6. **Couverture des scénarios** : tableau SC-IDENT → test(s) → résultat, y compris SC-IDENT-028 à 031 ; tout scénario critique sans test vert est bloquant ; 028–030 (importants) sans test vert : bloquant pour la bascule.
7. Rapport : points bloquants / non bloquants, mesures (couverture, violations), sans aucun secret. Tout point bloquant renvoie à l'étape 2 (3 boucles maximum, puis remontée à BackAli et Paul).

## 5. Recette BackAli (après l'étape 3)
Tests verts (y compris `compat` sur la stack de Manuel), rapport sans bloquant, intégrité vérifiée, 14 critiques couverts, répétition du retour arrière (identity-migration.md §6.3) planifiée avec Paul.
