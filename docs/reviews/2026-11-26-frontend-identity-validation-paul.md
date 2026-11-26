# Validation Paul — phase 2 front : identité (étape 7)

Auteur : Paul, le 26/11/2026. Destinataires : FrontPierre, FrontTesteur, Manuel, BackAli, utilisateur.
Base : `main` à `531cbf2` (PR #3 fusionnée), branche `chore/phase2-front-recette` à `48d8e92` (recette FrontPierre). Le dossier `frontend/` est identique entre `531cbf2` et `48d8e92`.
Entrées : `docs/specs/identity.md`, `docs/specs/pages.md`, `docs/specs/traceability.md`, revue `docs/reviews/2026-11-26-frontend-identity.md` (FrontReviewer, ACCEPTÉ), recette `docs/reviews/2026-11-26-frontend-identity-recette.md` (FrontPierre, ACCEPTÉ), validation back `docs/reviews/2026-11-26-identity-service-validation-paul.md`.

Mode sans l'outil Agent : aucun sous-agent lancé. Les suites sont rendues comme plan ordonné (§6).

## 1. Verdict : **VALIDÉ** (front d'identité, phase 2, périmètre 001, 002, 003, 007, 008, 017, 018, 025, 026)

Les quatre conditions de validation sont réunies :

| Condition | Constat |
|---|---|
| Chaque scénario critique du périmètre couvert par au moins un test qui passe | 001, 002, 003, 007, 008, 017, 018 : e2e et composant verts (§2). CI GitHub verte au premier run, e2e `@identity` 14/14 sur les deux amonts (Symfony `api:80`, identity `identity:8080`). Unitaires rejoués localement : 16/16 (§3). |
| Aucun point bloquant dans les rapports | Revue FrontReviewer : 0 bloquant (m1–m9 non bloquants). Recette FrontPierre : ACCEPTÉ. |
| Aucun test modifié ni affaibli après le commit des tests | `git diff 1bd9766..HEAD` sur `frontend/**/*.spec.ts` et `frontend/e2e/**` : 3 fichiers, +6 lignes, toutes dans `912bd0b` (`HttpTestingController.verify()`), donc un renforcement. Aucune ligne retirée. Configuration de test (`playwright.config.*`, `vitest.config.*`, `tsconfig.spec.json`) inchangée. |
| Matrice de traçabilité à jour | Faite dans ce commit (§4). |

## 2. Contrôle scénario par scénario

Méthode : pour chaque ligne front de la matrice, le titre du test existe mot pour mot dans le fichier cité, et chaque limite de `identity.md` qui relève du front est assertée. Les limites purement serveur (concurrence, hash en base, temps de réponse, journaux) restent couvertes par les lignes back, déjà validées sur le fond.

| Scénario | Prio. | Titres trouvés | Limites du front assertées | Constat |
|---|---|---|---|---|
| SC-IDENT-001 | critique | `register.spec.ts` (e2e) ; `auth.service.spec.ts`, `register.component.spec.ts` (composant) | 201 ; corps sans `password` (texte brut, insensible à la casse) ; clés `user` exactes `{id,name,email,avatarColor}` ; `trim` du nom et e-mail en minuscules, dans la réponse **et** dans `taskora.user` ; couleur dans la palette ; redirection `/boards` ; jeton accepté par `GET /api/me` | conforme |
| SC-IDENT-002 | critique | `register.spec.ts` ; `register.component.spec.ts` | 409 avec un e-mail dans une autre casse ; message exact sous le formulaire ; reste sur `/register` ; aucun jeton ; repli « Inscription impossible pour le moment. » ; bouton réactivé | conforme. Concurrence et « une seule ligne en base » : back. |
| SC-IDENT-003 | critique | `register.component.spec.ts` ; `register.spec.ts` | bouton désactivé : formulaire vide, nom vide, e-mail vide ou `pas-un-email`, mot de passe vide, 7 caractères, `ééééééé` ; activé à 8 et à `éééééééé` (comptage Unicode) ; e2e : nom d'espaces → 400 et message exact de l'API | conforme. Les autres messages 400 sont des cas API (back), conformément à la stratégie de validation. |
| SC-IDENT-007 | critique | `login.spec.ts` ; `auth.service.spec.ts`, `login.component.spec.ts` | e-mail en majuscules accepté ; `/boards` et menu utilisateur visibles ; `taskora.token` et `taskora.user` stockés ; jeton accepté par `GET /api/me` ; corps de requête transmis tel que saisi (pas de normalisation côté front) | conforme. L'acceptation croisée des jetons entre les deux backends reste prouvée par `CrossTokenCompatibilityTest` (back, SC-IDENT-010) ; l'e2e la complète en jouant la même suite contre chaque amont. |
| SC-IDENT-008 | critique | `login.spec.ts` ; `login.component.spec.ts` | 401 pour mauvais mot de passe **et** e-mail inconnu ; corps 401 identiques ; message exact ; aucun toast, en particulier pas celui de session ; reste sur `/login` ; pas de jeton ; autre erreur et erreur réseau (statut 0) → « Impossible de se connecter pour le moment. » | conforme. Oracle temporel et journaux : back. |
| SC-IDENT-017 | critique | `profile.spec.ts` ; `profile.component.spec.ts` | `PATCH /api/me {currentPassword,newPassword}` ; toast « Mot de passe modifié » ; deux champs vidés (longueur seulement, pas de valeur dans les rapports) ; nouveau mot de passe 200, ancien 401 ; bouton désactivé après succès | conforme (après le correctif `bb103eb`, prévu par l'ordre de travail). |
| SC-IDENT-018 | critique | `profile.component.spec.ts` ; `profile.spec.ts` | bouton désactivé : champs vides, actuel seul, nouveau de 7, nouveau de 8 sans actuel ; activé avec actuel et nouveau de 8 ; e2e : actuel faux → 400, toast « Mot de passe actuel incorrect. », toujours connecté, ancien 200 et nouveau 401 (mot de passe inchangé) | conforme. Cas `null`, `""`, `"0"`, `currentPassword` absent : API (back). |
| SC-IDENT-025 | important | `auth.interceptor.spec.ts` (3 tests) ; `session.spec.ts` | 401 hors `/auth/` : jeton et profil supprimés, `/login`, toast de session une seule fois ; 401 sur `/api/auth/login` : ni déconnexion ni toast ; `Authorization` ajouté hors `/auth/`, jamais sur `/auth/` ; e2e : signature altérée (exp valide, donc c'est l'API qui refuse), `/login`, toast, jeton supprimé | conforme |
| SC-IDENT-026 | important | `auth.guard.spec.ts` (2) ; `auth.service.spec.ts` (2) ; `navigation-guards.spec.ts` (paramétré) | anonyme sur `/boards`, `/boards/12`, `/profile` → `/login` **sans aucun appel API** (c'est bien la garde et non l'intercepteur) ; jeton expiré ou illisible → `/login` ; connecté sur `/`, `/login`, `/register` → `/boards` ; route inconnue → `/` ; `localStorage` indisponible → aucune exception, non connecté | conforme |

Correspondance avec `pages.md` : états de `/login` (§2 : 401, autre erreur, succès), de `/register` (§3 : message de l'API, repli, succès, `minlength=8`), de la coquille (§4 : 401 hors `/auth/`), de `/profile` section Sécurité (§7 : bouton désactivé, toasts, erreur 400 en toast) et des redirections (§1, §8) : tous couverts. Non couverts et hors périmètre de la phase 2 : spinner de chargement, jauge de robustesse, bouton Afficher/Masquer (aucun scénario ne les porte).

Observations non bloquantes (pas de renvoi au Testeur) :
- L'e2e SC-IDENT-002 et SC-IDENT-008 cible le message par texte et non par rôle : il deviendra plus exigeant avec la suite m6 (`getByRole('alert')`), déjà planifiée.
- Le test « localStorage indisponible » et les deux premiers tests de l'intercepteur n'appellent pas `verify()` : suite m5bis, déjà planifiée.

## 3. Contrôles effectués

| Contrôle | Résultat |
|---|---|
| `git diff --quiet 531cbf2 48d8e92 -- frontend` | identique : la recette n'a touché que la documentation |
| `docker run node:24-alpine … npx ng test --watch=false` | 6 fichiers, **16/16 verts** |
| Titres des tests contre la matrice (`grep` des `it(` et `test(`) | 18 lignes front, tous les titres trouvés ; seuls les tests paramétrés de `navigation-guards.spec.ts` ajoutent `[<url>]` ou `[<cas>]`, comme déjà noté dans la matrice |
| Intégrité depuis `1bd9766` | +6 lignes `verify()` (`912bd0b`), 0 suppression |
| e2e | non relancé (consigne) ; preuve : job `frontend-e2e-identity` vert sur les deux amonts au premier run |

## 4. Mises à jour de la spécification et de la matrice

- `traceability.md` :
  - 18 lignes front passent de `vert` à `validé` (référence à ce rapport, la preuve `vert` est conservée) : SC-IDENT-001 (2 lignes), 002 (2), 003 (2), 007 (2), 008 (2), 017 (2), 018 (2), 025 (2), 026 (2) ;
  - ligne de synthèse IDENT corrigée : « back : vert (`validé` après la répétition R0–R10) ; front : `validé` pour 001, 002, 003, 007, 008, 017, 018, 025, 026 ; à écrire pour 005, 014, 015, 016 » ;
  - note d'en-tête complétée. Les lignes back restent `vert` : leur passage à `validé` coïncide avec la répétition du retour arrière (décision de la validation back, §3).
- `identity.md` §6 : `role="alert"` n'est plus une hypothèse mais une **décision** : `<div class="error" role="alert">` sur `/login` et `/register`, sans `aria-live` supplémentaire. Le code ne le porte pas encore ; il arrive avec la suite S1/S2 (test d'abord).
- `pages.md` §2 et §3 : états d'erreur complétés par « message dans un élément `role="alert"` ».

## 5. Scénarios front non couverts : renvoi à FrontTesteur

Ces lignes restent `à écrire` et sont confiées à FrontTesteur (via FrontPierre), **hors périmètre de cette validation** :

| Scénario | Prio. | Test attendu | Remarque |
|---|---|---|---|
| SC-IDENT-005 | **critique** | e2e : inscription d'un invité, puis tableau visible sur `/boards` | Nécessite un tableau et une invitation préparés par l'API (Symfony tant que board-service n'existe pas). Fixture e2e à concevoir : propriétaire créé par l'API, `POST` d'invitation, puis inscription par l'UI. |
| SC-IDENT-014 | critique | composant `AuthService.refreshProfile` | Lecture de `GET /api/me` au démarrage et mise à jour de `taskora.user`. |
| SC-IDENT-015 | important | e2e page Profil : modifier son nom | Inclure nom vide (bouton ou message) et la borne de 120. |
| SC-IDENT-016 | secondaire | e2e page Profil : couleur d'avatar | — |

Le back de ces quatre scénarios est couvert et vert. SC-IDENT-005 et 014 sont critiques : leurs lignes front doivent être `validé` avant la fin de la phase 2 front, mais **pas avant la bascule** (voir §6, raisonnement).

## 6. Préparation de la bascule de l'authentification vers identity-service

### Fait
| Élément | État |
|---|---|
| C1 (OWASP), C2 | remplies (validation back §4) |
| Backend identity-service | VALIDÉ sur le fond (validation back) |
| Filet e2e front de non-régression | **fait** : 14 e2e `@identity` verts contre Symfony **et** contre identity, en CI ; c'était l'objectif de l'option A de la phase 2 |
| Scénarios importants 025 et 026 (seuls importants sans test avant la phase 2) | couverts et `validé` |
| Proxy de dev vers identity | livré (`91350c8`), limites documentées (m9) |

### Reste à faire
| Élément | Responsable | Bloque la bascule ? |
|---|---|---|
| **C3** : régénérer la paire de clés JWT avec une nouvelle passphrase, annoncer l'invalidation des jetons, puis relancer `identity-compat` (`CrossTokenCompatibilityTest` vert) | **utilisateur** | **oui** |
| **C4** : variable `SONAR_HOST_URL` et secret `SONAR_TOKEN` sur GitHub, puis quality gate vert | **utilisateur**, puis Manuel | **oui** |
| Répétition du retour arrière R0–R10 (validation back §5), après C3 | BackAli et Manuel ; Paul consigne | **oui** |
| Après la répétition : lignes back IDENT → `validé` | Paul | oui (critère « critiques `validé` avant la bascule ») |
| `ci-gate`, check requis unique, préparé par Manuel sur `ci/ci-gate` (`7aa6bcb`, non fusionné) | Manuel, puis l'utilisateur pour la protection de branche | **non** au sens strict, mais **recommandé avant** : sans lui, rien n'empêche de fusionner sur `main` une PR qui casserait l'e2e `@identity`, c'est-à-dire le filet de la bascule |
| m6 (`role="alert"`) | FrontTesteur puis FrontCodeur | **non** : accessibilité, sans effet sur le routage ni sur l'authentification |
| m5bis (`verify()` par test) | FrontTesteur | **non** : renforcement de tests existants, déjà verts |
| m1 (visionneuse JSON) | FrontTesteur puis FrontCodeur | **non** : sans rapport avec l'identité (SC-ATTACH-010) |
| Ligne de base ESLint B1–B4 | FrontCodeur | **non** |
| N2 (digests des images), N7 (résumé OWASP) | Manuel | non (recommandé) |
| Front 005, 014, 015, 016 | FrontTesteur | **non** : le comportement est inchangé à la bascule (même contrat, couvert côté back, y compris en `compat`). Pour SC-IDENT-005, la répétition R3 et R6 contrôlent déjà l'adhésion de l'invité de bout en bout. |

Une précaution s'ajoute : si S1/S2 (m6) est fusionné **avant** la bascule, la CI front doit être de nouveau verte sur les deux amonts avant la bascule, puisque S1 modifie des e2e d'identité.

## 7. Prochaines étapes, dans l'ordre

1. **Utilisateur** : C3 (nouvelle paire de clés JWT), puis C4 (Sonar). Accord pour pousser `chore/phase2-front-recette` (recette et cette validation).
2. **Manuel** : finaliser `ci-gate` (`ci/ci-gate`) et la PR associée ; après fusion, l'utilisateur déclare `ci-gate` comme unique check requis sur `main`. Puis N2 et N7, et le run Sonar après C4.
3. **BackAli et Manuel** : répétition R0–R10 sur la stack `taskora-rehearsal`, avec la nouvelle paire de clés. En R2 et R8, rejouer en plus l'e2e `@identity` contre la stack de répétition si elle est exposée via nginx.
4. **Paul** : consigner la répétition ; lignes back IDENT → `validé` ; annoncer la bascule si C1–C4 et la répétition sont consignées.
5. **Bascule** de `/api/auth/` et `/api/me` vers identity-service, l'e2e `@identity` vert contre identity en condition de sortie.
6. **En parallèle des étapes 2 à 5, côté front** (branche `fix/phase2-front-recette-suites`) : S1 (FrontTesteur : m5bis, m6, m1, tests d'abord), S2 (FrontCodeur : m6, m1, puis B1 et B2), S3 (FrontReviewer), recette FrontPierre, puis Paul met à jour la matrice (ligne `↳ composant` de SC-ATTACH-010).
7. **Front d'identité restant** : FrontTesteur écrit 014, 015, 016, puis 005 (fixture d'invitation) ; même circuit ; Paul valide.
8. **Phase 3 : board-service**. Paul complète `boards-members.md` (reprise des invitations, fin de l'endpoint transitoire, non-régression P3) ; BackAli publie le contrat et l'ordre de travail.
