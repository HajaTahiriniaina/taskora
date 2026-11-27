# Revue S3 — suites de la recette front (m5bis, m6, m1, lots B1 et B2)

Auteur : FrontReviewer, le 27/11/2026. Destinataires : FrontPierre (recette, étape 4), Manuel (seuil CI), Paul (traçabilité, `identity.md` §6).

Branche `fix/phase2-front-recette-suites`, base `main` `af1e1aa`, HEAD `7f01bc7`. Ordre de travail : `docs/reviews/2026-11-26-frontend-identity-recette.md` §5, étape 3.

| Commit | Auteur (rôle) | Objet |
|---|---|---|
| `8f634bf` | FrontTesteur (S1) | tests m5bis, m6, m1 — **nouvelle référence d'intégrité** |
| `66a493a` | FrontCodeur (S2) | m6 : `role="alert"` sur `/login` et `/register` |
| `5576a03` | FrontCodeur (S2) | m1 : visionneuse JSON sans espace parasite |
| `3e4a1c3` | FrontCodeur (S2) | lot B1 (`sort-interfaces`, `sort-object-types`), `LINT_MAX_WARNINGS` 904 → 769 |
| `80ba705` | FrontCodeur (S2) | lot B2, `LINT_MAX_WARNINGS` 769 → 761 |
| `7f01bc7` | fil principal | B1 ajouté à `.git-blame-ignore-revs` |

## 1. Verdict

**ACCEPTÉ.** Aucun point bloquant. Quatre points non bloquants (m1 à m4, §4).

## 2. Résultats des outils

Conteneur `node:24-alpine` (Node 24.22.3), `frontend/` monté sur `/app`, `node_modules` déjà présents (pas de `npm ci`). Aucune stack, aucun `docker compose`.

| Contrôle | Résultat |
|---|---|
| `npx ng lint --max-warnings=761` | code de sortie 0 ; **0 erreur, 761 avertissements** (= `LINT_MAX_WARNINGS`) |
| Avertissements par règle (`eslint . -f json`) | `perfectionist/sort-objects` 353 ; `perfectionist/sort-classes` 330 ; `template/click-events-have-key-events` 35 ; `template/interactive-supports-focus` 28 ; `template/no-autofocus` 10 ; `template/label-has-associated-control` 5. Total 761 : il ne reste que les lots B3 (78) et B4 (683). |
| `npx prettier --check .` | conforme (« All matched files use Prettier code style! ») |
| `npx tsc --noEmit` sur `tsconfig.app.json`, `tsconfig.spec.json`, `e2e/tsconfig.json` | 0 erreur sur les trois |
| `npx ng test --watch=false` | 7 fichiers, **17/17 verts** |
| `npx ng build --configuration production` | succès, aucun dépassement de budget, **aucun avertissement CommonJS** (il apparaissait encore dans le build de `5576a03` et de `3e4a1c3`, voir §3.3) |
| `npm audit --omit=dev` | non relancé : aucun `package*.json` modifié sur la branche |
| e2e Playwright | non relancé (consigne). Preuve rapportée par FrontCodeur, voir §3.6 |

## 3. Contrôles

### 3.1 Intégrité des tests

**Depuis la référence S1** (`git diff 8f634bf..HEAD`) : 24 fichiers, **aucun** `*.spec.ts`, aucun fichier `e2e/`, aucun `package*.json`, `tsconfig*.json`, `vitest.*`, `playwright.*` ou `.npmrc`. Seule modification d'`angular.json` : `"allowedCommonJsDependencies": ["sweetalert2"]` dans les options de la cible **`build`** (B2) ; la cible `test` est inchangée.

**S1 n'affaiblit rien par rapport à `main`** (`git diff af1e1aa..8f634bf`) :
- m5bis : trois `http.verify()` ajoutés (`auth.service.spec.ts` « localStorage indisponible », deux premiers SC-IDENT-025 de `auth.interceptor.spec.ts`), sans `afterEach` générique, conformément à la décision ;
- m6, composants : dans SC-IDENT-008 (`login.component.spec.ts`) et SC-IDENT-002 (`register.component.spec.ts`), les cinq lectures du message passent de `.error` à `[role="alert"]` avec le même texte exact : assertion plus exigeante. Les tests de succès SC-IDENT-007 et SC-IDENT-001 (C11, C14) gardent `expect(errorText(root)).toBeNull()` sur `.error` (`login.component.spec.ts:102`, `register.component.spec.ts:112`) ;
- m6, e2e : E02, E03 et E05 passent de `getByText(…, { exact: true }).toBeVisible()` à `getByRole('alert').filter({ hasText })` + `toBeVisible()` + `toHaveText(texte exact)` : renforcé. E05 compte toujours `page.locator('.swal2-container').count()` à 0 (`e2e/login.spec.ts:65`) ;
- m1 : nouveau `json-node.component.spec.ts`, `textContent` comparé **sans `trim`** ;
- aucun `skip`, `only`, `xit`, `fixme` ajouté ; les seules lignes `expect` retirées sont remplacées par les versions plus strictes ci-dessus.

### 3.2 Règle m2 (par fichier et par règle, `af1e1aa` → HEAD)

`eslint --format json` sur les 19 fichiers source modifiés, version de base (avec sa configuration) et version HEAD. **Aucune augmentation**, pour aucun fichier ni aucune règle. Les baisses correspondent exactement aux lots :

| Fichier | Avant → après | Règles qui baissent |
|---|---|---|
| `app.ts` | 2 → 1 | `component-selector` 1 → 0 |
| `core/api.service.ts` | 81 → 44 | `sort-interfaces` 16 → 0, `sort-object-types` 21 → 0 |
| `core/models.ts` | 77 → 17 | `sort-interfaces` 57 → 0, `sort-object-types` 2 → 0, `no-explicit-any` 1 → 0 |
| `core/trello-transfer.service.ts` | 16 → 13 | `sort-interfaces` 2 → 0, `no-explicit-any` 1 → 0 |
| `pages/board/board.component.ts` | 84 → 80 | `sort-interfaces` 2 → 0, `no-unused-vars` 2 → 0 |
| `shared/file-viewer.component.ts` | 47 → 39 | `sort-interfaces` 5 → 0, `sort-object-types` 1 → 0, `no-explicit-any` 1 → 0, `no-irregular-whitespace` 1 → 0 |
| `shared/trello-access.component.ts` | 10 → 9 | `no-explicit-any` 1 → 0 |
| `attachment-uploads.service.ts`, `dialog.service.ts`, `toast.service.ts`, `activity-text.ts`, `board-settings`, `card-format.ts`, `card-modal`, `filter-panel`, `rich-editor` | baisses | `sort-interfaces` / `sort-object-types` seulement |
| `login.component.ts`, `register.component.ts`, `json-node.component.ts` | 5 → 5, 6 → 6, 6 → 6 | inchangés |

Aucun fichier nouveau hors spec. Les avertissements d'accessibilité de template des lignes modifiées (`login`, `register`, `json-node`) : aucun nouveau.

### 3.3 Lot B1 : neutralité

- **Bundle identique** : `ng build --configuration production` sur des copies `git archive` de `5576a03` (avant B1) et de `3e4a1c3` (après), avec les mêmes `node_modules` : 28 fichiers dans `dist/frontend`, **`sha256sum` identiques** fichier par fichier (`diff` vide).
- **Réordonnancement seul** : les 150 lignes retirées et les 150 lignes ajoutées dans `frontend/src` forment le même multiensemble, à l'exception de 20 lignes où l'ordre des membres change à l'intérieur d'un littéral de type sur une seule ligne (`{ name: string; error: string }` → `{ error: string; name: string }`, `http.get<{ … }>`, annotations de champs comme `dueOptions: { … }[] = [`). Ce sont toutes des positions de type ; aucun littéral objet de valeur n'est touché.
- Aucun spec ni e2e dans le commit ; les deux règles passent en `error` ; B1 figure dans `.git-blame-ignore-revs` (`7f01bc7`).

### 3.4 Lot B2 : neutralité de comportement

| Changement | Analyse | Conclusion |
|---|---|---|
| `app-root` → `tk-root` (`app.ts`, `index.html`) | `git grep app-root -- frontend` : plus aucune occurrence (ni CSS, ni sélecteur de test, ni e2e). Seules mentions restantes : rapports historiques dans `docs/reviews/`. Les e2e de toute page (`navigation-guards`, `login`…) amorcent l'application, donc valident le nouveau sélecteur (voir §3.6). | neutre |
| `catch (e: any)` → `catch (e)` + `e as HttpErrorResponse \| undefined` (`trello-transfer.service.ts`, `file-viewer.component.ts`, `trello-access.component.ts`) | La conversion est effacée à la compilation ; les accès restent `?.error?.error` et `?.status` avec chaînage optionnel, donc le même JavaScript. `HttpErrorResponse.error` est `any`, la chaîne compile sans autre conversion. | neutre |
| `customValues: Record<string, any>` → `Record<string, unknown>` (`models.ts`) | Type seulement. Consommateurs : `card-item.component.ts:184` (`formatFieldValue(f, value: unknown)`) et `card-modal.component.ts:358` (`fieldValue()`), utilisé dans le template de la fiche carte. `tsc` et le build passent ; le projet n'active pas `strictTemplates`, donc les liaisons de template (`[href]`, `[value]`) restent vérifiées en mode de base, comme avant avec `any`. | neutre à l'exécution |
| BOM : `/^<U+FEFF littéral>/` → `/^﻿/` (`file-viewer.component.ts`) | `xxd` de la version précédente : octets `EF BB BF` = U+FEFF. L'échappement `﻿` désigne le même point de code. | équivalent |
| `no-unused-vars` avec `ignoreRestSiblings: true` | Voir ci-dessous. | accepté |
| `allowedCommonJsDependencies: ["sweetalert2"]` | Option de la cible `build` : elle ne fait que taire l'avertissement d'optimisation, sans changer le bundle produit par esbuild. | neutre |

**`ignoreRestSiblings` et la règle m2 — décision : accepté.** La règle m2 encadre le **nombre** d'avertissements ; elle ne dit rien des options de règle. Ici, l'option ne masque rien d'autre que les deux cas visés : sur HEAD, avec une configuration temporaire qui force `ignoreRestSiblings: false`, `no-unused-vars` ne signale que `board.component.ts:778` (`listTitle`, `createdBy` dans `const { …, listTitle, createdBy, attachments, ...summary } = detail`). C'est l'idiome standard pour omettre des propriétés d'un objet ; le réécrire (copie puis `delete`, ou préfixe `_` avec `varsIgnorePattern`) serait moins lisible sans gain. L'option est commentée dans `eslint.config.js` et la règle passe en `error`, ce qui est plus strict qu'avant (`warn`). Seule extension : un futur rest sibling inutilisé ne sera pas signalé, ce qui est le comportement voulu.

### 3.5 m1 (visionneuse JSON) et m6 (`role="alert"`)

**m1** (`json-node.component.ts`) :
- `.tog` : `[textContent]="toggleGlyph()"` sur un bouton vide (`></button>`), donc aucun nœud texte de template, aucune espace. Le nom accessible vient toujours de `[attr.aria-label]` (« Replier » / « Déplier »), qui prime sur le contenu ; `aria-expanded` inchangé.
- Sécurité : `textContent` est une propriété DOM texte, jamais interprétée comme du HTML, et la valeur vient d'un `computed` à deux littéraux (`'▾'`, `'▸'`). Aucun `innerHTML`, aucun `bypassSecurityTrust*`.
- `.sum` : `{{ summary() }}` collé aux balises ; le `{{ … }}` du `<span class="p">` est coupé par Prettier, mais les retours à la ligne sont **dans** l'expression, pas dans le texte : aucune espace rendue. Conforme à `frontend.md` §5.3 (pas de `prettier-ignore`, styles inchangés).
- Stabilité Prettier : `prettier --check .` conforme sur HEAD.
- Le test S1 (`textContent` exact `▾`, `▸`, `6 clés`, `6 éléments`) est vert.

**m6** (`login.component.ts:38`, `register.component.ts:37`) : `<div class="error" role="alert">` seul, sans `aria-live`, conformément à la décision et à `identity.md` §6. Pas de double annonce avec les toasts : l'intercepteur ne crée aucun toast pour une URL `/auth/` (`auth.interceptor.ts`, branches `!isAuthCall`), et les composants n'en créent pas ; E05 le vérifie (0 `.swal2-container`). Les autres régions vivantes de l'application (`trello-access` `role="alert"`, `board` et `rich-editor` en `polite`) ne sont pas sur ces pages. L'icône `tk-icon` reste `aria-hidden` : seul le texte est annoncé.

### 3.6 e2e (preuve rapportée, non relancée)

FrontCodeur rapporte, sur une stack jetable : 11/14 rouges sur `8f634bf` (avant S2) et **14/14 verts** après S2 sur les deux amonts (`api:80` et `identity:8080`). Je ne l'ai pas reproduit (consigne : pas de stack). Le rouge attendu au commit de référence est cohérent avec les tests modifiés (E02, E03, E05 dépendent de `role="alert"`). La preuve opposable reste le job CI `frontend-e2e-identity` de la PR.

## 4. Points

### Bloquants

Aucun.

### Non bloquants

- **m1 — accord de Manuel absent des messages de commit** (Manuel, FrontPierre). La recette (§5, étape 2.3) demande que la baisse de `LINT_MAX_WARNINGS` (`3e4a1c3` : 904 → 769 ; `80ba705` : 769 → 761) porte l'accord de Manuel dans le message de commit. Il n'y est pas. Non bloquant : le seuil ne fait que baisser, ce qui est exactement la demande CI (`docs/ci/requests/frontend.md`, `frontend.md` §5.2), et il est égal au compte mesuré (761). Action : Manuel donne son accord explicite à la recette (ou dans la PR) ; aucune réécriture d'historique demandée.
- **m2 — traçabilité restée à « rouge »** (Paul, FrontTesteur). Les lignes ajoutées en S1 dans `docs/specs/traceability.md` (SC-IDENT-002, 003, 008 en composant et e2e ; composant SC-ATTACH-010) indiquent encore « rouge (attendu …) », alors qu'elles sont vertes depuis `66a493a` et `5576a03`. À mettre à jour lors de la validation, avec la levée de l'hypothèse `role="alert"` dans `identity.md` §6 (le code la satisfait désormais).
- **m3 — rendu de la visionneuse JSON non mesuré en navigateur** (FrontTesteur). L'ordre de travail demandait une capture ou une mesure de largeur « si disponible ». Sans stack, je ne l'ai pas fait. Le test de composant garantit l'absence d'espace dans `textContent`, ce qui est la cause du débordement ; le contrôle visuel reviendra avec l'e2e SC-ATTACH-010 (ligne « à écrire »).
- **m4 — `strictTemplates` désactivé** (FrontPierre, pour information). Le passage de `customValues` à `unknown` n'est vérifié que côté TypeScript ; les liaisons du template de la fiche carte restent en vérification de base. Sans effet sur cette revue ; à considérer avant ou pendant le lot B4.

## 5. Synthèse pour FrontPierre

- Intégrité : rien de touché aux tests depuis `8f634bf` ; S1 ne fait que renforcer.
- m5bis, m6, m1 : conformes aux décisions de la recette.
- B1 : bundle identique octet pour octet, réordonnancement de types seul.
- B2 : huit corrections neutres ; avertissement CommonJS supprimé ; règles passées en `error`.
- Lint : 761 avertissements = nouveau seuil ; restent B3 (78, accessibilité) et B4 (683, tri des objets et classes).
