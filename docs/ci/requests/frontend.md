# Demande CI/CD frontend — socle de tests et e2e d'identité (phase 2)

Demandeur : FrontPierre. Destinataire : Manuel. Statut : **ouverte** (26/11/2026).
Références :
- plan de Paul, `docs/reviews/2026-11-26-identity-service-validation-paul.md` §6 ;
- `docs/architecture/frontend.md` §5 (choix d'outillage) ;
- ordre de travail `docs/specs/phase-2-frontend-identity-workorder.md` ;
- scénarios `docs/specs/identity.md` (SC-IDENT-001, 002, 003, 007, 008, 017, 018, 025, 026) ;
- modèle de format : [backend.md](backend.md).

Cette demande remplace le workflow provisoire `frontend.yml` (« jobs évidents en attendant FrontPierre »). Les jobs marqués « après C0 » ou « après T1 » ne deviennent bloquants qu'une fois les commits correspondants de l'ordre de travail fusionnés : C0 = outillage qualité et formatage par FrontCodeur, T1 = socle de tests par FrontTesteur.

## 1. Conventions communes à tous les jobs

- Runner `ubuntu-latest`. `permissions: {}` au niveau du workflow, puis `contents: read` par job. `checks: write` uniquement pour le job de publication des rapports, jamais sur une PR de fork. Pas de `pull_request_target`.
- Actions **épinglées par SHA**, version en commentaire. Ces SHA sont déjà utilisés dans le dépôt :
  - `actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1`, avec `persist-credentials: false` ;
  - `actions/setup-node@949feb2413d6458794dcd2491c4babbbce0c15c1 # v7.1.0` ;
  - `actions/upload-artifact@cf430e030ddbb5b0abf93d22962f4752f3646cd9 # v7.0.2`.

  Les autres (`actions/cache` pour les navigateurs Playwright, rapporteur JUnit) sont à épingler par Manuel. Je n'ai pas vérifié leurs SHA.
- Node : `node-version: "24"`, `cache: npm`, `cache-dependency-path: frontend/package-lock.json`. Il faut **Node ≥ 24.15.0**, car `@angular/core` 22.3.0 exige `^22.22.3 || ^24.15.0 || >=26` et jsdom 31.0.1 exige `^24.15.0` (vérifié dans le lockfile et le registre npm le 26/11/2026). L'image `node:24-alpine` du `Dockerfile` est déjà épinglée par digest.
- Répertoire de travail `frontend/`. Installation : `npm ci --no-audit --no-fund` (npm 11, `packageManager: npm@11.19.1`).
- **Aucun secret de dépôt** n'est nécessaire. Les secrets de la stack e2e sont éphémères, générés à l'exécution et masqués, comme dans `identity-compat` (§3).
- `concurrency` : groupe `${{ github.workflow }}-${{ github.ref }}`, annulation en cours sur les PR.
- Rapports et artefacts : tout sort dans `frontend/reports/` (ignoré par Git), avec une rétention de 14 jours.

## 2. Jobs demandés

| Job | Déclencheur | Commande(s) (depuis `frontend/`) | Bloquant |
|---|---|---|---|
| `frontend-quality` | PR et push sur `main` touchant `frontend/**` ou le workflow | `npm ci` ; `npm run lint` (= `ng lint`, angular-eslint + perfectionist) ; `npx prettier --check .` ; `npx tsc -p tsconfig.app.json --noEmit` ; `npx tsc -p tsconfig.spec.json --noEmit` ; `npx tsc -p e2e/tsconfig.json --noEmit` | oui, après C0 pour lint et prettier. Les deux dernières commandes `tsc` s'ajoutent après T1. Ne bloquent que les **erreurs** ESLint ; les avertissements de la ligne de base sont comptés par FrontReviewer (architecture §5.4). |
| `frontend-unit` | idem | `npm ci` ; `npx ng test --watch=false --coverage` (Vitest via `@angular/build:unit-test`, jsdom) | oui, dès T1. Retirer la condition `hashFiles('frontend/src/**/*.spec.ts')` de l'étape actuelle. |
| `frontend-build` | idem | `npm ci` ; `npx ng build --configuration production` | oui (existant) |
| `frontend-image` | idem | `docker build -f frontend/Dockerfile frontend/`, depuis la racine du dépôt. Construction seulement : **aucun push** de registre sans accord de l'utilisateur. | oui |
| `frontend-e2e-identity` | PR et push sur `main` touchant `frontend/**`, `services/identity-service/**`, `backend/**`, `docker-compose.yml`, `docker/mariadb/**` ou le workflow ; `workflow_dispatch` (entrée `upstream` : `both` par défaut, `symfony` ou `identity`) | voir §3, matrice sur les deux amonts d'identité | oui, **les deux branches** de la matrice (`fail-fast: false`) |
| `frontend-test-report` | après `frontend-unit` et `frontend-e2e-identity` (`if: always()`), sauf sur une PR de fork | publication des JUnit `reports/unit-junit.xml` et `reports/e2e-junit.xml` par un rapporteur épinglé, comme `identity-test-report` | non (publication) |

Remarques :
- `frontend-quality`, `frontend-unit` et `frontend-build` peuvent partager un job pour éviter trois `npm ci`. Je garde la séparation pour la lisibilité ; Manuel choisit.
- Le JUnit et la couverture de `ng test` viennent de la configuration Vitest posée par FrontTesteur (T1) :
  - JUnit : `reports/unit-junit.xml` ;
  - couverture : `reports/coverage/`, au format `lcov` et `cobertura`.

  Les noms exacts des options du builder `unit-test` sont à vérifier sur le schéma installé (architecture §5.1). Si le builder ne les expose pas, FrontTesteur les passe par un fichier `vitest` `runnerConfig`, et la commande CI ne change pas.
- Pas de seuil de couverture en phase 2 : seuls les fichiers d'identité sont couverts, donc la couverture est publiée sans être bloquante. Le seuil sera fixé en phase 3.

## 3. `frontend-e2e-identity` : stack compose et matrice

```yaml
strategy:
  fail-fast: false
  matrix:
    include:
      - backend: symfony
        upstream: "api:80"
      - backend: identity
        upstream: "identity:8080"
timeout-minutes: 45
permissions:
  contents: read
env:
  COMPOSE: docker compose -f docker-compose.yml --profile identity
  IDENTITY_UPSTREAM: ${{ matrix.upstream }}
  IDENTITY_WRITE_FREEZE: "off"
  WEB_BIND: "127.0.0.1"
  WEB_PORT: "8080"
```

Étapes :
1. `checkout`, puis `setup-node`. Node 24 suffit pour Playwright 1.65.1, qui exige Node ≥ 20.
2. **Secrets éphémères**, à l'identique d'`identity-compat` : boucle sur `JWT_PASSPHRASE INTERNAL_API_SECRET IDENTITY_DB_PASSWORD DB_PASSWORD DB_ROOT_PASSWORD APP_SECRET`, chaque valeur obtenue par `openssl rand -hex 32`, masquée par `::add-mask::`, puis écrite dans `$GITHUB_ENV`. Aucun `.env` n'est créé ni lu.
3. `$COMPOSE up -d --build`. La même stack sert aux deux branches : `database`, `api`, `db-init`, `identity` et `web`. Seul `IDENTITY_UPSTREAM` change ; c'est la configuration de coexistence réelle.
4. **Attente** (10 min au plus, mêmes motifs d'échec que `identity-compat`) :
   - `identity` est `healthy` ;
   - `curl -fsS http://127.0.0.1:8080/` répond ;
   - `curl -fsS http://127.0.0.1:8080/api/health` répond.
5. **Contrôle de l'amont** : vérifier que le conteneur `web` tourne avec l'amont attendu, sinon la branche `identity` testerait Symfony sans le dire :
   ```bash
   docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$($COMPOSE ps -q web)" | grep -qx "IDENTITY_UPSTREAM=${IDENTITY_UPSTREAM}"
   ```
   L'environnement de `web` ne contient aucun secret ; seule la ligne `IDENTITY_UPSTREAM` est testée, et rien n'est affiché.
6. `npm ci --no-audit --no-fund`.
7. Navigateurs Playwright :
   - cache `~/.cache/ms-playwright`, clé = version de `@playwright/test` lue dans `package-lock.json` ;
   - puis `npx playwright install --with-deps chromium`.
8. Tests :
   ```bash
   E2E_BASE_URL=http://127.0.0.1:8080 E2E_BACKEND=${{ matrix.backend }} CI=true \
     npx playwright test --grep @identity --project=chromium
   ```
9. **Contrôle de fuite** avant toute publication, sur le même modèle qu'`identity-compat` :
   - périmètre : `frontend/reports/` et, en cas d'échec, `docker compose logs --no-color` ;
   - échec si l'on trouve l'une des six valeurs éphémères, ou le motif `eyJ[A-Za-z0-9_-]+\.eyJ[A-Za-z0-9_-]+` ;
   - dans ce cas, les fichiers sont supprimés et non publiés.

   En CI, les traces Playwright sont désactivées (`trace: 'off'`) parce qu'elles contiennent les en-têtes `Authorization` et `localStorage`. Les rapports ne contiennent donc que des titres, des étapes et des captures d'écran.
10. Artefacts (rétention 14 jours) :
    - `frontend-e2e-identity-${{ matrix.backend }}` : `frontend/reports/e2e-junit.xml`, `frontend/reports/e2e-html/` et `frontend/reports/e2e-artifacts/` (captures d'écran des échecs) ;
    - en cas d'échec : `frontend-e2e-identity-${{ matrix.backend }}-compose-logs`.
11. `if: always()` : `$COMPOSE down -v --remove-orphans`. Les comptes créés par les tests disparaissent avec le volume.

Données de test : chaque test crée ses comptes par `POST /api/auth/register` à travers nginx, donc vers l'amont choisi. Les e-mails sont uniques, de la forme `e2e-<scénario>-<horodatage>-<aléa>@example.com`, et les mots de passe sont aléatoires pour chaque exécution. Le job n'utilise **aucun** compte de démonstration ni aucune donnée préchargée.

Exécution en local (pour la doc de Manuel, sans le lancer ici) : même stack avec `IDENTITY_UPSTREAM` dans `.env`, puis `docker compose up -d web` pour changer d'amont sans reconstruction. Ensuite, depuis `frontend/` : `npm run e2e` (= `playwright test`). Si `WEB_PORT` n'est pas 8080, exporter `E2E_BASE_URL`.

## 4. Formatage : les 59 fichiers non conformes à Prettier

- Ils sont corrigés par FrontCodeur dans un commit **dédié, sans aucun changement de logique** (ordre de travail, étape C0b) : `style(front): prettier --write (formatage seul)`.
  - Les fichiers sont listés par `npx prettier --list-different .`, puis formatés par liste explicite. Le hook `protect-tests.sh` refuse `prettier --write .`.
  - Ce commit est ensuite référencé dans `.git-blame-ignore-revs` à la racine.
- Les changements mécaniques d'ESLint (`--fix` de perfectionist sur les imports et les unions) sont dans un **autre** commit dédié, C0c.
- À la fusion de C0, retirer `continue-on-error: true` de l'étape Prettier : `prettier --check` devient bloquant.
- FrontReviewer vérifie que C0b et C0c ne contiennent que du formatage : ni fichier de test, ni `package*.json`, et `ng build` reste vert avant et après.

## 5. Rapports et artefacts (récapitulatif, rétention 14 jours)

| Artefact | Chemin (depuis `frontend/`) |
|---|---|
| JUnit unitaires et composants | `reports/unit-junit.xml` |
| Couverture | `reports/coverage/` (`lcov.info`, `cobertura-coverage.xml`) |
| JUnit e2e (par branche de matrice) | `reports/e2e-junit.xml` |
| Rapport HTML Playwright | `reports/e2e-html/` (`open: 'never'`) |
| Captures d'écran des échecs | `reports/e2e-artifacts/` |
| Journaux compose en cas d'échec | après contrôle de fuite (§3, étape 9) |

## 6. Seuils (récapitulatif)

- Tests unitaires, composants et e2e : 0 échec. Aucun `skip`, `fixme`, `only` ni `retries` > 0 en CI (`forbidOnly: true` dans la configuration Playwright).
- ESLint : 0 erreur. Les avertissements de la ligne de base de C0 ne doivent pas augmenter (compte relevé par FrontReviewer).
- Prettier : 0 fichier non conforme (après C0).
- Build de production : budgets d'`angular.json` inchangés.

## 7. Versions demandées (épinglées exactement, `--save-exact`)

| Outil | Version | Vérification |
|---|---|---|
| Angular (`@angular/*`, `@angular/build`, `@angular/cli`) | 22.3.0 (inchangé) | lockfile |
| TypeScript | 6.0.3 | lockfile |
| Prettier | 3.10.1 | lockfile |
| Vitest, `@vitest/coverage-v8` | 5.1.1 | registre npm (pair `vitest ^4.0.8 \|\| ^5.0.0` d'`@angular/build` 22.3.0 : lockfile) |
| jsdom | 31.0.1 | registre npm |
| `@playwright/test` | 1.65.1 (Chromium) | registre npm |
| ESLint | 10.15.0 | registre npm |
| angular-eslint | 22.8.0 | registre npm (pair : `eslint ^9 \|\| ^10`, `typescript-eslint ^8`) |
| typescript-eslint | 8.73.0 | registre npm (pair `typescript >=4.8.4 <6.1.0`, compatible avec 6.0.3) |
| eslint-plugin-perfectionist | 5.14.0 | registre npm |

Règle appliquée : je prends la dernière version stable publiée depuis au moins 7 jours. C'est pourquoi j'ai écarté Vitest 5.1.2 (25/11) et typescript-eslint 8.74.0 (23/11). Versions lues sur le registre npm le 26/11/2026 ; le contenu des paquets n'a pas été audité.
Non vérifié :
- les SHA d'`actions/cache` et du rapporteur JUnit ;
- les noms d'options du builder `@angular/build:unit-test` ;
- le fichier exact que génère `ng add angular-eslint`.

## 8. Réponse de Manuel
_À compléter._
