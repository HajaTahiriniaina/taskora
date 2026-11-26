# Recette front — phase 2 : socle de tests et identité

Auteur : FrontPierre, le 26/11/2026. Destinataires : Paul (validation, étape 7), puis FrontTesteur, FrontCodeur et Manuel (suites).
Ordre de travail : `docs/specs/phase-2-frontend-identity-workorder.md`. Revue : `docs/reviews/2026-11-26-frontend-identity.md` (FrontReviewer, ACCEPTÉ, m1 à m9 non bloquants).
Base de la recette : `main` à `531cbf2` (fusion de la PR #3, branche `feat/phase2-frontend-identity-tests`). Branche de recette : `chore/phase2-front-recette` (documentation seule).

Mode « sans l'outil Agent » : je n'ai lancé aucun sous-agent. Les suites sont rendues sous forme d'ordre de travail (§5), à exécuter par le fil principal.

## 1. Verdict

**ACCEPTÉ.**

- Toutes les étapes de l'ordre de travail sont livrées (C0a–C0d, T1, T2, C1, C2, revue), et les écarts constatés sont acceptables (§2.2).
- La CI frontend complète est verte au premier run sur GitHub, y compris l'e2e `@identity` **sur les deux amonts** : c'était la dernière condition de recette encore ouverte dans la revue (m7).
- L'intégrité des tests est respectée : les seules modifications de tests après T2 renforcent les tests (`verify()`, 912bd0b).
- Aucun point bloquant. Les points m1 à m9 sont tranchés en §4. Deux suites changent du comportement observable (m1, m6) : elles passent par un test d'abord (§5).

Les lignes front de SC-IDENT-001, 002, 003, 007, 008, 017, 018, 025 et 026 peuvent être présentées à Paul pour passer à `validé`.

## 2. Contrôle de l'ordre de travail

### 2.1 Étapes

| Étape | Attendu | Livré | Preuve |
|---|---|---|---|
| 1. FrontCodeur C0a–C0d | ESLint + perfectionist, `prettier --write`, `eslint --fix` (imports, unions), `.git-blame-ignore-revs` | `c3f40b8`, `489578a`, `a74eb34`, `8d22acd` | revue §2.2 et §3.3 ; hashes complets vérifiés dans `.git-blame-ignore-revs` |
| 2. FrontTesteur T1 | socle Vitest + Playwright, configuration conforme | `402b537` | revue §3.1 (configuration Playwright, couverture) |
| 2. FrontTesteur T2 | E01–E11, C01–C16, identifiant en tête | `1bd9766` (14 e2e, 16 unitaires) | revue §3.2 |
| 3. FrontCodeur C1 | proxy de dev identity | `91350c8` | revue §3.4 (table de routage simulée) |
| 3. FrontCodeur C2 | correctif seulement si test rouge réel | `bb103eb` (SC-IDENT-017, écart prévu dans l'ordre de travail) | revue §3.5 ; C16 et E06 verts |
| 4. FrontReviewer | rapport outillé | `e0cdfc7` | ACCEPTÉ, 0 bloquant |
| Suites de revue | m3, m5 (FrontTesteur) ; m7, m8 (Manuel) | `912bd0b` ; `8e67189` | §2.3 |
| CI (Manuel) | jobs de `docs/ci/requests/frontend.md` | `.github/workflows/frontend.yml` | premier run GitHub vert : `frontend-quality`, `frontend-unit`, `frontend-build`, `frontend-image`, `frontend-e2e-identity` (`api:80` et `identity:8080`), `frontend-test-report` |

### 2.2 Écarts par rapport à l'ordre de travail

| Écart | Décision |
|---|---|
| C0b : 57 fichiers formatés au lieu d'« environ 59 » ; `tsconfig.spec.json` laissé tel quel | Acceptable : `tsconfig.spec.json` appartient à FrontTesteur, et `prettier --check .` est vert. |
| C0b n'est pas strictement neutre pour le DOM : espaces ajoutés autour d'environ 17 interpolations | Acceptable pour l'ensemble. Un cas visible probable, la visionneuse JSON : suite m1 (§4). |
| C0a reformate `"styles"` d'`angular.json` sur une ligne | Acceptable : formatage sans effet, hors cible `test`. |
| Fichiers e2e par page au lieu d'un `identity.spec.ts` (plan de Paul) | Déjà décidé dans `frontend.md` §4.1. |
| C2 a été nécessaire (SC-IDENT-017 rouge) | Prévu par l'ordre de travail ; correctif minimal (signals). |
| `package.json` modifié après T1 par Manuel (`allowScripts`) et `.npmrc` ajouté | Acceptable : seul le champ `allowScripts` est ajouté. Dépendances, scripts `test`, `test:ci` et `e2e`, et cible `test` inchangés (`git diff 402b537..HEAD -- frontend/package.json` vérifié). |
| Specs unitaires avec des mots de passe factices fixes | Conforme après précision de la règle (m4, §4). |
| Tests modifiés après T2 (`912bd0b`) | Acceptable : six lignes ajoutées par FrontTesteur, uniquement `HttpTestingController.verify()` en `afterEach` (renforcement). Aucune assertion retirée. |

### 2.3 Suites déjà traitées

- **m3** (traçabilité SC-IDENT-017 à `vert`) : fait par FrontTesteur dans `912bd0b` (lignes 62–63).
- **m5** (`verify()` dans les specs de composants) : fait dans `912bd0b` pour `login`, `register` et `profile`.
- **m7** (CI) : fait par Manuel, `8e67189`. Premier run vert, e2e sur les deux amonts.
- **m8** (`allowScripts`) : fait par Manuel. Refus explicite des scripts d'installation de `esbuild`, `lmdb`, `msgpackr-extract` et `@parcel/watcher`, et `strict-allow-scripts=true`. Le build et l'image passent en CI, donc aucun de ces scripts n'est nécessaire.

## 3. Contrôles locaux de recette

Conteneur `node:24-alpine` (Node 24.22.3), `frontend/` monté, `node_modules` déjà présents (pas de `npm ci`). Aucune stack ni `docker compose`.

| Contrôle | Résultat |
|---|---|
| `npx ng lint --max-warnings=904` | code de sortie 0 ; 0 erreur, 904 avertissements (= ligne de base, = `LINT_MAX_WARNINGS` de la CI). 828 sont signalés « fixable » par `--fix` : voir §4.7. |
| `npx prettier --check .` | conforme |
| `npx tsc -p tsconfig.spec.json --noEmit` et `npx tsc -p e2e/tsconfig.json --noEmit` | 0 erreur |
| `npx ng test --watch=false` | 6 fichiers, **16/16 verts** (après `912bd0b`) |
| `npx eslint --max-warnings=0` sur les 5 specs HTTP | 0 avertissement |
| `git diff --stat 1bd9766..HEAD -- 'frontend/**/*.spec.ts' 'frontend/e2e/**'` | 3 fichiers, +6 lignes, toutes dans `912bd0b` |
| `git diff --stat 402b537..HEAD` sur la configuration de test | `package.json` (+6, `allowScripts`) et `.npmrc` (+3) seulement |

L'e2e n'a pas été relancé localement : la preuve est le job `frontend-e2e-identity` de la CI, sur les deux amonts.

## 4. Décisions sur les points ouverts

### m1 — espaces ajoutés par Prettier autour des interpolations : **correctif demandé, limité à `json-node.component.ts`**

Lecture du code (`frontend/src/app/shared/json-node.component.ts`) :
- `.row` est en `display: flex; white-space: pre-wrap`. `white-space` est hérité ; les boutons `.tog` et `.sum` ne le redéfinissent pas.
- Depuis `489578a`, le bouton `.tog` contient `{{ open() ? '▾' : '▸' }}` sur sa propre ligne. Le compilateur Angular (sans `preserveWhitespaces`) réduit les blancs à **une espace de chaque côté**, et le nœud texte devient « ▾ » entouré d'espaces. Avec `pre-wrap`, ces espaces sont rendues. Le bouton fait `width: 16px` en police monospace de 13 px : trois caractères d'environ 8 px chacun débordent de la largeur. Le triangle est décalé ou rogné : **écart visible probable**.
- Le bouton `.sum` (« 3 éléments ») reçoit une espace de tête, ce qui donne un rembourrage asymétrique : écart visible mineur.
- Les autres contextes `pre-wrap` du dépôt ont été vérifiés : `file-viewer` (`<pre>{{ text() }}</pre>`, `.mail-text`) garde l'interpolation collée aux balises, et `.rich-editable` et `.ProseMirror` sont rendus par Tiptap, pas par un template. Les autres interpolations sont en `white-space: normal`, où les espaces sont invisibles.

Décision : on corrige ce seul composant. Le texte du bouton est un comportement observable, donc le test vient d'abord (FrontTesteur), puis le correctif (FrontCodeur). Le correctif doit rester conforme à `prettier --check`, avec des signals `computed` (`toggleGlyph()`, `summary()`) ou une interpolation collée aux balises. Pas de `<!-- prettier-ignore -->` si une écriture compacte suffit.
Nouvelle règle (`frontend.md` §5.3) : dans un conteneur `white-space: pre*`, une interpolation reste collée à ses balises.

### m2 — règle « fichiers modifiés sans avertissement » : **précisée**

La règle de `frontend.md` §5.2 était intenable pour un fichier existant : corriger ses avertissements de ligne de base impose le réordonnancement que la phase 2 interdit. Nouvelle règle, outillable :
- **fichier nouveau** : `npx eslint --max-warnings=0 <fichiers>`, aucune exception ;
- **fichier existant modifié** : pour chaque règle, le nombre d'avertissements du fichier ne doit pas augmenter par rapport à la base de la branche (`git merge-base`). Mesure : `eslint --format json` sur les deux versions. Le Reviewer donne la comparaison par fichier et par règle ;
- **global** : `LINT_MAX_WARNINGS` (CI) ne fait que baisser. Toute PR qui réduit le compte l'abaisse dans le même commit (Manuel, ou FrontCodeur avec accord de Manuel) ;
- les avertissements d'accessibilité de template (`click-events-have-key-events`, `interactive-supports-focus`, `label-has-associated-control`, `no-autofocus`) sur les **lignes modifiées** sont corrigés dans la même PR, avec un test si le comportement au clavier change.

`profile.component.ts` (12 avant C2, 12 après) est donc conforme.

### m4 — mots de passe fixes dans les specs unitaires : **admis, règle précisée**

L'interdiction de l'ordre de travail (§8 de la revue, « aucun mot de passe fixe ») vise les **tests qui atteignent un amont réel** : `e2e/**` et tout test qui crée un compte réel. Là, le mot de passe est aléatoire par exécution (`crypto`), et c'est le cas aujourd'hui.

Dans les specs unitaires et de composants (`src/**/*.spec.ts`), les requêtes ne quittent jamais le processus (`HttpTestingController`). Des valeurs **manifestement factices** et fixes y sont admises, et même nécessaires pour tester les bornes à 7 et 8 caractères. Conditions :
- aucune valeur du README, aucun identifiant de démonstration ou réel ;
- aucun littéral `eyJ…` ;
- aucune valeur qui ressemble à un secret de configuration (clé, jeton).

Aucune action sur les specs existantes. Règle ajoutée à `frontend.md` §4.2.

### m5bis — `verify()` dans `auth.service.spec.ts` et `auth.interceptor.spec.ts` : **verify() par test, pas d'`afterEach` générique**

La revue s'était trompée : ces deux fichiers appellent `verify()`, mais seulement dans certains tests. Constat sur le code :
- `auth.service.spec.ts` :
  - le test SC-IDENT-026 `isLoggedIn` recrée le module de test dans une boucle (`resetTestingModule` + `setup()`) et n'émet aucune requête ;
  - le test « localStorage indisponible » émet `POST /api/auth/login` sans `verify()` ;
  - C03 et C04 appellent `verify()`.
- `auth.interceptor.spec.ts` :
  - les deux premiers tests SC-IDENT-025 n'appellent pas `verify()` ;
  - or le premier exerce `logout()` après un 401 : une requête parasite déclenchée par la déconnexion passerait inaperçue.

Décision : pas d'`afterEach` générique dans ces deux fichiers (il dépendrait du dernier module créé par la boucle et de l'ordre avec `vi.restoreAllMocks`). FrontTesteur ajoute `http.verify()` (ou `ctx.http.verify()`) **à la fin de chaque test qui émet une requête** et qui ne l'appelle pas encore : « localStorage indisponible », et les deux premiers tests de l'intercepteur. Les tests sans requête n'en ont pas besoin. C'est un renforcement de test seul, sans changement de comportement : il est fait avec mon accord, donné ici.

### m6 — `role="alert"` sur les messages d'erreur de `/login` et `/register` : **oui**

`identity.md` §6 demande que les messages d'erreur soient annoncés (HYPOTHÈSE). L'application utilise déjà `role="alert"` pour ses messages d'erreur (`trello-access.component.ts`) et les toasts SweetAlert2 en exposent un. L'icône `tk-icon` est `aria-hidden`, donc seul le texte est annoncé.

Décision : `<div class="error" role="alert">` dans `login.component.ts` et `register.component.ts`. Pas d'`aria-live` en plus : `role="alert"` l'implique (assertive). C'est un changement de comportement accessible, donc **test d'abord** :
1. FrontTesteur rend C12 et C13 exigeants : le message est lu dans l'élément de rôle `alert` (`[role="alert"]`) et non plus par la classe `.error`. Les tests sont rouges au commit.
2. En e2e, E02, E03 et E05 ciblent le message par `getByRole('alert').filter({ hasText: … })`, avec le texte exact. C'est compatible avec E05, qui compte les toasts par `.swal2-container` et non par rôle.
3. FrontCodeur ajoute l'attribut. Les tests passent au vert sans autre changement.

Le Reviewer vérifie ensuite que l'hypothèse de `identity.md` §6 est levée côté front (Paul met à jour la spécification).

### m9 — limites du proxy de dev : **documentées**

Ajoutées dans `frontend.md` §6 :
- seul l'hôte `identity` active le routage d'identité ; une autre valeur, faute de frappe comprise, retombe silencieusement sur Symfony ;
- ni le gel des écritures ni la limite de 64 Kio de nginx ne sont reproduits ;
- la référence reste l'e2e à travers nginx (CI).

Aucune modification du code.

### m3, m7, m8

Traités (§2.3). Aucune action.

### Observations de la revue

- E05 (comptage immédiat des toasts) : accepté tel quel, la justification est dans le code.
- Avertissement CommonJS de `sweetalert2` : hors périmètre, à traiter avec la ligne de base (§4.7, B2).

### 4.7 Plan de réduction de la ligne de base ESLint (904 avertissements)

| Lot | Règles | Nombre | Risque | Responsable et méthode |
|---|---|---:|---|---|
| B1 | `perfectionist/sort-interfaces`, `perfectionist/sort-object-types` | 135 | nul à l'exécution : types seulement | FrontCodeur. `eslint --fix` par liste de fichiers, `tsc` sur les trois projets, bundle de production identique après normalisation (même méthode que la revue §3.3). Les deux règles passent en `error`, et `LINT_MAX_WARNINGS` baisse à 769. Commit `style(front): …`, ajouté à `.git-blame-ignore-revs`. |
| B2 | `no-explicit-any` (4), `no-unused-vars` (2), `no-irregular-whitespace` (1), `component-selector` (`app-root` → `tk-root`, 1), avertissement CommonJS `sweetalert2` | 8 | faible | FrontCodeur, corrections manuelles, `ng build` et tests unitaires verts. `tk-root` touche `index.html` : contrôle par un e2e existant (toute page). Les règles passent en `error`. |
| B3 | accessibilité des templates : `click-events-have-key-events` (35), `interactive-supports-focus` (28), `label-has-associated-control` (5), `no-autofocus` (10) | 78 | **comportement** (clavier, focus) | Page par page, rattaché aux ordres de travail des pages (tableau, fiche carte…). Test clavier d'abord (FrontTesteur), puis correctif (FrontCodeur). Pas de lot global. |
| B4 | `perfectionist/sort-objects` (353), `perfectionist/sort-classes` (330) | 683 | **réel** : ordre des initialiseurs de champs qui dépendent d'`inject()` ou d'autres champs, ordre d'évaluation | Chantier dédié, après que les pages concernées ont des tests (phase 3 ou plus tard). Configuration de `sort-classes` d'abord, avec des groupes qui gardent les champs avant le constructeur et respectent les dépendances. Puis correction fichier par fichier, tests unitaires et e2e verts à chaque commit. Jamais `--fix` global. |

Chaque lot abaisse `LINT_MAX_WARNINGS` dans le même commit. La CI affiche déjà une notice quand le compte passe sous le seuil. B1 et B2 peuvent être faits tout de suite ; B3 et B4 attendent les tests des pages.

## 5. Ordre de travail des suites

```
Branche  : fix/phase2-front-recette-suites (depuis main)
Spec     : docs/specs/identity.md §6 (accessibilité) ; SC-ATTACH-010 (visionneuse JSON) pour m1
Endpoints: aucun
1. FrontTesteur — S1 : m5bis, m6 (tests d'abord), m1 (test d'abord) ; commit de référence
2. FrontCodeur  — S2 : m6, m1 ; puis B1 et B2 (ligne de base)
3. FrontReviewer — S3 : contrôles, rapport docs/reviews/<date>-frontend-recette-suites.md
4. FrontPierre   — recette
```

### Étape 1 — FrontTesteur (S1), un commit `test(front): …`

1. **m5bis** : ajouter `http.verify()` à la fin de :
   - `auth.service.spec.ts`, test « SC-IDENT-026 — localStorage indisponible… » (`ctx.http.verify()`) ;
   - `auth.interceptor.spec.ts`, les deux premiers tests SC-IDENT-025.

   Aucun `afterEach` générique. Ces tests doivent rester verts.
2. **m6** :
   - dans `login.component.spec.ts` et `register.component.spec.ts`, lire le message d'erreur dans `[role="alert"]` (C12, C13). Ils sont **rouges** au commit ;
   - en e2e, dans `register.spec.ts` (E02, E03) et `login.spec.ts` (E05), cibler le message avec `getByRole('alert').filter({ hasText: '<texte exact>' })`. Ces tests sont rouges tant que le code n'a pas changé. `tsc -p e2e/tsconfig.json` doit rester vert.
3. **m1** : nouveau `src/app/shared/json-node.component.spec.ts`, avec le titre `SC-ATTACH-010 — visionneuse JSON : bouton de repli et résumé sans espace parasite`. Assertions sur `textContent` **sans `trim`** :
   - `.tog` vaut exactement `▾` (ouvert), puis `▸` après un clic ;
   - `.sum` vaut exactement `6 clés` pour un objet de 6 clés à la profondeur 2 (fermé), et `6 éléments` pour un tableau de 6 éléments à la profondeur 2 (fermé : `open` vaut faux si `depth >= 2` et plus de 5 enfants).

   Ce test est **rouge** au commit.
4. Preuve de sensibilité pour m5bis : déclencher temporairement une requête parasite, constater l'échec de `verify()`, puis restaurer.
5. Vérifier `npx eslint --max-warnings=0` et `npx prettier --check` sur les fichiers touchés.
6. Dans `docs/specs/traceability.md`, ajouter une ligne `↳ composant (visionneuse JSON)` sous SC-ATTACH-010 (`rouge`) ; la ligne existante reste « à écrire » pour le reste du scénario.
7. Donner le hash : c'est la nouvelle référence d'intégrité.

### Étape 2 — FrontCodeur (S2)

1. `fix(front): messages d'erreur annoncés (role="alert") sur /login et /register` : ajouter l'attribut seulement.
2. `fix(front): visionneuse JSON sans espace parasite dans les boutons` : dans `json-node.component.ts` seulement, des `computed` `toggleGlyph()` et `summary()`, ou une interpolation collée aux balises, conforme à Prettier. Aucun changement de style.
3. B1 puis B2 (§4.7), chacun dans un commit séparé, en abaissant `LINT_MAX_WARNINGS` dans `.github/workflows/frontend.yml`, avec l'accord de Manuel indiqué dans le message de commit.
4. Aucun fichier de test, aucune configuration de test. Si un test paraît faux, l'écrire dans le rapport.
5. Avant de rendre : `ng lint` (compte ≤ nouveau seuil), `prettier --check .`, les trois `tsc`, `ng test --watch=false`, `ng build`. L'e2e est vérifié en CI.

### Étape 3 — FrontReviewer (S3)

- Intégrité depuis le commit S1.
- Règle m2 appliquée par fichier et par règle.
- Bundle identique pour B1.
- Rendu de la visionneuse JSON contrôlé (capture ou mesure de largeur dans le navigateur e2e si disponible).
- Lint aligné sur le nouveau seuil.

## 6. Pour Paul

- Lignes front SC-IDENT-001, 002, 003, 007, 008, 017, 018, 025 et 026 : `vert`, preuve CI sur les deux amonts. Elles sont prêtes pour `validé`. La ligne de synthèse IDENT de `traceability.md` indique encore « front : à écrire » : elle est à mettre à jour lors de la validation.
- `identity.md` §6 : l'hypothèse `role="alert"` est retenue (m6), et sera levée après S2.
- Les scénarios front IDENT non couverts en phase 2 (lignes « à écrire » : SC-IDENT-005, 014, 015 et 016) restent hors périmètre de cette recette.
