# Décision utilisateur sur R8 et sortie de phase 1 — identity-service (Paul)

- Date : 27/11/2026
- Auteur : Paul (chef d'équipe, test planner) ; décision prise par l'utilisateur (product owner), consignée à sa demande
- Destinataires : BackAli, Manuel, FrontPierre, utilisateur
- Branche : `chore/identity-r8-rerun` (base `bdaa245`)
- Références :
  - reprise de R8 : `docs/reviews/2026-11-27-identity-r8-rerun.md` (BackAli, Manuel) ;
  - répétition du retour arrière R0–R10 : `docs/reviews/2026-11-26-identity-rollback-rehearsal.md` ;
  - décision de latence **PERF-IDENT-1** : `docs/specs/identity.md` §6.1 et `docs/reviews/2026-11-26-identity-latency-decision-paul.md` ;
  - revue B1 : `docs/reviews/2026-11-26-identity-b1-native-bcrypt.md` (BackReviewer, ACCEPTÉ) ;
  - validations Paul : `docs/reviews/2026-11-26-identity-service-validation-paul.md` (back) et `docs/reviews/2026-11-26-frontend-identity-validation-paul.md` (front) ;
  - bascule et retour arrière : `docs/architecture/identity-migration.md` §5 et §6.
- Ce document ne contient ni secret, ni hash, ni jeton, ni valeur de `.env`, ni donnée personnelle.

## 1. Décision de l'utilisateur (27/11/2026)

Question posée sur le verdict de R8 (M3 invalide pour cause d'hôte chargé). Réponse de l'utilisateur :

> « Pour la R8, pourquoi faire des mesures? Tant que ça fonctionne, on règlera les mesures plus tard »

Confirmation de la consignation : « Oui, consigne-la ».

Contenu de la décision :
1. Les critères de latence **L1 à L4** de PERF-IDENT-1 **ne bloquent plus la bascule**. Ils deviennent un **suivi après la bascule** :
   - mesure des p50 et p95 sur le matériel cible, ou sur un poste au calme ;
   - ajout d'une ligne de journal `INFO` qui nomme le moteur bcrypt actif (constat N2 de R8).
2. **L5** (0 × 5xx, 100 % des codes attendus) **reste exigé**.
3. Le critère PERF-IDENT-1 et son protocole (`identity.md` §6.1) restent **la référence** de la mesure de suivi. Ils ne sont ni supprimés ni assouplis.

Paul n'a pas assoupli le seuil seul : conformément à PERF-IDENT-1 (« aucun assouplissement […] n'est décidé sans lui »), la décision revient à l'utilisateur, qui l'a prise.

## 2. Éléments sur lesquels repose la décision

| Élément | Constat | Source |
|---|---|---|
| M0 stack neuve | 6 services sains, 7 comptes `$2y$`/13 créés par l'API Symfony | R8 §4 |
| M1 gel, reprise, `verify`, routage | `run` et `verify` verts, routage vers `identity:8080` en 13 s | R8 §4 |
| M2 contrôles via identity | 200 / 401 / 401, corps 401 identiques ; moteur natif libxcrypt chargé, aucun repli Java | R8 §4 |
| M3 mesures PERF-IDENT-1 | **invalide** : écart > 5 % entre blocs d'un même amont, à cause de l'hôte (sur batterie, un autre projet Docker à ~325 % CPU). À titre indicatif, ratio p50 de 0,94 à 1,01 (contre 1,32–1,33 au constat B1) | R8 §4, §6 |
| M4 e2e `@identity` contre identity | 14/14 | R8 §4 |
| M5 journaux | 0 fuite ; 0 × 5xx sur 12 800 requêtes mesurées | R8 §4 |
| M6 nettoyage | aucune ressource `taskora-rehearsal` restante ; stack `taskora` intacte | R8 §4 |
| Cause de B1 | corrigée par le moteur natif ; prouvée par `BcryptLatencyIT` (ratio 1,00 à la vérification, 1,01 au hachage, seuil 1,15) | revue B1 §2 |
| Garde en CI | `BcryptLatencyIT` tourne dans `identity-service` (`./mvnw … verify`) sur chaque PR qui touche `services/` ; le job est requis par `ci-gate`, seul check obligatoire de la branche | `.github/workflows/ci.yml`, `identity-service.yml` |
| Retour arrière | routage vers Symfony en ~1 s (1 s le 26/11 ; commutations de 0,9 à 2,2 s le 27/11) | répétition §5 ; R8 §2 |

## 3. Conséquences consignées

| Point | Statut au 27/11/2026 |
|---|---|
| R8 (reprise M0–M6) | **conforme sur le périmètre fonctionnel** (M0, M1, M2, M4, M5, M6). M3 reste consignée « invalide » ; ses critères L1–L4 passent au suivi (§6). |
| Répétition du retour arrière (`identity-migration.md` §6.3) | **réussie** : R1–R7 et R9 du 26/11, R8 repris le 27/11 selon la décision ci-dessus. |
| B1 | **clos** (cause corrigée, revue ACCEPTÉ, garde `BcryptLatencyIT` en CI). |
| C1 | remplie (`identity-security` vert, PR #1). Résumé chiffré OWASP encore à reporter par Manuel (N7). |
| C2 | remplie (`identity-service` et `identity-compat` 32/32 verts, PR #1). |
| C3 | remplie en local le 26/11/2026. **À refaire** sur tout autre environnement où l'ancienne passphrase a servi. |
| C4 | **remplie** : l'utilisateur a confirmé que le job Sonar s'exécute en CI (PR #7). |
| Lignes back IDENT de la matrice | `vert` → **`validé`** (27/11/2026, ce document), comme annoncé par la validation back du 26/11 (§3 et §5) une fois la répétition réussie. |
| Ligne PERF-IDENT-1 de la matrice | banc relatif `BcryptLatencyIT` validé ; L1–L4 de bout en bout : **suivi post-bascule**. |
| Phase 1 (backend d'identité) | **sortie déclarée**. |
| Bascule | **peut être planifiée** (§5). |

## 4. Risque résiduel accepté par l'utilisateur

| Risque | Mitigation |
|---|---|
| Le p95 de la connexion et de l'inscription à travers nginx n'est **pas prouvé** selon le protocole PERF-IDENT-1 (M3 invalide). | 1. Garde en CI : `BcryptLatencyIT` bloque via `ci-gate` toute régression du moteur au-delà de 1,15 × PHP. 2. Retour arrière rapide vers Symfony (~1 s, §6.2 de `identity-migration.md`). 3. Mesure après la bascule selon le protocole PERF-IDENT-1 (§6, point 1). |

## 5. Ce qui est prêt

- identity-service : backend validé (14 critiques couverts et verts, revues sans bloquant, tests intacts), moteur bcrypt natif en service.
- Bascule et retour arrière répétés en recette ; reprise idempotente et `verify` éprouvés.
- Front d'identité validé pour 001, 002, 003, 007, 008, 017, 018, 025, 026 ; e2e `@identity` 14/14 sur les deux amonts.
- Conditions C1 à C4 consignées comme remplies (sous réserve de C3 sur les autres environnements).

### Liste de préparation de la bascule (`identity-migration.md` §5)

| # | Action | Qui | Fait |
|---|---|---|---|
| 1 | Choisir et **annoncer la fenêtre** (coupure des écritures d'identité < 5 min, U4), à T-24 h | utilisateur, BackAli | ☐ |
| 2 | C3 refaite sur l'environnement cible si l'ancienne passphrase y a servi (nouvelle paire, jetons invalidés, annonce) | utilisateur | ☐ |
| 3 | **Sauvegarde `mariadb-dump` de `taskora.user`**, fichier conservé **hors dépôt** | Manuel | ☐ |
| 4 | T-10 min : identity déployé **non routé** ; `verify` à blanc | Manuel, BackAli | ☐ |
| 5 | T0 : **gel** nginx (503 sur `POST /api/auth/register` et `PATCH /api/me` uniquement) | Manuel | ☐ |
| 6 | T0+1 : `--identity.legacy-import=run` puis `verify` **vert** ; rouge → abandon (§6.1), dégel | BackAli | ☐ |
| 7 | T0+2 : bascule de **`IDENTITY_UPSTREAM`** vers `identity:8080`, gel levé | Manuel | ☐ |
| 8 | T0+3 : contrôles à chaud (§5, étape 6) : login, jeton Symfony sur `GET /api/me`, jeton identity sur `GET /api/boards`, inscription d'un invité de recette, `verify` | BackAli | ☐ |
| 9 | T0+5 → T+24 h : **surveillance renforcée** (taux de 401, 5xx, outbox `PENDING` > 1 min) | Manuel, BackAli | ☐ |
| 10 | Retour arrière éventuel (§6.2) : laisser identity démarré au moins 60 s après le dernier enregistrement et jusqu'à `PENDING` = 0 dans l'outbox avant de l'arrêter | Manuel, BackAli | ☐ |

Points d'attention pour la surveillance, issus de la répétition :
- **N2 (répétition)** : le journal d'accès nginx est **perdu à chaque recréation** du conteneur `web` (commutation par `IDENTITY_UPSTREAM`). L'exporter avant toute commutation, ou surveiller depuis une source persistante.
- **N4 (répétition)** : Symfony journalise chaque 4xx (400, 409) en « Uncaught PHP Exception » de niveau **erreur**. Ne pas les compter comme des 5xx ; filtrer par statut HTTP.
- **N3 (répétition, confirmé en R8)** : après un retour arrière, la vidange de l'outbox dépend du rattrapage à +60 s (35 à 38 s le 26/11, 45 à 65 s le 27/11). Laisser identity démarré **au moins 60 s** après le dernier enregistrement, et jusqu'à `PENDING` = 0.

## 6. Suites

| # | Action | Qui | Bloque la bascule |
|---|---|---|---|
| 1 | Mesure de latence selon PERF-IDENT-1 (p50, p95, L1–L5) sur le matériel cible ou un poste au calme, compte rendu chiffré dans `docs/reviews/` | BackAli, Manuel | non (après la bascule, décision du 27/11) |
| 2 | Ligne `INFO` au démarrage nommant le moteur bcrypt actif (R8 N2), test d'abord | BackAli (BackTesteur → BackCodeur → BackReviewer) | non |
| 3 | Mise à jour des ADR A2 et A13 de `backend-hexagonal.md` (moteur natif FFM, `IDENTITY_BCRYPT_ENGINE`, `IDENTITY_BCRYPT_COST`) — revue B1 m1 | BackAli | non |
| 4 | Test de démarrage du contexte : refus si la bibliothèque manque ou si `bcrypt-engine` est inconnu — revue B1 m3 | BackTesteur | non |
| 5 | Résumé chiffré OWASP dans `docs/ci/requests/backend.md` (N7) | Manuel | non |
| 6 | Suites front m1, m5bis, m6 (validation front §5) ; si m6 est fusionné avant la bascule, CI front verte sur les deux amonts avant la bascule | FrontTesteur, FrontCodeur | non |
| 7 | Scénarios front restants SC-IDENT-005, 014, 015, 016 | FrontTesteur, puis validation Paul | non |
