# Revue frontend — page Profil (SC-IDENT-014, 015, 016)

- Relecteur : FrontReviewer — 27/11/2026
- Branche : `feat/front-profile-tests`
- Référence d'intégrité (tests, FrontTesteur) : `1360ebf`
- Commit relu (FrontCodeur) : `4ebde8a` — `fix(front): formulaire Profil synchronisé avec le profil (SC-IDENT-014, 015)`

## 1. Verdict

**ACCEPTÉ AVEC RÉSERVES.**

Aucun point bloquant : tous les contrôles outillés sont verts, les tests sont intacts et le correctif répond aux deux tests rouges (profil rafraîchi après l'ouverture, nom renvoyé par l'API après un 200). Réserves, à traiter avant la validation de Paul :

- M1 : le cas qui justifie `keepEdit` (profil rechargé pendant une saisie) n'est couvert par aucun test ;
- les 9 tests e2e de `frontend/e2e/profile.spec.ts` n'ont pas encore été exécutés ; aucune stack n'a été lancée, à la demande de l'utilisateur.

## 2. Contrôles outillés

Tous les contrôles ont tourné dans Docker (`node:24-alpine`, montage de `frontend/`). Aucune stack docker compose n'a été lancée.

| Contrôle | Commande | Résultat |
|---|---|---|
| Installation | `npm ci` (`.npmrc` : `strict-allow-scripts=true`, `allowScripts` respecté) | OK |
| Lint | `npx eslint .` | **0 erreur, 761 avertissements** (plafond 761). `profile.component.ts` : 12 avertissements avant et après le commit, avec les mêmes règles (10 `perfectionist/sort-classes`/`sort-objects`, 1 `label-has-associated-control`, 1 `sort-objects` dans les styles). Seuls les numéros de ligne ont changé : **aucun nouvel avertissement**. |
| Formatage | `npx prettier --check .` | OK : « All matched files use Prettier code style! » |
| Types | `npx tsc -p tsconfig.app.json --noEmit` et `-p tsconfig.spec.json` | 0 erreur |
| Tests unitaires | `npx ng test --watch=false` (Vitest, jsdom) | **7 fichiers, 33 tests, 33 verts** (dont les 16 de `profile.component.spec.ts` : 14 `it` et les 2 cas du `it.each`) |
| Build | `npx ng build` | OK, budgets respectés. Total initial : 437,95 kB brut, 111,58 kB transféré. |
| Dépendances | `npm audit --omit=dev` | 0 vulnérabilité |
| e2e | `frontend/e2e/profile.spec.ts` (9 tests) | **Non exécuté.** Il faut une stack, et aucune ne doit être lancée pendant la démo client. **Réserve.** |

## 3. Contrôle d'intégrité des tests

- `git diff 1360ebf..HEAD --stat` : un seul fichier, `frontend/src/app/pages/profile/profile.component.ts` (+30 / −9).
- Le Codeur n'a modifié aucun des fichiers suivants : `*.spec.ts`, `e2e/**`, `playwright.config.ts`, `vitest.config.ts`, `tsconfig.spec.json`, `angular.json`, `package.json`.
- Aucun `skip`, `only`, `todo`, `xit`, `xdescribe` ni `fixme` dans `src/` ou `e2e/`. Aucune assertion n'a été supprimée et aucun seuil n'a été baissé.

**Intégrité respectée.**

## 4. Revue du correctif (`linkedSignal` + `keepEdit`)

`name` et `color` deviennent des `linkedSignal` qui suivent `auth.user()`. `keepEdit(source, previous)` renvoie la valeur précédente quand elle diffère de la source précédente (champ modifié). Sinon, il renvoie la nouvelle source.

| Situation | Comportement observé (lecture du code et de l'implémentation de `linkedSignal`) | Avis |
|---|---|---|
| Ouverture de la page, puis `refreshProfile()` (GET /api/me) | Les champs non modifiés prennent le nom et la couleur reçus (SC-IDENT-014). | Correct, couvert par un test |
| Profil rechargé pendant une saisie | Le champ modifié garde la saisie ; l'autre champ suit. | Correct, **non testé** (M1) |
| Saisie ramenée à la valeur d'origine | Le champ est de nouveau considéré comme non modifié et suit la source. | Acceptable |
| 200 sur `PATCH /api/me` | `auth.setUser(u)`, puis `name.set(u.name)` et `color.set(u.avatarColor)`. `set()` recalcule d'abord le `linkedSignal` sur la nouvelle source, donc `previous.source` vaut alors `u.name` : le champ suit de nouveau la source (SC-IDENT-015, trim). | Correct. L'ordre `setUser` → `set` est nécessaire : voir S3. |
| Erreur (400, etc.) | La saisie est conservée et `saving` revient à `false`. Un rechargement ultérieur ne l'écrase pas. | Correct |
| Déconnexion sans navigation (401 → `auth.logout()` dans l'intercepteur, la page reste affichée) | Les champs non modifiés passent à `''` et `PALETTE[6]`, le champ modifié garde sa saisie. | Mineur (m4) |
| Changement d'utilisateur | En pratique impossible sans détruire la page (`/login` est protégé par `guestGuard`, hors de la coquille). | Sans objet |

- **Zoneless / signals** : correct. L'ancien code utilisait des champs simples, que la vue zoneless ne rafraîchissait pas. `[(ngModel)]="name"` sur un `WritableSignal` est pris en charge par Angular (liaison bidirectionnelle sur un signal). Le template lit `name()` et `color()` partout.
- **Contrat** : seul `PATCH /api/me` est appelé, avec `{name, avatarColor}` sous forme de chaînes. `null` n'est jamais envoyé.
- **Sécurité** : rien de nouveau (pas d'`innerHTML`, aucun jeton touché).
- **Lisibilité** : fonction pure et courte, avec une JSDoc claire et des commentaires qui renvoient aux scénarios. Bon niveau.

## 5. Constats

### Bloquants

Aucun.

### Majeurs

| # | Constat | Emplacement | Correction attendue |
|---|---|---|---|
| M1 | **Aucun test ne couvre le rechargement du profil pendant une saisie.** C'est la seule raison d'être de `keepEdit`. Si on le remplaçait par `computation: (s) => s` (ou par un `computed`), tous les tests resteraient verts, alors qu'un GET /api/me tardif effacerait la saisie de l'utilisateur. | `frontend/src/app/pages/profile/profile.component.ts:17-19`, `:313-320` | FrontTesteur : ajouter un test composant. Saisir « Camille M. », puis `refreshProfile()` renvoie « Camille Martin » : le champ garde « Camille M. ». La couleur non modifiée suit la couleur reçue. Faire de même pour une couleur choisie avant le rechargement. |

### Mineurs

| # | Constat | Emplacement | Correction attendue |
|---|---|---|---|
| m1 | **`color.set(u.avatarColor)` après un 200 n'est pas vérifié côté interface.** Le test SC-IDENT-016 contrôle `auth.user()` et `localStorage`, mais pas la pastille sélectionnée après la réponse. Rien ne vérifie non plus que la couleur suit de nouveau la source après l'enregistrement. | `profile.component.spec.ts:305-319` ; code `profile.component.ts:365` | FrontTesteur : après le 200, vérifier `selectedColors(ctx)`. Puis enchaîner un `refreshProfile()` avec une autre couleur et vérifier que la sélection suit. |
| m2 | **La palette du profil diffère de celle de l'inscription.** `PALETTE` (profil) ne contient pas `#f59e0b`, tiré à l'inscription (identity.md §1), mais contient `#f97316`, `#eab308` et `#64748b`, que l'inscription n'utilise pas. Un utilisateur dont la couleur est `#f59e0b` ne voit aucune pastille sélectionnée et ne peut plus revenir à sa couleur s'il en change. La spec n'est pas enfreinte (« modifiable avec une couleur `#RRGGBB` ») et la couleur est bien conservée au renommage (testé). | `frontend/src/app/core/models.ts:236-247` | Décision de Paul ou de FrontPierre : aligner les deux palettes (ajouter `#f59e0b` au moins), ou afficher la couleur actuelle comme pastille supplémentaire si elle est hors palette. |
| m3 | **`refreshProfile()` ne gère pas les erreurs.** `this.api.me().subscribe((u) => this.setUser(u))` n'a pas de callback `error`. Une erreur réseau ou un 5xx au démarrage affiche le toast générique de l'intercepteur, puis remonte en erreur RxJS non gérée dans la console. Le profil périmé de `localStorage` reste affiché sans indication. Le code existait avant le commit. | `frontend/src/app/core/auth.service.ts:28-30` | Ajouter `error: () => {}` (le toast vient déjà de l'intercepteur), ou une politique explicite (garder le profil stocké), avec un test unitaire. |
| m4 | Après une déconnexion par 401 sans navigation, la page Profil reste affichée. Les champs non modifiés se vident (`''`, `PALETTE[6]`) et le champ modifié garde sa saisie. Ce n'est pas dangereux (un nouvel enregistrement renverrait 401), mais l'affichage est incohérent. La cause est l'intercepteur, qui ne redirige pas : ce n'est pas un défaut du commit relu. | `frontend/src/app/core/auth.interceptor.ts:20-22` | Hors périmètre : rediriger vers `/login` après `auth.logout()`, dans une tâche dédiée. |
| m5 | **Accessibilité des pastilles de couleur.** `aria-label` vaut le code hexadécimal, qu'un lecteur d'écran épelle sans en donner le sens. Aucun `aria-pressed` ni `role="radio"` n'expose la sélection, qui n'est signalée que visuellement (bordure et icône). Le libellé « Couleur de l'avatar » n'est associé à aucun contrôle (`label-has-associated-control`, déjà dans la ligne de base). Le problème existait avant le commit. | `profile.component.ts:41-55` | Suivre le plan B3 page par page : `role="radiogroup"` avec `aria-labelledby`, puis `role="radio"` et `aria-checked`, ou `aria-pressed`, sur chaque pastille, avec un nom lisible (« Bleu ciel », etc.). Ajouter un test clavier (FrontTesteur) avant le correctif. |

### Suggestions

| # | Suggestion |
|---|---|
| S1 | Exécuter les 9 tests e2e de `frontend/e2e/profile.spec.ts` dès qu'une stack peut être lancée (après la démo), puis mettre à jour `docs/specs/traceability.md` : lignes 57-58 et 61 passent de « rouge » à « vert (unitaire) », et les lignes e2e indiquent le résultat de l'exécution. |
| S2 | `keepEdit` pourrait aller dans `shared/` (par exemple `form-signals.ts`) si d'autres formulaires (paramètres du tableau, fiche carte) en ont besoin. Pas nécessaire pour l'instant. |
| S3 | Ajouter un commentaire sur l'ordre `auth.setUser(u)` puis `name.set(...)` dans `saveProfile()`. Si on inversait cet ordre, `previous.source` resterait l'ancien nom après le `set` et le champ ne suivrait plus les rechargements. Un test (cf. m1) le verrouillerait. |

## 6. Suite à donner

1. FrontTesteur : tests M1 et m1 (rouges attendus seulement si le code régresse ; avec le code actuel, ils doivent passer). Ensuite, nouvelle référence d'intégrité.
2. FrontPierre / Paul : décision sur la palette (m2) et sur la gestion d'erreur de `refreshProfile()` (m3).
3. Exécuter les e2e (S1) avant la validation de Paul contre SC-IDENT-014, 015, 016.
