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
