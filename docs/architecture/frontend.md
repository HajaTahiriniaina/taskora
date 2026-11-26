# Architecture frontend — Taskora (Angular)

Auteur : FrontPierre. Lecteurs : FrontTesteur, FrontCodeur, FrontReviewer, Manuel, Paul.
Créé le 26/11/2026 (phase 2 : socle de tests front et e2e d'identité).

## 1. Périmètre et pile

- `frontend/` : application Angular **22.3.0**, avec des composants autonomes, des signals et la détection de changement **sans zone.js** (zoneless). Le préfixe des sélecteurs est `tk-`.
- Dépendances principales : HttpClient avec le backend XHR (pour la progression des envois), Tiptap, SweetAlert2 (toasts et dialogues), service worker Web Push.
- Le build passe par `@angular/build:application`. L'image de production est `frontend/Dockerfile` : node 24 pour le build, puis nginx. nginx sert l'application et route `/api/auth/` et `/api/me` vers l'amont `IDENTITY_UPSTREAM` (`api:80` ou `identity:8080`). Le reste de `/api/` va à Symfony.
- En développement, `ng serve` passe par le proxy `frontend/proxy.conf.mjs`. Il ne lit que les clés `API_BIND`/`API_PORT` dans l'environnement ou le `.env` racine. Le routage d'identité est à ajouter (§6).
- Gestion de la session :
  - `core/auth.service.ts` : jeton et profil dans `localStorage` (`taskora.token`, `taskora.user`) ; `isLoggedIn` lit `exp` ;
  - `core/auth.interceptor.ts` : ajoute `Bearer` hors `/auth/` ; un 401 hors `/auth/` déclenche la déconnexion et le toast de session ;
  - `core/auth.guard.ts` : `authGuard` et `guestGuard`.

## 2. Règles d'architecture (rappel, appliquées par FrontReviewer)

- Aucun secret dans le frontend. La seule clé publique admise est la clé VAPID, servie par l'API.
- Aucun appel à un endpoint absent des contrats `docs/contracts/openapi/*.yaml`. Un besoin nouveau passe par `docs/contracts/requests/<NNN>-<sujet>.md`.
- La migration du backend ne modifie aucune page tant que les contrats HTTP sont conservés (`docs/specs/pages.md`).

## 3. Stratégie de tests (décision phase 2)

| Niveau | Outil | Emplacement | Cible |
|---|---|---|---|
| Unitaire et composant | `ng test` : builder `@angular/build:unit-test`, runner **Vitest**, environnement **jsdom** | `frontend/src/**/<fichier>.spec.ts`, à côté du fichier testé | services, gardes, intercepteur, logique et rendu des composants (TestBed, `provideHttpClientTesting` + `HttpTestingController`, `provideRouter`) |
| E2E | **Playwright** (`@playwright/test`), Chromium | `frontend/e2e/<page>.spec.ts`, avec les aides dans `frontend/e2e/support/**` | parcours des pages de `pages.md` contre la stack Docker, à travers nginx |

### 3.1 Pourquoi Vitest avec `@angular/build:unit-test`
- C'est le builder de tests unitaires **natif de la CLI**. Il est livré dans `@angular/build` 22.3.0, déjà installé, et déclare `vitest ^4.0.8 || ^5.0.0` en dépendance paire (vérifié dans le lockfile). Depuis Angular 21, la CLI génère des projets avec Vitest par défaut. `tsconfig.spec.json` référence déjà `vitest/globals`.
- Karma est déprécié : `@angular/build` ne le garde qu'en option. Jest n'a pas de builder officiel en Angular 22.
- Il réutilise le pipeline esbuild/Vite de l'application. Aucune configuration webpack ni de `zone.js/testing` n'est nécessaire, ce qui convient à une application zoneless.
- J'ai choisi jsdom plutôt que le mode navigateur de Vitest (`@vitest/browser-playwright`). C'est plus léger en CI, sans navigateur pour les tests de composants. Le comportement en vrai navigateur est couvert par Playwright. On pourra revenir sur ce choix si un composant dépend de l'API de mise en page (CDK drag and drop, virtualisation), mais pas en phase 2.
- Zoneless dans les tests :
  - `await fixture.whenStable()` remplace `fakeAsync`/`tick`, qui dépendent de zone.js ;
  - les horloges se simulent avec `vi.useFakeTimers()` ;
  - aucun test n'appelle `provideZoneChangeDetection`.

### 3.2 Pourquoi Playwright pour l'e2e
- C'est l'outil demandé par Paul et par `pages.md`. Il est multi-navigateur, ses sélecteurs sont accessibles (`getByRole`, `getByLabel`), il attend automatiquement (pas de `waitForTimeout`), et il dispose d'un client `request` pour préparer les données par l'API.
- Les tests passent **par nginx** (`E2E_BASE_URL`, par défaut `http://127.0.0.1:8080`). Le même code de test tourne donc contre Symfony ou identity-service selon `IDENTITY_UPSTREAM`, sans aucune branche dans les tests. Le nom du backend (`E2E_BACKEND`) ne sert qu'à l'étiquette du rapport.
- Phase 2 : Chromium seul. Firefox et WebKit seront ajoutés plus tard (WebKit pour iOS et le push).

## 4. Conventions de tests

### 4.1 Arborescence
```
frontend/
  playwright.config.ts          # FrontTesteur
  tsconfig.spec.json            # existant, unitaires
  e2e/
    tsconfig.json               # FrontTesteur (types @playwright/test, node)
    support/
      accounts.ts               # création de comptes par l'API
      session.ts                # connexion par l'UI ou dépôt du jeton dans localStorage
      jwt.ts                    # jetons expirés, illisibles ou altérés, construits dans le navigateur
    register.spec.ts            # page /register
    login.spec.ts               # page /login
    profile.spec.ts             # page /profile
    session.spec.ts             # coquille : expiration de session
    navigation-guards.spec.ts   # gardes et redirections
  src/app/**/<fichier>.spec.ts  # unitaires et composants, colocalisés
  reports/                      # sorties de tests et de couverture (ignoré par Git)
```
- Un fichier e2e par page (`pages.md`). Le plan de Paul parlait d'un seul `identity.spec.ts` ; le découpage par page le remplace, et le regroupement se fait par l'étiquette `@identity`, retenue par `--grep @identity`.
- Le titre de chaque test commence par l'identifiant du scénario : `test('SC-IDENT-007 — …', { tag: '@identity' }, …)` ou `it('SC-IDENT-025 — …')`.

### 4.2 Données de test
- **Chaque test crée ses propres comptes** par `POST /api/auth/register`, envoyé avec le `request` de Playwright à `E2E_BASE_URL`, donc à l'amont actif :
  - e-mail unique `e2e-<scénario>-<horodatage>-<aléa>@example.com` (domaine réservé par la RFC 2606) ;
  - mot de passe aléatoire par exécution (`crypto.randomBytes`, 16 caractères ou plus) ;
  - nom `E2E <scénario>`.
- Interdit : les comptes de démonstration du README, les identifiants réels et les données préchargées ou partagées entre tests. Rien n'est lu dans `.env`.
- Aucun nettoyage par l'API, puisqu'il n'existe aucune route de suppression de compte et qu'aucune n'est demandée. En CI, la stack est détruite (`down -v`). En local, les comptes `e2e-…@example.com` s'accumulent dans la base de développement ; c'est acceptable, et une stack compose dédiée est conseillée.
- Les tests sont indépendants : `fullyParallel: true`, aucun ordre imposé. En CI : 2 workers et `retries: 0` (un test instable doit être corrigé, pas rejoué), avec `forbidOnly: true`.
- Tests unitaires : réponses HTTP simulées par `HttpTestingController`. Elles respectent le schéma OpenAPI : `user` = `{id, name, email, avatarColor}` et erreurs `{"error": "…"}`. Pour un jeton factice, on construit en mémoire une charge utile `{exp}`, sans signature réelle.

### 4.3 Sélecteurs
Ordre de préférence :
1. `getByRole` avec un nom accessible, par exemple `getByRole('button', { name: 'Se connecter' })` ;
2. `getByLabel`, par exemple `getByLabel('Mot de passe', { exact: true })`, car le bouton « Afficher » est voisin ;
3. `getByText` pour un message visible par l'utilisateur, avec le texte exact de la spécification ;
4. `data-testid`, **seulement** si aucun des trois ne convient. Son ajout dans le code de production est fait par FrontCodeur, à la demande du Testeur écrite dans son rapport.

Interdits : sélecteurs CSS de mise en forme (`.btn-primary`), XPath, index positionnels, `waitForTimeout`.
Toasts SweetAlert2 : utiliser le rôle si le toast en expose un (à vérifier par FrontTesteur), sinon `page.locator('.swal2-container').getByText('…')`. Ce conteneur est la seule exception CSS admise : c'est une bibliothèque tierce et on n'y ajoute pas de `data-testid`.

### 4.4 Jetons et rapports
- Aucun jeton ni mot de passe dans un titre de test, une annotation, `console.log`, une pièce jointe ou une capture d'écran volontaire.
- Les jetons forgés pour SC-IDENT-025/026 (expirés, illisibles, signature altérée) sont **construits ou modifiés dans le navigateur**, dans `page.evaluate`, à partir du jeton stocké ou de paramètres simples. Aucun littéral `eyJ…` ne figure dans le code ni dans les rapports. Le contrôle de fuite de la CI le refuserait (`docs/ci/requests/frontend.md` §3).
- Traces Playwright : `off` en CI, `retain-on-failure` en local seulement (variable `E2E_TRACE=on`). Captures d'écran : `only-on-failure`. Vidéo : `off`.

### 4.5 Intégrité des tests
- Fichiers de référence :
  - `frontend/**/*.spec.ts` et `frontend/e2e/**` (protégés par le hook `.claude/hooks/protect-tests.sh` contre FrontCodeur) ;
  - la configuration d'exécution : `frontend/playwright.config.ts`, `frontend/tsconfig.spec.json`, la cible `test` d'`angular.json`, toute configuration Vitest, et les scripts `test`, `test:ci` et `e2e` de `package.json`.
- Le hook ne protège pas ces fichiers de configuration. FrontReviewer vérifie donc qu'ils sont inchangés entre le commit de socle (T1) et `HEAD`.
- Sont refusés : `skip`, `fixme`, `only`, retries > 0 en CI, exclusion de fichier, `expect.soft` qui masquerait une assertion bloquante, timeout gonflé.

## 5. Outillage

### 5.1 Tests (mis en place par FrontTesteur)
- Cible `test` dans `angular.json` : builder `@angular/build:unit-test`, `tsConfig: tsconfig.spec.json`, runner `vitest`, couverture v8, rapporteurs `default` et `junit`, sortie dans `reports/`. Les noms exacts des options sont à reprendre du schéma installé, `node_modules/@angular/build/src/builders/unit-test/schema.json` ; je ne les ai pas vérifiés.
- Dépendances de développement, en version exacte : `vitest` 5.1.1, `@vitest/coverage-v8` 5.1.1, `jsdom` 31.0.1, `@playwright/test` 1.65.1.
- Scripts : `test` (`ng test`), `test:ci` (`ng test --watch=false --coverage`), `e2e` (`playwright test`).

### 5.2 Lint (mis en place par FrontCodeur)
- `ng add angular-eslint@22.8.0` (schématique officielle), qui crée la configuration plate `eslint.config.js` et la cible `lint`. Ensuite `eslint` 10.15.0, `typescript-eslint` 8.73.0 et `eslint-plugin-perfectionist` 5.14.0, en version exacte.
- Règles :
  - `typescript-eslint` recommended ;
  - angular-eslint `tsRecommended`, `templateRecommended` et `templateAccessibility` ;
  - préfixe des sélecteurs : `tk` (élément kebab-case, attribut camelCase).
- Règles perfectionist (tri `natural`, `asc`) :
  - en **erreur**, car leur correction automatique est sans risque : `sort-imports`, `sort-named-imports`, `sort-named-exports`, `sort-union-types`, `sort-intersection-types` ;
  - en **avertissement** en phase 2 : `sort-classes`, `sort-objects`, `sort-interfaces`, `sort-object-types`. Un réordonnancement massif peut changer le comportement, par exemple l'ordre des initialiseurs de champs qui dépendent d'`inject()`, ou l'ordre des routes. Ces règles ne sont **pas** corrigées automatiquement sur le code existant. Elles passeront en erreur après un chantier dédié.
- Dossiers ignorés : `dist`, `.angular`, `node_modules`, `reports`, `public`.
- Ligne de base : une erreur non corrigible automatiquement dans le code existant fait passer la règle en `warn` dans `eslint.config.js`, avec le commentaire `// ligne de base phase 2`, et la liste va dans le rapport de FrontReviewer. Aucun `eslint-disable` dans le code. Les fichiers nouveaux ou modifiés n'ont aucun avertissement (`--max-warnings=0` sur la liste des fichiers modifiés).

### 5.3 Formatage
Prettier 3.10.1 avec la configuration `.prettierrc` existante : largeur 100, guillemets simples, parseur `angular` pour le HTML. Les 59 fichiers non conformes sont reformatés dans un commit dédié (§7). Ensuite, `prettier --check` est bloquant en CI.

### 5.4 Versions
Le tableau détaillé des versions et de leur vérification est dans `docs/ci/requests/frontend.md` §7.
- Il faut Node ≥ 24.15.0 en CI comme en local. Le poste de développement actuel a Node 16 ; à mettre à niveau par l'utilisateur.
- La version d'Angular ne change pas.

## 6. Proxy de développement et identité
`proxy.conf.mjs` doit pouvoir envoyer `/api/auth/` (préfixe) et `/api/me` (chemin exact, comme `location = /api/me` dans nginx) vers identity-service quand l'amont choisi est identity. Le reste de `/api` et `/uploads` continue d'aller vers Symfony.
- Le choix suit `IDENTITY_UPSTREAM`, lu dans l'environnement ou le `.env` racine. Seules les clés `IDENTITY_UPSTREAM`, `IDENTITY_BIND` et `IDENTITY_PORT` sont ajoutées à la lecture.
- La cible est `http://<IDENTITY_BIND>:<IDENTITY_PORT, 8082 par défaut>`, publiée par `docker-compose.identity-dev.yml`.
- Réalisation : FrontCodeur (ordre de travail, C1). Le format de clé regex du proxy Vite (`^/api/me$`) est à vérifier.

## 7. Historique des décisions
| Date | Décision |
|---|---|
| 26/11/2026 | Vitest + jsdom via `@angular/build:unit-test` ; Playwright Chromium ; un fichier e2e par page avec l'étiquette `@identity` ; comptes créés par l'API dans chaque test ; ESLint (angular-eslint + perfectionist) avec ligne de base ; formatage Prettier dans un commit dédié. |
