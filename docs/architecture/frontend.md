# Architecture frontend — Taskora (Angular)

Auteur : FrontPierre. Lecteurs : FrontTesteur, FrontCodeur, FrontReviewer, Manuel, Paul.
Créé le 26/11/2026 (phase 2 : socle de tests front et e2e d'identité).

## 1. Périmètre et pile

- `frontend/` : application Angular **22.3.0**, avec des composants autonomes, des signals et la détection de changement **sans zone.js** (zoneless). Le préfixe des sélecteurs est `tk-`.
- Dépendances principales : HttpClient avec le backend XHR (pour la progression des envois), Tiptap, SweetAlert2 (toasts et dialogues), service worker Web Push.
- Le build passe par `@angular/build:application`. L'image de production est `frontend/Dockerfile` : node 24 pour le build, puis nginx. nginx sert l'application et route `/api/auth/` et `/api/me` vers l'amont `IDENTITY_UPSTREAM` (`api:80` ou `identity:8080`). Le reste de `/api/` va à Symfony.
- En développement, `ng serve` passe par le proxy `frontend/proxy.conf.mjs`. Il ne lit que les clés `API_BIND`, `API_PORT`, `IDENTITY_UPSTREAM`, `IDENTITY_BIND` et `IDENTITY_PORT` dans l'environnement ou le `.env` racine, et route l'identité comme nginx (§6).
- Gestion de la session :
  - `core/auth.service.ts` : jeton et profil dans `localStorage` (`taskora.token`, `taskora.user`) ; `isLoggedIn` lit `exp` ;
  - `core/auth.interceptor.ts` : ajoute `Bearer` hors `/auth/` ; un 401 hors `/auth/` déclenche la déconnexion et le toast de session ;
  - `core/auth.guard.ts` : `authGuard` et `guestGuard`.

## 2. Règles d'architecture (rappel, appliquées par FrontReviewer)

- Aucun secret dans le frontend. La seule clé publique admise est la clé VAPID, servie par l'API.
- Aucun appel à un endpoint absent des contrats `docs/contracts/openapi/*.yaml`. Un besoin nouveau passe par `docs/contracts/requests/<NNN>-<sujet>.md`.
- La migration du backend ne modifie aucune page tant que les contrats HTTP sont conservés (`docs/specs/pages.md`).
- Accessibilité : un message d'erreur de formulaire affiché dans la page porte `role="alert"` (décision m6, `identity.md` §6). Pas d'`aria-live` en plus.

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
- Mots de passe (décision de recette m4, 26/11/2026) :
  - tests qui atteignent un amont réel (`e2e/**`, création de compte réelle) : mot de passe **aléatoire par exécution**, jamais fixe ;
  - specs unitaires et de composants (`src/**/*.spec.ts`, requêtes simulées par `HttpTestingController`, qui ne quittent pas le processus) : valeurs **manifestement factices** et fixes admises, par exemple pour les bornes de longueur. Jamais une valeur du README, un identifiant de démonstration ou réel, un littéral `eyJ…` ni une valeur qui ressemble à un secret de configuration.
- `HttpTestingController.verify()` : en `afterEach` quand chaque test crée un seul module ; sinon à la fin de chaque test qui émet une requête (décision m5bis).
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
  - en **erreur** depuis le lot B1 (27/11/2026, `3e4a1c3`) : `sort-interfaces`, `sort-object-types` (types seulement, bundle identique) ;
  - en **avertissement** : `sort-classes`, `sort-objects`. Un réordonnancement massif peut changer le comportement, par exemple l'ordre des initialiseurs de champs qui dépendent d'`inject()`, ou l'ordre des routes. Ces règles ne sont **pas** corrigées automatiquement sur le code existant. Elles passeront en erreur à la fin du lot B4.
- Passées en **erreur** par le lot B2 (27/11/2026, `80ba705`) : `@typescript-eslint/no-explicit-any`, `@typescript-eslint/no-unused-vars` (avec `ignoreRestSiblings: true`, commenté dans `eslint.config.js`), `no-irregular-whitespace`, `@angular-eslint/component-selector` (racine `tk-root`).
- Dossiers ignorés : `dist`, `.angular`, `node_modules`, `reports`, `public`.
- Ligne de base : une erreur non corrigible automatiquement dans le code existant fait passer la règle en `warn` dans `eslint.config.js`, avec le commentaire `// ligne de base phase 2`, et la liste va dans le rapport de FrontReviewer. Aucun `eslint-disable` dans le code.
- Avertissements dans une PR (règle précisée à la recette, m2, 26/11/2026) :
  - **fichier nouveau** : aucun avertissement (`npx eslint --max-warnings=0 <fichiers>`) ;
  - **fichier existant modifié** : pour chaque règle, le nombre d'avertissements du fichier n'augmente pas par rapport à `git merge-base` (comparaison `eslint --format json` des deux versions, donnée par le Reviewer) ;
  - avertissements d'accessibilité de template sur les **lignes modifiées** : corrigés dans la même PR, avec un test si le comportement au clavier change ;
  - **global** : `LINT_MAX_WARNINGS` (CI) ne fait que baisser, jamais monter ; une PR qui réduit le compte l'abaisse dans le même commit. Historique : 904 (26/11/2026), 769 (B1), **761** (B2, 27/11/2026, valeur courante).
  - **accord de Manuel** (position de FrontPierre à la recette des suites, m1, 27/11/2026, soumise à Manuel) : une **baisse** de `LINT_MAX_WARNINGS` jusqu'au compte mesuré applique la règle « le seuil ne fait que baisser » et ne demande pas d'accord au cas par cas. FrontCodeur l'applique dans le commit qui réduit le compte et y cite le compte mesuré ; Manuel en est informé par la PR. Toute **hausse** est interdite (aucune exception sans décision de l'utilisateur). Tout changement d'option de règle qui fait disparaître des avertissements sans corriger le code demande l'accord explicite de Manuel et de FrontPierre, écrit dans la PR. Tant que Manuel n'a pas confirmé cette position dans `docs/ci/requests/frontend.md` §8, son accord est demandé dans la PR.
- Réduction de la ligne de base : lots B1 à B4 de `docs/reviews/2026-11-26-frontend-identity-recette.md` §4.7. État au 27/11/2026 (`docs/reviews/2026-11-27-frontend-recette-suites-recette.md`) :

| Lot | Règles | Nombre | État |
|---|---|---:|---|
| B1 | `sort-interfaces`, `sort-object-types` | 135 | **fait** (`3e4a1c3`, dans `.git-blame-ignore-revs`) ; bundle identique octet pour octet |
| B2 | `no-explicit-any`, `no-unused-vars`, `no-irregular-whitespace`, `component-selector`, avertissement CommonJS `sweetalert2` | 8 | **fait** (`80ba705`) |
| B3 | accessibilité des templates : `click-events-have-key-events` (35), `interactive-supports-focus` (28), `no-autofocus` (10), `label-has-associated-control` (5) | 78 | **suivant** : page par page, dans l'ordre de travail de chaque page, test clavier d'abord (FrontTesteur) puis correctif (FrontCodeur). Pas de lot global. |
| ST | `strictTemplates` (hors lint, §5.4) | — | **à faire avant B4** |
| B4 | `sort-objects` (353), `sort-classes` (330) | 683 | **après ST** et après les tests des pages concernées ; configuration des groupes de `sort-classes` d'abord, jamais `--fix` global |

### 5.3 Formatage
Prettier 3.10.1 avec la configuration `.prettierrc` existante : largeur 100, guillemets simples, parseur `angular` pour le HTML. Les 59 fichiers non conformes sont reformatés dans un commit dédié (§7). Ensuite, `prettier --check` est bloquant en CI.
- Blancs dans les templates (décision m1) : Prettier peut remettre une interpolation sur sa propre ligne ; Angular rend alors une espace de chaque côté du texte. C'est invisible en `white-space: normal`, mais **visible dans un conteneur `white-space: pre*`** (exemple : visionneuse JSON). Dans ces conteneurs, l'interpolation reste collée à ses balises, ou passe par un `computed` court qui tient sur la ligne.

### 5.4 Vérification des templates (`strictTemplates`)
Décision de recette m4 (27/11/2026) : `strictTemplates` n'est pas activé aujourd'hui (`angularCompilerOptions` ne contient que `strictInjectionParameters` et `strictInputAccessModifiers`, et `compilerOptions` n'active pas `strict`). Les liaisons de template ne sont donc vérifiées qu'en mode de base.
- `strictTemplates: true` est activé dans un lot dédié **ST**, **avant** B4 : B4 réordonne des membres de classes utilisés par les templates, et on veut que le compilateur vérifie ces liaisons pendant ce chantier.
- Méthode (FrontCodeur) : mesure d'abord, sur une branche jetable, des erreurs de compilation par fichier avec le drapeau activé, rendue dans le rapport ; puis corrections de types seulement (annotations, garde de type, `computed` typé), sans `$any()` ni `// @ts-ignore`, et le drapeau dans le même commit, car une erreur de template bloque le build et ne peut pas aller dans une ligne de base. Bundle de production comparé avant et après ; tout écart est expliqué. Tests unitaires, build et e2e verts.
- Si la mesure dépasse 40 erreurs, le lot est découpé page par page (corrections d'abord, drapeau en dernier).
- Le passage de TypeScript en `strict` n'est **pas** décidé : il est mesuré en même temps (compte d'erreurs `tsc --strict --noEmit` dans le rapport ST) et sera tranché à la recette de ST.

### 5.5 Versions
Le tableau détaillé des versions et de leur vérification est dans `docs/ci/requests/frontend.md` §7.
- Il faut Node ≥ 24.15.0 en CI comme en local. Le poste de développement actuel a Node 16 ; à mettre à niveau par l'utilisateur.
- La version d'Angular ne change pas.

## 6. Proxy de développement et identité
`proxy.conf.mjs` doit pouvoir envoyer `/api/auth/` (préfixe) et `/api/me` (chemin exact, comme `location = /api/me` dans nginx) vers identity-service quand l'amont choisi est identity. Le reste de `/api` et `/uploads` continue d'aller vers Symfony.
- Le choix suit `IDENTITY_UPSTREAM`, lu dans l'environnement ou le `.env` racine. Seules les clés `IDENTITY_UPSTREAM`, `IDENTITY_BIND` et `IDENTITY_PORT` sont ajoutées à la lecture.
- La cible est `http://<IDENTITY_BIND>:<IDENTITY_PORT, 8082 par défaut>`, publiée par `docker-compose.identity-dev.yml`.
- Réalisation : FrontCodeur (C1, `91350c8`), vérifiée par FrontReviewer : clés `'/api/auth/'` et `'^/api/me(?:[?#]|$)'` avant `/api`.
- Limites connues (décision m9) : le proxy est un outil de développement, pas une reproduction de nginx.
  - Seul l'hôte `identity` dans `IDENTITY_UPSTREAM` active le routage d'identité. Toute autre valeur, faute de frappe comprise, retombe **silencieusement** sur Symfony.
  - Le gel des écritures et la limite de corps de 64 Kio de nginx ne sont pas reproduits.
  - La référence reste l'e2e à travers nginx (`frontend-e2e-identity`, deux amonts).

## 7. Historique des décisions
| Date | Décision |
|---|---|
| 27/11/2026 (recette des suites) | Suites S1–S3 acceptées (`docs/reviews/2026-11-27-frontend-recette-suites-recette.md`). `role="alert"` en place sur `/login` et `/register` ; visionneuse JSON sans espace parasite ; lots B1 et B2 faits, `LINT_MAX_WARNINGS` = 761. Position m1 soumise à Manuel : une baisse du seuil jusqu'au compte mesuré ne demande pas d'accord au cas par cas ; hausse interdite. Contrôle visuel de la visionneuse JSON rattaché à l'e2e SC-ATTACH-010 (m3). `strictTemplates` activé dans un lot ST avant B4 (m4, §5.4). Ordre des suites : scénarios IDENT front, B3 par page, ST, SC-ATTACH-010, B4. |
| 26/11/2026 (recette) | Phase 2 acceptée (`docs/reviews/2026-11-26-frontend-identity-recette.md`). Règles précisées : avertissements ESLint par fichier (m2, §5.2), mots de passe des specs (m4, §4.2), `verify()` (m5bis, §4.2), blancs en `pre-wrap` (m1, §5.3), limites du proxy (m9, §6). `role="alert"` sur les messages d'erreur des formulaires (m6). Plan de réduction de la ligne de base B1–B4. |
| 26/11/2026 | Vitest + jsdom via `@angular/build:unit-test` ; Playwright Chromium ; un fichier e2e par page avec l'étiquette `@identity` ; comptes créés par l'API dans chaque test ; ESLint (angular-eslint + perfectionist) avec ligne de base ; formatage Prettier dans un commit dédié. |
