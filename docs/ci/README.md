# CI/CD et Docker — Taskora

Responsable : Manuel. Demandes d'entrée : [requests/backend.md](requests/backend.md) (BackAli), [requests/frontend.md](requests/frontend.md) (FrontPierre, traitée le 26/11/2026).

## Workflows GitHub Actions (`.github/workflows/`)

| Workflow | Jobs | Déclencheurs |
|---|---|---|
| `identity-service.yml` | `identity-build` (`verify` : unitaires, ArchUnit, IT Testcontainers, JaCoCo), `identity-test-report` (publication JUnit, seul job avec `checks: write`, pas sur les PR de fork), `identity-quality` (`-Pquality`, puis `rewrite:dryRun` : échec si le patch n'est pas vide), `identity-sonar` (si `SONAR_TOKEN` + `SONAR_HOST_URL`), `identity-image` (`docker build`, sans push) | PR et push sur `main` touchant `services/**` |
| `identity-security.yml` | `identity-security` (OWASP Dependency-Check, CVSS ≥ 7 bloquant, cache NVD hebdomadaire, SARIF → onglet Security ; **échoue** si `NVD_API_KEY` est absent — dependency-check 13 ne tourne pas sans clé, donc aussi sur les PR de fork ; clé lue par `-DnvdApiKeyEnvironmentVariable=NVD_API_KEY`, jamais en ligne de commande) | PR touchant `services/**/pom.xml`, lundi 05:00 UTC, manuel |
| `identity-compat.yml` | `identity-compat` (stack compose complète + `-Pcompat` contre 8081 / 8082 / 8080) | PR touchant `services/identity-service/**`, `backend/**`, `docker-compose*.yml`, `docker/mariadb/**`, `frontend/nginx.conf`, `frontend/Dockerfile` |
| `backend.yml` | `backend-phpunit` (PHPUnit sur `taskora_test`, JUnit en artefact) | PR touchant `backend/**`, `docker-compose.yml`, `docker/mariadb/**` |
| `frontend.yml` | `frontend-quality` (`ng lint` : 0 erreur et avertissements ≤ ligne de base `LINT_MAX_WARNINGS` = 904 ; `prettier --check .` ; `tsc --noEmit` app, spec, e2e ; rapport `eslint.json`), `frontend-unit` (`ng test --watch=false --coverage` : JUnit `reports/unit-junit.xml`, couverture `reports/coverage/` sans seuil), `frontend-build` (`ng build --configuration production`), `frontend-image` (`docker build`, sans push), `frontend-e2e-identity` (matrice `symfony` = `api:80` / `identity` = `identity:8080`, `fail-fast: false`, stack compose `-p taskora-ci --profile identity`, contrôle de l'amont effectif de `web`, Playwright `--grep @identity` Chromium, contrôle de fuite avant publication, `down -v` toujours), `frontend-test-report` (JUnit unitaires + e2e, seul job avec `checks: write`, pas sur les PR de fork). Tous bloquants sauf `frontend-test-report`. | PR et push sur `main` touchant `frontend/**`, `services/identity-service/**`, `services/pom.xml`, `backend/**`, `docker-compose*.yml`, `docker/mariadb/**` ou le workflow ; `workflow_dispatch` (entrée `upstream` : `both`, `symfony`, `identity`) |

Non implémenté : publication d'images (GHCR) et déploiement (accord de l'utilisateur requis).
`frontend.yml` n'a qu'un filtre de chemins (union des déclencheurs de la demande) : une PR qui ne touche que `backend/**` relance aussi qualité, unitaires, build et image du front (quelques minutes), plutôt que d'ajouter une action de filtrage non épinglée.

Règles appliquées : `permissions: {}` au niveau du workflow puis minimales par job ; actions épinglées par SHA (version en commentaire) ; `persist-credentials: false` ; pas de `pull_request_target` ; secrets éphémères générés à l'exécution (`openssl rand`, masqués) ; paire de clés JWT de test générée par le conteneur `api` dans le volume `jwt_keys` ; artefacts conservés 14 jours ; journaux compose publiés seulement s'ils ne contiennent ni secret ni jeton `eyJ…​.eyJ…`.
OWASP : le rapport viendra du premier run GitHub Actions de `identity-security` (après un push approuvé par l'utilisateur) ; pas de clé NVD locale.
Profil `quality` : ne **jamais** exécuter de tests sur des classes compilées avec `-Pquality` (le Checker Framework y écrit `@Pure` dans le bytecode, ArchUnit échoue) ; les jobs `quality` et `security-scan` passent `-DskipTests -DskipITs` (`-DskipTests` seul ne saute pas Failsafe), et en local toujours `clean` entre un build `-Pquality` et un build de tests.
Validation : `docker run --rm -v "$PWD":/repo -w /repo rhysd/actionlint@sha256:b1934ee5f1c509618f2508e6eb47ee0d3520686341fec936f3b79331f9315667` (1.7.12, shellcheck inclus).
SHA d'actions : vérifiés par `git ls-remote https://github.com/<owner>/<repo> refs/tags/<tag> 'refs/tags/<tag>^{}'` (le SHA épinglé est celui du commit, donc la ligne `^{}` pour une étiquette annotée comme `dorny/test-reporter`).

### Frontend : politique npm des scripts d'installation (m8)
npm 11 (`packageManager: npm@11.19.1`) **bloque par défaut** les scripts `preinstall`/`install`/`postinstall` des dépendances et avertit pour chaque paquet non couvert par `package.json#allowScripts`.
- Décision : **refus explicite** (`false`, nom seul) pour les quatre paquets signalés, écrit par `npm install-scripts deny` dans `frontend/package.json#allowScripts` :
  - `esbuild` (`postinstall: node install.js`, simple vérification/optimisation du binaire) ;
  - `lmdb`, `msgpackr-extract` (`node-gyp-build-optional-packages`) et `@parcel/watcher` (`build-from-source.js`) : compilation native de secours.
  Les binaires précompilés arrivent par les dépendances optionnelles du lockfile (`@esbuild/linux-x64`, `@lmdb/lmdb-linux-x64`, `@msgpackr-extract/msgpackr-extract-linux-x64`, `@parcel/watcher-linux-x64-glibc`, et les variantes `musl`/`arm64`/`darwin`) : aucun script n'est nécessaire ni exécuté. C'est le comportement par défaut de npm 11, désormais explicite et sans avertissement.
- `frontend/.npmrc` : `strict-allow-scripts=true`. Toute **nouvelle** dépendance avec un script non revu fait échouer `npm ci` (`ESTRICTALLOWSCRIPTS`) en CI, dans le `Dockerfile` (qui copie `.npmrc`) et en local : il faut alors la revoir puis `npm install-scripts approve <pkg>` (entrée épinglée `pkg@version`) ou `npm install-scripts deny <pkg>`. `--dangerously-allow-all-scripts` est interdit.
- Le lockfile ne change pas. Vérifié dans `node:24-alpine` (npm 11.19.1) : `npm ci` sans avertissement, `ng build` et `ng test` verts ; suppression d'une entrée → `npm ci` en échec comme attendu.
- Poste local en Node 16 : npm 8 ignore ces réglages ; mettre à niveau vers Node ≥ 24.15.0 (architecture front §5.4).

### Frontend : e2e en local
Même stack que la CI : `IDENTITY_UPSTREAM` dans `.env`, puis `docker compose --profile identity up -d --build` ; changer d'amont par `docker compose up -d web` (sans reconstruction). Depuis `frontend/` (Node ≥ 24.15) : `npx playwright install chromium` une fois, puis `npm run e2e -- --grep @identity` (`E2E_BASE_URL` si `WEB_PORT` ≠ 8080). Une stack dédiée (`-p <nom>` avec d'autres ports) évite d'accumuler des comptes `e2e-…@example.com` dans la base de développement.

### Secrets et variables à créer dans GitHub (par l'utilisateur)
| Nom | Type | Usage |
|---|---|---|
| `NVD_API_KEY` | secret (**créé**) | `identity-security` (obligatoire : sans lui le job échoue) |
| `SONAR_TOKEN` | secret | `identity-sonar` (facultatif : job ignoré s'il manque) |
| `SONAR_HOST_URL` | variable de dépôt | URL du serveur Sonar (facultatif, avec `SONAR_TOKEN`) |

## Docker Compose

| Profil | Services | Commande |
|---|---|---|
| (aucun) | `database`, `api`, `converter`, `web` | `docker compose up -d --build` |
| `identity` | + `db-init`, `identity` | `docker compose --profile identity up -d --build` |
| `quality` | + `sonarqube` (http://127.0.0.1:9000) | `docker compose --profile quality up -d sonarqube` |

- Ports (réglables dans `.env` : `*_PORT` et `*_BIND`, voir README) : `web` 8080 ; `api` **127.0.0.1**:8081 ; `identity` non publié (réseau interne `backend`), ou 127.0.0.1:8082 avec `-f docker-compose.yml -f docker-compose.identity-dev.yml` ; 8091 (management) jamais publié.
- Réseaux : `backend` interne (database, api, identity, web), `converter` interne (api, Gotenberg).

### Base de données (`docker/mariadb/initdb.d/10-taskora-init.sh`)
Script idempotent exécuté en root : schéma `identity`, compte `identity` (droits exacts d'[identity-migration.md §2](../architecture/identity-migration.md), jamais `DELETE` sur `taskora.user`), base `taskora_test` et droits de `taskora` dessus.
- Volume neuf : exécuté par `/docker-entrypoint-initdb.d` (le droit sur `taskora.user` est accordé plus tard par `db-init`, la table n'existant qu'après les migrations Symfony).
- Volume existant : `docker compose exec database /docker-entrypoint-initdb.d/10-taskora-init.sh` (ou `db-init`, lancé automatiquement par le profil `identity`).
- Contrôle sans hash : interroger `information_schema.SCHEMA_PRIVILEGES` / `TABLE_PRIVILEGES` (pas `SHOW GRANTS`).

### Routage d'identité (nginx)
`frontend/nginx.conf` est un gabarit `envsubst` de l'image nginx officielle.
- `IDENTITY_UPSTREAM` : `api:80` (Symfony, défaut) ou `identity:8080` → amont de `location /api/auth/` et `location = /api/me`.
- `IDENTITY_WRITE_FREEZE=on` : 503 JSON sur `POST /api/auth/register` et `PATCH /api/me` (gel de la bascule).
- Changer la valeur dans `.env`, puis `docker compose up -d web` : nouveau conteneur, **sans reconstruction** d'image.
- Corps plafonné à **64 Kio** sur `/api/auth/` et `/api/me` (A18, SC-IDENT-035) : au-delà, 413 `{"error":"Corps de requête trop volumineux."}` en JSON, y compris en `chunked` ; le reste de l'API garde 105 Mo (pièces jointes).

### Images épinglées par digest (m10)
`mariadb`, `gotenberg`, `sonarqube` (compose) et `node`, `nginx` (`frontend/Dockerfile`) : `tag@sha256:…` (index multi-architecture). Mise à jour : `docker buildx imagetools inspect <image:tag>`. Restent sur étiquette : `eclipse-temurin` (`services/identity-service/Dockerfile`, BackCodeur), `dunglas/frankenphp` et `composer` (`backend/Dockerfile`), `docker pull mariadb:11.8` des workflows (pré-chargement Testcontainers, qui référence l'étiquette). `identity` : `mem_limit: 768m`, `pids_limit: 256`.

### PHPUnit en local
```bash
docker compose run --rm --no-deps -e APP_ENV=test -v "$PWD/backend":/app --entrypoint sh api \
  -c 'unset INTERNAL_API_SECRET; vendor/bin/phpunit'
```
`unset` est nécessaire : la variable réelle injectée par compose l'emporterait sur la valeur factice de `backend/.env.test`.
