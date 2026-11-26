# Architecture cible du backend — Spring Boot hexagonal, microservices

Statut : **proposée** (à valider par l'équipe). Elle remplace le backend Symfony actuel (`backend/`).
Responsable du document : BackAli. Les spécifications fonctionnelles viennent de Paul (`docs/specs/`).

## 1. Socle technique

| Élément | Choix | Remarque |
|---|---|---|
| Langage | Java 25 (LTS) | Build et exécution dans Docker (`eclipse-temurin:25`) |
| Framework | Spring Boot 4.2.0 | Dernière version stable sur Maven Central (publiée le 19/11/2026), voir §7 |
| Spring Cloud | 2026.0.0 (Gateway, Config si besoin) | **Vérifié** : le train 2026.0.0 est construit sur `spring-cloud-build` 5.1.0, lui-même aligné sur Spring Boot 4.2.0 (voir §7) |
| Build | Maven multi-modules (`services/<nom>/pom.xml`) | Parent commun `services/pom.xml` |
| API REST | Spring Web MVC pour les commandes, **Spring Data REST** pour les lectures (voir §4) | |
| Persistance | Spring Data JPA, MariaDB 11.8 : une base (schéma) par service | Migrations Flyway par service |
| Messagerie | RabbitMQ (Spring AMQP), événements de domaine | Outbox transactionnelle pour la fiabilité |
| Sécurité | Spring Security, JWT (resource server) validé par la gateway **et** chaque service | |
| Stockage fichiers | S3 (AWS SDK v2), URLs présignées | Repris à l'identique du backend Symfony |
| Conversion Office → PDF | Gotenberg 8 (réseau Docker interne) | Inchangé |
| Web Push | VAPID (bibliothèque `nl.martijndwars:web-push` ou équivalent maintenu) | Clés VAPID existantes conservées |

## 2. Découpage en microservices

Le découpage suit les contextes métier déjà présents dans le code Symfony.

| Service | Responsabilités (repris de) | Données propres |
|---|---|---|
| `gateway` | Point d'entrée unique `/api/**`, routage, validation JWT, limitation de débit, CORS | aucune |
| `identity-service` | Inscription, connexion, JWT, profil (`AuthController`, `User`) | users |
| `board-service` | Tableaux, membres, invitations, listes, cartes, étiquettes, checklists, champs personnalisés, commentaires, mentions, activité, archives, corbeille (`Board*`, `List*`, `Card*`, `CustomField*`, `TrashController`, `ActivityLogger`, `BoardAccess`, `Trash`) | boards, lists, cards, comments, activities… |
| `attachment-service` | Upload (100 Mo), S3, miniatures, aperçus Gotenberg, flux de lecture, couverture (`AttachmentController`, `UploadController`, `Uploads`, `DocumentPreview`, `CommentAttachments`) | attachments |
| `notification-service` | Notifications internes, abonnements et envoi Web Push (`NotificationController`, `PushController`, `Notifier`, `PushSender`) | notifications, push_subscriptions |
| `import-service` | Import Trello et transfert des pièces jointes (`ImportController`, `TrelloFilesController`, `TrelloImporter`, `TrelloFileTransfer`) | trello_import_files |

Communication :
- **Synchrone** (REST interne, via le réseau Docker) uniquement pour les lectures indispensables, par exemple le contrôle d'accès à un tableau depuis `attachment-service`.
- **Asynchrone** (RabbitMQ) pour tout le reste. Événements principaux : `CardAssigned`, `CommentPosted`, `MemberMentioned`, `CardMoved`, `BoardTrashed`, `BoardPurged`, `AttachmentUploaded`, `TrelloImportCompleted`.
- Chaque service possède ses données ; aucun service ne lit la base d'un autre.

## 3. Structure hexagonale d'un service

```
services/board-service/src/main/java/com/taskora/board/
├── domain/                  # Cœur métier, Java pur (aucune dépendance Spring/JPA)
│   ├── model/               # Agrégats, entités, value objects
│   ├── event/               # Événements de domaine
│   └── exception/
├── application/
│   ├── port/in/             # Cas d'utilisation (interfaces) : ArchiveCardUseCase…
│   ├── port/out/            # Ports sortants : CardRepositoryPort, EventPublisherPort…
│   └── service/             # Implémentations des cas d'utilisation
├── adapter/
│   ├── in/web/              # Contrôleurs REST (commandes), DTO, mappers
│   ├── in/messaging/        # Consommateurs RabbitMQ
│   ├── out/persistence/     # Entités JPA, repositories Spring Data, mappers, ressources Spring Data REST
│   └── out/messaging/       # Publication d'événements, outbox
└── config/                  # Assemblage Spring (beans, sécurité)
```

Règles imposées (vérifiées par des tests **ArchUnit** écrits par BackTesteur) :
1. `domain` ne dépend d'aucun autre paquet ni d'aucun framework.
2. `application` ne dépend que de `domain`.
3. Les `adapter` dépendent de `application` (ports), jamais l'inverse ; aucun adapter n'en appelle un autre directement.
4. Les entités JPA ne sortent jamais de `adapter.out.persistence` ; elles sont converties en modèle de domaine.
5. Aucun cycle entre paquets.

## 4. Place de Spring Data REST dans l'hexagone

Spring Data REST expose directement des repositories, ce qui contourne les cas d'utilisation. Pour respecter l'hexagone :
- Il est utilisé **dans `adapter/out/persistence` (ou un module `adapter/in/rest-query`) pour les lectures** : collections paginées, projections, recherche.
- Les méthodes d'écriture des repositories exportés sont désactivées (`@RestResource(exported = false)` sur `save`, `delete`…) ou les repositories exportés sont en lecture seule.
- Toute **écriture** passe par un contrôleur `adapter/in/web` qui appelle un cas d'utilisation (règles métier, droits, événements).
- La sécurité s'applique aussi aux ressources Spring Data REST : filtrage par appartenance au tableau, et pas d'exposition des identifiants internes.

## 5. Qualité et tests

| Vérification | Outil | Responsable |
|---|---|---|
| Tests unitaires du domaine et des cas d'utilisation | JUnit 5, AssertJ, Mockito | BackTesteur |
| Tests d'architecture | ArchUnit | BackTesteur |
| Tests d'intégration (base, RabbitMQ, S3) | Testcontainers (MariaDB, RabbitMQ, LocalStack) | BackTesteur |
| Tests d'API | MockMvc / RestAssured, contrat OpenAPI | BackTesteur |
| Tests de sécurité | Spring Security Test (accès refusé, IDOR, JWT invalide), OWASP Dependency-Check | BackTesteur |
| Formatage | Spotless + Google Java Format | BackReviewer |
| Style | Checkstyle (règles Google) | BackReviewer |
| Nullité et typage | Checker Framework (Nullness Checker) | BackReviewer |
| Refactorings automatiques et migrations | OpenRewrite (`rewrite:dryRun`) | BackReviewer |
| Analyse statique | SonarQube (moteur de SonarLint) via `sonar-maven-plugin` ; SonarLint lui-même est un plugin d'IDE sans CLI | BackReviewer |

## 6. Migration depuis Symfony

Le backend Symfony reste en service jusqu'à la bascule. Ordre proposé : `identity-service` → `board-service` → `attachment-service` → `notification-service` → `import-service` → `gateway` et bascule du frontend.

Pour chaque service :
- **Mêmes routes `/api/...`** que le backend actuel, sauf décision contraire publiée dans `docs/contracts/`.
- **Reprise des données** : script de migration depuis la base MariaDB actuelle.
- **Clés conservées** : mêmes clés S3 et mêmes clés VAPID.

## 7. Socle figé (vérifié sur Maven Central le 26/11/2026)

Règle : une version gérée par le BOM Spring Boot n'est **jamais** surchargée dans un module ; seules les entrées marquées « épinglée » sont déclarées dans `services/pom.xml`. Toute montée de version passe par cette table.

| Élément | Version | Source / remarque |
|---|---|---|
| Java | 25 (Temurin) | images `eclipse-temurin:25-jdk` (build) et `eclipse-temurin:25-jre` (exécution), étiquettes vérifiées sur Docker Hub |
| Maven | 3.10.2, wrapper 3.3.4 (`distributionType=only-script`) | image de build `maven:3.10.2-eclipse-temurin-25` (vérifiée) |
| Spring Boot (parent `spring-boot-starter-parent`) | **4.2.0** | 4.2.0 = dernière release ; gère Spring Framework 7.1.0, Spring Security 7.2.0, Hibernate 7.4.15.Final, Jackson 3.1.8, JUnit Jupiter 6.1.3, Mockito 5.24.0, AssertJ 3.27.7, Testcontainers 2.0.5, Flyway 12.11.0, MariaDB Connector/J 3.5.11, Tomcat 11.0.27, Surefire/Failsafe 3.6.1, Compiler 3.16.0 |
| Spring Cloud (BOM importé) | **2026.0.0** | parent `spring-cloud-dependencies-parent` 5.1.0 → `spring-cloud-build` 5.1.0 déclare `spring-boot.version` 4.2.0 : compatibilité confirmée. Non utilisé par identity-service en phase 1 (gateway plus tard), importé dès maintenant dans le parent |
| JWT | `com.nimbusds:nimbus-jose-jwt` **10.9.1** (géré via `spring-security-oauth2-jose` 7.2.0) | 10.10 existe, on garde la version alignée sur Spring Security |
| Clé privée PKCS#8 chiffrée, Argon2 | `org.bouncycastle:bcpkix-jdk18on` et `bcprov-jdk18on` **1.87** (épinglées) | déchiffrement PBES2/3DES de la clé Lexik ; `Argon2PasswordEncoder` de Spring Security |
| Flyway | 12.11.0 (géré par Boot) + `flyway-mysql` | 13.13.0 existe ; non retenue (hors BOM) |
| ArchUnit | `com.tngtech.archunit:archunit-junit5` **1.5.1** (épinglée) | |
| Testcontainers | 2.0.5 (BOM Boot) : `testcontainers-mariadb`, `testcontainers-junit-jupiter` ; image `mariadb:11.8` | |
| RestAssured | `io.rest-assured:rest-assured` **6.3.0** (épinglée) | 7.0.0-RC1 écartée (pré-version) |
| Validation OpenAPI | `com.atlassian.oai:swagger-request-validator-restassured` **3.0.0** (épinglée) | valide chaque réponse de test contre `docs/contracts/openapi/identity-service.yaml` |
| WireMock | `org.wiremock:wiremock-standalone` **3.13.2** (épinglée) | 4.0.0 n'existe qu'en bêta |
| Spotless | `spotless-maven-plugin` **3.12.0** + google-java-format **2.0.0** | **Révisé (revue 1, B1)** : 1.37.0 lève `InvocationTargetException` sur JDK 25 ; 2.0.0 vérifiée sur `eclipse-temurin:25-jdk` (`spotless:check` s'exécute et signale 63 fichiers à reformater) |
| Checkstyle | `maven-checkstyle-plugin` **3.6.0** + `com.puppycrawl.tools:checkstyle` **14.4.0**, `google_checks.xml` | |
| Checker Framework | `org.checkerframework:checker` **4.4.0** (Nullness Checker, profil `quality`) + `checker-qual` **4.4.0** (`provided`, hérité du parent) | **Révisé (revue 1, B3)** : vérifié sur JDK 25 ; il fallait `checker-qual` et l'export `-J--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED` en plus de ceux déjà présents |
| OpenRewrite | `rewrite-maven-plugin` **6.46.1** + `rewrite-spring` **6.37.1**, `rewrite-migrate-java` **3.42.1**, `rewrite-static-analysis` **2.41.1** ; recettes `UpgradeSpringBoot_4_0`, `UpgradeToJava25`, `CommonStaticAnalysis` | **Révisé (revue 1, B4)** : les versions de `rewrite-recipe-bom` 3.38.0 sont absentes de Maven Central (404) et `UpgradeSpringBoot_4_2` n'existe pas ; `rewrite:dryRun` résolu et exécuté. `rewrite:dryRun` seulement en CI |
| Agent Mockito | `-javaagent:${org.mockito:mockito-core:jar}` dans l'`argLine` de Surefire et Failsafe (`maven-dependency-plugin:properties`) | revue 1, B4 (`AddMockitoJavaAgentToMavenSurefirePlugin`) : l'auto-attachement dynamique est déprécié depuis JDK 21 ; configuré dans le parent, ce qui satisfait la recette |
| JaCoCo | `jacoco-maven-plugin` **0.8.16** | seuils au §9 A12 |
| Sonar | `sonar-maven-plugin` **5.9.0.7382** | |
| OWASP | `dependency-check-maven` **13.0.0** | clé NVD par secret CI `NVD_API_KEY` |
| Enforcer | `maven-enforcer-plugin` **3.7.0** | `requireJavaVersion [25,)`, `requireMavenVersion [3.9,)`, `dependencyConvergence` |
| Tests PHP (endpoint transitoire) | `phpunit/phpunit` 13.4.x, `symfony/browser-kit` 8.1.* (require-dev) | le backend Symfony n'a aujourd'hui **aucune** infrastructure de test |

## 8. Faits vérifiés sur le système réel (étape 0 identity, 26/11/2026)

Méthode : lecture de la configuration, du code vendor (`lexik/jwt-authentication-bundle` v3.2.0, `lcobucci/jwt` 5.7.0, `symfony/security-bundle` 8.1.11, `symfony/password-hasher` 8.1.0), requêtes d'agrégat en lecture seule sur MariaDB 11.8.9, requêtes HTTP sur http://localhost:8080 avec un e-mail inexistant (aucun compte créé : 3 comptes avant, 3 après).

### 8.1 JWT
| Point | Constat |
|---|---|
| Algorithme | **RS256** (`debug:config` : `signature_algorithm: RS256`, encodeur `lcobucci`) ; clé RSA **2048 bits** |
| Clés | `config/jwt/private.pem` (PKCS#8 **chiffré** : PBES2, PBKDF2-HMAC-SHA256, DES-EDE3-CBC) et `public.pem`, générées par `lexik:jwt:generate-keypair --skip-if-exists` dans l'entrypoint du conteneur `api`, volume `jwt_keys` monté sur `/app/config/jwt`, fichiers `644 root` |
| Phrase secrète | variable `JWT_PASSPHRASE` (compose : `${JWT_PASSPHRASE:-…}`) |
| En-tête émis | `{"typ":"JWT","alg":"RS256"}`, **pas de `kid`** |
| Revendications émises | `iat` et `exp` en **secondes entières** (`time()`), `exp = iat + 604800`, `roles` (tableau, `["ROLE_USER"]`), `username` (= e-mail). Ni `sub`, `iss`, `aud`, `nbf`, `jti` |
| Validation | `clock_skew: 0` ; `exp` **obligatoire** ; seul `RS256` accepté (contrainte `SignedWith`) ; `iat` dans le futur → invalide |
| Précédence des messages (401 `{"code":401,"message":…}`) | en-tête absent ou ne se découpant pas en exactement 2 morceaux sur l'espace avec préfixe `Bearer` (insensible à la casse) → `JWT Token not found` ; jeton illisible → `Invalid JWT Token` ; `iat` futur ou `exp` absent → `Invalid JWT Token` **sauf si** `now ≥ exp` → `Expired JWT Token` (l'expiration l'emporte, **même si la signature est fausse**) ; signature/algorithme/`nbf` invalides → `Invalid JWT Token` ; revendication `username` absente → `Unable to find key "username" in the token payload.` ; compte introuvable → `Invalid credentials.` (`expose_security_errors: none` masque `UserNotFoundException`) |
| En-tête de réponse 401 | `WWW-Authenticate: Bearer` (observé sur `/api/me` et sur l'échec de login) |
| Chargement de l'utilisateur | par `email` via Doctrine : la comparaison suit la collation de la colonne (voir 8.3) |

### 8.2 Hachage des mots de passe
| Point | Constat |
|---|---|
| Configuration | `password_hashers: auto` → `NativePasswordHasher` bcrypt `$2y$`, **coût 13** (PHP 8.4, `PASSWORD_DEFAULT = 2y` ; sodium présent mais `native` est choisi en premier) ; en test : coût 4 |
| **Répartition en base** | **`$2y$` coût 13 : 3 comptes sur 3**. Aucun `$argon2…`, `$2a$`, `$2b$`, ni autre coût. Longueur maximale du hash : 60 |
| Mots de passe > 72 octets ou contenant NUL | Symfony **pré-hache** : `base64(sha512(mot de passe brut))`, au hachage **et** à la vérification. Bcrypt ne tronque donc pas silencieusement |
| Longueur maximale | `MAX_PASSWORD_LENGTH = 4096` **octets** : au hachage → `InvalidPasswordException` → **500** `{"error":"Erreur interne du serveur."}` (inscription constatée, aucun compte créé) ; à la vérification → `false` (→ 401 au login, 400 `Mot de passe actuel incorrect.` sur `PATCH /me`) ; mot de passe vide → `false` |
| Re-hachage | aucun (`PasswordUpgraderInterface` non implémenté) |

### 8.3 Données `taskora.user`
- Schéma : `id INT AUTO_INCREMENT`, `email VARCHAR(180) NOT NULL UNIQUE`, `name VARCHAR(120)`, `password VARCHAR(255)`, `avatar_color VARCHAR(20)`, `roles LONGTEXT utf8mb4_bin CHECK(json_valid)`, `created_at DATETIME`.
- Collation de `email` : **`utf8mb4_unicode_ci`** (PAD SPACE) : comparaison insensible à la casse **et aux accents** (`e` = `é`, `ss` = `ß`), **espaces finaux ignorés**, espaces initiaux significatifs. L'index unique suit la même collation.
- 3 lignes, `MAX(id) = 3` (auto-incrément suivant 4), 0 doublon après `LOWER(TRIM())`, 0 e-mail non normalisé, `roles = []` partout, longueurs max nom 27 / e-mail 20.
- **15 clés étrangères** pointent sur `user.id` (`board_member`, `card_assignee`, `push_subscription` en `ON DELETE CASCADE`, `invitation.invited_by_id` en `SET NULL`, etc.) : une ligne de `taskora.user` ne doit **jamais** être supprimée ni ré-identifiée.
- `created_at` est écrit dans le fuseau PHP **`Europe/Paris`** (`docker/php.ini`), sans fuseau en base.
- L'utilisateur MariaDB `taskora` n'a de droits que sur `taskora.*` : le schéma `identity` et son utilisateur doivent être créés avec le compte root (script d'initialisation, Manuel).

### 8.4 Corps d'erreur de `POST /api/auth/login` (observés)
| Requête | Statut | Corps |
|---|---|---|
| e-mail inconnu, mot de passe quelconque | 401 | `{"code":401,"message":"Invalid credentials."}` |
| mot de passe vide `""`, e-mail vide `""`, e-mail > 4096 octets, e-mail avec espace initial | 401 | idem |
| clé `password` absente | 400 | `{"error":"The key \"password\" must be provided."}` |
| clé `email` absente (ou `{}`) | 400 | `{"error":"The key \"email\" must be provided."}` (l'e-mail est contrôlé avant le mot de passe) |
| `email` non chaîne (nombre, `null`) / `password` non chaîne | 400 | `{"error":"The key \"email\" must be a string."}` / `… \"password\" …` |
| corps non JSON, vide, `[]`, `"x"` (avec `Content-Type: application/json`) | 400 | `{"error":"Invalid JSON."}` (anglais, différent du `JSON invalide.` des contrôleurs) |
| `Content-Type` sans `json` (ex. absent) | 404 | `{"error":"Unable to find the controller for path \"/api/auth/login\". The route is wrongly configured."}` |
| `GET` | 405 | `{"error":"No route found for \"GET http://…/api/auth/login\": Method Not Allowed (Allow: POST)"}` |

Les 400 passent par `ApiExceptionListener` (forme `{"error"}`), les 401 par le gestionnaire d'échec Lexik (forme `{"code","message"}`).
**Corrigé (décision utilisateur E19, E20)** dans identity-service : les 400 et le 404 de ce tableau sont remplacés par les règles françaises de l'inscription (A15) ; les 401 restent identiques.

### 8.5 Autres constats utiles
- `POST /api/auth/register` (Symfony) : `[]` est accepté comme `{}` — **corrigé (décision utilisateur E16)** dans identity-service ; conversions PHP `(string)` : nombre → texte, `true` → `"1"`, `false`/`null` → `""`, tableau → `"Array"` (un `name` tableau est accepté et vaut `"Array"`) — **corrigé (décision utilisateur E17)** ; reproduits tels quels : `trim` PHP = espaces, `\t \n \r \0 \x0B` uniquement ; longueurs en points de code (`mb_*`).
- `PATCH /api/me` (Symfony) : `isset` (une valeur `null` vaut « absente », **conservé** par E17) ; `!empty($newPassword)` (`""`, `"0"` → aucun changement de mot de passe) — **corrigé (décision utilisateur E21)** : seuls l'absence, `null` et `""` valent « pas de changement » ; une couleur invalide est ignorée et l'expression `/^#[0-9a-fA-F]{6}$/` accepte un `\n` final — **corrigé (décision utilisateur E18)** : 400 `Couleur d'avatar invalide.`
- Mot de passe > 4096 octets à l'inscription ou dans `newPassword` → 500 chez Symfony — **corrigé (décision utilisateur E15)** : 400 `Le mot de passe ne doit pas dépasser 4096 octets.`
- Inscription concurrente avec le même e-mail : Symfony ne capture pas la violation d'unicité (→ 500) ; la cible renvoie 409 (SC-IDENT-002, déjà spécifié par Paul).
- `JsonResponse` échappe `"`, `/` et les caractères non ASCII (`"`, `\/`, `è`) : l'équivalence est **sémantique** (même JSON après analyse), pas octet par octet.
- Le port `8081` du conteneur `api` est publié sur l'hôte : tout endpoint interne de Symfony est joignable depuis l'hôte, d'où le secret partagé obligatoire (ADR A10).
- `nginx` ne route que `/api/` et `/uploads/` vers `api` ; `/internal/` n'est donc pas exposé par le front.

## 9. Décisions d'architecture (ADR)

Format : contexte → décision → conséquences. Statut : **acceptée** (étape 0 identity, 26/11/2026) sauf mention.

### A1 — Signature et validation des JWT pendant la coexistence
- **Contexte** : Symfony émet et valide des RS256 (8.1). Le front et toutes les routes non migrées doivent accepter les jetons d'identity-service, et identity-service doit accepter ceux déjà émis par Symfony (7 jours).
- **Décision** :
  1. **Même paire de clés** : identity-service lit `private.pem` et `public.pem` depuis le volume `jwt_keys` monté **en lecture seule** sur `/run/taskora/jwt` (`JWT_PRIVATE_KEY_PATH`, `JWT_PUBLIC_KEY_PATH`), phrase secrète `JWT_PASSPHRASE`. Déchiffrement PKCS#8 par BouncyCastle (`PEMParser` + `JceOpenSSLPKCS8DecryptorProviderBuilder`). La clé est chargée au démarrage ; échec de chargement → le service ne démarre pas (message sans contenu de clé).
  2. **Émission** (Nimbus `RSASSASigner`) : en-tête `{"typ":"JWT","alg":"RS256"}` sans `kid` ; revendications `iat` (secondes entières, `Clock` injectée), `exp = iat + 604800` (`JWT_TTL`, défaut 604800), `roles` (rôles stockés + `ROLE_USER`, sans doublon, ordre de `User::getRoles()` PHP), `username` (= e-mail stocké). **Ajout** : `uid` (entier, id utilisateur), ignoré par Symfony. Rien d'autre (pas de `sub`, `iss`, `aud`, `nbf`, `jti` en phase 1).
  3. **Validation** (filtre maison `LexikCompatibleJwtFilter`, pas le `BearerTokenAuthenticationFilter` de Spring dont l'extraction, la tolérance d'horloge de 60 s et les messages diffèrent) : reproduit exactement l'algorithme et la précédence des messages du §8.1 ; seul `RS256` est accepté (vérification de `alg` **avant** toute vérification cryptographique ; `none`, `HS*`, `RS384/512`, `PS*`, `ES*` → invalide) ; tolérance 0 s ; `exp` obligatoire et numérique ; puis chargement du compte par `username` (absent → `Invalid credentials.`).
  4. **Plus tard** (gateway) : endpoint JWKS interne et `kid` ; hors phase 1.
- **Conséquences** : test croisé SC-IDENT-010 dans les deux sens (jeton Symfony → identity, jeton identity → Symfony) obligatoire et bloquant en CI (`identity-compat`) ; les tests utilisent une paire de clés générée à l'exécution, chiffrée comme celle de Lexik (`openssl genpkey … -aes256` ou `-des3`) ; jamais de clé commitée.

### A2 — Hachage des mots de passe
- **Contexte** : 100 % des hashes réels sont `$2y$` coût 13 ; Symfony pré-hache au-delà de 72 octets ; limite 4096 octets.
- **Décision** : `PasswordHasherPort` implémenté par `SymfonyCompatiblePasswordHasher` (adapter `out.security`) :
  - **Hachage** : bcrypt **`$2y$` coût 13** (configurable `identity.password.bcrypt-cost`, 4 en test). Spring `BCryptPasswordEncoder(BCryptVersion.$2Y, 13)`.
  - **Pré-hachage** : si la longueur **en octets UTF-8** > 72 ou si le mot de passe contient `\0` → `Base64(SHA-512(octets))` (base64 standard avec remplissage, 88 caractères ASCII), au hachage et à la vérification des hashes `$2…`. PHP passe ensuite ces 88 caractères à bcrypt, qui n'en lit que les **72 premiers octets** ; or `BCryptPasswordEncoder` (Spring Security ≥ 6.3) **refuse** une entrée de plus de 72 octets : l'adapter tronque donc explicitement l'entrée effective à 72 octets avant l'appel bcrypt (troncature sûre : ASCII). Test obligatoire : un hash produit par PHP pour un mot de passe de 100 octets est vérifié par Java, et inversement.
  - **Vérification** par préfixe : `$2y$`, `$2a$`, `$2b$` → bcrypt (tous coûts 4–31) ; `$argon2id$`, `$argon2i$` → `Argon2PasswordEncoder` (BouncyCastle), **sans pré-hachage** ; tout autre préfixe, hash tronqué ou corrompu → `false` (jamais d'exception → jamais 500).
  - Mot de passe vide ou > 4096 octets → `false` à la vérification ; > 4096 octets à l'inscription ou dans `newPassword` → **400** `Le mot de passe ne doit pas dépasser 4096 octets.` — **corrigé (décision utilisateur E15)**, contrôlé dans le domaine (`PlainPassword.requireMaximumLength`, `PasswordTooLongException`) **avant** tout hachage ; le hasher garde une garde défensive (exception) qui ne doit jamais être atteinte. Au login, > 4096 octets → 401 (inchangé).
  - Comparaison à temps constant (assurée par les encodeurs Spring) ; e-mail inconnu au login → vérification d'un hash factice `$2y$13$…` pré-calculé au démarrage, pour un temps comparable (SC-IDENT-008).
  - Pas de re-hachage à la connexion en phase 1.
- **Conséquences** : argon2 n'est pas présent en base mais reste vérifiable (jeu d'essai SC-IDENT-023) ; les hashes produits par identity-service sont lisibles par PHP `password_verify` → retour arrière sans perte.

### A3 — Identifiants
- **Décision** : `INT` conservés. `identity.user_account.id` = id historique ; `AUTO_INCREMENT` MariaDB (repart à `MAX(id)+1` après la reprise, ajustement explicite par `ALTER TABLE … AUTO_INCREMENT`). La projection (A9) insère dans `taskora.user` **avec l'id explicite**. Dans le domaine, `UserId` est un value object `int` ; exposé en JSON comme entier. Les événements portent aussi `userId` entier, et `eventId` UUID v4.
- **Conséquences** : pas de remappage d'identifiants pour les autres services ; une seule source d'allocation (identity) après la bascule ; pendant un retour arrière, Symfony alloue à nouveau (son `AUTO_INCREMENT` a suivi les insertions explicites).

### A4 — Format des erreurs
- **Décision** : un seul `@RestControllerAdvice` (`ApiErrorHandler`) et un seul point d'entrée sécurité (`LexikAuthenticationEntryPoint`).
  - Erreurs métier et de validation : `{"error":"<message exact>"}` avec les statuts de Symfony.
  - Erreurs d'authentification JWT et échec de login : `{"code":401,"message":"<message Lexik exact>"}` + `WWW-Authenticate: Bearer`.
  - Erreurs de forme du login : **corrigé (décision utilisateur E19, E20)** : mêmes règles et messages français que l'inscription (`JSON invalide.`, `Le champ "<nom>" doit être une chaîne de caractères.`, `Le champ "<nom>" est obligatoire.`), `Content-Type` non contrôlé ; plus de messages anglais `json_login` ni de 404.
  - 404/405 Spring : `{"error":"…"}` avec le bon statut ; le texte n'est pas contractuel (il contient l'hôte côté Symfony).
  - 5xx : `{"error":"Erreur interne du serveur."}`, jamais de trace, d'exception ni de SQL ; `spring.web.error.include-*` à `never` et `spring.web.error.whitelabel.enabled=false` (**Boot 4 a renommé `server.error.*` en `spring.web.error.*`** : l'ancienne clé est ignorée sans erreur, revue 1 B4).
  - Jackson sérialise en UTF-8 sans échappement `\uXXXX` : équivalence sémantique avec Symfony (§8.5), les tests de compatibilité comparent le JSON analysé.
- **Conséquences** : les messages sont des constantes d'une classe `ErrorMessages` (adapter web) testées une par une.

### A5 — Pas d'écriture exportée par Spring Data REST ; pas de ressource Spring Data REST dans identity-service
- **Décision** : `spring-boot-starter-data-rest` n'est **pas** une dépendance d'identity-service. Aucune ressource de lecture n'est exportée.
- **Justification** : la seule lecture du domaine est `GET /api/me`, qui ne prend aucun identifiant ; une collection `users` créerait une surface d'énumération (ids séquentiels, e-mails) sans besoin fonctionnel ; l'écriture exportée contournerait les cas d'utilisation, la projection historique et l'outbox. Les lectures d'autres services (nom, couleur d'un membre) passeront par les événements `UserRegistered`/`UserProfileUpdated`.
- **Conséquences** : test ArchUnit/`ApplicationContext` vérifiant l'absence de `RepositoryRestResource` et de `spring-data-rest` sur le classpath ; la règle du §4 s'appliquera aux services suivants.

### A6 — Ports hexagonaux d'identity-service
Paquet racine `com.taskora.identity`.
| Couche | Contenu |
|---|---|
| `domain.model` | `User` (agrégat), `NewUser`, `UserId`, `Email`, `PhpEmailValidator`, `PersonName`, `AvatarColor`, `PlainPassword`, `HashedPassword`, `Roles`, `ProfileUpdate`, `ProfileUpdateResult`, `PhpString` (trim PHP, longueur et troncature en points de code, minuscules) |
| `domain.service` | `PasswordHashing` (interface de domaine : `hash`, `matches`), étendue par `PasswordHasherPort` |
| `domain.event` | `DomainEvent`, `UserRegistered`, `UserProfileUpdated` |
| `domain.exception` | `ValidationException` (message exact), `EmailAlreadyUsedException`, `InvalidCurrentPasswordException`, `PasswordTooLongException`, `InvalidCredentialsException`, `TokenRejectedException` (+ `TokenRejection`) |
| `application.port.in` | `RegisterUserUseCase`, `AuthenticateUseCase`, `GetProfileUseCase`, `UpdateProfileUseCase`, `ResolveAuthenticatedUserUseCase`, `DeliverPendingMessagesUseCase`, `ImportLegacyUsersUseCase` |
| `application.port.out` | `UserRepositoryPort`, `PasswordHasherPort`, `TokenIssuerPort`, `TokenVerifierPort`, `LegacyUserProjectionPort`, `OutboxPort`, `InvitationClaimPort`, `LegacyUserSourcePort`, `UnitOfWorkPort` |
| `application.service` | implémentations des cas d'utilisation, Java pur (pas d'annotation Spring ; transactions via `UnitOfWorkPort`) |
| `adapter.in.web` | `AuthController`, `MeController`, `PhpJsonBody` (nom conservé ; lecture stricte : objet JSON exigé, corps vide refusé, champs chaîne, `null` = absent sur `PATCH` ; E16, E17, E20), `JsonLoginRequestParser` (règles françaises, E19), `ApiErrorHandler`, `ErrorMessages`, DTO |
| `adapter.in.web.security` | `LexikCompatibleJwtFilter`, `LexikAuthenticationEntryPoint`, `LexikTokenExtractor` |
| `adapter.in.scheduling` | `OutboxRelayScheduler` (appelle `DeliverPendingMessagesUseCase`) |
| `adapter.in.cli` | `LegacyImportRunner` (`--identity.legacy-import=run|verify`) |
| `adapter.out.persistence` | entités JPA `UserAccountEntity`, `OutboxMessageEntity`, repositories Spring Data, mappers, `JpaUnitOfWork` |
| `adapter.out.security` | `SymfonyCompatiblePasswordHasher`, `NimbusTokenIssuer`, `NimbusTokenVerifier`, `LexikKeyLoader` |
| `adapter.out.legacy` | `JdbcLegacyUserProjection`, `JdbcLegacyUserSource`, `SymfonyInvitationClaimClient` (`RestClient`) |
| `config` | assemblage des beans, `SecurityConfig`, propriétés typées `IdentityProperties` |
- Le filtre de sécurité appelle `ResolveAuthenticatedUserUseCase` (port in), qui utilise `TokenVerifierPort` et `UserRepositoryPort` : aucun adapter n'en appelle un autre.
- `java.time.Clock` et `java.util.random.RandomGenerator` sont injectés (JDK, autorisés dans `domain`/`application`).

### A7 — Unicité de l'e-mail sous concurrence
- **Décision** : colonne `identity.user_account.email VARCHAR(180) COLLATE utf8mb4_unicode_ci` avec index unique `uq_user_account_email`, **même collation que l'historique** (mêmes égalités : casse, accents, espaces finaux). Le cas d'utilisation contrôle d'abord (`existsByEmail`) pour le message, puis l'insertion ; une `DataIntegrityViolationException` sur cet index est traduite par l'adapter en `EmailAlreadyUsedException` → **409** `Un compte existe déjà avec cette adresse e-mail.` La transaction est annulée (aucune projection, aucune ligne d'outbox).
- La projection `taskora.user` a son propre index unique (même collation) : une violation y est traitée de la même manière (cas d'un compte créé côté Symfony pendant un retour arrière non resynchronisé).
- La connexion recherche l'e-mail **tel que saisi** (`WHERE email = ?`, sans `trim` ni minuscules côté Java) : la collation reproduit exactement Symfony (§8.3). Exception : > 4096 octets → 401 sans requête.
- **Conséquences** : test d'intégration avec N inscriptions parallèles (même e-mail, casses différentes) → 1 ligne, N−1 × 409, 0 × 500.

### A8 — Mise à jour atomique du profil
- **Décision** : `User.applyProfileUpdate(ProfileUpdate, PasswordHasherPort)` valide tout dans l'ordre (`name` si présent → `avatarColor` exactement `#RRGGBB` sinon 400 `Couleur d'avatar invalide.` — **corrigé (décision utilisateur E18)** → si `newPassword` présent, non `null` et ≠ `""` (`"0"` compris — **corrigé (décision utilisateur E21)**) : mot de passe actuel, longueur ≥ 8, puis ≤ 4096 octets — E15) ; la forme du corps et le type des champs (E16, E17) sont vérifiés avant, par l'adapter web, produit un **nouvel** état, et n'est persisté qu'à la fin dans une seule transaction (`UnitOfWorkPort`) avec la projection et l'événement. Toute exception → rien n'est écrit (SC-IDENT-019).
- `UserProfileUpdated` est ajouté à l'outbox seulement si `name` ou `avatarColor` change réellement (pas pour un changement de mot de passe seul).

### A9 — Projection historique `taskora.user` (U1-B)
- **Décision** (révisée après la revue 1) : `LegacyUserProjectionPort.upsert(User)` implémenté par `JdbcLegacyUserProjection` (`JdbcClient`) : `UPDATE <legacy>.\`user\` SET email=?, name=?, password=?, avatar_color=?, roles=? WHERE id=?` ; si **0 ligne trouvée** (`useAffectedRows=false`, défaut du pilote), `INSERT … (id, email, name, password, avatar_color, roles, created_at)` avec l'id explicite. **Pas de `ON DUPLICATE KEY UPDATE`** : un ODKU se déclencherait aussi sur l'index unique `email` et écraserait la ligne d'un **autre** compte ; ici une collision (e-mail ou id) lève `DuplicateKeyException` → 409 et annulation de la transaction. Jamais `created_at` en mise à jour, **jamais de DELETE**. Schéma historique configurable `identity.legacy.schema` (défaut `taskora`).
- **Même transaction** que l'écriture dans `identity` : même serveur MariaDB, même `DataSource`, même gestionnaire de transactions (XA inutile). L'utilisateur MariaDB `identity` a `SELECT, INSERT, UPDATE` sur `taskora.user` seulement.
- `created_at` est stocké dans `identity` et dans `taskora` en heure locale **`Europe/Paris`** (`identity.legacy.zone`), comme PHP ; `roles` = `[]` (JSON) comme Symfony.
- La projection est supprimée à la migration de board-service (fin de la coexistence).

### A10 — Consommation des invitations : client de l'endpoint Symfony transitoire (U2-a)
- **Contrat** : `docs/contracts/openapi/identity-service.internal.yaml`, `POST /internal/invitations/claim`, en-tête `X-Internal-Secret`.
- **Côté identity** :
  1. La transaction d'inscription écrit : `user_account`, projection `taskora.user`, et dans l'outbox **trois** messages : `InvitationClaimRequested` (destination `symfony-invitations`, dû immédiatement), `InvitationClaimRequested` de **rattrapage** (même charge, dû à `+60 s`, couvre une invitation créée par Symfony pendant la fenêtre d'inscription), `UserRegistered` (destination `broker`).
  2. **Après validation** de la transaction (et seulement après, sinon Symfony ne voit pas la projection), le cas d'utilisation tente **une livraison synchrone** du premier message (`InvitationClaimPort`, délai de connexion 1 s, de lecture 3 s) pour que les tableaux soient visibles dès `/boards` (SC-IDENT-005). Succès → message `DELIVERED`. Échec → le message reste `PENDING`, l'inscription répond quand même **201** (le compte existe), le relais réessaie.
  3. Le relais (`OutboxRelayScheduler`, toutes les 5 s) prend les messages dus par lot avec `SELECT … FOR UPDATE SKIP LOCKED`, backoff exponentiel 5 s → 5 min plafonné, sans limite de tentatives ; au-delà de 20 tentatives, journal `WARN` (sans e-mail) et métrique `identity_outbox_stuck`.
  4. Réponses : `200` → livré ; `401`, `400`, `409` → `FAILED` (non rejouable, `ERROR` dans le journal, intervention manuelle) ; `404` (utilisateur pas encore visible), `5xx`, délai dépassé → rejouable.
- **Côté Symfony** (`InternalInvitationController`, hors pare-feu `/api`) : comparaison `hash_equals` du secret `INTERNAL_API_SECRET` (vide ou absent → toujours refusé), logique reprise de `AuthController::register` (adhésion `member`, notification `board_invite` avec le texte exact, suppression des invitations), une transaction, **idempotent** (adhésion déjà présente → ignorée, invitation supprimée quand même), erreurs JSON `{"error"}` (étendre `ApiExceptionListener` à `/internal`).
- **Conséquences** : le tout-ou-rien de Symfony devient « compte créé + consommation rejouable idempotente » (option autorisée par SC-IDENT-005) ; tests : WireMock (succès, 500, délai, 401) et PHPUnit côté Symfony.

### A11 — Outbox transactionnelle
- **Décision** : table `identity.outbox_message` (`id BIGINT AUTO_INCREMENT`, `event_id CHAR(36) UNIQUE`, `type VARCHAR(64)`, `destination VARCHAR(32)` ∈ {`symfony-invitations`, `broker`}, `aggregate_id INT`, `payload JSON`, `occurred_at DATETIME(6)` UTC, `available_at DATETIME(6)` UTC, `status VARCHAR(16)` ∈ {`PENDING`, `DELIVERED`, `FAILED`, `HELD`}, `attempts INT`, `last_error VARCHAR(255)` sans donnée personnelle, `delivered_at DATETIME(6)`), index `(destination, status, available_at)`.
- En phase 1, les messages `broker` sont écrits en `HELD` (aucun transport : pas de RabbitMQ, U2) ; le futur relais RabbitMQ les publiera dans l'ordre d'`id`. Le format JSON des charges est celui du contrat interne (schémas `UserRegistered`, `UserProfileUpdated`, `InvitationClaimRequest`).
- Purge des messages `DELIVERED` de plus de 30 jours (tâche planifiée).

### A12 — Seuils de qualité
- JaCoCo : **lignes ≥ 90 %** et **branches ≥ 80 %** sur `domain` et `application` ; lignes ≥ 80 % sur le module ; la build échoue en dessous.
- Checkstyle (Google, avec la seule exception de portée de l'A17), Spotless et Checker Framework : **0** violation (échec de build en profil `quality`).
- Sonar : les seules exceptions sont celles, revues et justifiées, de l'A16.
- OWASP : échec si CVSS ≥ 7.
- Sonar : quality gate « Sonar way », 0 bug/vulnérabilité bloquants ou critiques, 0 security hotspot non revu.

### A13 — Configuration et secrets
- Tout passe par variables d'environnement : `IDENTITY_DB_URL`, `IDENTITY_DB_USER`, `IDENTITY_DB_PASSWORD`, `JWT_PRIVATE_KEY_PATH`, `JWT_PUBLIC_KEY_PATH`, `JWT_PASSPHRASE`, `JWT_TTL`, `LEGACY_SCHEMA`, `LEGACY_ZONE`, `SYMFONY_INTERNAL_URL` (défaut `http://api:80`), `INTERNAL_API_SECRET`. Aucune valeur réelle dans le dépôt ; valeurs factices dans `.env.example` (Manuel).
- Journalisation : jamais de mot de passe, hash, jeton, clé, secret interne ; les e-mails ne sont pas journalisés en `INFO` (identifiant numérique à la place).
- Port HTTP interne 8080 ; `management.server.port` 8091 (`/actuator/health` pour Docker, non routé par nginx) ; port publié pour le développement : **8082** (proxy Angular).

### A14 — Reprise des données : commande ré-exécutable plutôt que migration Flyway
- **Contexte** : Paul proposait `V2__import_legacy_users.sql`. Une migration Flyway ne s'exécute qu'une fois, alors qu'il faut copier à la bascule (après gel des écritures) et resynchroniser après un retour arrière.
- **Décision** : Flyway ne crée que le schéma (`V1`, `V2`) ; la copie est le cas d'utilisation `ImportLegacyUsersUseCase`, lancé par `LegacyImportRunner` (`java -jar identity-service.jar --identity.legacy-import=run`), idempotent (`INSERT … ON DUPLICATE KEY UPDATE` par id), avec rapport de contrôle. Détails : [identity-migration.md](identity-migration.md).

### A15 — Écarts E15–E21 corrigés dans identity-service (décisions utilisateur du 26/11/2026)
- **Contexte** : l'utilisateur a choisi de **corriger** ces écarts plutôt que de les reproduire (docs/specs/decisions.md). Symfony n'est pas modifié.
- **Décision** — statut : **corrigé (décision utilisateur)** :
  | Réf. | identity-service | Où | Scénario |
  |---|---|---|---|
  | E15 | mot de passe > 4096 octets UTF-8 (inscription, `newPassword`) → 400 `Le mot de passe ne doit pas dépasser 4096 octets.` ; login → 401 inchangé | domaine (`PlainPassword`) | SC-IDENT-028 |
  | E16 | `register`, `login` et `PATCH /me` : corps JSON non objet → 400 `JSON invalide.` | adapter web (`PhpJsonBody`) | SC-IDENT-029 |
  | E17 | champ présent non chaîne → 400 `Le champ "<nom>" doit être une chaîne de caractères.` ; `null` refusé à l'inscription, « absent » sur `PATCH` ; contrôle de type de tous les champs connus avant les contrôles de contenu, dans l'ordre du contrat ; s'applique aussi au login (E19) | adapter web | SC-IDENT-030 |
  | E18 | `avatarColor` doit correspondre à `\A#[0-9a-fA-F]{6}\z`, sinon 400 `Couleur d'avatar invalide.` (sans modification partielle) | domaine (`AvatarColor`) | SC-IDENT-031 |
  | E19 | `login` : objet JSON (E16) → type de `email` puis `password` (non chaîne, `null` compris → 400 E17) → présence de `email` puis `password` (absent ou `""` → 400 `Le champ "<nom>" est obligatoire.`) ; `Content-Type` non contrôlé ; identifiants faux → 401 `{"code":401,"message":"Invalid credentials."}` inchangé | adapter web (`JsonLoginRequestParser`) | SC-IDENT-032 (et SC-IDENT-009 ajusté) |
  | E20 | corps vide sur `register`, `login`, `PATCH /me` → 400 `JSON invalide.` (la règle transverse « vide = `{}` » ne s'applique plus à ces routes) | adapter web (`PhpJsonBody`) | SC-IDENT-033 |
  | E21 | `PATCH /me` : seuls `newPassword` absent, `null` ou `""` = pas de changement ; `"0"` et toute autre chaîne passent les contrôles (mot de passe actuel, longueurs) | adapter web + domaine | SC-IDENT-034 |
- **Conséquences** : ces cas sont des **divergences voulues** : les tests `compat` (miroir Symfony) les excluent explicitement (liste dans l'ordre de travail) ; les autres comportements (trim PHP à l'inscription, points de code, collation, recherche de l'e-mail brut au login, 401 Lexik) restent reproduits. Le contrat public documente tous les nouveaux 400.

### A16 — Faux positifs Sonar revus (revue 1, B6)
- **Contexte** : A12 exige 0 vulnérabilité critique non revue. Sonar signale S4502 (CRITICAL, CSRF désactivé, `config/SecurityConfig.java`) et 6 × S2077 (MAJOR, SQL formaté, `adapter/out/legacy/JdbcLegacyUserSource.java`).
- **Analyse** :
  - S4502 : l'API est sans état (`SessionCreationPolicy.STATELESS`), l'authentification passe **uniquement** par l'en-tête `Authorization: Bearer` (A1), il n'existe ni cookie ni session ; un navigateur n'ajoute jamais cet en-tête de lui-même, donc une requête intersite forgée n'est pas authentifiée. La protection CSRF n'apporte rien et casserait les clients non navigateur. **Sûr.** À réexaminer si un jour un cookie d'authentification est introduit (alors : CSRF obligatoire).
  - S2077 : seul le nom du schéma historique est concaténé, validé `[a-z_]+` au démarrage (`LegacySchema`, refus de démarrer sinon), avec des fragments constants ; toutes les valeurs passent en paramètres liés. **Sûr.**
- **Décision** : exclusion **déclarative et versionnée** dans `services/pom.xml` (`sonar.issue.ignore.multicriteria`, entrées `e1` = `java:S4502` sur `**/com/taskora/identity/config/SecurityConfig.java`, `e2` = `java:S2077` sur `**/com/taskora/identity/adapter/out/legacy/JdbcLegacyUserSource.java`), avec la justification en commentaire. Préférée à « Safe » dans l'interface (perdu sur un serveur neuf ou une nouvelle clé de projet) et à `// NOSONAR` (qui masque **toutes** les règles de la ligne). Portée minimale : une règle × un fichier ; toute occurrence ailleurs reste signalée.
- **Conséquences** : BackReviewer vérifie à chaque revue que la liste ne contient que ces deux entrées ; toute nouvelle exclusion exige une ADR.

### A17 — Portée de la règle Javadoc de Checkstyle (revue 1, B2)
- **Contexte** : 164 violations Google, dont 131 `MissingJavadocMethod` (méthodes publiques et constructeurs) et 22 `LineLength`. L'intention d'A12 est un code **lisible et dont le contrat est documenté là où il est consommé**, pas une Javadoc systématique.
- **Décision** :
  1. `google_checks.xml` reste la configuration (aucune règle désactivée, aucun seuil changé, `violationSeverity=warning`, échec de build).
  2. **Une seule exception de portée** : `MissingJavadocMethod` ne s'applique pas aux paquets `adapter` et `config` (`services/config/checkstyle/checkstyle-suppressions.xml`, branché par `propertyExpansion` sur le crochet `org.checkstyle.google.suppressionfilter.config` de google_checks).
  3. Elle reste **obligatoire** dans `domain` et `application` (modèle, événements, exceptions, ports in/out, services, records) et pour `IdentityServiceApplication` : c'est le contrat de l'hexagone, lu par les autres agents et services.
  4. `MissingJavadocType` (chaque type public documenté), `LineLength` 100, `SummaryJavadoc` et toutes les autres règles s'appliquent **partout**, adapters et config compris.
- **Justification** : le contrat des adapters est déjà documenté à un niveau plus fiable que la Javadoc (OpenAPI avec `x-scenarios` pour les contrôleurs, schémas internes et ADR A1–A16 pour les clients, la sécurité et la persistance, `IdentityProperties` pour la configuration) ; une Javadoc sur chaque méthode `@Bean`, constructeur d'injection ou accesseur d'entité JPA répéterait le code sans rien apprendre au lecteur. Ce n'est pas un assouplissement pour « passer » : les longueurs de ligne, les noms et le `default` de `switch` restent à corriger, et la règle Javadoc garde toute sa force là où vit le contrat.
- **Mesure** : avec cette portée, `checkstyle:check` passe de 164 à **97** violations (64 `MissingJavadocMethod` dans `domain`/`application`, 22 `LineLength`, 5 `SummaryJavadoc`, 2 `VariableDeclarationUsageDistance`, 1 `GoogleMethodName`, 1 `AbbreviationAsWordInName`, 1 `JavadocParagraph`, 1 `MissingSwitchDefault`) : toutes à corriger par BackCodeur.

### A18 — Robustesse après la revue 1 (m1, m2, m3, m4)
- **Secret interne obligatoire** : `identity.symfony.internal-secret` (`INTERNAL_API_SECRET`) vide ou composé d'espaces → **le service refuse de démarrer** (validation de `IdentityProperties`, message citant la variable `INTERNAL_API_SECRET`, jamais la valeur). Évite des claims tous en `FAILED` et des invitations perdues.
- **Taille du corps** : `POST /api/auth/register`, `POST /api/auth/login` et `PATCH /api/me` lisent au plus **65 536 octets** (64 Kio, largement au-dessus du plus grand corps légitime : mot de passe ≤ 4096 octets, nom 120, e-mail 180). Au-delà → **413** `{"error":"Corps de requête trop volumineux."}`, sans lire la suite. Mécanisme : `Content-Length` > limite → refus immédiat ; sinon lecture bornée du flux (`InputStream.readNBytes(limite + 1)`), ce qui couvre aussi `Transfer-Encoding: chunked`. L'authentification passe avant (`PATCH /api/me` sans jeton → 401). Défense en profondeur côté nginx (`client_max_body_size 64k` sur `/api/auth/` et `/api/me`, page d'erreur 413 en JSON identique).
- **Reprise (ODKU)** : l'import garde `INSERT … SELECT … ON DUPLICATE KEY UPDATE` (rapide, atomique, idempotent), **précédé** d'un contrôle bloquant : nombre de lignes source dont l'e-mail normalisé existe déjà dans la cible **sous un autre id** (`JOIN … ON t.email = LOWER(TRIM(s.email)) AND t.id <> s.id`). S'il est > 0, l'import échoue **avant toute écriture** (code de sortie ≠ 0, nombre seulement). L'ODKU ne peut alors se déclencher que sur la clé primaire.
- **Journaux SQL** : `org.hibernate.orm.jdbc.error` et `org.hibernate.engine.jdbc.spi.SqlExceptionHelper` à **`OFF`** (et non `ERROR`) : les messages du pilote citent les valeurs en conflit.
