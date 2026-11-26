# Ordre de travail — phase 2 : socle de tests front et identité

Auteur : FrontPierre, le 26/11/2026. Mode « Équipe complète » (`docs/team/workflow.md`) : le fil principal exécute cet ordre **dans l'ordre des étapes ci-dessous**, puis renvoie les rapports à FrontPierre pour recette.

```
Page(s)    : /register, /login, /profile, coquille (session), gardes (/, /boards, /boards/:id, /profile, **)
Spec       : docs/specs/identity.md (SC-IDENT-001, 002, 003, 007, 008, 017, 018, 025, 026), docs/specs/pages.md §1–4, 7, 8
Endpoints  : docs/contracts/openapi/identity-service.yaml — POST /api/auth/register, POST /api/auth/login, GET et PATCH /api/me ;
             GET /api/boards (Symfony, existant, appelé par /boards). Aucun endpoint nouveau, aucune demande à BackAli.
Branche    : feat/phase2-frontend-identity-tests
Références : docs/architecture/frontend.md (décisions), docs/ci/requests/frontend.md (CI, Manuel),
             plan de Paul : docs/reviews/2026-11-26-identity-service-validation-paul.md §6
1. FrontCodeur   — C0 : outillage qualité et formatage (aucune logique)
2. FrontTesteur  — T1 : socle de tests ; T2 : tests SC-IDENT ; commit des tests de référence
3. FrontCodeur   — C1 : proxy de dev ; C2 : seulement les écarts révélés par un test rouge
4. FrontReviewer — contrôles outillés, rapport docs/reviews/2026-11-26-frontend-identity.md
```

**Pourquoi FrontCodeur passe en premier.** C0 ne touche aucun comportement : c'est de l'outillage et du formatage mécanique. Le faire avant les tests offre deux avantages :
- le formatage ne peut toucher aucun fichier de test, puisqu'il n'en existe pas encore ;
- FrontTesteur écrit directement des tests conformes à ESLint et à Prettier. Sinon, FrontCodeur ne pourrait pas corriger le lint des tests, car le hook le lui interdit.

**Aucun code de production n'est attendu** (plan de Paul) : le comportement existe déjà. Le code de production ne change que si un test révèle un écart réel (C2).

## Prérequis (utilisateur ou fil principal)
- **Node ≥ 24.15.0 et npm 11** sur le poste. Le poste a aujourd'hui Node 16.20.1, sur lequel Angular 22 ne tourne pas.
- Accès réseau au registre npm pour les `npm install` des étapes C0 et T1. Aucun `npm install` n'a été lancé par FrontPierre.
- Pour exécuter l'e2e en local : la stack compose avec le profil `identity`, démarrée par l'utilisateur ou Manuel. FrontPierre ne démarre ni n'arrête aucune stack.
- Aucun agent ne lit ni n'affiche `.env` ou `backend/.env`.

---

## Étape 1 — FrontCodeur : C0, outillage qualité (quatre commits séparés, dans cet ordre)

Chaque commit est vérifié par `npx ng build --configuration production` (vert) avant d'être fait.

| # | Commit | Contenu | Interdits |
|---|---|---|---|
| C0a | `build(front): ESLint (angular-eslint, typescript-eslint, perfectionist)` | `ng add angular-eslint@22.8.0`, puis `npm install -D --save-exact eslint@10.15.0 typescript-eslint@8.73.0 eslint-plugin-perfectionist@5.14.0`. Contenu de `eslint.config.js` défini dans `docs/architecture/frontend.md` §5.2. Script `lint` (= `ng lint`). Préfixe `tk` ; aligner aussi `"prefix"` d'`angular.json` (`app` → `tk`). | aucune modification de `src/**` |
| C0b | `style(front): prettier --write (formatage seul)` | `npx prettier --list-different .` (environ 59 fichiers). Relire la liste ; ajouter à `.prettierignore` les fichiers générés ou tiers éventuels (`reports/`, `public/` vendored), au moins `reports`. Ensuite `npx prettier --list-different . \| xargs npx prettier --write`. Le hook refuse `prettier --write .` ; utiliser la liste. | aucun changement de logique, aucun `package*.json` |
| C0c | `style(front): eslint --fix (perfectionist : imports, unions)` | `npx eslint --fix` **par liste de fichiers** de `src/` (jamais `.`), limité aux règles en erreur de §5.2, puis `prettier --write` sur les mêmes fichiers. Les erreurs restantes non corrigibles automatiquement passent en `warn`, avec le commentaire `// ligne de base phase 2`. Leur liste, par règle et par nombre, va dans le rapport. | pas de `sort-classes` ni de `sort-objects` en `--fix` ; pas d'`eslint-disable` |
| C0d | `chore(front): .git-blame-ignore-revs` | Fichier `.git-blame-ignore-revs` à la racine, avec les hashes de C0b et C0c. | — |

Rapport de FrontCodeur :
- les hashes de C0a à C0d ;
- le nombre de fichiers formatés ;
- les avertissements de la ligne de base, par règle ;
- le résultat de `npm run lint`, `npx prettier --check .` et `ng build`.

---

## Étape 2 — FrontTesteur : T1, socle de tests ; T2, tests SC-IDENT

### T1 — `build(front): socle de tests (Vitest, Playwright)` (un commit, **propriété de FrontTesteur**)
Ces fichiers font partie de la référence d'intégrité (`frontend.md` §4.5) : seul FrontTesteur les écrit.
- `npm install -D --save-exact vitest@5.1.1 @vitest/coverage-v8@5.1.1 jsdom@31.0.1 @playwright/test@1.65.1`.
- `angular.json`, cible `test` :
  - builder `@angular/build:unit-test`, `tsConfig: tsconfig.spec.json`, runner `vitest` ;
  - couverture v8 dans `reports/coverage` (`lcov`, `cobertura`) ;
  - JUnit dans `reports/unit-junit.xml`.

  Prendre les options exactes du schéma installé. Si un réglage manque, utiliser un `runnerConfig` Vitest **dans `frontend/`**.
- `package.json` : scripts `test:ci` (`ng test --watch=false --coverage`) et `e2e` (`playwright test`). `test` reste `ng test`.
- `playwright.config.ts` :
  - `testDir: 'e2e'` ;
  - `baseURL: process.env.E2E_BASE_URL ?? 'http://127.0.0.1:8080'` (pas de lecture de `.env`) ;
  - un projet `chromium` ;
  - `fullyParallel: true`, `forbidOnly: !!process.env.CI`, `retries: 0`, `workers: process.env.CI ? 2 : undefined` ;
  - `outputDir: 'reports/e2e-artifacts'` ;
  - rapporteurs `list`, `html` (`outputFolder: 'reports/e2e-html'`, `open: 'never'`) et `junit` (`outputFile: 'reports/e2e-junit.xml'`) ;
  - `use.trace` : `process.env.E2E_TRACE === 'on' ? 'retain-on-failure' : 'off'` ; `screenshot: 'only-on-failure'` ; `video: 'off'` ;
  - pas de `webServer` : la stack est externe ;
  - titre du rapport ou métadonnées avec `E2E_BACKEND`.
- `e2e/tsconfig.json`, avec les types `node` et `@playwright/test`. `.gitignore` : `/reports`, `/playwright-report`, `/test-results`. `.dockerignore` : `e2e`, `reports`.
- `e2e/support/accounts.ts`, `session.ts` et `jwt.ts`, selon `frontend.md` §4.2 et §4.4.
- Vérifier que `npx ng test --watch=false` démarre, même sans test, et que `npx playwright test --list` fonctionne.

### T2 — `test(front): SC-IDENT-001, 002, 003, 007, 008, 017, 018, 025, 026` (commit de référence)
Tous les tests e2e portent `{ tag: '@identity' }`. Chaque titre commence par l'identifiant du scénario.

**E2E** (`frontend/e2e/`, contre nginx, valables pour les deux amonts) :

| Id | Fichier | Titre | Assertions clés |
|---|---|---|---|
| E01 | `register.spec.ts` | `SC-IDENT-001 — inscription nominale : /boards, nom et e-mail normalisés, couleur de la palette, réponse sans mot de passe` | Saisie d'un nom entouré d'espaces et d'un e-mail en casse mixte. Vérifier : URL `/boards` ; `taskora.user` (lu par `page.evaluate`) avec le nom réduit par `trim` et l'e-mail en minuscules ; `avatarColor` dans la palette de `identity.md` §1 ; corps de la réponse 201 (`waitForResponse`) sans clé `password` ; `GET /api/me` avec le jeton stocké → 200. |
| E02 | `register.spec.ts` | `SC-IDENT-002 — e-mail déjà utilisé (autre casse) : message 409 sous le formulaire, aucune session` | Compte créé par l'API, puis inscription par l'UI avec le même e-mail en majuscules. Vérifier : texte exact « Un compte existe déjà avec cette adresse e-mail. » ; on reste sur `/register` ; `taskora.token` absent. |
| E03 | `register.spec.ts` | `SC-IDENT-003 — nom composé d'espaces : message de l'API affiché` | `required` côté client laisse passer des espaces. Vérifier le texte exact « Le champ "name" est obligatoire. » et qu'aucune session n'est créée. |
| E04 | `login.spec.ts` | `SC-IDENT-007 — connexion nominale (e-mail dans une autre casse) : /boards, jeton accepté` | Compte créé par l'API, connexion par l'UI. Vérifier : `/boards` affiché ; bouton « Menu utilisateur » visible ; `GET /api/me` avec le jeton → 200 et e-mail attendu. Contre identity, `/boards` appelle Symfony avec un jeton d'identity, ce qui exerce la coexistence. |
| E05 | `login.spec.ts` | `SC-IDENT-008 — mauvais mot de passe et e-mail inconnu : même message, mêmes corps 401, pas de toast de session` | Dans les deux cas : « E-mail ou mot de passe incorrect. » ; corps 401 identiques ; aucun toast « Votre session a expiré, reconnectez-vous. » (limite de SC-IDENT-025) ; pas de jeton stocké ; on reste sur `/login`. |
| E06 | `profile.spec.ts` | `SC-IDENT-017 — changement de mot de passe : toast, champs vidés, nouveau accepté, ancien refusé` | Compte par l'API, puis connexion, puis `/profile`. Remplir « Mot de passe actuel » et « Nouveau mot de passe », cliquer sur « Changer le mot de passe ». Vérifier : toast « Mot de passe modifié » ; les deux champs vides ; `POST /api/auth/login` (API) avec le nouveau → 200, avec l'ancien → 401. |
| E07 | `profile.spec.ts` | `SC-IDENT-018 — mot de passe actuel faux : toast d'erreur, mot de passe inchangé` | Vérifier : toast « Mot de passe actuel incorrect. » ; toujours connecté ; connexion API avec l'ancien → 200, avec le nouveau → 401. |
| E08 | `session.spec.ts` | `SC-IDENT-025 — jeton refusé par l'API : retour à /login, jeton supprimé, toast de session` | Connexion, puis altération **dans le navigateur** de la signature du jeton stocké (`exp` reste valide, donc la garde laisse passer), puis navigation vers `/boards`. L'API répond 401. Vérifier : URL `/login` ; toast « Votre session a expiré, reconnectez-vous. » ; `taskora.token` absent. |
| E09 | `navigation-guards.spec.ts` | `SC-IDENT-026 — visiteur anonyme : /boards, /boards/12 et /profile redirigent vers /login` | Un test par URL (paramétré). |
| E10 | `navigation-guards.spec.ts` | `SC-IDENT-026 — jeton stocké expiré ou illisible : redirection vers /login` | Jeton à `exp` passé construit dans le navigateur ; chaîne illisible. |
| E11 | `navigation-guards.spec.ts` | `SC-IDENT-026 — utilisateur connecté : /, /login et /register redirigent vers /boards ; route inconnue → /` | Connecté : redirection vers `/boards`. Anonyme sur une route inconnue : `/`. |

**Composants et unitaires** (Vitest + TestBed, jsdom, HTTP simulé conforme au contrat) :

| Id | Fichier | Titre | Assertions clés |
|---|---|---|---|
| C01 | `src/app/core/auth.service.spec.ts` | `SC-IDENT-026 — isLoggedIn : faux si jeton absent, expiré ou illisible ; vrai si exp futur` | Couvre `AuthService.isExpired` par `isLoggedIn`. Jetons construits en mémoire. |
| C02 | idem | `SC-IDENT-026 — localStorage indisponible : aucune exception, utilisateur non connecté` | `getItem` et `setItem` levant une exception. |
| C03 | idem | `SC-IDENT-007 — login stocke taskora.token et taskora.user` | `POST /api/auth/login`, corps `{email,password}`. |
| C04 | idem | `SC-IDENT-001 — register stocke jeton et profil` | `POST /api/auth/register`, corps `{name,email,password}`. |
| C05 | `src/app/core/auth.guard.spec.ts` | `SC-IDENT-026 — authGuard : UrlTree /login sans session valide, true sinon` | — |
| C06 | idem | `SC-IDENT-026 — guestGuard : UrlTree /boards avec session valide, true sinon` | — |
| C07 | `src/app/core/auth.interceptor.spec.ts` | `SC-IDENT-025 — 401 hors /auth/ : déconnexion, /login, toast de session` | `ToastService` simulé, `Router` espionné. |
| C08 | idem | `SC-IDENT-025 — 401 sur /api/auth/login : ni déconnexion ni toast` | — |
| C09 | idem | `SC-IDENT-025 — en-tête Authorization ajouté hors /auth/, jamais sur /auth/` | — |
| C10 | `src/app/pages/auth/register.component.spec.ts` | `SC-IDENT-003 — bouton désactivé : champ vide, e-mail invalide, mot de passe de 7 caractères (dont « ééééééé ») ; activé à 8` | — |
| C11 | idem | `SC-IDENT-001 — succès : navigation vers /boards` | — |
| C12 | idem | `SC-IDENT-002 — 409 : message de l'API affiché ; repli « Inscription impossible pour le moment. »` | — |
| C13 | `src/app/pages/auth/login.component.spec.ts` | `SC-IDENT-008 — 401 : « E-mail ou mot de passe incorrect. » ; autre erreur : « Impossible de se connecter pour le moment. »` | — |
| C14 | idem | `SC-IDENT-007 — succès : navigation vers /boards` | — |
| C15 | `src/app/pages/profile/profile.component.spec.ts` | `SC-IDENT-018 — bouton « Changer le mot de passe » désactivé sans mot de passe actuel ou avec un nouveau de moins de 8 caractères` | `PushService` simulé : son constructeur appelle `push.start()`. |
| C16 | idem | `SC-IDENT-017 — succès : PATCH /api/me {currentPassword,newPassword}, champs vidés dans le DOM, toast « Mot de passe modifié »` | Vérifier le **rendu** après `await fixture.whenStable()`, pas seulement les propriétés. |

Règles pour FrontTesteur :
- Sélecteurs, données, jetons et rapports : `docs/architecture/frontend.md` §4.
- Aucun `waitForTimeout`, aucun compte de démonstration.
- Le comportement existe déjà, donc la plupart des tests seront **verts au premier passage**. À la place du « rouge d'abord », fais une **preuve de sensibilité** pour chaque fichier : casse temporairement, en local et sans commit, la condition visée (par exemple inverser `isAuthCall` ou retirer la redirection de `guestGuard`), constate l'échec pour la bonne raison, puis restaure. Indique dans le rapport, pour chaque test, ce qui a été cassé.
- Un test **rouge pour une raison réelle** reste rouge, est commité tel quel et est signalé (voir C2).
- Écart prévisible, à confirmer : `ProfileComponent.savePassword` vide `currentPassword` et `newPassword`, qui sont des champs simples et non des signals, dans un callback HTTP. En mode zoneless, le DOM risque de ne pas être rafraîchi. C16 et E06 doivent l'attraper.
- Exécuter : `npx ng test --watch=false` et `npx tsc -p e2e/tsconfig.json --noEmit`. Si l'utilisateur a démarré la stack, lancer `npx playwright test --grep @identity` contre `IDENTITY_UPSTREAM=api:80`, puis `identity:8080`, en changeant l'amont par `docker compose up -d web`. Sinon, indiquer « e2e non exécutés localement, à exécuter en CI ».
- Avant le commit, `npx eslint` et `npx prettier --check` sur tes fichiers.
- Commits : T1 `build(front): …`, puis T2 `test(front): SC-IDENT-…`. Donne les deux hashes : ce sont les références d'intégrité.
- Mettre à jour `docs/specs/traceability.md` en remplaçant « à écrire » par `rouge` ou `vert` sur les lignes front concernées. Les noms de fichiers et de tests y sont déjà prévus.

---

## Étape 3 — FrontCodeur : C1 et C2

- **C1** `feat(front): proxy de dev vers identity-service (/api/auth/, /api/me)`. Selon `frontend.md` §6, ne modifier que `frontend/proxy.conf.mjs` :
  - ne lire que `IDENTITY_UPSTREAM`, `IDENTITY_BIND` et `IDENTITY_PORT`, en plus des clés existantes ;
  - ne rien afficher de `.env` ;
  - comportement inchangé si `IDENTITY_UPSTREAM` est absent ou vaut `api:80`.
- **C2** : seulement si un test de T2 est rouge pour un écart réel, `fix(front): <écart> (SC-IDENT-…)`. Correction minimale ; exemple prévu : passer `currentPassword` et `newPassword` de `ProfileComponent` en signals, ou utiliser une notification de changement.
  - Aucune modification des fichiers de test, de `playwright.config.ts`, de `tsconfig.spec.json`, de la cible `test` d'`angular.json` ni des scripts de test.
  - Si un test paraît faux, l'écrire dans le rapport ; seul FrontTesteur le corrige, après mon accord.
- Ajout d'un `data-testid` : seulement s'il est demandé dans le rapport de FrontTesteur.
- Avant de rendre :
  - `npx prettier --write <fichiers modifiés>` et `npx eslint --fix <fichiers modifiés>` ;
  - `npx ng test --watch=false`, `ng build` et, si la stack est disponible, l'e2e `@identity` sur les deux amonts.

---

## Étape 4 — FrontReviewer : rapport `docs/reviews/2026-11-26-frontend-identity.md`

Contrôles (tous outillés, résultats chiffrés) :
1. `npm run lint`, qui doit donner 0 erreur. Les avertissements ne doivent pas dépasser la ligne de base de C0c ; donner le compte par règle. Puis `npx eslint <fichiers ajoutés ou modifiés depuis C0a> --max-warnings=0`.
2. `npx prettier --check .` : 0 fichier.
3. `npx tsc -p tsconfig.app.json --noEmit`, `npx tsc -p tsconfig.spec.json --noEmit` et `npx tsc -p e2e/tsconfig.json --noEmit`.
4. `npx ng test --watch=false --coverage`, puis e2e `@identity` sur les deux amonts si la stack est disponible ; sinon, le dire.
5. **Intégrité** : `git diff <T2>..HEAD` vide sur `frontend/**/*.spec.ts` et `frontend/e2e/**`, et `git diff <T1>..HEAD` vide sur :
   - `frontend/playwright.config.ts` ;
   - `frontend/tsconfig.spec.json` ;
   - la cible `test` d'`angular.json` ;
   - les scripts `test`, `test:ci` et `e2e`.

   Aucun `skip`, `only`, `fixme`, `retries` > 0 ni `waitForTimeout`.
6. **Formatage pur** : C0b et C0c ne touchent que du formatage ou des imports. Le diff ne contient ni `package*.json` ni fichier de test, et `ng build` reste vert avant et après.
7. **Couverture des scénarios** : chaque ligne E01–E11 et C01–C16 existe, avec l'identifiant en tête du titre.
8. **Sécurité et confidentialité** :
   - aucun littéral `eyJ…` ni mot de passe fixe dans `e2e/**` et `*.spec.ts` ;
   - aucune lecture de `.env` hors du proxy (clés listées) ;
   - aucune donnée de démonstration ;
   - traces Playwright désactivées en CI.
9. **Accessibilité** des sélecteurs : `data-testid` seulement si justifié ; signaler en remarque non bloquante l'absence de `role="alert"` sur les messages d'erreur (`identity.md` §6, HYPOTHÈSE).

Un point bloquant renvoie à FrontCodeur (C2) ou, si c'est un test, à FrontTesteur avec mon accord. Trois boucles au maximum.

## Recette (FrontPierre) puis validation (Paul)
- Recette : tests verts, y compris l'e2e sur les deux amonts en CI quand Manuel aura livré `frontend-e2e-identity`. Rapport du Reviewer sans point bloquant, intégrité respectée, scénarios couverts.
- Paul valide SC-IDENT-025 et 026, puis passe les lignes front à `validé`.
