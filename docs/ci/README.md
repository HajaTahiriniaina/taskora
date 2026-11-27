# CI/CD et Docker — Taskora

Responsable : Manuel. Demandes d'entrée : [requests/backend.md](requests/backend.md) (BackAli), [requests/frontend.md](requests/frontend.md) (FrontPierre, traitée le 26/11/2026).

## Orchestration : `ci.yml` et le check unique `ci-gate`

Les filtres `paths` par workflow empêchaient de rendre leurs jobs obligatoires : une PR qui ne touche pas ces chemins (docs seules, par exemple) ne les déclenche jamais et GitHub attend indéfiniment (« Expected — Waiting for status »). D'où un seul workflow d'entrée.

- `ci.yml` (`name: ci`) se déclenche sur **toutes** les PR (sans filtre de chemins), sur push vers `main` et en manuel (`workflow_dispatch`). Concurrence : groupe `ci-<ref>`, annulation du run précédent sur PR seulement.
- Job `changes` : `actions/checkout` (`fetch-depth: 0`, `persist-credentials: false`) puis `git diff --name-only --no-renames` (aucune action de filtrage tierce) :
  - PR : `base.sha...head.sha` (diff « trois points », comme les filtres GitHub) ;
  - push sur `main` : `before..sha` ;
  - `workflow_dispatch`, commit introuvable ou premier push : tout est relancé.
  Il produit un booléen par zone, qui reproduit exactement les anciens filtres (rappelés en tête de chaque workflow appelé) ; toute modification de `ci.yml` relance toutes les zones.

  | Sortie | Chemins | Événements |
  |---|---|---|
  | `backend` | `backend/**`, `docker-compose.yml`, `docker/mariadb/**`, `backend.yml` | PR |
  | `identity_service` | `services/**`, `identity-service.yml` | PR, push `main` |
  | `identity_security` | `services/**/pom.xml`, `identity-security.yml` | PR (+ planifié et manuel, en autonome) |
  | `identity_compat` | `services/identity-service/**`, `backend/**`, `docker-compose.yml`, `docker-compose.identity-dev.yml`, `docker/mariadb/**`, `frontend/nginx.conf`, `frontend/Dockerfile`, `identity-compat.yml` | PR |
  | `frontend` | `frontend/**`, `services/identity-service/**`, `services/pom.xml`, `backend/**`, `docker-compose*.yml`, `docker/mariadb/**`, `frontend.yml` | PR, push `main` |
- Les cinq workflows sont **réutilisables** (`on: workflow_call`) et appelés par un job de même nom avec `if: needs.changes.outputs.<zone> == 'true'`. Ils n'ont plus de déclencheur `pull_request`/`push` (rien ne tourne deux fois). Restent autonomes : `identity-security` (lundi 05:00 UTC et manuel) et `frontend` (manuel, entrée `upstream`).
- Permissions : `permissions: {}` partout au niveau du workflow. Chaque job appelant accorde l'union de ce que demandent les jobs appelés, qui gardent leurs propres permissions minimales. `checks: write` n'est accordé qu'aux appels `identity-service` et `frontend`, et seuls leurs jobs `*-test-report` l'utilisent. `security-events: write` n'est accordé qu'à l'appel `identity-security`.
- Secrets : pas de `secrets: inherit`. Seuls `SONAR_TOKEN` (vers `identity-service`) et `NVD_API_KEY` (vers `identity-security`) sont transmis explicitement ; les autres workflows n'en reçoivent aucun. `vars.SONAR_HOST_URL` reste lu directement.
- Concurrence : seul `ci.yml` en a pour les runs de CI. `frontend` et `identity-security` gardent un groupe à nom fixe (`frontend-<ref>`, `identity-security-<ref>`) pour leurs runs autonomes. Un appelé qui reprendrait `github.workflow` (qui vaut alors `ci`) partagerait le groupe de l'appelant et provoquerait un interblocage.
- Artefacts : tous les appels partagent le même run, donc les noms doivent être uniques. Ils le sont déjà : `backend-phpunit-junit`, `identity-test-reports`, `identity-quality-reports`, `identity-dependency-check`, `identity-image-trivy`, `identity-compat-reports`, `identity-compat-compose-logs`, `frontend-lint-report`, `frontend-unit-reports`, `frontend-e2e-identity-<backend>`, `frontend-compose-logs-<backend>`.
- Job `ci-gate` (`needs` : `changes` et les cinq appels, `if: always()`) :
  - échec si un résultat est `failure` ou `cancelled`, y compris l'échec de `changes` ;
  - succès si tous sont `success` ou `skipped` (zone non concernée).
  Il est rapporté sur **chaque** PR.

### Noms des checks (PR)
Format `<job appelant> / <job appelé>` ; un appel ignoré n'apparaît que sous le nom de l'appelant, avec le statut « ignoré ».
- `changes`, `ci-gate`
- `backend / backend-phpunit`
- `identity-service / identity-build (verify)`, `identity-service / identity-test-report`, `identity-service / identity-quality (Spotless, Checkstyle, Nullness, OpenRewrite)`, `identity-service / identity-sonar`, `identity-service / identity-image (build, Trivy)`
- `identity-security / identity-security`
- `identity-compat / identity-compat`
- `frontend / frontend-quality (lint, prettier, tsc)`, `frontend / frontend-unit (ng test, Vitest)`, `frontend / frontend-build (ng build production)`, `frontend / frontend-image`, `frontend / frontend-e2e-identity (symfony)`, `frontend / frontend-e2e-identity (identity)`, `frontend / frontend-test-report`
- Checks créés par `dorny/test-reporter` (inchangés) : `identity-service tests`, `frontend unit tests`, `frontend e2e identity`.

### Protection de `main` (à faire par l'utilisateur)
1. Pousser la branche `ci/ci-gate` et ouvrir sa PR : `ci-gate` doit y avoir tourné **au moins une fois**, sinon GitHub ne le propose pas dans la liste des checks.
2. Dans *Settings → Rules → Rulesets* (ou *Settings → Branches → Branch protection rules*), créer ou modifier la règle de `main` :
   - activer *Require status checks to pass* ;
   - ajouter **`ci-gate`** comme **seul** check requis, avec la source *GitHub Actions* ;
   - ne pas ajouter les checks `<appelant> / <appelé>` : ils sont ignorés sur certaines PR.
3. Facultatif mais recommandé : *Require branches to be up to date before merging*. La CI tourne alors sur la PR rebasée sur le dernier `main`, au prix de relances plus fréquentes.
4. Supprimer des checks requis les anciens noms (`backend-phpunit`, `identity-build (verify)`, …), s'ils y figuraient : ils ne seront plus jamais rapportés sous ce nom.

### Limites connues
- **PR de fork** : `NVD_API_KEY` n'est pas transmis aux forks. Une PR de fork qui touche un `pom.xml` fait donc échouer `identity-security`, puis `ci-gate` (comportement d'avant, désormais bloquant). Un mainteneur relance alors depuis une branche du dépôt. Sonar reste ignoré et les `*-test-report` sont sautés, comme avant.
- **Push sur `main`** : seuls `identity-service` et `frontend` tournent, comme avant. `backend`, `identity-compat` et `identity-security` ne tournent que sur PR (et `identity-security` chaque lundi).
- **Runs autonomes** : le run planifié d'`identity-security` et les runs manuels de `frontend` ne passent pas par `ci-gate` et ne bloquent rien. Un `workflow_dispatch` de `ci.yml` relance tout, avec les deux amonts e2e.
- **Workflows réutilisables** : au plus 4 niveaux d'imbrication et 20 workflows distincts par run (ici 1 niveau, 5 workflows). Un appelé ne voit que les secrets transmis explicitement. Dans un appelé, `github.workflow` vaut `ci`, d'où les groupes de concurrence à nom fixe.
- **Coût** : chaque PR lance au minimum `changes` et `ci-gate`, deux jobs de quelques secondes.

## Workflows GitHub Actions (`.github/workflows/`)

Tous les déclencheurs PR et push passent par `ci.yml` (voir ci-dessus) ; la colonne indique les chemins de la zone correspondante.

| Workflow | Jobs | Déclencheurs |
|---|---|---|
| `identity-service.yml` | `identity-build` (`verify` : unitaires, ArchUnit, IT Testcontainers, JaCoCo), `identity-test-report` (publication JUnit, seul job avec `checks: write`, pas sur les PR de fork), `identity-quality` (`-Pquality`, puis `rewrite:dryRun` : échec si le patch n'est pas vide), `identity-sonar` (si `SONAR_TOKEN` + `SONAR_HOST_URL`), `identity-image` (`docker build` sans push, puis analyse Trivy de la même image : voir « Analyse Trivy de l'image identity ») | `ci.yml` : PR et push sur `main` touchant `services/**` |
| `identity-security.yml` | `identity-security` (OWASP Dependency-Check, CVSS ≥ 7 bloquant, cache NVD hebdomadaire, SARIF → onglet Security ; **échoue** si `NVD_API_KEY` est absent — dependency-check 13 ne tourne pas sans clé, donc aussi sur les PR de fork ; clé lue par `-DnvdApiKeyEnvironmentVariable=NVD_API_KEY`, jamais en ligne de commande) | `ci.yml` : PR touchant `services/**/pom.xml` ; autonome : lundi 05:00 UTC, manuel |
| `identity-compat.yml` | `identity-compat` (stack compose complète + `-Pcompat` contre 8081 / 8082 / 8080) | `ci.yml` : PR touchant `services/identity-service/**`, `backend/**`, `docker-compose*.yml`, `docker/mariadb/**`, `frontend/nginx.conf`, `frontend/Dockerfile` |
| `backend.yml` | `backend-phpunit` (PHPUnit sur `taskora_test`, JUnit en artefact) | `ci.yml` : PR touchant `backend/**`, `docker-compose.yml`, `docker/mariadb/**` |
| `frontend.yml` | `frontend-quality` (`ng lint` : 0 erreur et avertissements ≤ ligne de base `LINT_MAX_WARNINGS` = 904 ; `prettier --check .` ; `tsc --noEmit` app, spec, e2e ; rapport `eslint.json`), `frontend-unit` (`ng test --watch=false --coverage` : JUnit `reports/unit-junit.xml`, couverture `reports/coverage/` sans seuil), `frontend-build` (`ng build --configuration production`), `frontend-image` (`docker build`, sans push), `frontend-e2e-identity` (matrice `symfony` = `api:80` / `identity` = `identity:8080`, `fail-fast: false`, stack compose `-p taskora-ci --profile identity`, contrôle de l'amont effectif de `web`, Playwright `--grep @identity` Chromium, contrôle de fuite avant publication, `down -v` toujours), `frontend-test-report` (JUnit unitaires + e2e, seul job avec `checks: write`, pas sur les PR de fork). Tous bloquants sauf `frontend-test-report`. | `ci.yml` : PR et push sur `main` touchant `frontend/**`, `services/identity-service/**`, `services/pom.xml`, `backend/**`, `docker-compose*.yml`, `docker/mariadb/**` ou le workflow ; autonome : `workflow_dispatch` (entrée `upstream` : `both`, `symfony`, `identity`) |

Non implémenté : publication d'images (GHCR) et déploiement (accord de l'utilisateur requis).
`frontend` n'a qu'une zone (union des déclencheurs de la demande) : une PR qui ne touche que `backend/**` relance aussi qualité, unitaires, build et image du front (quelques minutes).

Règles appliquées : `permissions: {}` au niveau du workflow puis minimales par job ; actions épinglées par SHA (version en commentaire) ; `persist-credentials: false` ; pas de `pull_request_target` ; secrets éphémères générés à l'exécution (`openssl rand`, masqués) ; paire de clés JWT de test générée par le conteneur `api` dans le volume `jwt_keys` ; artefacts conservés 14 jours ; journaux compose publiés seulement s'ils ne contiennent ni secret ni jeton `eyJ…​.eyJ…`.
OWASP : le rapport viendra du premier run GitHub Actions de `identity-security` (après un push approuvé par l'utilisateur) ; pas de clé NVD locale.
Profil `quality` : ne **jamais** exécuter de tests sur des classes compilées avec `-Pquality` (le Checker Framework y écrit `@Pure` dans le bytecode, ArchUnit échoue) ; les jobs `quality` et `security-scan` passent `-DskipTests -DskipITs` (`-DskipTests` seul ne saute pas Failsafe), et en local toujours `clean` entre un build `-Pquality` et un build de tests.
Validation : `docker run --rm -v "$PWD":/repo -w /repo rhysd/actionlint@sha256:b1934ee5f1c509618f2508e6eb47ee0d3520686341fec936f3b79331f9315667` (1.7.12, shellcheck inclus).
SHA d'actions : vérifiés par `git ls-remote https://github.com/<owner>/<repo> refs/tags/<tag> 'refs/tags/<tag>^{}'` (le SHA épinglé est celui du commit, donc la ligne `^{}` pour une étiquette annotée comme `dorny/test-reporter`).

### Analyse Trivy de l'image identity (C-B1)
Job `identity-image` (check `identity-service / identity-image (build, Trivy)`), donc à chaque run d'`identity-service` (PR et push sur `main` touchant `services/**`) et couvert par `ci-gate`.
- **Outil** : binaire Trivy de l'image officielle `aquasec/trivy:0.76.2@sha256:8f6bb947dbc528902e11f2f5be52f7b519f6a39d5d148f41166f2040499768fb` (index multi-architecture, identique sur `ghcr.io/aquasecurity/trivy:0.76.2`, utilisé en repli ; vérifié par `docker buildx imagetools inspect` sur les deux). Pas d'action `aquasecurity/trivy-action` (incident de compromission d'étiquettes ; l'image figée par digest est préférée). Mise à jour : nouvelle version, `docker buildx imagetools inspect aquasec/trivy:<version>`, puis `TRIVY_IMAGE_DIGEST` et `TRIVY_IMAGES` dans `identity-service.yml`.
- **Cible** : l'image construite à l'étape précédente, passée en archive (`docker save` → `--input`) ; aucun socket Docker monté dans Trivy, aucun push. Trivy tourne avec l'UID du runner.
- **Seuil bloquant** : `--severity HIGH,CRITICAL --ignore-unfixed`, `--scanners vuln --pkg-types os,library` (paquets Ubuntu et JAR Java), `--exit-code 1`. Une vulnérabilité sans correctif publié ne bloque pas.
- **Bases** : `trivy-db` et `trivy-java-db` téléchargées à part (`--download-db-only`, `--download-java-db-only`) avec repli `mirror.gcr.io` puis `ghcr.io` (`TRIVY_DB_REPOSITORY`, `TRIVY_JAVA_DB_REPOSITORY`) et 3 tentatives ; l'analyse tourne ensuite avec `--skip-db-update --skip-java-db-update`. Base indisponible → job en échec (jamais d'analyse silencieuse sans base). Cache `actions/cache/restore` / `actions/cache/save` (même SHA que `actions/cache` v6.1.0), clé `trivy-db-<jour UTC>` avec repli `trivy-db-` ; Trivy ne retélécharge que si la base a dépassé sa date de mise à jour. Sauvegardé dès que les bases sont à jour, même si le seuil échoue.
- **Rapport** : artefact `identity-image-trivy` (`trivy-identity-image.json` et `.txt` en tableau, 14 jours), publié après un contrôle de fuite (jeton `eyJ….eyJ…` ou clé privée PEM → rien n'est publié). Pas d'envoi SARIF : l'appel `identity-service` n'a donc pas `security-events: write` (seul `identity-security` l'a).
- **Traiter un constat** :
  1. paquet OS (Ubuntu) : rafraîchir le digest de `eclipse-temurin:25-jdk` / `25-jre` dans `services/identity-service/Dockerfile` (`docker buildx imagetools inspect`) dès qu'Adoptium a reconstruit l'étiquette ; sinon attendre cette reconstruction ;
  2. bibliothèque Java : monter la version (BOM Spring Boot, propriété de version ou `<dependencyManagement>` dans `services/pom.xml`), puis relancer `identity-security` ;
  3. en dernier recours, `services/identity-service/.trivyignore` (lu automatiquement s'il existe) : une ligne `CVE-AAAA-NNNNN exp:AAAA-MM-JJ` par exception, précédée d'un commentaire `#` donnant la justification (non exploitable, pas de correctif applicable…), avec une expiration courte (≤ 90 jours). Toute exception est revue par BackReviewer.
- **Local** (même commande que la CI) :
  ```bash
  docker build -f services/identity-service/Dockerfile -t throwaway/identity:scan services/
  mkdir -p /tmp/trivy/cache && docker save -o /tmp/trivy/identity.tar throwaway/identity:scan
  docker run --rm --user "$(id -u):$(id -g)" -v /tmp/trivy/cache:/cache -v /tmp/trivy:/work -e TRIVY_CACHE_DIR=/cache \
    aquasec/trivy:0.76.2@sha256:8f6bb947dbc528902e11f2f5be52f7b519f6a39d5d148f41166f2040499768fb image --input /work/identity.tar \
    --scanners vuln --pkg-types os,library --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1
  docker image rm throwaway/identity:scan && rm -rf /tmp/trivy
  ```

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
| `SONAR_ORGANIZATION` | variable de dépôt | Clé d'organisation SonarQube Cloud (`-Dsonar.organization`) ; inutile pour un serveur auto-hébergé |
| `SONAR_PROJECT_KEY` | variable de dépôt | Clé de projet Sonar (`-Dsonar.projectKey`) ; sinon clé Maven par défaut |

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
`mariadb`, `gotenberg`, `sonarqube` (compose) et `node`, `nginx` (`frontend/Dockerfile`) : `tag@sha256:…` (index multi-architecture). Mise à jour : `docker buildx imagetools inspect <image:tag>`. `eclipse-temurin:25-jdk` / `25-jre` (`services/identity-service/Dockerfile`) aussi, contrôlés le 27/11/2026 (m6). Restent sur étiquette : `dunglas/frankenphp` et `composer` (`backend/Dockerfile`), `docker pull mariadb:11.8` des workflows (pré-chargement Testcontainers, qui référence l'étiquette). `identity` : `mem_limit: 768m`, `pids_limit: 256`.

### PHPUnit en local
```bash
docker compose run --rm --no-deps -e APP_ENV=test -v "$PWD/backend":/app --entrypoint sh api \
  -c 'unset INTERNAL_API_SECRET; vendor/bin/phpunit'
```
`unset` est nécessaire : la variable réelle injectée par compose l'emporterait sur la valeur factice de `backend/.env.test`.
