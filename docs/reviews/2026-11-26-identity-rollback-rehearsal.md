# Répétition du retour arrière d'identity-service (R0–R10)

- Date : 26/11/2026, 16 h 15 – 16 h 40 (UTC)
- Exécution : BackAli (étapes R0–R9) et Manuel (stack, nginx, nettoyage)
- Référence : `main` `934dd2c` ; branche `chore/identity-rollback-rehearsal`
- Plan suivi : validation Paul `2026-11-26-identity-service-validation-paul.md` §5 (R0–R10) ; procédure `docs/architecture/identity-migration.md` §3, §5, §6 (§6.3)
- Ce rapport ne contient aucun secret, hash, jeton, valeur de `.env` ni donnée personnelle. Il ne donne que des comptages, des statuts et des empreintes CRC32.

## 1. Verdict : **ÉCHOUÉE**

Le critère de réussite du plan est « R1–R9 tous conformes ». R1 à R7 et R9 sont conformes. **R8 ne l'est pas** :
- `POST /api/auth/login` via identity a un p95 de **641,6 ms**, puis **643,5 ms** au second échantillon ;
- le seuil est **< 500 ms** (identity.md §6, P2) ;
- Symfony, mesuré sur la même machine et la même stack, est à 498,3 ms.

Le reste est conforme, y compris tout ce qui concerne le retour arrière :
- les deux bascules et les deux retours arrière ;
- `verify` toujours vert après une bascule ;
- aucune perte de compte, de mot de passe ni de message d'outbox ;
- les e2e `@identity` sont verts sur les deux amonts (14/14 à chaque passage) ;
- aucune fuite dans les journaux.

Le mécanisme de retour arrière est donc démontré. L'écart porte sur la performance de connexion. Il revient à BackAli (§7, B1) avant une nouvelle répétition, limitée au moins à R8.

## 2. Environnement

| Élément | Valeur |
|---|---|
| Hôte | Apple M1, 8 cœurs, Docker Desktop 27.3.1 (8 CPU, 7,6 Gio), Compose v2.29.7 ; images `arm64` natives |
| Projet compose | `-p taskora-rehearsal`, `--env-file` **vide** : le `.env` racine n'est pas lu. Le worktree n'a pas de `backend/.env`, donc l'`env_file` optionnel d'`api` est absent et Symfony prend `.env.dist` (stockage local, push non configuré). |
| Fichiers | `docker-compose.yml` + `docker-compose.identity-dev.yml`, profil `identity` (database, api, converter, db-init, identity, web) |
| Ports (tous sur 127.0.0.1, libres et vérifiés avant) | web 28080, api 28081, database 23307, identity 28082 |
| Images | construites depuis `934dd2c` : `taskora-rehearsal-{api,web,identity}` ; supprimées en R10 |
| Secrets | `JWT_PASSPHRASE`, `INTERNAL_API_SECRET`, `IDENTITY_DB_PASSWORD`, `DB_PASSWORD`, `DB_ROOT_PASSWORD`, `APP_SECRET` (`openssl rand -hex 32`), plus les mots de passe des comptes de recette. Ils ont été générés **une seule fois** dans un fichier temporaire hors dépôt (mode 600), rechargé à chaque commande. Ils sont restés stables toute la répétition : aucun conteneur n'a été recréé par une rotation involontaire. Ils n'ont jamais été affichés et ont été supprimés en R10. |
| Paire de clés JWT | Neuve : générée par l'entrypoint d'`api` dans le volume `taskora-rehearsal_jwt_keys` avec la passphrase éphémère. C'est l'équivalent de la « nouvelle paire » exigée après C3. |
| Commutation | `IDENTITY_UPSTREAM` / `IDENTITY_WRITE_FREEZE`, puis `up -d --no-deps web` (recréation de web seul, sans `--build`). À chaque commutation, on contrôle la variable du conteneur et la configuration nginx rendue (`"http://<amont>"` présent 2 fois). |
| Commandes ponctuelles | `run --rm --no-deps -T identity --identity.legacy-import=run|verify` et `--identity.outbox-drain` |
| Stack `taskora` de travail | jamais visée ; état identique avant et après (§6) |

## 3. Données de recette (écart à « copie de la base »)

**Écart assumé.** Le plan prévoit un `mariadb-dump` de la base de travail. Il n'a **pas** été fait : la base `taskora` contient des données personnelles. La base de répétition a été remplie **par l'API Symfony** (amont `api:80`) avec des données synthétiques, toutes sur `@exemple.test` :
- 6 comptes (`$2y$` coût 13, hachés par Symfony comme en production) ;
- 3 tableaux (A et B pour Alice, C pour Bob), avec listes par défaut, une liste ajoutée et des étiquettes ;
- adhésions : Bob administrateur et Carla membre de A, Dave membre de B (6 adhésions au total) ;
- 2 invitations en attente : une pour le futur compte R1 sur A, une sur B qui reste en attente ;
- 4 notifications (`board_invite`, `member_joined`) ;
- un **changement de mot de passe** via Symfony (Bob) : nouveau mot de passe → 200, ancien → 401.

Conséquence : la reprise a été exercée sur des volumes faibles (6, puis 24, puis 432 comptes) et sur un seul profil de hash (`$2y$`/13, le seul présent en production d'après §8). Les profils `$2a$`, `$2b$`, argon2 et les hashes tronqués restent couverts par les tests d'intégration (§7), pas par cette répétition. Aucun dump n'a été créé : il n'y a rien à supprimer pour R10.

## 4. Étapes

### R0 — Comptages avant ; `verify` à blanc ✅
- `taskora.user` : 6 lignes, `MAX(id)=6`, hashes `$2y$`/13 = 6. `identity.user_account` : 0 ligne, `AUTO_INCREMENT=1`.
- Autres tables : 3 tableaux, 6 adhésions, 2 invitations, 4 notifications, 0 abonnement push.
- `verify` à blanc : **exit 1**, attendu puisque la cible est vide. Source : `count=6`, `crc32Sum=15865898615`, `crc32Xor=2127679829`, `normalizedEmailDuplicates=0`, `targetEmailConflicts=0`. Contrôles en échec : `count, maxId, crc32*, hashPrefixes, idSymmetricDifference, autoIncrementAboveMaxId`.

### R1 — Bascule §5, étapes 3 à 5 ✅ (16 s)
| Temps | Action | Résultat |
|---|---|---|
| T0 | gel : `IDENTITY_WRITE_FREEZE=on`, amont `api:80`, recréation de web (1,7 s) | `POST /api/auth/register` → **503** `{"error":"Maintenance en cours, réessayez dans quelques minutes."}` ; `PATCH /api/me` → **503**, même corps ; lectures : `GET /api/me` 200, `POST /api/auth/login` 200, `GET /api/boards` 200 |
| T0+1 | `--identity.legacy-import=run` | exit 0 ; `source=6 insérés=6 mis à jour=0 inchangés=0 cible seule=0` ; contrôles cohérents (cible `count=6`, mêmes CRC, `AUTO_INCREMENT` ≥ `MAX+1`) |
| | `--identity.legacy-import=verify` | **exit 0, cohérents** |
| T0+2 | amont `identity:8080`, gel levé (recréation de web en 0,9 s) | configuration rendue → `identity:8080` ; en-têtes Spring Security sur `/api/auth/login` (preuve que la requête passe par identity) |

Durée totale du gel au routage : **16 s**, pour un objectif < 5 min.

### R2 — Contrôles à chaud §5, étape 6 ✅
| Contrôle | Statut |
|---|---|
| Login d'Alice (compte repris) via identity | 200 |
| Login de Carla via identity | 200 |
| `GET /api/me` (identity) avec le jeton émis par Symfony **avant** la bascule | 200 (même id) |
| `GET /api/boards` (Symfony) avec un jeton émis par identity | 200 (2 tableaux) |
| e2e `@identity` contre identity (§5) | **14/14** |

Revendications : Symfony émet `exp, iat, roles, username` ; identity émet les mêmes, plus `uid`. Le jeton identity est accepté par Symfony.

### R3 — Inscription et changement de mot de passe de R1 via identity ✅
- `POST /api/auth/register` de R1 → **201**. Adhésion visible côté Symfony : `GET /api/boards/{A}` → 4 membres, dont R1, et l'invitation de R1 est consommée. `GET /api/boards` de R1 (jeton identity) → « Tableau de recette A ».
- La projection `taskora.user` a le même id que la cible, et le hash est identique octet pour octet des deux côtés (`$2y$13$`).
- Outbox : chaque inscription produit 3 messages (ADR A10 de `backend-hexagonal.md`) : réclamation immédiate **`DELIVERED`**, réclamation de rattrapage `PENDING` due à +60 s, et `UserRegistered` en `HELD`, ce qui est attendu en phase 1 sans courtier.
- `PATCH /api/me` (changement de mot de passe de R1) via identity → **200**.

### R4 — Retour arrière §6.2 ✅
- Amont `api:80`, recréation de web seule : **1,0 s**, objectif < 1 min. Contrôle de l'environnement et de la configuration rendue : OK.
- Outbox avant vidage : `symfony-invitations` 9 `DELIVERED` et 9 `PENDING` (rattrapages +60 s) ; `broker` 9 `HELD`.
- Relais d'identity laissé démarré : **`PENDING` = 0 au bout de 38 s**. Après vidage : 18 `DELIVERED`, 0 `FAILED`, 0 tentative en échec. `--identity.outbox-drain` de contrôle : `0 message(s) traité(s)`. Aucune perte de message.

### R5 — Via Symfony ✅
| Contrôle | Attendu | Obtenu |
|---|---|---|
| Login de R1 avec le **nouveau** mot de passe (changé via identity) | 200 | **200** |
| Login de R1 avec l'ancien mot de passe | 401 | **401** |
| `GET /api/me` (Symfony) avec le jeton identity émis en R3 | 200 | **200** |
| Login de Bob (mot de passe changé au remplissage) et d'Alice | 200 | 200 / 200 |

### R6 — Inscription de R2 via Symfony ; non-régression P3 avec un jeton identity ✅
L'inscription de R2 via Symfony donne **201**. Les rejeux ci-dessous utilisent le jeton d'Alice émis par identity en R2. Pour R1, c'est son jeton identity de R3.

| Scénario | Contrôle | Résultat |
|---|---|---|
| SC-BOARD-002 | `GET /api/boards` d'Alice | 200 ; A (owner, 4 membres, 5 listes, 0 carte, `favorite=false`) et B (owner, 2 membres, 0 liste) ; C (Bob) absent ; R2 → `[]` ✅ |
| SC-BOARD-009 | ajout de R2 avec un e-mail entouré d'espaces et en majuscules, `role:"admin"` | 201 `added`, `role=admin` ✅ |
| | nouvel ajout de R2 | 409 « Cet utilisateur est déjà membre du tableau. » ✅ |
| | `role:"owner"` (Dave) | 201, rôle ramené à `member` ✅ |
| SC-BOARD-011 | invitation d'un e-mail sans compte | 202 `invited` avec le message du contrat ✅ ; réinvitation → 409 ✅ ; e-mail invalide → 400 « Adresse e-mail invalide. » ✅ ; e-mail vide → 400 ✅ ; invitation listée dans `GET /api/boards/{id}` ✅ |
| SC-NOTIF-001 | `GET /api/notifications` pour R2, Carla, R1 et Alice | 200 ; R2 : 2 notifications (`board_invite`, `member_joined`) ; Carla 3 ; R1 3 ; Alice, l'actrice : 0. Chaque utilisateur ne voit que les siennes ✅ (volumes faibles : la limite de 40 et le tri sur 45 notifications ne sont pas rejoués) |
| SC-PUSH-008 | 2 abonnements de R1 et 1 d'Alice | 201 ; désactivation de l'appareil 0 → 204 ; retrait de l'appareil 1 → 204 ; retrait de l'appareil d'Alice par R1 → **404** « Appareil introuvable. » ; `DELETE /subscriptions` avec l'endpoint d'Alice → 204 **sans effet** (Alice garde 1 appareil) ✅ |
| | `POST /api/push/test` (désabonné) | 404 ✅ |

Limites : le push n'est pas configuré (`configured=false`, pas de clés VAPID). L'envoi réel d'une bannière et le désabonnement du navigateur à la déconnexion (volet front) ne sont pas testables ici.

En complément : **e2e `@identity` contre Symfony : 14/14**. Les 8 comptes e2e inscrits via Symfony ont été rapatriés en R7.

### R7 — Re-bascule §5 depuis l'étape 3 ✅ (14 s)
- Gel : 503 sur les deux écritures, lectures en 200.
- `run` : exit 0, `source=24 insérés=9 mis à jour=0 inchangés=15 cible seule=0`. Les 9 comptes insérés sont R2 et les 8 comptes e2e inscrits via Symfony.
- `verify` : **exit 0, cohérents** (`count=24`, `maxId=25`, `crc32Sum=68137254533`, `crc32Xor=2820497019`, identiques de part et d'autre).
- Routage vers identity, gel levé. Durée totale : 14 s.

### R8 — Via identity : logins de R1 et R2 ✅, mesures p95 ❌
- Login de R1 avec le nouveau mot de passe : **200**. Login de R2 (inscrit via Symfony) : **200**. Ancien mot de passe de R1 : 401. Bob : 200.
- `GET /api/me` (identity) avec le jeton **Symfony** de R2 : 200 ; avec son jeton identity : 200. `GET /api/boards` de R2 : 200.
- e2e `@identity` contre identity : **14/14**.
- Mesures : voir §5. **Login p95 = 641,6 ms > 500 ms** → non conforme.

### R9 — Comptages, journaux, 5xx ✅
- Comptages juste après les logins de R8, avant les mesures de charge : `taskora.user` = 24, dont **8 hors comptes e2e = R0 (6) + 2 (R1, R2)** ✅. Le reste correspond aux 16 comptes e2e des passages R2 et R6. `identity.user_account` = 24, `AUTO_INCREMENT=26`. Hashes `$2y$`/13 partout.
- Comptages en fin de répétition : 432 = 8 + 24 comptes e2e + 400 comptes de mesure (`perf-…`) ; source et cible identiques ; `verify` final vert.
- Journaux `identity`, `web`, `api`, `database` et `db-init` parcourus en mémoire, après R8 puis en fin de répétition : **0** jeton `eyJ…`, **0** hash `$2y$`/argon2, **0** occurrence d'un secret ou d'un mot de passe de recette, **0** `Authorization: Bearer`, **0** champ `password` (SC-IDENT-024) ✅.
- 5xx : **0**, en dehors des 503 voulus par le gel. Le journal d'accès du conteneur web courant compte 1 629 requêtes (200, 201, 304, 400, 401, 409). Le client de recette a enregistré le statut de chaque appel de la répétition, environ 4 100 appels : aucun 5xx. Côté `api`, les 9 lignes « Uncaught PHP Exception » sont des 4xx attendus (400 et 409).

### R10 — Nettoyage ✅
`docker compose -p taskora-rehearsal … down -v --remove-orphans` a supprimé les 6 conteneurs, les 3 volumes (`taskora-rehearsal_{db_data,jwt_keys,uploads}`) et les 3 réseaux. Les images `taskora-rehearsal-{api,web,identity}` ont été supprimées.

Le fichier de secrets, les jetons, l'espace e2e et ses rapports ont été supprimés. Aucun dump n'avait été créé.

Contrôle final : **aucun** conteneur, volume, réseau ni image nommé `taskora-rehearsal`. Les conteneurs `taskora-*` ont les mêmes id, états et `StartedAt` qu'avant la répétition (§6).

### Complément hors plan : second retour arrière et écriture Symfony rapatriée ✅
Après R9, un second retour arrière a été fait (R8bis : web en 1,0 s ; 8 `PENDING` vidés en 35 s, 0 `FAILED`) pour mesurer Symfony. Ensuite :
- **X1** : Carla change son mot de passe **via Symfony** → 200.
- **X2** : re-bascule (gel 503/503, lectures 200). `run` donne `source=432 insérés=200 mis à jour=1 inchangés=231`, puis `verify` est vert. Durée : 15 s. L'écriture Symfony faite pendant le retour arrière est donc rapatriée par la copie idempotente (§6.2, point 5).
- **X3** : via identity, Carla se connecte avec le nouveau mot de passe (200) ; l'ancien est refusé (401) ; R1 et R2 → 200.

## 5. Latences (client sur l'hôte → nginx 127.0.0.1:28080, séquentiel, 10 requêtes de chauffe exclues)

| Amont | Endpoint | n | p50 (ms) | p95 (ms) | max (ms) | Codes | Seuil | Conforme |
|---|---|---|---|---|---|---|---|---|
| identity | `POST /api/auth/login` | 200 | 605,5 | **641,6** | 811,1 | 200 × 200 | p95 < 500 | **non** |
| identity (2ᵉ échantillon, après X2) | `POST /api/auth/login` | 200 | 609,5 | **643,5** | 682,6 | 200 × 200 | p95 < 500 | **non** |
| identity | `GET /api/me` | 1 000 | 3,7 | 5,7 | 16,6 | 200 × 1 000 | p95 < 50 | oui |
| identity (2ᵉ échantillon) | `GET /api/me` | 1 000 | 3,1 | 4,5 | 17,4 | 200 × 1 000 | p95 < 50 | oui |
| identity | `POST /api/auth/register` | 200 | 615,1 | 658,7 | 895,8 | 201 × 200 | — | — |
| Symfony | `POST /api/auth/login` | 200 | 459,7 | 498,3 | 528,6 | 200 × 200 | p95 < 500 | oui, de justesse |
| Symfony | `GET /api/me` | 1 000 | 3,6 | 5,1 | 12,7 | 200 × 1 000 | p95 < 50 | oui |
| Symfony | `POST /api/auth/register` | 200 | 461,6 | 492,5 | 561,4 | 201 × 200 | — | — |

Les deux amonts sont mesurés sur la même stack, la même machine et le même compte (bcrypt `$2y$` coût 13). Le surcoût d'identity est d'environ **+29 % au login** et **+34 % à l'inscription**. Il est stable d'un échantillon à l'autre et indépendant du préchauffage de la JVM. Le coût est dans le hachage : `GET /api/me`, qui ne hache pas, est équivalent sur les deux amonts. Le risque était identifié dans `phase-1-identity.md` (« Login plus lent (coût bcrypt côté JVM) »).

## 6. Résultats e2e `@identity` (Playwright 1.65.1, Chromium, image `mcr.microsoft.com/playwright:v1.65.1-noble`)
Dépendances installées par `npm ci` dans `node:24-alpine`, sur une copie de `frontend/` hors dépôt. Les tests ont visé `E2E_BASE_URL=http://host.docker.internal:28080` avec `CI=true`.

| Passage | Amont | Résultat | Durée |
|---|---|---|---|
| R2 | identity | **14 réussis / 14**, 0 échec, 0 ignoré | 22,0 s |
| R6 (après le retour arrière) | Symfony | **14 réussis / 14** | 18,4 s |
| R8 (après la re-bascule) | identity | **14 réussis / 14** | 20,6 s |

Scénarios couverts : SC-IDENT-001, 002, 003, 007, 008, 017, 018, 025, 026. Contrôle de fuite des rapports et des sorties : 0 jeton et 0 secret.

## 7. Constats

### Bloquant
- **B1 — p95 du login via identity hors seuil** : 641,6 et 643,5 ms, pour un seuil < 500 ms (identity.md §6, P2, R8). Symfony est à 498,3 ms sur le même hôte. L'inscription subit le même surcoût.
  - Cause probable : vérification bcrypt en Java pur (`org.springframework.security.crypto.bcrypt.BCrypt` dans `SymfonyCompatiblePasswordHasher`), plus lente que l'implémentation C de PHP à coût égal.
  - Renvoi : **BackAli**. Il faut soit réduire le coût de vérification sans toucher aux hashes ni au coût 13 (implémentation bcrypt plus rapide, ressources CPU du conteneur), soit que **Paul** décide du seuil (par exemple relatif à Symfony, ou mesuré sur le matériel cible). La mesure a été faite sur un poste de développement (Docker Desktop, M1) : il faut la refaire sur l'environnement cible avant de conclure.
  - Aucune correction n'a été faite pendant la répétition.

### Non bloquants
- **N1 — Micro-coupure à chaque commutation.** §5 et §6.2 décrivent un « rechargement `nginx -s reload` sans reconstruction ». Le mécanisme livré **recrée** le conteneur web (gabarit envsubst rendu au démarrage). À chaque commutation, environ 1 s sans réponse (`curl: (52) Empty reply`) a été observée, sur 6 commutations au total. C'est acceptable dans une fenêtre annoncée, mais il faut aligner le texte de §5 et §6.2 ou prévoir un vrai rechargement (Manuel).
- **N2 — Journaux d'accès nginx perdus à chaque recréation de web.** Le taux de 5xx de la surveillance de 24 h (§5, étape 7) ne peut pas reposer sur `docker logs` de web après une commutation. Prévoir une collecte externe des journaux (Manuel).
- **N3 — Délai de vidage de l'outbox après un retour arrière** : 35 à 38 s, à cause des réclamations de rattrapage dues à +60 s. Il faut laisser identity démarré au moins 60 s après le dernier enregistrement. La règle §6.2-3 le prévoit, mais la durée est à mentionner dans la procédure.
- **N4 — Bruit dans les journaux Symfony** : chaque 4xx (400, 409) est journalisé en « Uncaught PHP Exception » de niveau erreur. Ce comportement existait avant la migration. Il faut le filtrer dans la surveillance des 5xx.
- **N5 — Écarts de commande** :
  - `run --rm --no-deps -T` au lieu de `run --rm` : évite de relancer `db-init` et ses dépendances, sans effet sur le résultat ;
  - l'overlay `docker-compose.identity-dev.yml` publie identity sur 127.0.0.1:28082, ce qui n'existe pas en production ; il n'a servi qu'à un contrôle d'en-têtes, et toutes les mesures sont passées par nginx.

### Écarts au plan (assumés)
1. Base remplie par l'API avec des données synthétiques au lieu d'un `mariadb-dump` de la base de travail (§3).
2. Mesures ajoutées : 200 inscriptions par amont et Symfony mesuré pour comparaison. Ces comptes `perf-…` sont exclus du contrôle « R0 + 2 », qui a été fait avant les mesures.
3. e2e joués en R2, R6 (Symfony) et R8 ; les comptes e2e sont exclus du contrôle « R0 + 2 ».
4. Étapes complémentaires R8bis et X1–X3 : second retour arrière et écriture Symfony rapatriée.
5. Le nom du compte rendu suit la demande (`2026-11-26-identity-rollback-rehearsal.md`) et non le gabarit du plan (`…-identity-service-rollback-rehearsal.md`).

## 8. Suites
1. **BackAli** : traiter B1, puis relancer une répétition, au minimum R7–R9 avec les mesures R8, sur une stack `taskora-rehearsal` neuve.
2. **Paul** : décider du seuil de login (absolu < 500 ms ou relatif à Symfony, matériel de référence). Ne pas passer les lignes back IDENT à `validé` tant que R8 n'est pas conforme.
3. **Manuel** : N1 et N2 (texte de §5 et §6.2 ou rechargement réel ; collecte des journaux nginx).
