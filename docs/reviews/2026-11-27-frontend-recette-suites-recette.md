# Recette front — suites de la recette phase 2 (S4)

Auteur : FrontPierre, le 27/11/2026. Destinataires : Paul (validation), Manuel (seuil de lint, m1), FrontTesteur, FrontCodeur, FrontReviewer (suites).
Ordre de travail contrôlé : `docs/reviews/2026-11-26-frontend-identity-recette.md` §5 (S1 à S3). Revue : `docs/reviews/2026-11-27-frontend-recette-suites.md` (FrontReviewer, ACCEPTÉ, m1 à m4 non bloquants).
Branche `fix/phase2-front-recette-suites`, base `main` `af1e1aa`, recette sur `c431a21`. Documentation seule : je n'ai relancé ni outil, ni test, ni stack ; la recette s'appuie sur les preuves de la revue et du rapport de FrontCodeur.

## 1. Verdict

**ACCEPTÉ.**

- Les trois étapes de l'ordre de travail sont livrées, dans l'ordre prévu (test d'abord, puis code, puis revue) : §2.
- L'intégrité des tests est respectée : aucun fichier de test ni de configuration de test modifié depuis la référence S1 `8f634bf`, et S1 ne fait que renforcer les tests par rapport à `main` (revue §3.1).
- Les décisions de la recette du 26/11 sont appliquées telles quelles (m5bis, m6, m1) ; B1 est neutre (bundle identique), B2 est neutre en comportement.
- Aucun point bloquant ; m1 à m4 de la revue sont tranchés en §3.

La branche peut être proposée en PR. Elle n'est **pas poussée** : le push attend l'accord de l'utilisateur. La preuve opposable de l'e2e reste le job CI `frontend-e2e-identity` de cette PR, sur les deux amonts.

## 2. Contrôle de l'ordre de travail

| Étape | Attendu (recette §5) | Livré | Preuve | Conforme |
|---|---|---|---|---|
| S1 FrontTesteur | m5bis : `verify()` dans 3 tests, sans `afterEach` générique | `8f634bf` | revue §3.1 | oui |
| S1 FrontTesteur | m6 : C12/C13 lus dans `[role="alert"]`, E02/E03/E05 par `getByRole('alert').filter({ hasText })`, rouges au commit | `8f634bf` | revue §3.1 ; 11/14 e2e rouges sur `8f634bf` (rapport FrontCodeur) | oui |
| S1 FrontTesteur | m1 : `json-node.component.spec.ts`, `textContent` sans `trim`, rouge au commit | `8f634bf` | revue §3.1 et §3.5 | oui |
| S1 FrontTesteur | ligne de traçabilité SC-ATTACH-010 (composant) | `8f634bf`, passée à vert par `c431a21` | `docs/specs/traceability.md` | oui |
| S2 FrontCodeur | m6 : attribut seul | `66a493a` | revue §3.5 : `role="alert"` sans `aria-live`, pas de double annonce (E05 : 0 toast) | oui |
| S2 FrontCodeur | m1 : `json-node.component.ts` seul, `computed`, conforme à Prettier, sans `prettier-ignore` | `5576a03` | revue §3.5 : `[textContent]="toggleGlyph()"`, `{{ summary() }}` collé, styles inchangés | oui |
| S2 FrontCodeur | B1 puis B2, commits séparés, seuil abaissé dans le même commit | `3e4a1c3` (904 → 769), `80ba705` (769 → 761) ; B1 dans `.git-blame-ignore-revs` (`7f01bc7`) | revue §3.3, §3.4 | oui, **sauf** la mention de l'accord de Manuel dans le message (m1, §3) |
| S2 FrontCodeur | aucun fichier de test ni de configuration de test | — | revue §3.1 : seul `allowedCommonJsDependencies` dans la cible `build` d'`angular.json` | oui |
| S3 FrontReviewer | intégrité, règle m2 par fichier et par règle, bundle B1, rendu JSON, seuil | `b50ca9a` | revue §2–§3 | oui, **sauf** le rendu JSON en navigateur (m3, §3) |

### Preuves retenues

| Contrôle | Résultat | Source |
|---|---|---|
| Tests unitaires et de composants | 7 fichiers, **17/17 verts** (16 + le nouveau test de la visionneuse JSON) | revue §2 |
| Lint | 0 erreur, **761 avertissements** = `LINT_MAX_WARNINGS` (`.github/workflows/frontend.yml:43`) ; il ne reste que B3 (78) et B4 (683) | revue §2 |
| Règle m2 | aucune hausse, pour aucun fichier ni aucune règle ; les baisses correspondent aux lots | revue §3.2 |
| Prettier, trois `tsc` | conformes, 0 erreur | revue §2 |
| Build de production | succès, budgets respectés, avertissement CommonJS supprimé | revue §2 |
| B1 | **bundle identique** octet pour octet (28 fichiers, `sha256sum`) entre `5576a03` et `3e4a1c3` | revue §3.3 |
| e2e | 11/14 rouges sur `8f634bf`, **14/14 verts** après S2 sur `api:80` et `identity:8080` (stack jetable) | rapport FrontCodeur, revue §3.6 ; non reproduit, la CI de la PR fait foi |

### Traçabilité et spécification (point m2 de la revue)

- `c431a21` passe à « vert après S2 » les six lignes front ajoutées en S1 (SC-IDENT-002, 003, 008 en composant et en e2e) et la ligne composant de SC-ATTACH-010. Conforme.
- `identity.md` §6 (ligne 386) : **plus aucune hypothèse** sur `role="alert"`. Le texte porte la **décision** de Paul du 26/11 (`<div class="error" role="alert">`, sans `aria-live`). Il reste une formulation datée : « absent du code au 26/11/2026, ajouté par la suite S1/S2 ». Ce n'est pas une hypothèse, mais elle est périmée : je demande à Paul, lors de sa validation, de la remplacer par « en place depuis `66a493a` (27/11/2026) ». Je ne modifie pas `identity.md`, qui appartient à Paul.

## 3. Décisions sur les points non bloquants de la revue

### m1 — accord de Manuel sur les baisses de `LINT_MAX_WARNINGS` : **accepté sans réécriture ; règle précisée**

Constat : les messages de `3e4a1c3` et `80ba705` donnent le compte (904 → 769 → 761) mais pas l'accord de Manuel, que ma recette demandait.

Position de FrontPierre :
- La règle permanente de l'utilisateur est que **les seuils ne font que baisser**. Une baisse du seuil jusqu'au compte mesuré n'est donc pas une décision, mais l'application de cette règle ; la CI la réclame déjà par une notice. Exiger un accord au cas par cas n'apporte rien et crée exactement l'écart constaté ici.
- Nouvelle règle (`frontend.md` §5.2) :
  - **baisse** jusqu'au compte mesuré : faite par FrontCodeur dans le commit qui réduit le compte, avec le compte mesuré dans le message ; Manuel en est informé par la PR, sans accord préalable ;
  - **hausse** : interdite, sans exception hors décision de l'utilisateur ;
  - changement d'**option de règle** qui fait disparaître des avertissements sans corriger le code (cas d'`ignoreRestSiblings` en B2, accepté par la revue §3.4) : accord explicite de Manuel et de FrontPierre, écrit dans la PR.
- Aucune réécriture d'historique : 761 est égal au compte mesuré par la revue.

Action **Manuel** : confirmer cette position dans `docs/ci/requests/frontend.md` §8, et y mettre à jour la valeur du seuil, qui indique encore 904 (ligne du job `frontend-quality`). Tant que la confirmation n'est pas écrite, son accord est demandé dans la PR de cette branche (seuil 761).

### m2 — traçabilité restée à « rouge » : **traité** par `c431a21` (§2). Reste la formulation datée d'`identity.md` §6 (Paul).

### m3 — rendu de la visionneuse JSON non contrôlé en navigateur : **accepté ; contrôle planifié avec l'e2e SC-ATTACH-010**

Le test de composant garantit qu'aucune espace n'entoure le glyphe et le résumé, ce qui était la cause du débordement : le risque restant est faible. Le contrôle visuel n'exige pas de stack aujourd'hui et ne justifie pas un e2e isolé sans les endpoints de pièces jointes.

Plan : dans l'e2e SC-ATTACH-010 (§4, étape 5), le cas JSON contient, en plus du parcours :
- assertion de mise en page sur le bouton `.tog` : `scrollWidth <= clientWidth` (aucun débordement), lue par `evaluate` sur l'élément trouvé par `getByRole('button', { name: 'Replier' })` ;
- même assertion sur le bouton de résumé `.sum` d'un nœud fermé ;
- capture d'écran seulement en cas d'échec (configuration actuelle), pas de comparaison de captures en phase 2.

Responsable : FrontTesteur, dans l'ordre de travail SC-ATTACH-010.

### m4 — `strictTemplates` désactivé : **activé dans un lot dédié ST, avant B4**

Constat (`frontend/tsconfig.json`) : `angularCompilerOptions` ne contient que `strictInjectionParameters` et `strictInputAccessModifiers`, et `compilerOptions` n'active pas `strict`. Les liaisons de template ne sont vérifiées qu'en mode de base ; le passage de `customValues` à `Record<string, unknown>` (B2) n'est vérifié que côté TypeScript.

Décision :
- **ST avant B4**. B4 réordonne les membres des classes, dont beaucoup sont lus par les templates ; on veut que le compilateur vérifie ces liaisons pendant ce chantier. ST passe après les scénarios d'identité et B3 sur les pages d'identité, qui touchent peu de templates.
- Méthode (`frontend.md` §5.4) :
  1. FrontCodeur mesure d'abord, sur une branche jetable, les erreurs de compilation par fichier avec `strictTemplates: true`, ainsi que le compte `tsc --strict --noEmit` sur `tsconfig.app.json` (mesure seulement) ;
  2. corrections de types seulement : annotations, gardes de type, `computed` typés. Pas de `$any()`, pas de `// @ts-ignore`. Le drapeau est activé dans le même commit, car une erreur de template bloque le build et ne peut pas aller dans une ligne de base ;
  3. au-delà de 40 erreurs, découpage page par page, corrections d'abord et drapeau en dernier ;
  4. bundle de production comparé avant et après, avec chaque écart expliqué ; tests unitaires, build et e2e verts ;
  5. FrontReviewer vérifie l'absence de contournement (`$any`, `!` non justifié, `@ts-ignore`) et la neutralité du bundle.
- Le passage de TypeScript en `strict` n'est pas décidé ici. Il sera tranché à la recette de ST, sur la mesure.

## 4. Suites front : ordre, responsables

Chaque étape suit le circuit habituel : FrontTesteur (tests rouges, commit de référence), puis FrontCodeur (sans toucher aux tests), puis FrontReviewer (rapport), puis recette FrontPierre et validation Paul. Une branche par étape.

| Ordre | Travail | Responsables | Préalable | Contenu |
|---:|---|---|---|---|
| 0 | PR de cette branche | utilisateur (push), Manuel (accord m1 dans la PR ou §8) | accord de l'utilisateur | CI verte, dont `frontend-e2e-identity` sur les deux amonts ; Paul passe les lignes à `validé` et corrige la formulation d'`identity.md` §6 |
| 1 | Scénarios IDENT front de la page Profil : **SC-IDENT-014** (composant `AuthService.refreshProfile`), **SC-IDENT-015** et **SC-IDENT-016** (e2e page Profil) | FrontTesteur, puis FrontCodeur si un test est rouge, FrontReviewer | aucun : `GET`/`PATCH /api/me` sont dans `identity-service.yaml` | e2e sur les deux amonts (`@identity`) ; SC-IDENT-016 : la limite « valeur invalide » diffère entre Symfony (200, ignorée) et identity-service (400, décision E18) : le test e2e ne couvre que le cas nominal par l'UI, la limite reste en API côté back |
| 2 | **B3 sur les pages d'identité** (`/login`, `/register`, `/profile`, coquille) | FrontTesteur (test clavier d'abord : Tab, Entrée, Espace, focus visible), puis FrontCodeur | étape 1 (tests de la page Profil en place) | ne corriger que les avertissements B3 de ces pages ; `LINT_MAX_WARNINGS` abaissé au compte mesuré dans le même commit (règle m1) |
| 3 | **SC-IDENT-005** front (e2e : inscription puis tableau visible sur `/boards`) | FrontPierre (demande de contrat), BackAli, puis FrontTesteur | **demande `docs/contracts/requests/001-…`** : la création d'un tableau et l'invitation par e-mail, nécessaires pour préparer les données, ne sont dans aucun contrat OpenAPI | je l'ouvre au lancement de l'étape ; aucun test contre un endpoint non accepté |
| 4 | **ST** (`strictTemplates`) | FrontCodeur, FrontReviewer | étapes 1–2 | §3, m4 |
| 5 | **SC-ATTACH-010** e2e (visionneuses), avec le contrôle de rendu de la visionneuse JSON (m3) | FrontTesteur, puis FrontCodeur | contrat des pièces jointes accepté (phase attachment-service de Paul) ; jeux de fichiers de test sans donnée réelle | `scrollWidth <= clientWidth` sur `.tog` et `.sum` ; « aucun script exécuté dans l'e-mail » |
| 6 | **B3 sur les autres pages** (tableau, fiche carte, tableau de bord, partage Trello…) | FrontTesteur puis FrontCodeur, page par page | ordre de travail de chaque page, avec ses tests | même méthode qu'à l'étape 2 ; jamais de lot global |
| 7 | **B4** (`sort-objects`, `sort-classes`) | FrontCodeur, FrontReviewer | ST fait ; tests des pages concernées en place | configuration des groupes de `sort-classes` d'abord ; fichier par fichier ; jamais `--fix` global ; les deux règles passent en erreur à la fin, seuil abaissé à chaque commit |

Les étapes 1 à 4 ne dépendent d'aucune autre équipe, sauf la demande de contrat de l'étape 3. Les étapes 5 à 7 suivent le calendrier des pages de Paul.

## 5. Pour Paul

- Les lignes front SC-IDENT-002, 003 et 008 (composant et e2e, suite m6) et la ligne composant de SC-ATTACH-010 sont vertes : elles sont prêtes pour `validé` après la CI de la PR.
- `identity.md` §6 : plus d'hypothèse `role="alert"` ; formulation « absent du code au 26/11/2026 » à remplacer par « en place depuis `66a493a` ».
- Scénarios front IDENT restant à écrire : SC-IDENT-005 (contrat à demander), 014, 015 et 016 (étapes 1 et 3).
