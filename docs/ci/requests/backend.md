# Demande CI/CD backend — identity-service (phase 1)

Demandeur : BackAli. Destinataire : Manuel. Statut : **ouverte** (26/11/2026).
Références : architecture §7 (versions), §9 (ADR), [identity-migration.md](../../architecture/identity-migration.md), contrats `docs/contracts/openapi/identity-service*.yaml`, plan de Paul `docs/specs/phase-1-identity.md` §5.

## 1. Conventions communes à tous les jobs
- Runner `ubuntu-latest` avec Docker (Testcontainers). `permissions: contents: read` (plus `checks: write` / `pull-requests: write` seulement pour la publication des rapports).
- Actions **épinglées par SHA** ; `actions/setup-java` distribution `temurin`, `java-version: 25`, `cache: maven`.
- Toutes les commandes depuis `services/` avec le wrapper : `./mvnw -B -ntp …` (Maven 3.10.2, wrapper 3.3.4 `only-script`).
- Aucun secret réel. Les seuls secrets de dépôt demandés : `NVD_API_KEY` (OWASP), `SONAR_TOKEN` (Sonar, facultatif).
- **Paire de clés JWT de test générée à l'exécution**, jamais commitée, chiffrée comme celle de Lexik :
  ```bash
  mkdir -p "$RUNNER_TEMP/jwt"
  export JWT_PASSPHRASE="$(openssl rand -hex 24)"
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -aes256 -pass env:JWT_PASSPHRASE -out "$RUNNER_TEMP/jwt/private.pem"
  openssl pkey -in "$RUNNER_TEMP/jwt/private.pem" -passin env:JWT_PASSPHRASE -pubout -out "$RUNNER_TEMP/jwt/public.pem"
  echo "::add-mask::$JWT_PASSPHRASE"
  ```
  Les tests unitaires et d'intégration génèrent **eux-mêmes** leur paire en mémoire (classe de test `TestKeys`) : cette étape ne sert qu'aux jobs qui démarrent la stack compose.
- `INTERNAL_API_SECRET` de CI : `openssl rand -hex 32`, masqué.
- Testcontainers : `TESTCONTAINERS_RYUK_DISABLED` **non** positionné (Ryuk actif) ; image `mariadb:11.8` (pré-tirée pour le cache : `docker pull mariadb:11.8`). Pas de RabbitMQ ni de LocalStack en phase 1.

## 2. Jobs demandés

| Job | Déclencheur | Commande(s) | Bloquant |
|---|---|---|---|
| `identity-build` | PR et push touchant `services/**` | `./mvnw -B -ntp -pl identity-service -am verify` | oui |
| `identity-quality` | idem | `./mvnw -B -ntp -pl identity-service -am -Pquality verify -DskipTests` puis `./mvnw -B -ntp -pl identity-service rewrite:dryRun` (échec si `target/rewrite/rewrite.patch` non vide) | oui |
| `identity-security` | PR touchant `services/**/pom.xml` + hebdomadaire (lundi 05:00 UTC) | `./mvnw -B -ntp -pl identity-service -am -Psecurity-scan verify -DskipTests -DnvdApiKey=${{ secrets.NVD_API_KEY }}` | oui (CVSS ≥ 7) |
| `identity-compat` | PR touchant `services/identity-service/**`, `backend/**`, `docker-compose.yml`, `frontend/nginx.conf` | stack compose complète (§3), puis `./mvnw -B -ntp -pl identity-service verify -Pcompat -Dcompat.symfony.url=http://localhost:8081 -Dcompat.identity.url=http://localhost:8082 -Dcompat.web.url=http://localhost:8080` | oui |
| `backend-phpunit` | PR touchant `backend/**` | `docker compose run --rm -e APP_ENV=test -e DATABASE_URL=… api sh -c 'composer install --no-interaction && php bin/console doctrine:database:create --if-not-exists --env=test && php bin/console doctrine:migrations:migrate -n --env=test && vendor/bin/phpunit --log-junit var/phpunit-junit.xml'` (image avec dépendances `require-dev`, base `taskora_test`) | oui |
| `identity-sonar` | PR (si `SONAR_TOKEN` présent) | `./mvnw -B -ntp -pl identity-service -am verify sonar:sonar -Dsonar.token=${{ secrets.SONAR_TOKEN }} -Dsonar.qualitygate.wait=true` | oui si actif |
| `identity-image` | push sur `main` et PR | `docker build -f services/identity-service/Dockerfile services/` (build seulement en PR ; pas de push de registre sans accord de l'utilisateur) | oui |
| `frontend-e2e-identity` | PR touchant `frontend/**` ou `services/identity-service/**` | à définir avec FrontPierre (Playwright `identity.spec.ts` contre la stack compose) | oui |

### Ce que lance `verify` (profil par défaut)
- Surefire : tests `*Test` (unitaires, ArchUnit, MockMvc). Failsafe : tests `*IT` (Testcontainers MariaDB, WireMock, `@SpringBootTest`).
- Tag JUnit `compat` **exclu** par défaut, inclus seulement par `-Pcompat` (il exige la stack compose).
- JaCoCo `check` : lignes ≥ 90 % et branches ≥ 80 % sur `com.taskora.identity.domain..` et `..application..` ; lignes ≥ 80 % sur le module (A12).
- Enforcer : Java ≥ 25, Maven ≥ 3.9, convergence des dépendances.

### Profil `quality`
`spotless:check` (google-java-format 1.37.0), `checkstyle:check` (google_checks, `failOnViolation`, `violationSeverity=warning`), compilation avec le Nullness Checker (Checker Framework 4.4.0) sur `src/main` uniquement.

### Profil `security-scan`
`dependency-check:check` 13.0.0, `failBuildOnCVSS=7`, formats `HTML,JSON,SARIF`, cache de la base NVD (`~/.m2/repository/org/owasp/dependency-check-data`) par `actions/cache` avec clé hebdomadaire.

## 3. Stack pour `identity-compat` (et développement local)

À ajouter à `docker-compose.yml` **sans casser la stack actuelle** :
1. Service `identity` :
   - build `services/identity-service/Dockerfile`, contexte `services/` ; multi-étapes `maven:3.10.2-eclipse-temurin-25` → `eclipse-temurin:25-jre` ; utilisateur non root (uid 10001) ; `-XX:MaxRAMPercentage=75` ;
   - `healthcheck` sur `http://localhost:8091/actuator/health` ;
   - ports : `8082:8080` en développement (`IDENTITY_PORT`), rien d'autre ; 8091 non publié ;
   - volume `jwt_keys:/run/taskora/jwt:ro` ; `depends_on: api: condition: service_healthy` (les clés sont créées par l'entrypoint de `api`) et `database: service_healthy` ;
   - environnement : `IDENTITY_DB_URL=jdbc:mariadb://database:3306/identity`, `IDENTITY_DB_USER=identity`, `IDENTITY_DB_PASSWORD=${IDENTITY_DB_PASSWORD}`, `JWT_PRIVATE_KEY_PATH=/run/taskora/jwt/private.pem`, `JWT_PUBLIC_KEY_PATH=/run/taskora/jwt/public.pem`, `JWT_PASSPHRASE=${JWT_PASSPHRASE}` (même variable que `api`), `LEGACY_SCHEMA=taskora`, `LEGACY_ZONE=Europe/Paris`, `SYMFONY_INTERNAL_URL=http://api:80`, `INTERNAL_API_SECRET=${INTERNAL_API_SECRET}`.
2. Service `api` : ajouter `INTERNAL_API_SECRET=${INTERNAL_API_SECRET}` et un `healthcheck` (`/api/health`) ; **recommandation** : publier `8081` sur `127.0.0.1` seulement (l'endpoint `/internal/*` est sinon joignable depuis le réseau de l'hôte ; il reste protégé par le secret).
3. Base : script d'initialisation **idempotent exécuté en root** (le compte `taskora` n'a de droits que sur `taskora.*`) : schéma `identity`, utilisateur `identity` et droits exacts de [identity-migration.md §2](../../architecture/identity-migration.md#2-comptes-mariadb-init-sql-idempotent-manuel-exécuté-en-root) ; base `taskora_test` + droits de `taskora` dessus (PHPUnit). Attention : `/docker-entrypoint-initdb.d` ne s'exécute que sur un volume vide ; prévoir un service d'initialisation `db-init` (client `mariadb`, `restart: "no"`) pour les volumes existants.
4. nginx (`frontend/nginx.conf`) : `location /api/auth/` et `location = /api/me` vers un amont **paramétrable** (`IDENTITY_UPSTREAM`, défaut `api:80` tant que la bascule n'est pas faite ; `identity:8080` après), rechargeable sans reconstruction (gabarit `envsubst` du conteneur nginx officiel). Prévoir aussi le mode « gel » (503 JSON sur `POST /api/auth/register` et `PATCH /api/me`) décrit dans identity-migration.md §5.
5. `.env.example` : `IDENTITY_DB_PASSWORD`, `INTERNAL_API_SECRET`, `IDENTITY_PORT`, `IDENTITY_UPSTREAM` avec valeurs factices.
6. RabbitMQ : **non requis** en phase 1 (U2-a).

Dans `identity-compat`, le job génère la paire de clés (§1) **dans le volume** avant le démarrage d'`api` (`--skip-if-exists` la réutilise) : `docker volume create taskora_jwt_keys` + copie via un conteneur `alpine`, ou laisser `api` la générer avec `JWT_PASSPHRASE` aléatoire de CI (plus simple, recommandé).

## 4. Rapports et artefacts (rétention 14 jours)
| Artefact | Chemin |
|---|---|
| JUnit (Surefire + Failsafe) | `services/identity-service/target/surefire-reports/`, `target/failsafe-reports/` (publication via un rapporteur de tests épinglé) |
| Couverture | `services/identity-service/target/site/jacoco/` (`jacoco.xml` pour Sonar) |
| Checkstyle / Spotless | `target/checkstyle-result.xml` |
| OpenRewrite | `target/rewrite/rewrite.patch` |
| OWASP | `target/dependency-check-report.{html,json,sarif}` (SARIF téléversé dans l'onglet Security si autorisé) |
| PHPUnit | `backend/var/phpunit-junit.xml` |
| Journaux compose en cas d'échec de `identity-compat` | `docker compose logs --no-color` (vérifier qu'ils ne contiennent ni jeton ni mot de passe : le job échoue si `grep` trouve la passphrase ou un motif `eyJ…\.eyJ…`) |

## 5. Seuils (récapitulatif)
- Tests : 0 échec, 0 test ignoré non justifié (BackReviewer).
- JaCoCo : domaine + application lignes ≥ 90 %, branches ≥ 80 % ; module lignes ≥ 80 %.
- Checkstyle, Spotless, Nullness : 0 violation.
- OWASP : CVSS < 7.
- Sonar : quality gate vert.

## 6. Réponse de Manuel
Statut : **accepté avec modifications** (26/11/2026). Détail : [../README.md](../README.md).
- Workflows : `identity-service.yml` (identity-build, identity-test-report, identity-quality, identity-sonar, identity-image), `identity-security.yml`, `identity-compat.yml`, `backend.yml` (backend-phpunit). `frontend-e2e-identity` : en attente de FrontPierre.
- Écarts :
  - `identity` n'a que le réseau interne `backend` ; le port 8082 (127.0.0.1) est publié par `docker-compose.identity-dev.yml` (utilisé par `identity-compat`).
  - Init MariaDB : `docker/mariadb/initdb.d/10-taskora-init.sh` (initdb + service `db-init` du profil `identity`, après `api` sain, car le droit sur `taskora.user` exige la table).
  - nginx : `IDENTITY_UPSTREAM` (défaut `api:80`) et `IDENTITY_WRITE_FREEZE` (`on|off`) ; changement = `docker compose up -d web`, sans reconstruction (gabarit rendu au démarrage du conteneur, pas `nginx -s reload`).
  - Sonar : jeton lu par variable d'environnement (pas `-Dsonar.token` en ligne de commande) ; URL du serveur = variable de dépôt `SONAR_HOST_URL`.
  - backend-phpunit : `DATABASE_URL` de compose vise déjà `/taskora` (suffixe `_test` ajouté par doctrine.yaml) ; ajout de `unset INTERNAL_API_SECRET` et de `lexik:jwt:generate-keypair`.

### Orchestration (Manuel, 26/11/2026)
- Les workflows `identity-service`, `identity-security`, `identity-compat` et `backend` sont appelés par `ci.yml`, sans changement de leurs jobs. Ils tournent selon les mêmes chemins, calculés par le job `changes`. Le seul check requis sur `main` est `ci-gate` ; les checks s'appellent désormais `<zone> / <job>`, par exemple `identity-service / identity-build (verify)`. Détail : [../README.md](../README.md#orchestration--ciyml-et-le-check-unique-ci-gate).

### Boucle de revue 1 (Manuel, 26/11/2026)
- B5 : `identity-security` échoue avec « NVD_API_KEY absent : dependency-check 13 ne peut pas s'exécuter » si le secret manque ; clé passée par `-DnvdApiKeyEnvironmentVariable=NVD_API_KEY`. Secret `NVD_API_KEY` créé par l'utilisateur dans GitHub.
- Rapport OWASP : `identity-security` vert sur la PR #1 ; résumé chiffré : voir N7 ci-dessous.
- m2 : nginx `client_max_body_size 64k` + 413 JSON sur `/api/auth/` et `location = /api/me` (vérifié : 65 536 octets → 400 du backend, 65 537 → 413, `chunked` → 413).
- m9 : fait par le coordinateur (`DB_BIND=127.0.0.1` par défaut, 0b6af27).
- m10 : digests pour mariadb, gotenberg, sonarqube, node, nginx ; `mem_limit`/`pids_limit` sur `identity`. `eclipse-temurin:25-jdk` et `25-jre` (`services/identity-service/Dockerfile`) sont épinglés par digest depuis (BackCodeur) ; `dunglas/frankenphp` et `composer` (`backend/Dockerfile`) le sont par N2 ci-dessous.

### Suites de la recette du 26/11/2026 (Manuel, 26/11/2026)
Référence : [recette identity-service](../../reviews/2026-11-26-identity-service-recette.md), points N2 et N7.

#### N2 : images du backend Symfony épinglées par digest
`backend/Dockerfile` : mêmes étiquettes, suivies du digest d'index multi-architecture, résolu le 26/11/2026 par `docker buildx imagetools inspect <image:tag>`.

| Image | Digest (index OCI multi-architecture) |
|---|---|
| `dunglas/frankenphp:1-php8.4-alpine` | `sha256:f81693fa548684eb12964eeab78d86325b75c5cd91289c09ffc4dd4404a641e5` |
| `composer:2` | `sha256:0049ce90b432795bf413a738b073963ada1a689e1a8708104684baafe94586ec` |

Vérification : `docker build -f backend/Dockerfile backend/` (contexte `./backend` du service `api` de `docker-compose.yml`) réussi, sous une étiquette jetable supprimée ensuite. Mise à jour : relancer `imagetools inspect`, remplacer le digest, reconstruire.

#### N7 : résumé OWASP (condition C1)
- Gate (`identity-security.yml` + profil `security-scan` de `services/pom.xml`) : `./mvnw -B -ntp -pl identity-service -am -Psecurity-scan verify -DskipTests -DskipITs`, dependency-check-maven 13.0.0, `failBuildOnCVSS=7`. Le job échoue dès qu'une dépendance a un score CVSS ≥ 7 ; un job vert signifie donc **0 vulnérabilité de CVSS ≥ 7**. Les vulnérabilités de score < 7 ne bloquent pas, mais figurent dans le rapport.
- Run local impossible sans clé NVD. Essai du 26/11/2026 : `./mvnw -B -ntp -pl identity-service -am -Psecurity-scan org.owasp:dependency-check-maven:check -DfailBuildOnCVSS=7`, dans `eclipse-temurin:25-jdk` (même digest que le Dockerfile d'identity), avec un dépôt Maven jetable. Résultat : échec en 21 s, avec `NvdApiException: Invalid API Key, length of 0` puis `NoDataException: No documents exist`. Aucune clé n'a été demandée ni lue.
- Les nombres sont donc à reporter depuis l'artefact du **dernier run vert** de `identity-security` (PR #1, fusion `86b3b07`, ou le dernier run hebdomadaire vert) :
  1. GitHub → *Actions* → run `ci` de la PR #1 (ou run `identity-security` planifié) → job `identity-security / identity-security` vert ;
  2. section *Artifacts* → **`identity-dependency-check`** (rétention 14 jours ; s'il a expiré, relancer `identity-security` par *Run workflow*) ;
  3. dans l'archive : `dependency-check-report.html` (en-tête *Summary*), `dependency-check-report.json` et `dependency-check-report.sarif`, produits dans `services/identity-service/target/`. Depuis le JSON :
     - dépendances analysées : `jq '.dependencies | length'` ;
     - dépendances vulnérables : `jq '[.dependencies[] | select(.vulnerabilities)] | length'` ;
     - vulnérabilités par sévérité : `jq -r '[.dependencies[].vulnerabilities[]? | .severity] | group_by(.) | map("\(.[0]): \(length)") | .[]'` ;
     - CVE et scores : `jq -r '.dependencies[] | select(.vulnerabilities) | .fileName as $f | .vulnerabilities[] | "\($f) \(.name) \(.cvssv3.baseScore // .cvssv2.score)"'`.

| Indicateur | Valeur |
|---|---|
| Run de référence (n°, date) | à compléter depuis l'artefact CI |
| Version de dependency-check / date de la base NVD | 13.0.0 / à compléter depuis l'artefact CI |
| Dépendances analysées | à compléter depuis l'artefact CI |
| Dépendances avec au moins une vulnérabilité | à compléter depuis l'artefact CI |
| Vulnérabilités CVSS ≥ 7 (bloquantes) | 0 (job vert ; à confirmer depuis l'artefact CI) |
| Vulnérabilités CVSS < 7 (identifiant, dépendance, score) | à compléter depuis l'artefact CI |
| Suppressions (`suppressionFile`) | aucune configurée dans `services/pom.xml` |

---

# Demande CI/CD backend — board-service (phase 3)

Demandeur : BackAli. Destinataire : Manuel. Statut : **ouverte** (27/11/2026).
Références : architecture §10 (B-ADR-1 à 12), [board-migration.md](../../architecture/board-migration.md) (§2 schéma `board`, §3 droits, §5.2 routage, §7 répétition), contrats `docs/contracts/openapi/board-service*.yaml` et `symfony.internal.yaml`, spec `docs/specs/phase-3-board-service.md` §13, ordre de travail `docs/specs/phase-3-board-workorder-t0-t1.md`.
Principe : **même orchestration que pour identity** (`ci.yml`, zones calculées par `changes`, workflows réutilisables, `ci-gate` seul check requis). Les conventions du §1 ci-dessus s'appliquent telles quelles (SHA épinglés, `permissions: {}` puis minimales, secrets éphémères masqués, pas de `secrets: inherit`, artefacts 14 jours, contrôle de fuite avant publication, `-Pquality` jamais suivi de tests sans `clean`).

## B1. Nouvelles zones dans `changes` (`ci.yml`)
| Sortie | Chemins (ERE) | Événements |
|---|---|---|
| `board_service` | `^(services/board-service/\|services/pom\.xml$\|services/config/\|services/\.mvn/\|services/mvnw\|\.github/workflows/board-service\.yml$)` | PR, push `main` |
| `board_security` | `^(services/board-service/pom\.xml$\|services/pom\.xml$\|\.github/workflows/board-security\.yml$)` | PR (+ planifié lundi 05:30 UTC et manuel, en autonome) |
| `board_compat` | `^(services/board-service/\|services/identity-service/\|backend/\|docker-compose[^/]*\.yml$\|docker/mariadb/\|frontend/nginx\.conf$\|frontend/Dockerfile$\|\.github/workflows/board-compat\.yml$)` | PR |
| `backend` (existante) | ajouter `docs/contracts/openapi/symfony\.internal\.yaml$` (les tests PHPUnit des endpoints internes le valident) | PR |
| `frontend` (existante) | ajouter `services/board-service/` (e2e `@board` sur les deux amonts) | PR, push `main` |
- `ci.yml` modifié relance toutes les zones (règle existante).
- La zone `identity_service` (`^services/`) se déclenche aussi sur une modification de board-service : **voulu** (parent Maven commun).
- Sur push `main` : `board_security` et `board_compat` à `false` (comme identity).

## B2. Workflows demandés (réutilisables, `on: workflow_call`)
| Workflow / job | Commande(s) (depuis `services/`) | Bloquant | Artefact |
|---|---|---|---|
| `board-service.yml` / `board-build (verify)` | `./mvnw -B -ntp -pl board-service -am verify` (unitaires, ArchUnit, IT Testcontainers MariaDB + WireMock, JaCoCo A12) | oui | `board-test-reports` |
| `board-service.yml` / `board-test-report` | rapporteur JUnit épinglé (seul job avec `checks: write`, pas sur les PR de fork) | non | — |
| `board-service.yml` / `board-quality (Spotless, Checkstyle, Nullness, OpenRewrite)` | `./mvnw -B -ntp -pl board-service -am clean -Pquality verify -DskipTests -DskipITs` puis `./mvnw -B -ntp -pl board-service rewrite:dryRun` (échec si `target/rewrite/rewrite.patch` non vide) | oui | `board-quality-reports` |
| `board-service.yml` / `board-sonar` | comme `identity-sonar` (`-pl board-service`, clé de projet `taskora-board-service`) ; si `SONAR_TOKEN` + `SONAR_HOST_URL` | oui si actif | — |
| `board-service.yml` / `board-image (build, Trivy)` | `docker build -f services/board-service/Dockerfile services/` puis Trivy **identique** au job `identity-image` (même digest, mêmes options `--severity HIGH,CRITICAL --ignore-unfixed --pkg-types os,library --exit-code 1`, `.trivyignore` lu dans `services/board-service/`) ; pas de push | oui | `board-image-trivy` |
| `board-security.yml` / `board-security` | `./mvnw -B -ntp -pl board-service -am -Psecurity-scan verify -DskipTests -DskipITs -DnvdApiKeyEnvironmentVariable=NVD_API_KEY` ; CVSS ≥ 7 bloquant ; SARIF → onglet Security | oui | `board-dependency-check` |
| `board-compat.yml` / `board-compat` | stack compose complète (§B3) puis `./mvnw -B -ntp -pl board-service verify -Pcompat -Dcompat.symfony.url=http://127.0.0.1:8081 -Dcompat.board.url=http://127.0.0.1:8083 -Dcompat.web.url=http://127.0.0.1:8080` (miroir Symfony ↔ board-service sur la **même base**, matrice d'accès SC-BOARD-021, écritures croisées SC-BOARD-023, claim croisé SC-BOARD-030, puis **test de routage** SC-BOARD-022 : le job commute `BOARD_T1_UPSTREAM` `api:80` → `board:8080` → `api:80` par `docker compose up -d web` et vérifie la table route → amont avec le marqueur `X-Taskora-Backend`) | oui | `board-compat-reports`, `board-compat-compose-logs` |
| `backend.yml` / `backend-phpunit` (existant) | inchangé ; couvre désormais `BoardAccess` en 404 (U8-a), `/internal/notifications`, migration `internal_event_receipt` (`doctrine:migrations:migrate` puis `doctrine:schema:validate`) | oui | `backend-phpunit-junit` |
| `frontend.yml` / `frontend-e2e-board (symfony\|board)` | à définir avec FrontPierre : matrice `BOARD_T1_UPSTREAM` = `api:80` / `board:8080`, `--grep @board`, même gabarit que `frontend-e2e-identity` | oui | `frontend-e2e-board-<backend>`, `frontend-compose-logs-board-<backend>` |
- `ci-gate` : ajouter `board-service`, `board-security`, `board-compat` à `needs` (même règle : `success` ou `skipped`).
- Permissions des appels : `board-service` → `contents: read`, `actions: read`, `checks: write` (test-report seul) ; `board-security` → `contents: read`, `actions: read`, `security-events: write` ; `board-compat` → `contents: read`. Secrets transmis explicitement : `SONAR_TOKEN` → `board-service`, `NVD_API_KEY` → `board-security`.
- Concurrence : groupe à nom fixe `board-security-<ref>` pour les runs autonomes (pas `github.workflow`).
- Alternative acceptée : généraliser `identity-security.yml` en un workflow paramétré par module (`inputs.module`) appelé deux fois ; noms d'artefacts distincts obligatoires.

### Ce que lance `verify` (board-service)
- Surefire `*Test` (unitaires, ArchUnit, MockMvc), Failsafe `*IT` (Testcontainers `mariadb:11.8` avec le **schéma `taskora` créé par les migrations Doctrine de `backend/migrations`**, voir B4 ; WireMock pour Symfony).
- Tag JUnit `compat` exclu par défaut, inclus par `-Pcompat`.
- JaCoCo : lignes ≥ 90 %, branches ≥ 80 % sur `com.taskora.board.domain..` et `..application..` ; lignes ≥ 80 % sur le module.
- Tag JUnit `concurrency` (IT de concurrence, 20–100 répétitions) **inclus** dans `verify` ; durée attendue < 10 min. Si le job dépasse 20 min, Manuel le signale à BackAli (pas de sortie silencieuse du tag).

## B3. Stack pour `board-compat` (et développement local)
À ajouter **sans casser** la stack actuelle ni le profil `identity` :
1. Service `board` (profil `board`) :
   - build `services/board-service/Dockerfile`, contexte `services/` ; multi-étapes `maven:3.10.2-eclipse-temurin-25` → `eclipse-temurin:25-jre` (digests épinglés comme identity) ; utilisateur non root (uid 10001) ; `-XX:MaxRAMPercentage=75` ; `mem_limit: 768m`, `pids_limit: 256` ;
   - `healthcheck` `http://localhost:8091/actuator/health` ; réseau `backend` seulement ; port publié **uniquement** par `docker-compose.board-dev.yml` (`127.0.0.1:8083:8080`) ; 8091 jamais publié ;
   - volume `jwt_keys:/run/taskora/jwt:ro` mais **seul `public.pem` est lu** (`JWT_PUBLIC_KEY_PATH`) ; **aucune** variable `JWT_PASSPHRASE` ni `JWT_PRIVATE_KEY_PATH` dans son environnement (vérifié par un test de configuration et par BackReviewer) ;
   - `depends_on` : `api` sain (clés et migrations), `database` sain, `db-init` terminé avec succès ;
   - environnement : `BOARD_DB_URL=jdbc:mariadb://database:3306/taskora`, `BOARD_DB_USER=board`, `BOARD_DB_PASSWORD=${BOARD_DB_PASSWORD}`, `JWT_PUBLIC_KEY_PATH=/run/taskora/jwt/public.pem`, `SYMFONY_INTERNAL_URL=http://api:80`, `INTERNAL_SECRET_IDENTITY_TO_BOARD=${INTERNAL_SECRET_IDENTITY_TO_BOARD}`, `INTERNAL_SECRET_BOARD_TO_SYMFONY=${INTERNAL_SECRET_BOARD_TO_SYMFONY}` (**jamais** `INTERNAL_API_SECRET`, U12), `LEGACY_ZONE=Europe/Paris`, `BOARD_ROUTING_MARKER=${BOARD_ROUTING_MARKER:-off}`, `BOARD_PURGE_ENABLED=off`.
2. Base (`docker/mariadb/initdb.d/10-taskora-init.sh` et `db-init`) : schéma `board` et compte `board` avec **exactement** les droits de board-migration.md §3 (`REVOKE ALL` puis `GRANT`, droits sur `taskora.*` seulement quand les tables existent), variable `BOARD_DB_PASSWORD` (obligatoire si le profil `board` est actif). Le droit T4 (`INSERT` sur `attachment`) n'est accordé que si `BOARD_GRANT_COMMENT_FILES=on` (défaut `off`).
3. nginx (`frontend/nginx.conf`, gabarit `envsubst`) : `BOARD_T1_UPSTREAM` … `BOARD_T5_UPSTREAM` (défaut `api:80`), emplacements par expression régulière de board-migration.md §5.2, déclarés **avant** `location /api/` et après les emplacements d'identité ; `proxy_pass` par variable comme `IDENTITY_UPSTREAM` ; les routes « toujours Symfony » ne doivent être capturées par aucune expression (test de routage dans `board-compat`). Corps : limite actuelle de `/api/` inchangée (105 Mo).
4. Symfony (`api`) : ajouter `INTERNAL_SECRET_BOARD_TO_SYMFONY=${INTERNAL_SECRET_BOARD_TO_SYMFONY}` (U12) à côté de l'existant `INTERNAL_API_SECRET` (identity → Symfony, conservé) ; pour T5 : `TRASH_PURGE_ENABLED` (défaut `on`) lu par `docker/entrypoint.sh`.
5. identity : nouvelles variables `INVITATION_CLAIM_URL` (défaut `${SYMFONY_INTERNAL_URL}`) et `INVITATION_CLAIM_SECRET` (défaut `${INTERNAL_API_SECRET}`) ; après la bascule de T1 : `http://board:8080` et `${INTERNAL_SECRET_IDENTITY_TO_BOARD}`, commutés ensemble.
5 bis. **Secrets internes par appelant (U12)** : trois secrets **distincts**, générés éphémères en CI et en recette (`openssl rand -hex 32` chacun, masqués, jamais égaux : le job échoue si deux valeurs coïncident) : `INTERNAL_API_SECRET` (identity → Symfony), `INTERNAL_SECRET_IDENTITY_TO_BOARD` (identity → board), `INTERNAL_SECRET_BOARD_TO_SYMFONY` (board → Symfony). Chaque conteneur ne reçoit que les siens (B-ADR-5 §6) : `board` = les deux derniers ; `api` = le premier et le troisième ; `identity` = les deux premiers. `backend-phpunit` : valeurs factices distinctes dans `backend/.env.test` et `unset` des deux variables réelles, comme aujourd'hui.
6. `.env.example` : `BOARD_DB_PASSWORD`, `BOARD_PORT`, `BOARD_T1_UPSTREAM`…`BOARD_T5_UPSTREAM`, `BOARD_ROUTING_MARKER`, `INVITATION_CLAIM_URL`, `INVITATION_CLAIM_SECRET`, `INTERNAL_SECRET_IDENTITY_TO_BOARD`, `INTERNAL_SECRET_BOARD_TO_SYMFONY`, `BOARD_GRANT_COMMENT_FILES`, valeurs factices **différentes**.
7. Dans `board-compat` : projet compose dédié (`-p taskora-ci-board`), secrets `openssl rand` masqués, `BOARD_ROUTING_MARKER=on`, `down -v` toujours ; contrôle de fuite des journaux (jeton `eyJ….eyJ…`, les trois secrets internes, `BOARD_DB_PASSWORD`, motif d'e-mail `@exemple.test` dans les lignes `INFO` de `board`) avant publication.

### Mesure des faits (U11)
`docs/architecture/board-facts.sql` n'est **jamais** exécuté par la CI ni sur une stack de CI : Manuel le lance à la main sur la base de travail, uniquement avec l'accord explicite de l'utilisateur au moment de l'exécution, après la démo client, avant RB0 de T1 puis avant T2 (board-migration.md §1.3).

## B4. Schéma `taskora` dans les tests d'intégration
Les IT de board-service créent le schéma `taskora` **avec les migrations Doctrine réelles** (spec §9.1), pas avec un script réécrit. Mécanisme demandé (à valider par Manuel) : un fichier `services/board-service/src/test/resources/schema/taskora-schema.sql` **généré** par un job ou une commande documentée (`docker compose run --rm --no-deps api php bin/console doctrine:migrations:migrate -n` sur une base vide, puis `mariadb-dump --no-data --skip-comments taskora`) et **contrôlé en CI** : le job `board-build` régénère le fichier et échoue si `git diff --exit-code` n'est pas vide (dérive entre Doctrine et le fichier). Alternative : conteneur Symfony dans Testcontainers (plus lent). Réponse de Manuel attendue avant l'étape 1 de l'ordre de travail.

## B5. Seuils
Mêmes seuils que §5 (identity), plus : 0 test `@Disabled` ou tag exclu non justifié ; Trivy HIGH/CRITICAL corrigeables = 0 ; `board-compat` : 0 différence hors divergences listées dans le code (`INTENTIONAL_DIVERGENCES`).

## B6. Réponse de Manuel

### Réponse à B4 (Manuel, 27/11/2026) : **accepté**, fichier généré et contrôlé en CI
Choix : le fichier SQL généré (proposition de BackAli). Le conteneur Symfony dans Testcontainers est écarté : il faudrait construire l'image PHP à chaque `verify` (plusieurs minutes), alors que le fichier généré donne le même schéma, octet pour octet.

**1. Fichier** : `services/board-service/src/test/resources/schema/taskora-schema.sql` (sur le classpath de test).
- Propriétaire : **BackTesteur**. Il le génère avec la commande ci-dessous et le commite avec les tests de l'étape 1.
- Le fichier est dans `src/test/**`, donc protégé contre les Codeurs par le hook `protect-tests.sh`.
- Contenu (structure seule, aucune donnée) :
  - un en-tête qui donne le nombre de migrations et la dernière appliquée ;
  - `SET NAMES utf8mb4`, puis `SET FOREIGN_KEY_CHECKS = 0` ;
  - `CREATE DATABASE IF NOT EXISTS taskora` (utf8mb4_unicode_ci), puis `USE taskora` ;
  - un `DROP TABLE IF EXISTS` et un `CREATE TABLE` par table : les 18 tables Doctrine et `doctrine_migration_versions` (sans ses lignes) ;
  - `SET FOREIGN_KEY_CHECKS = 1` à la fin.
- Normalisation pour un résultat déterministe :
  - aucun commentaire conditionnel `/*!…*/` ni `/*M!…*/` (ScriptUtils les supprimerait sans les exécuter, ce qui casserait les clés étrangères) ;
  - aucun `AUTO_INCREMENT=N`, aucune date.
- Le fichier se charge à l'identique par le client `mariadb`, par `/docker-entrypoint-initdb.d` ou par `ScriptUtils` (`withInitScript`).

**2. Production** : `docker/mariadb/tools/taskora-schema.sh` (Manuel). Commande depuis la racine du dépôt :
```
docker/mariadb/tools/taskora-schema.sh            # écrit le fichier ci-dessus
docker/mariadb/tools/taskora-schema.sh --check    # contrôle : code 1 et diff unifié si le fichier a dérivé
docker/mariadb/tools/taskora-schema.sh --output <chemin>   # autre destination (essais)
```
- Étapes du script :
  1. Il construit l'image Symfony depuis `backend/` (cache Docker). Variante : `TASKORA_SCHEMA_API_IMAGE=<image>` réutilise une image déjà construite ; `backend/migrations` y est alors monté en lecture seule.
  2. Il démarre MariaDB jetable : réseau Docker `--internal` et conteneur `taskora-schema-*`. L'image est lue dans le service `database` de `docker-compose.yml` (même digest), avec `--character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci`. Les mots de passe sont éphémères et ne sont jamais affichés.
  3. Il lance `php bin/console doctrine:migrations:migrate -n` (`APP_ENV=prod`, sans l'entrypoint : pas de clés JWT, pas de purge).
  4. Il vérifie que le nombre de migrations appliquées est égal au nombre de fichiers `Version*.php`.
  5. Il exporte avec `mariadb-dump --no-data`, puis normalise le résultat.
  6. Il supprime toujours le conteneur et le réseau, même en cas d'échec.
- Différence avec la commande proposée en B4 : le script ne passe **pas** par `docker compose`. Il ne peut donc ni viser ni modifier les stacks `taskora` ou `taskora-e2e`.
- Prérequis : `docker`, `openssl`, `git`.
- Durée mesurée le 27/11/2026 : environ 13 s avec le cache de l'image (12 migrations, dernière `Version20261125165023`).
- Déterminisme vérifié : deux générations successives donnent un fichier identique (`--check` à 0). Une ligne ajoutée au fichier fait échouer `--check` (code 1).

**3. Contrôle en CI** (mis en place avec §6.2) :
- Dans `board-service.yml`, le job `board-build` lance d'abord `docker/mariadb/tools/taskora-schema.sh --check`, puis `verify`. Le job échoue si le fichier ne correspond plus aux migrations Doctrine.
- La zone `board_service` de `changes` (§B1) est étendue à `^backend/migrations/` et `^docker/mariadb/` : une migration Doctrine relance le contrôle.
- **Conséquence pour l'ordre de travail** : la migration `internal_event_receipt` (§4.2, BackCodeur) fera dériver le fichier. BackCodeur ne peut pas écrire dans `src/test/**`. BackTesteur régénère donc le fichier (une commande, commit `test(board): schéma taskora régénéré`) après le commit de BackCodeur. BackReviewer vérifie que le diff du fichier ne contient que la nouvelle table. Si BackAli préfère que ce fichier échappe à la règle des tests (il est entièrement déterminé et contrôlé par la CI), c'est à lui de trancher.

**4. Chargement recommandé dans les IT** (vérifié à la main le 27/11/2026, avec le fichier généré et le script d'init de la branche) :
- Conteneur : `MariaDBContainer` sur l'image `mariadb:11.8@sha256:e1d5495f445654134f226f55a41ce0da227116f6f27f283101f405c794d841fc` (digest de compose), commande `--character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci`.
- Deux fichiers copiés dans `/docker-entrypoint-initdb.d/`, exécutés par l'entrypoint en root et dans l'ordre alphabétique :
  - `05-taskora-schema.sql` : `MountableFile.forClasspathResource("schema/taskora-schema.sql")` ;
  - `10-taskora-init.sh` : `MountableFile.forHostPath("../../docker/mariadb/initdb.d/10-taskora-init.sh", 0755)`. Le chemin est relatif au module : Failsafe s'exécute dans `services/board-service`.
- Variables d'environnement du conteneur :
  - `MARIADB_DATABASE=taskora` ;
  - `MARIADB_ROOT_PASSWORD` (le script l'exige ; à fixer par `withEnv` si la version de Testcontainers ne le positionne pas) ;
  - `IDENTITY_DB_PASSWORD` et `BOARD_DB_PASSWORD` : valeurs aléatoires générées par le test ;
  - `BOARD_GRANT_COMMENT_FILES` : facultatif, défaut `off`.
- Résultat : le **vrai** compte `board`, avec exactement les droits de board-migration.md §3, créé par le **vrai** script d'init. `BoardDbPrivilegesIT` éprouve donc le script livré et non une copie.
- Ce qui a été constaté sous le compte `board` :
  - 16 tables visibles dans `taskora`, 54 droits de table, `SELECT` par colonne sur `user` (`avatar_color, created_at, email, id, name`) ;
  - `ALTER TABLE card` → 1142, `SELECT password` → 1143, `INSERT` dans `notification` → 1142 ;
  - `CREATE TABLE` dans `board` accepté, mais `DROP TABLE` dans `board` → 1142, car §3 n'accorde pas `DROP`. Flyway `clean` est donc impossible (garder `cleanDisabled`) ; les IT ne doivent pas compter sur un `DROP` sous ce compte.
- Variante plus légère : `withInitScript("schema/taskora-schema.sql")`. Le compte `board` doit alors être créé par le test, et ce n'est plus le script livré qui est vérifié.

### Stack board-service (§B3, réalisé le 27/11/2026, étape 0 de l'ordre de travail)
- `docker-compose.yml` :
  - service `board` (profil `board`) conforme à B3.1 : build `services/` + `board-service/Dockerfile` (à écrire par BackCodeur), uid 10001, `read_only`, `cap_drop: ALL`, `no-new-privileges`, `mem_limit: 768m`, `pids_limit: 256`, healthcheck 8091, réseau `backend` seul ;
  - variables de `board` : `jwt_keys` en lecture seule avec `JWT_PUBLIC_KEY_PATH` seul ; les deux secrets internes de board (jamais `INTERNAL_API_SECRET`) ; `BOARD_ROUTING_MARKER` (défaut `off`) ; `BOARD_PURGE_ENABLED=off` ;
  - `depends_on` de `board` : `database` sain, `api` sain, `db-init` terminé ;
  - `db-init` : profils `identity` **et** `board` ;
  - `database` et `db-init` reçoivent `BOARD_DB_PASSWORD` et `BOARD_GRANT_COMMENT_FILES` ;
  - `api` reçoit `INTERNAL_SECRET_BOARD_TO_SYMFONY` ;
  - `identity` reçoit `INVITATION_CLAIM_URL` (défaut `http://api:80`) et `INVITATION_CLAIM_SECRET` (défaut : la valeur de `INTERNAL_API_SECRET`) ;
  - `web` reçoit `BOARD_T1_UPSTREAM` … `BOARD_T5_UPSTREAM` (défaut `api:80`).
  - `BOARD_DB_PASSWORD` vide : le compte `board` n'est ni créé ni modifié (message de l'init). La stack sans profil n'est donc pas cassée, et board-service ne peut pas se connecter.
- `docker-compose.board-dev.yml` : publie `127.0.0.1:8083:8080`, variables `BOARD_BIND` et `BOARD_PORT`. Le port 8091 n'est jamais publié.
- `docker/mariadb/initdb.d/10-taskora-init.sh` : schéma `board` et compte `board` selon board-migration.md §3 :
  - `REVOKE ALL`, puis les `GRANT` exacts ;
  - droits sur `taskora` seulement quand les 16 tables existent ;
  - `INSERT` sur `attachment` seulement si `BOARD_GRANT_COMMENT_FILES=on` ; un retour à `off` retire ce droit à l'exécution suivante (vérifié) ;
  - script idempotent.
- `frontend/nginx.conf` : une `location ~` par tranche, déclarée avant `location /api/`. Chaque alternative reprend une expression de board-migration.md §5.2. Les blocs reprennent les en-têtes et délais de `/api/` ; la limite de corps du serveur (105 Mo) est inchangée. `frontend/Dockerfile` : valeurs par défaut `api:80` des cinq variables (sans elles, envsubst laisserait `${BOARD_Tn_UPSTREAM}` dans la configuration).
- `.env.example` : variables du §B3.6, avec des valeurs factices distinctes. `INVITATION_CLAIM_URL` et `INVITATION_CLAIM_SECRET` sont laissées vides (valeurs par défaut avant la bascule).
- Hors de l'étape 0 : `TRASH_PURGE_ENABLED` (T5) et les workflows (§6.2, après le commit des tests de l'étape 1).
- Vérifications du 27/11/2026 :
  - `docker compose config -q` sans profil et avec `identity`, `board`, `identity+board+quality` (fichiers `-identity-dev` et `-board-dev` compris) ;
  - `nginx -t` sur le gabarit (image `nginx` de `frontend/Dockerfile`) ;
  - **table de routage** : 55 chemins, faux amonts `api`/`board` sur un réseau interne jetable. Le jeu couvre chaque expression de T1 à T5 et les routes « toujours Symfony » (pièces jointes, `trello-files`, imports, notifications, push, auth, `/api/me`, health) ; un corps de 70 Ko passe sur `/api/boards`. Résultat : 0 erreur, que tout aille vers Symfony, que tout aille vers board ou que seule T1 soit basculée ;
  - stack jetable `-p taskora-board` (ports 127.0.0.1 libres, secrets éphémères) : `database` et `api` sains, puis `db-init` → « OK (schéma board, compte board…) », 54 droits de table et 5 colonnes sur `user`. Stack supprimée ensuite (`down -v --rmi local`).

### Mesure des faits (U11) : commande prête, **non exécutée**
À lancer par Manuel seulement, avec l'accord explicite de l'utilisateur donné au moment même, après la démo client et avant RB0 de T1 :
```
docker exec -i taskora-database-1 sh -c 'MYSQL_PWD="$MARIADB_PASSWORD" exec mariadb --batch -u "$MARIADB_USER" "$MARIADB_DATABASE"' \
  < docs/architecture/board-facts.sql > "$TMPDIR/board-facts.tsv"
```
- Le compte applicatif et son mot de passe sont lus dans l'environnement du conteneur ; ils ne sont jamais affichés.
- La session est en lecture seule (en tête du fichier SQL) et ne renvoie que des agrégats.
- Les nombres sont ensuite recopiés dans board-migration.md §1.4, puis le `.tsv` est supprimé.
