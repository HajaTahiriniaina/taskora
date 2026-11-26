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

