# Revue front — phase 2 : socle de tests et identité

Auteur : FrontReviewer, le 26/11/2026. Destinataire : FrontPierre (recette), puis Paul.
Ordre de travail : `docs/specs/phase-2-frontend-identity-workorder.md`, étape 4.
Branche `feat/phase2-frontend-identity-tests`, HEAD `bb103eb`, base `main` `03d88bb`.

| Commit | Auteur (rôle) | Objet |
|---|---|---|
| `64f1295` | FrontPierre | docs : demande CI, architecture, ordre de travail |
| `a4e0ce8` | fil principal | hook `protect-tests.sh` étendu à la configuration de test front |
| `c3f40b8` (C0a) | FrontCodeur | ESLint (angular-eslint, typescript-eslint, perfectionist) |
| `489578a` (C0b) | FrontCodeur | `prettier --write`, formatage seul |
| `a74eb34` (C0c) | FrontCodeur | `eslint --fix` (imports, unions) et ligne de base |
| `8d22acd` (C0d) | FrontCodeur | `.git-blame-ignore-revs` |
| `402b537` (T1) | FrontTesteur | socle de tests (Vitest, Playwright) — **référence d'intégrité de la configuration** |
| `1bd9766` (T2) | FrontTesteur | tests SC-IDENT — **référence d'intégrité des tests** |
| `91350c8` (C1) | FrontCodeur | proxy de dev vers identity-service |
| `bb103eb` (C2) | FrontCodeur | correctif SC-IDENT-017 (champs de mot de passe en signals) |

## 1. Verdict

**ACCEPTÉ.** Aucun point bloquant. Neuf points non bloquants (m1 à m9), chacun avec son destinataire (§4).

Tous les contrôles outillés sont verts. L'intégrité des tests est respectée. Les commits de formatage ne changent ni la logique ni le CSS compilé. Le proxy et le correctif SC-IDENT-017 sont corrects.

## 2. Résultats des outils

Exécution dans `node:24-alpine` (Node 24.22.3, npm 11.19.1) : le poste a Node 16. Les `node_modules` étaient déjà installés, donc aucun `npm ci` n'a été nécessaire.

| Contrôle | Commande (dans `frontend/`) | Résultat |
|---|---|---|
| Lint | `npx ng lint` (= `npm run lint`) | **0 erreur**, 904 avertissements (= ligne de base documentée, détail ci-dessous) |
| Lint, fichiers ajoutés ou modifiés après C0 | `npx eslint --max-warnings=0 <19 fichiers de T1, T2, C1 et C2>` | 0 erreur. 18 fichiers sans aucun avertissement. `profile.component.ts` a 12 avertissements, tous antérieurs : le même fichier à `1bd9766`, avant C2, en a aussi 12 (voir m2) |
| Formatage | `npx prettier --check .` | « All matched files use Prettier code style! » (0 fichier) |
| Types (application) | `npx tsc -p tsconfig.app.json --noEmit` | 0 erreur |
| Types (unitaires) | `npx tsc -p tsconfig.spec.json --noEmit` | 0 erreur |
| Types (e2e) | `npx tsc -p e2e/tsconfig.json --noEmit` | 0 erreur |
| Unitaires et composants | `npx ng test --watch=false --coverage` | **6 fichiers, 16/16 verts** (Vitest 5.1.1, jsdom). JUnit dans `reports/unit-junit.xml`. Couverture globale de `src/app` : 14,59 % des instructions, publiée sans seuil (phase 2) |
| E2E `@identity` | non exécutés par le Reviewer (consigne : aucune stack démarrée) | **Preuve rapportée** par FrontCodeur : 14/14 sur les deux amonts (Symfony `api:80` et identity `identity:8080`), sur une stack jetable. À confirmer en CI par `frontend-e2e-identity` |
| Build | `npx ng build --configuration production` | vert, budgets respectés. Un avertissement : « Module 'sweetalert2' … is not ESM ». Il est antérieur à la branche : `dialog.service.ts` n'a reçu qu'un réordonnancement d'import |
| Dépendances | `npm audit --omit=dev` et `npm audit` | 0 vulnérabilité |

### 2.1 Avertissements ESLint par règle (HEAD)

| Règle | Nombre | Origine |
|---|---:|---|
| `perfectionist/sort-objects` | 353 | `warn` en phase 2 (§5.2) |
| `perfectionist/sort-classes` | 330 | `warn` en phase 2 (§5.2) |
| `perfectionist/sort-interfaces` | 92 | `warn` en phase 2 (§5.2) |
| `perfectionist/sort-object-types` | 43 | `warn` en phase 2 (§5.2) |
| `@angular-eslint/template/click-events-have-key-events` | 35 | ligne de base phase 2 |
| `@angular-eslint/template/interactive-supports-focus` | 28 | ligne de base phase 2 |
| `@angular-eslint/template/no-autofocus` | 10 | ligne de base phase 2 |
| `@angular-eslint/template/label-has-associated-control` | 5 | ligne de base phase 2 |
| `@typescript-eslint/no-explicit-any` | 4 | ligne de base phase 2 |
| `@typescript-eslint/no-unused-vars` | 2 | ligne de base phase 2 |
| `@angular-eslint/component-selector` | 1 | ligne de base phase 2 (`app-root`) |
| `no-irregular-whitespace` | 1 | ligne de base phase 2 |
| **Total** | **904** | 818 perfectionist + 86 en ligne de base, identique au message de `a74eb34` |

`npx eslint .` donne le même total que `ng lint` (904, 0 erreur). La cible `lint` couvre `src/**/*.ts`, `src/**/*.html`, `e2e/**/*.ts`, `playwright.config.ts` et `vitest.config.ts`.

### 2.2 Conformité de `eslint.config.js` à `frontend.md` §5.2

- `typescript-eslint` recommended, angular-eslint `tsRecommended`, `templateRecommended` et `templateAccessibility` : présents. `@eslint/js` recommended s'y ajoute (généré par la schématique).
- Préfixe `tk` :
  - `directive-selector` : attribut, camelCase, en erreur ;
  - `component-selector` : élément, kebab-case, en `warn` pour la seule occurrence `app-root`, commentée « ligne de base phase 2 ».
- `angular.json` : `prefix` est passé de `app` à `tk`.
- perfectionist, options `{ order: 'asc', type: 'natural' }` :
  - en erreur : `sort-imports`, `sort-named-imports`, `sort-named-exports`, `sort-union-types`, `sort-intersection-types` ;
  - en avertissement : `sort-classes`, `sort-objects`, `sort-interfaces`, `sort-object-types`.
- Dossiers ignorés : `dist/`, `.angular/`, `node_modules/`, `reports/`, `public/`. Conforme.
- Ligne de base : chaque règle passée en `warn` porte le commentaire `// ligne de base phase 2`. **Aucun `eslint-disable`** dans `src/`, `e2e/` ni la configuration.
- Versions exactes :
  - `eslint` 10.15.0, `typescript-eslint` 8.73.0, `eslint-plugin-perfectionist` 5.14.0, `angular-eslint` 22.8.0 ;
  - `vitest` et `@vitest/coverage-v8` 5.1.1, `jsdom` 31.0.1, `@playwright/test` 1.65.1.

## 3. Contrôles manuels

### 3.1 Intégrité des tests

| Vérification | Résultat |
|---|---|
| `git diff 1bd9766..HEAD` | Deux fichiers seulement : `frontend/proxy.conf.mjs` (C1) et `frontend/src/app/pages/profile/profile.component.ts` (C2). Aucun `*.spec.ts`, rien sous `e2e/**`, aucune configuration de test. |
| `git diff 402b537..HEAD` sur la configuration de test (`playwright.config.ts`, `tsconfig.spec.json`, `vitest.config.ts`, `angular.json`, `package.json`, `package-lock.json`) | **Vide.** La cible `test` d'`angular.json` et les scripts `test`, `test:ci` et `e2e` sont inchangés depuis T1. Entre T1 et HEAD, seul T2 (FrontTesteur) ajoute des fichiers de test, plus 6 lignes dans `e2e/support/session.ts`. |
| Affaiblissement | Aucun `.only`, `.skip`, `.fixme`, `xit`, `xdescribe`, `expect.soft`, `waitForTimeout` ni timeout gonflé, et aucun `eslint-disable`. |
| Configuration Playwright | `retries: 0`, `forbidOnly: !!CI`, `workers: CI ? 2 : undefined`, `fullyParallel: true`. Trace : `E2E_TRACE === 'on' ? 'retain-on-failure' : 'off'`, donc `off` en CI. Captures `only-on-failure`, vidéo `off`, pas de `webServer`. |
| Couverture | `coverageInclude: src/app/**/*.ts`, `coverageExclude: src/**/*.spec.ts` seulement. Aucune autre exclusion. |
| Hook `a4e0ce8` | Ajoute `playwright.config.*`, `vitest.config.*`, `vitest.workspace.*` et `tsconfig.spec.json` au motif protégé. La règle correspondante est aussi ajoutée dans `workflow.md`. |

### 3.2 Couverture des scénarios

- **E2E** : E01 à E11 existent, avec l'identifiant en tête du titre et `{ tag: '@identity' }`. E09 est paramétré sur 3 URL et E10 sur 2 cas, soit 14 tests au total, ce qui correspond au 14/14 rapporté.
- **Unitaires et composants** : C01 à C16 existent, avec l'identifiant en tête du titre. 16 tests au total, qui correspondent aux 16 tests exécutés.

### 3.3 Formatage pur (C0b `489578a`, C0c `a74eb34`)

**Aucun fichier de test ni `package*.json` dans les deux commits.** Il n'existait pas encore de test au moment de C0.

#### C0b — classement de chaque fichier par contenu

| Classement | Nombre | Détail |
|---|---:|---|
| Contenu identique hors blancs | 8 | — |
| Identique hors blancs et ponctuation `, ; ( ) " '` | 29 | — |
| Autres | 21 | `.prettierignore` (nouveau), 12 SCSS, 5 composants à template en ligne, 2 HTML, `index.html` |

Pour les 21 autres fichiers, le diff mot à mot montre seulement :
- des normalisations Prettier : `.75` → `0.75`, `rgba(0,0,0,.4)` → `rgba(0, 0, 0, 0.4)`, `/>` auto-fermants, `>` déplacé en début de ligne, retours à la ligne.

#### C0b et C0c — build de production avant et après

J'ai comparé les builds de production de `c3f40b8`, `489578a` et `a74eb34`, faits avec `--output-hashing none`.
- **CSS compilé** (`styles.css`) : identique hors blancs. La seule différence textuelle est l'espacement dans la valeur de propriétés personnalisées (`--shadow`).
- **`index.html`** : identique hors indentation.
- **`main.js` (C0b)** : identique après normalisation des noms minifiés.
- **Chunks paresseux (C0b)** : les seules différences sémantiques viennent du compilateur de templates. Environ 17 interpolations, que Prettier a remises sur leur propre ligne, reçoivent un espace de début ou de fin dans leur nœud texte, par exemple `" ▾ "` au lieu de `"▾"`. C'est invisible avec `white-space: normal` ; voir m1.
- **C0c** :
  - les 29 fichiers source ont **exactement le même multiensemble de jetons** avant et après : réordonnancement pur, imports et membres d'unions de types ;
  - aucun import à effet de bord (`import '…'`) n'est déplacé ;
  - le bundle ne diffère que par l'ordre des modules dans le chunk et par les noms minifiés ;
  - le seul autre fichier modifié est `eslint.config.js` (ligne de base).
- **Build vert** aux trois commits.

`.git-blame-ignore-revs` contient les **hashes complets** `489578a6d3d9b5c984cbca614da0aa29de09f8b3` (C0b) et `a74eb3467a8484920809c92acf2a01484dbe9f59` (C0c). Ils correspondent à `git rev-parse`.

### 3.4 C1 — proxy de dev (`frontend/proxy.conf.mjs`)

**Correct.** J'ai simulé la règle de correspondance de Vite (préfixe, ou RegExp si la clé commence par `^`, première entrée gagnante) sur l'objet exporté, dans un conteneur sans `.env`.

| Requête | Sans `IDENTITY_UPSTREAM` ou avec `api:80` | Avec `identity:8080` |
|---|---|---|
| `/api/auth/login`, `/api/auth/register` | Symfony `127.0.0.1:8081` | identity `127.0.0.1:8082` |
| `/api/me`, `/api/me?x=1` | Symfony | identity |
| `/api/me/`, `/api/members`, `/api/medias`, `/api/auth`, `/api/authx` | Symfony | Symfony, comme nginx (`location /api/auth/` et `location = /api/me`) |
| `/api/boards`, `/uploads/…` | Symfony | Symfony |

- Le comportement par défaut est inchangé : l'objet exporté est le même qu'avant (`/api`, `/uploads`).
- `IDENTITY_BIND=0.0.0.0` devient `127.0.0.1` et `IDENTITY_PORT` est respecté (8082 par défaut, comme `docker-compose.identity-dev.yml`).
- J'ai vérifié dans `@angular/build` (`load-proxy-config.js`) :
  - une clé commençant par `^` n'est pas convertie en glob ;
  - l'ordre des entrées est conservé.
- Seules les clés `API_BIND`, `API_PORT`, `IDENTITY_UPSTREAM`, `IDENTITY_BIND` et `IDENTITY_PORT` sont lues, et **aucune valeur n'est affichée** : pas de `console.*`.

### 3.5 C2 — SC-IDENT-017 (`profile.component.ts`)

**Correct et minimal.**
- `currentPassword` et `newPassword` deviennent `signal('')`. La liaison `[(ngModel)]="currentPassword"` sur un `WritableSignal` est la liaison bidirectionnelle sur signal prise en charge par Angular ; elle fonctionne en zoneless.
- `savePassword()` lit les signals pour le corps du `PATCH` et les vide par `.set('')` dans le callback. Le DOM et le bouton se rafraîchissent : C16 vérifie les valeurs affichées et le bouton désactivé après `whenStable()`.
- La condition du bouton `[disabled]="!currentPassword() || newPassword().length < 8"` est sémantiquement identique à l'ancienne.
- Le reste du composant n'est pas modifié : `name` et `color` restent liés par `ngModel` à des champs simples, et seuls des événements DOM les modifient.
- Aucune nouvelle alerte ESLint (12 avant, 12 après).

### 3.6 Sécurité, données et sélecteurs

- **Littéraux `eyJ`** : aucun dans `e2e/**`, `src/**` ou `docs/reviews/`. Les jetons forgés sont construits dans le navigateur (`e2e/support/jwt.ts`, `page.evaluate`) :
  - jeton expiré : signature factice `'signature'` ;
  - jeton illisible : `'pas-un-jeton'` ;
  - jeton altéré : un caractère de la signature remplacé.
- **Comptes e2e** : tous créés par `POST /api/auth/register` (`createAccount`) :
  - e-mail `e2e-<scénario>-<horodatage>-<aléa>@example.com` ;
  - mot de passe aléatoire de 20 caractères, tiré par `crypto.getRandomValues` ;
  - aucun compte de démonstration ni donnée partagée.
- **Fuites dans les rapports** : aucun jeton ni mot de passe n'est affiché. Les assertions ne portent que sur la présence du jeton (`hasStoredToken`) et la longueur des champs (E06).
- **Lecture de `.env`** : aucune dans les tests ni dans `playwright.config.ts` (variables `E2E_*` et `CI` seulement). Le proxy ne lit que les cinq clés listées.
- **Sélecteurs** : `getByRole`, `getByLabel` (`exact: true` à côté du bouton « Afficher ») et `getByText` avec le texte exact.
  - Seule exception CSS : `.swal2-container`, admise par §4.3.
  - Aucun `data-testid`, aucun XPath, aucun index positionnel.
  - Les toasts sont ciblés par `getByRole('alert')`.
- **`.dockerignore`** : contient `e2e` et `reports`, en plus de `node_modules`, `dist`, `.angular` et `.git`.
- **`.gitignore`** : contient `/reports`. Les sorties de mes exécutions (`reports/`, `dist/`) sont ignorées et l'arbre de travail reste propre.

## 4. Points

### Bloquants
Aucun.

### Non bloquants

| # | Point | Destinataire |
|---|---|---|
| m1 | **C0b n'est pas strictement neutre pour le DOM.** Environ 17 interpolations remises sur leur propre ligne par Prettier ont maintenant un espace de début ou de fin dans leur nœud texte. C'est invisible avec `white-space: normal`. Un cas peut être visible : `src/app/shared/json-node.component.ts`, où `.row` est en `white-space: pre-wrap`, avec le bouton `.tog` (`{{ open() ? '▾' : '▸' }}`) et le bouton `.sum` (compteur). **Attendu** : contrôle visuel de la visionneuse JSON. S'il y a un écart, recoller l'interpolation aux balises dans ce seul template (Prettier l'accepte avec `<!-- prettier-ignore -->` ou une écriture compacte). | FrontCodeur (décision FrontPierre) |
| m2 | La règle « fichiers nouveaux ou modifiés : `--max-warnings=0` » (§5.2) n'est pas tenue par `profile.component.ts` : 12 avertissements `sort-classes`, `sort-objects` et `label-has-associated-control`, **tous antérieurs à C2**. Les corriger exigerait le réordonnancement que §5.2 interdit en phase 2. **Attendu** : préciser la règle, par exemple « aucun avertissement sur les lignes ajoutées », ou exempter les règles de la ligne de base. | FrontPierre |
| m3 | `docs/specs/traceability.md` lignes 62–63 : SC-IDENT-017 (e2e et composant) est toujours marqué `rouge`, alors que le test est vert depuis `bb103eb` (unitaires 16/16 ici, e2e 14/14 rapportés). **Attendu** : passer ces lignes à `vert` avec la preuve et la date. | FrontTesteur |
| m4 | L'ordre de travail (§8) dit « aucun mot de passe fixe dans `*.spec.ts` ». Or les specs unitaires contiennent des valeurs factices fixes : `'motdepasse'`, `'motdepas'`, `'ancienmdp1'`, `'nouveaumdp1'`… Elles ne visent que `HttpTestingController` et ne quittent jamais le processus ; les longueurs fixes sont nécessaires pour les cas 7/8 caractères. Je ne les considère pas comme une fuite. **Attendu** : confirmer que la règle vise l'e2e (amont réel), ou demander des valeurs générées. | FrontPierre, puis FrontTesteur si nécessaire |
| m5 | `register.component.spec.ts` et `profile.component.spec.ts` n'appellent pas `HttpTestingController.verify()` en `afterEach`, contrairement aux trois autres specs HTTP. Une requête inattendue passerait inaperçue. | FrontTesteur (après accord de FrontPierre) |
| m6 | Accessibilité : les messages d'erreur de `/login` et `/register` (`<div class="error">`, `login.component.ts:38`, `register.component.ts:37`) n'ont ni `role="alert"` ni `aria-live`. Voir `identity.md` §6, HYPOTHÈSE. | FrontPierre (décision), puis FrontCodeur |
| m7 | **CI** (`docs/ci/requests/frontend.md`, statut « ouverte ») : `.github/workflows/frontend.yml` a encore Prettier en `continue-on-error`, la condition `hashFiles('frontend/src/**/*.spec.ts')`, et ni lint, ni `tsc` spec/e2e, ni `frontend-e2e-identity`. Les e2e ne sont donc validés en CI sur aucun des deux amonts. C'est le prérequis de la recette. | Manuel |
| m8 | **npm `allowScripts`** : l'installation (npm 11) signale des scripts d'installation non autorisés pour `esbuild`, `lmdb`, `@parcel/watcher` et `msgpackr-extract`. C'est un constat rapporté pendant C0 et T1 ; je ne l'ai pas reproduit, faute de `npm ci` dans cette revue. Avec les `node_modules` existants, le build et les tests passent dans `node:24-alpine`. **Attendu** : décider de la politique, liste d'autorisation explicite ou avertissement accepté, et l'appliquer de la même façon en CI et dans le `Dockerfile`. | Manuel |
| m9 | Proxy de dev :<br>- seul l'hôte `identity` active le routage d'identité ; une autre valeur, par exemple une faute de frappe, retombe silencieusement sur Symfony ;<br>- le gel des écritures et la limite de 64 Kio de nginx ne sont pas reproduits.<br>C'est acceptable pour un proxy de développement. Le documenter en une ligne dans `frontend.md` §6 suffit. | FrontPierre |

### Observations (aucune action requise)

- E05 compte les toasts immédiatement, sans réessai. C'est un choix justifié dans le code : l'intercepteur crée le toast avant que le composant n'affiche son message. Faux vert possible seulement si cet ordre changeait.
- C0a reformate aussi `"styles"` dans `angular.json` en une ligne. C'est du formatage hors commit dédié, sans effet.
- L'avertissement CommonJS de `sweetalert2` est antérieur à la branche. On pourra le traiter par `allowedCommonJsDependencies` dans un chantier séparé.

## 5. Contrôle d'intégrité des tests (récapitulatif)

```
git diff --stat 1bd9766..HEAD   → frontend/proxy.conf.mjs, frontend/src/app/pages/profile/profile.component.ts (aucun test)
git diff --stat 402b537..HEAD -- frontend/{playwright.config.ts,tsconfig.spec.json,vitest.config.ts,angular.json,package.json,package-lock.json}
                                → vide
grep .only/.skip/.fixme/xit/waitForTimeout/expect.soft/eslint-disable (e2e/, src/) → aucun
```

Les références d'intégrité restent T1 = `402b537` (configuration) et T2 = `1bd9766` (tests).
