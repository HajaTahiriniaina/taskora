# Reprise de R8 après la correction B1 (M0–M6)

- Date : 27/11/2026, 06 h 21 – 07 h 15 (UTC)
- Exécution : BackAli (données, contrôles, mesures, e2e, journaux) et Manuel (stack, commutations, nettoyage)
- Référence : `main` `42a8ee7` (moteur bcrypt natif libxcrypt et scan Trivy) ; branche `chore/identity-r8-rerun`
- Plan suivi : `docs/architecture/identity-migration.md` §6.3, « Reprise de R8 » (M0–M6) ; protocole `docs/specs/identity.md` §6.1, décision **PERF-IDENT-1**. Ces deux textes ont été lus sur la branche `docs/identity-latency-threshold`, pas encore fusionnée.
- Ce rapport ne contient aucun secret, hash, jeton, valeur de `.env` ni donnée personnelle. Il ne donne que des comptages, des statuts et des durées.

## 1. Synthèse

| Étape | Attendu | Obtenu | Statut |
|---|---|---|---|
| M0 | stack neuve et saine, données synthétiques | 6 services sains, 7 comptes `$2y$`/13 créés par l'API Symfony | ✅ |
| M1 | `verify` vert, routage vers identity | `run` exit 0, `verify` exit 0 « cohérents », configuration nginx rendue → `identity:8080` ; 13 s | ✅ |
| M2 | 200 / 401 / 401, même corps ; moteur natif | 200 / 401 / 401, corps identiques ; libxcrypt chargée par la JVM, aucun repli Java | ✅ |
| M3 | L1, L2, L3, L5 ; `GET /api/me` p95 < 50 ms | **mesure invalide** à deux reprises : écart entre blocs d'un même amont > 5 % (bruit de l'hôte, §6) | ❌ invalide |
| M4 | e2e `@identity` 14/14 sur identity | **14/14**, 0 échec, 0 ignoré | ✅ |
| M5 | 0 fuite, 0 × 5xx | 0 / 0 | ✅ |
| M6 | aucune ressource `taskora-rehearsal` | 0 conteneur, volume, réseau ni image ; stack `taskora` identique | ✅ |

**M3 ne permet pas de conclure.** Le protocole exige de rejouer une mesure dont deux blocs d'un même amont s'écartent de plus de 5 %. La mesure a été rejouée une fois (série B), avec le même défaut. Je me suis arrêté là, comme demandé en cas d'échec : l'hôte n'était pas dans les conditions du protocole (sur batterie, charge concurrente forte, §6).

À titre **indicatif seulement**, la médiane d'identity est au niveau de celle de Symfony sur les deux routes et les deux séries : ratio p50 de 0,94 à 1,01, contre 1,32–1,33 lors du constat B1. Le verdict revient à Paul.

## 2. Environnement

| Élément | Valeur |
|---|---|
| Hôte | Apple M1, 8 cœurs ; Docker Desktop, moteur 27.3.1 (8 CPU, 7,65 Gio), Compose v2.29.7-desktop.1 ; images `arm64` natives |
| Alimentation | **sur batterie** pendant toute la mesure (48 % à 06 h 30, 39 % à 06 h 45) ; secteur rebranché seulement après M3 (écart, §6) |
| Projet compose | `-p taskora-rehearsal`, `--env-file` **vide** (le `.env` racine n'est pas lu), profil `identity` (database, api, converter, db-init, identity, web). Toutes les commandes passent par un script qui impose `-p taskora-rehearsal`. |
| Surcharge compose | Fichier temporaire hors dépôt : `services.api.env_file: !reset []`. La copie de travail contient un `backend/.env` (ni lu ni affiché) ; sans cette surcharge, l'`env_file` optionnel d'`api` l'aurait chargé. Symfony prend donc `.env.dist`. La surcharge ne touche à aucune limite de ressources. |
| Overlay `identity-dev` | non utilisé : port d'identity non publié, toutes les requêtes passent par nginx |
| Ports (127.0.0.1, libres et vérifiés avant) | web 28080, api 28081, database 23307 |
| Limites de ressources (`docker inspect`) | identity `mem_limit` 768 Mio (805 306 368 o), pas de limite CPU ; api sans limite mémoire ni CPU. Ce sont les valeurs du `docker-compose.yml` versionné, sans surcharge. |
| Images | `taskora-rehearsal-{api,web,identity}`, construites depuis `42a8ee7` (arbre propre). Les 27 étapes viennent du cache BuildKit, adressé par contenu, donc identiques à un build neuf des mêmes sources. Présence vérifiée dans l'image identity : `LibxcryptBcryptEngine.class` et `libcrypt.so.1`. Images supprimées en M6. |
| Secrets | `JWT_PASSPHRASE`, `INTERNAL_API_SECRET`, `IDENTITY_DB_PASSWORD`, `DB_PASSWORD`, `DB_ROOT_PASSWORD`, `APP_SECRET` (`openssl rand -hex 32`) et 3 mots de passe de recette. Générés une seule fois dans un fichier temporaire hors dépôt (mode 600), stables toute la reprise, jamais affichés, supprimés en M6. Paire de clés JWT neuve, générée par l'entrypoint d'`api`. |
| Commutation | `IDENTITY_UPSTREAM` / `IDENTITY_WRITE_FREEZE` puis `up -d --no-deps --force-recreate web`. Après chaque commutation : contrôle de la variable du conteneur et de la configuration rendue (`"http://<amont>"` présent 2 fois). 11 commutations, de 0,9 à 2,2 s jusqu'au premier 200 sur `/`. |
| Commandes ponctuelles | `run --rm --no-deps -T identity --identity.legacy-import=run|verify` |
| Client de mesure | Python 3.9.6 (`http.client`) sur l'hôte → `127.0.0.1:28080` (nginx). Une connexion TCP par requête, séquentiel ; temps mesuré de l'envoi à la lecture complète de la réponse. Statut de chaque requête enregistré. |
| Stack `taskora` de travail | jamais visée ; mêmes id, états et `StartedAt` avant et après (§4, M6) |

## 3. Données de recette (M0)
Toutes créées **par l'API Symfony** (amont `api:80`) ; aucun dump de la base de travail.
- 7 comptes `@exemple.test` : 6 comptes nominaux et le compte de mesure `perf-login`. `taskora.user` : 7 lignes, `MAX(id)=7`, hashes `$2y$`/13 = 7. `identity.user_account` : 0 ligne, `AUTO_INCREMENT=1`.
- Changement de mot de passe via Symfony (Bob) : `PATCH /api/me` → 200 ; nouveau mot de passe → 200 ; ancien → 401.
- Comptes ajoutés pendant les mesures : 2 000 inscriptions séquentielles (8 blocs × 250, chauffe comprise) et 880 inscriptions concurrentes (2 × 440), toutes `perf-…@exemple.test`. S'y ajoutent 8 comptes créés par la suite e2e, sur son propre domaine `@example.com` (§7, N4).
- Fin de reprise : `taskora.user` = `identity.user_account` = 2 455 comptes, tous `$2y$`/13.

## 4. Étapes

### M0 — Stack neuve ✅
`up -d --wait` : database, api et identity *healthy*, `db-init` exit 0, web et converter démarrés, en 24 s. Avant le démarrage, il n'existait aucun conteneur, volume ni image `taskora-rehearsal`.

### M1 — Gel, reprise, `verify`, routage ✅ (13 s)
| Action | Résultat |
|---|---|
| Gel (`IDENTITY_WRITE_FREEZE=on`, amont `api:80`) | `POST /api/auth/register` → **503**, `PATCH /api/me` → **503** |
| `--identity.legacy-import=run` | exit 0 ; `source=7 insérés=7 mis à jour=0 inchangés=0 cible seule=0` ; contrôles cohérents (`count=7`, `maxId=7`, `crc32Sum=20476489107`, `crc32Xor=1887503609`, `hashPrefixes $2y$/13=7`, `idSymmetricDifference=0`, `autoIncrementAboveMaxId=true`, 0 doublon, 0 conflit) |
| `--identity.legacy-import=verify` | **exit 0, « Contrôles de reprise : cohérents »** |
| Routage `identity:8080`, gel levé | variable du conteneur web et configuration rendue → `"http://identity:8080"` × 2 |

### M2 — Contrôles via identity ✅
| Contrôle | Attendu | Obtenu |
|---|---|---|
| Login d'Alice (hachée par Symfony, reprise) | 200 + jeton | **200**, jeton présent |
| Mauvais mot de passe | 401 | **401** `{"code":401,"message":"Invalid credentials."}` |
| E-mail inconnu | 401, même corps | **401**, corps **identique** octet pour octet |
| Login de `perf-login` / de Bob (mot de passe changé via Symfony) | 200 | 200 / 200 |
| `GET /api/me` avec le jeton identity | 200 | 200 |
| Preuve de routage | en-têtes Spring Security | `X-Content-Type-Options`, `X-Frame-Options`, `X-XSS-Protection`, `Cache-Control`, `Pragma`, `Expires` présents |

**Moteur bcrypt natif en service** (seuls les noms de variables ont été listés, jamais les valeurs) :
- `IDENTITY_BCRYPT_ENGINE` est **absente** de l'environnement du conteneur, donc la valeur par défaut `native` de `application.yml` s'applique (`${IDENTITY_BCRYPT_ENGINE:native}`) ;
- `JAVA_TOOL_OPTIONS` contient `--enable-native-access=ALL-UNNAMED` ;
- après les logins, `/proc/1/maps` du processus JVM montre `libcrypt.so.1.1.0` chargée (4 projections) : `crypt_rn` est bien appelée par FFM ;
- journaux d'identity : 0 ligne « restricted method », `IllegalCallerException`, `JavaBcryptEngine` ou `bcrypt-engine=java` ; démarrage normal en 5,6 s. Le code n'a pas de repli silencieux : si libxcrypt manque, `LibxcryptBcryptEngine` lève une `IllegalStateException` et le service ne démarre pas.

### M3 — Mesures PERF-IDENT-1 ❌ invalides
Déroulé conforme au protocole :
- chemin client sur l'hôte → nginx → amont ; commutation par `IDENTITY_UPSTREAM` uniquement ;
- blocs alternés S, I, S, I ; par bloc et par route, 50 requêtes de chauffe exclues puis 200 mesurées (1 000 pour `GET /api/me`, Symfony compris) ;
- login avec un même compte haché par Symfony ; inscriptions avec des e-mails uniques ;
- ordre dans chaque bloc : login, inscription, `GET /api/me`.

Chaque passage Symfony → identity a suivi la re-bascule §5 (gel 503/503, `run`, `verify` vert, routage), entre 14 et 18 s. Sans elle, les comptes inscrits par Symfony auraient manqué dans identity, avec un risque de collision d'id. Chaque retour vers Symfony a attendu `PENDING` = 0 dans l'outbox avant le bloc suivant (45 à 65 s), pour que le relais ne charge pas `api` pendant la mesure Symfony.

| Série | S | I | S | I | Écart S ↔ I | Re-bascules |
|---|---|---|---|---|---|---|
| A | S1 06:25–06:30 | I1 06:30–06:34 | S2 06:35–06:39 | I2 06:39–06:43 | < 30 min ✅ | `run` 250 insérés ×2, `verify` vert ×2 |
| B (rejeu) | S3 06:46–06:50 | I3 06:50–06:54 | S4 06:55–07:00 | I4 07:00–07:05 | < 30 min ✅ | `run` 250 insérés ×2, `verify` vert ×2 |

#### Par bloc (ms)
| Route | Bloc | n | p50 | p95 | p99 | max | Codes |
|---|---|---|---|---|---|---|---|
| login | S1 | 200 | 475,4 | 525,7 | 740,0 | 824,9 | 200 × 200 |
| login | I1 | 200 | 467,4 | 589,4 | 716,1 | 1 011,0 | 200 × 200 |
| login | S2 | 200 | 457,0 | 487,5 | 505,8 | 510,6 | 200 × 200 |
| login | I2 | 200 | 445,7 | 468,7 | 484,0 | 485,3 | 200 × 200 |
| login | S3 | 200 | 481,4 | 676,5 | 781,5 | 1 196,4 | 200 × 200 |
| login | I3 | 200 | 465,7 | 532,5 | 562,2 | 642,8 | 200 × 200 |
| login | S4 | 200 | 503,5 | 969,5 | 1 114,1 | 1 197,0 | 200 × 200 |
| login | I4 | 200 | 458,0 | 501,7 | 556,7 | 780,9 | 200 × 200 |
| register | S1 | 200 | 488,0 | 964,8 | 2 926,1 | 3 925,8 | 201 × 200 |
| register | I1 | 200 | 468,0 | 507,2 | 579,1 | 778,0 | 201 × 200 |
| register | S2 | 200 | 458,9 | 491,3 | 496,7 | 500,7 | 201 × 200 |
| register | I2 | 200 | 478,9 | 700,9 | 852,4 | 1 012,3 | 201 × 200 |
| register | S3 | 200 | 478,1 | 562,0 | 597,1 | 649,1 | 201 × 200 |
| register | I3 | 200 | 471,0 | 545,3 | 610,8 | 777,4 | 201 × 200 |
| register | S4 | 200 | 479,9 | 590,8 | 802,3 | 1 016,4 | 201 × 200 |
| register | I4 | 200 | 474,8 | 675,8 | 915,0 | 996,8 | 201 × 200 |
| `GET /api/me` | S1 | 1 000 | 4,5 | 6,7 | 11,3 | 19,5 | 200 × 1 000 |
| `GET /api/me` | I1 | 1 000 | 3,5 | 5,4 | 9,6 | 36,9 | 200 × 1 000 |
| `GET /api/me` | S2 | 1 000 | 3,4 | 5,3 | 6,4 | 33,2 | 200 × 1 000 |
| `GET /api/me` | I2 | 1 000 | 7,3 | 20,2 | 47,2 | 384,4 | 200 × 1 000 |
| `GET /api/me` | S3 | 1 000 | 4,3 | 6,2 | 7,9 | 14,8 | 200 × 1 000 |
| `GET /api/me` | I3 | 1 000 | 2,9 | 5,3 | 8,8 | 17,0 | 200 × 1 000 |
| `GET /api/me` | S4 | 1 000 | 4,7 | 7,6 | 12,8 | 72,5 | 200 × 1 000 |
| `GET /api/me` | I4 | 1 000 | 6,0 | 30,2 | 64,5 | 2 229,3 | 200 × 1 000 |

#### Validité : écart entre les deux blocs d'un même amont (seuil 5 %)
Écart = |a − b| / min(a, b).

| Série | Route | Symfony p50 | Symfony p95 | identity p50 | identity p95 | Validité |
|---|---|---|---|---|---|---|
| A | login | 4,0 % | **7,8 %** | 4,9 % | **25,8 %** | ❌ |
| A | register | **6,3 %** | **96,4 %** | 2,3 % | **38,2 %** | ❌ |
| B | login | 4,6 % | **43,3 %** | 1,7 % | **6,1 %** | ❌ |
| B | register | 0,4 % | **5,1 %** | 0,8 % | **23,9 %** | ❌ |

Le p50 est stable (≤ 5 % dans 7 cas sur 8). Le p95 ne l'est dans aucun cas. Les écarts viennent de pointes ponctuelles, présentes sur les deux amonts sans ordre fixe : S1 register (p99 2,9 s), S4 login, I2 register, I4 `GET /api/me` (max 2,2 s). Leurs dates coïncident avec la charge concurrente relevée sur l'hôte (§6).

#### Regroupement par amont (400 mesures) et L1–L5 : **calcul indicatif, mesure invalide**
| Série | Route | Symfony p50 / p95 / p99 / max | identity p50 / p95 / p99 / max | Ratio p50 | Ratio p95 | L1 (p95 ≤ 1,15) | L2 (p50 ≤ 1,15) | L3 (p95 ≤ 1 000) | L4 (≤ 1,10) | L5 |
|---|---|---|---|---|---|---|---|---|---|---|
| A | login | 464,9 / 511,5 / 640,8 / 824,9 | 458,0 / 523,4 / 638,8 / 1 011,0 | 0,985 | 1,023 | 1,023 ✅* | 0,985 ✅* | 523,4 ✅* | ✅* | ✅ |
| A | register | 469,4 / 715,9 / 1 635,0 / 3 925,8 | 471,6 / 615,7 / 780,8 / 1 012,3 | 1,005 | 0,860 | 0,860 ✅* | 1,005 ✅* | 615,7 ✅* | ✅* | ✅ |
| B | login | 489,4 / 881,6 / 1 054,7 / 1 197,0 | 461,3 / 517,1 / 562,0 / 780,9 | 0,943 | 0,587 | 0,587 ✅* | 0,943 ✅* | 517,1 ✅* | ✅* | ✅ |
| B | register | 478,6 / 566,7 / 733,9 / 1 016,4 | 472,2 / 607,3 / 889,0 / 996,8 | 0,987 | 1,072 | 1,072 ✅* | 0,987 ✅* | 607,3 ✅* | ✅* | ✅ |

\* Valeur calculée qui satisfait la condition, mais **non opposable** : la règle des 5 % invalide la mesure. Les ratios p95 (de 0,59 à 1,07) reflètent surtout les pointes de l'hôte, en particulier côté Symfony, pas l'écart propre à identity.

- **L5** : 0 × 5xx et 100 % de codes attendus (200 au login, 201 à l'inscription) sur les 12 000 requêtes mesurées et les 800 requêtes concurrentes mesurées. Les requêtes de chauffe n'ont pas été comptées par le client.
- **`GET /api/me`** (critère absolu p95 < 50 ms) : chaque bloc identity est sous 50 ms (5,4 / 20,2 / 5,3 / 30,2 ms). Regroupé : 14,6 ms en série A et 19,0 ms en série B. Même réserve de bruit (I2, I4).
- **Comparaison avec B1** (26/11, hors protocole) : ratio p50 login 1,32–1,33 et inscription 1,33. Ici, de 0,94 à 1,01 sur les quatre couples. C'est un indice fort que le surcoût systématique a disparu, pas une preuve au sens de PERF-IDENT-1.

#### Mesure informative à 4 requêtes simultanées (identity, sans seuil ; seul L5 s'applique)
20 requêtes de chauffe exclues, puis 200 mesurées. Le débit est calculé sur les 220 requêtes.

| Série | Route | p50 | p95 | p99 | max | Codes | Débit |
|---|---|---|---|---|---|---|---|
| A (06:44) | login | 567,9 | 766,4 | 832,5 | 925,4 | 200 × 200 | 6,73 req/s |
| A | register | 568,9 | 1 155,3 | 1 916,8 | 1 940,5 | 201 × 200 | 6,01 req/s |
| B (07:05) | login | 646,1 | 1 734,4 | 2 060,9 | 2 258,4 | 200 × 200 | 4,97 req/s |
| B | register | 721,4 | 1 336,0 | 1 623,4 | 1 676,9 | 201 × 200 | 4,40 req/s |

L5 : 0 × 5xx ✅. La série B coïncide avec la charge maximale de l'hôte (moyenne de charge 50 à 07 h 06).

### M4 — e2e `@identity` contre identity ✅
Playwright 1.65.1, Chromium, image `mcr.microsoft.com/playwright:v1.65.1-noble`. Dépendances installées par `npm ci` dans `node:24-alpine`, sur une copie de `frontend/` hors dépôt. Cible : `E2E_BASE_URL=http://host.docker.internal:28080`, avec `CI=true`. Amont vérifié juste avant : `identity:8080`.

| Amont | Résultat | Durée |
|---|---|---|
| identity | **14 réussis / 14**, 0 échec, 0 ignoré, 0 instable | 33,9 s |

Scénarios couverts : SC-IDENT-001, 002, 003, 007, 008, 017, 018, 025, 026. La reprise §6.3 ne demande l'e2e que contre identity : Symfony n'a pas été rejoué. Contrôle de fuite de la sortie et des rapports JUnit et HTML : 0 jeton, 0 hash, 0 secret.

### M5 — Journaux ✅
Journaux de tous les services (`identity`, `web`, `api`, `database`, `db-init`, `converter`), des 10 exécutions `legacy-import` et des sorties du client, parcourus avant `down`. Les occurrences ont été comptées sans être affichées. Contrôle positif : le même script détecte bien les secrets dans le fichier de secrets (9 occurrences).

| Recherche | Occurrences |
|---|---|
| jeton `eyJ…` | 0 |
| hash `$2a/b/x/y$NN$` ou `$argon2` | 0 |
| `Authorization: Bearer` / `Bearer <jeton>` | 0 |
| champ `password` / `currentPassword` / `newPassword` | 0 |
| valeur d'un des 6 secrets ou des 3 mots de passe de recette | 0 |

5xx :
- client : 0 sur 12 800 requêtes mesurées ;
- journal d'accès du dernier conteneur web (2 203 requêtes) : 1 714 × 200, 478 × 201, 1 × 304, 2 × 400, 7 × 401, 1 × 409, **0 × 5xx** ;
- identity : 0 ligne `ERROR` ou `WARN` ;
- `api` : 0 « Uncaught », 0 `CRITICAL` ; 9 lignes `INFO` de dépréciation Doctrine.

Les 503 du gel n'ont été émis que pendant les fenêtres de gel voulues (5 fois).

### M6 — Nettoyage ✅
- `docker compose -p taskora-rehearsal … down -v --remove-orphans` : 6 conteneurs, 3 volumes (`taskora-rehearsal_{db_data,jwt_keys,uploads}`) et 3 réseaux supprimés. Images `taskora-rehearsal-{api,web,identity}` supprimées.
- Supprimés aussi : le fichier de secrets, la copie e2e (rapports et `node_modules`) et les journaux exportés. Seuls restent, hors dépôt, les fichiers de mesure (durées et codes uniquement) et les scripts.
- Contrôle final : **0** conteneur, volume, réseau et image `taskora-rehearsal`.
- Stack `taskora` : 5 conteneurs, avec les mêmes id, état et `StartedAt` avant et après (`diff` vide). Aucune commande compose n'a visé ce projet.
- Les images `node:24-alpine` et `mcr.microsoft.com/playwright:v1.65.1-noble`, qui ne sont pas propres à la recette, sont conservées.

## 5. Évaluation L1–L5 (factuelle ; verdict à Paul)

| Réf. | Login | Inscription | Statut factuel |
|---|---|---|---|
| L1 p95 ≤ 1,15 × | A 1,023 ; B 0,587 | A 0,860 ; B 1,072 | condition satisfaite par les nombres, **mesure invalide** (> 5 % entre blocs) |
| L2 p50 ≤ 1,15 × | A 0,985 ; B 0,943 | A 1,005 ; B 0,987 | idem |
| L3 p95 ≤ 1 000 ms | A 523,4 ; B 517,1 | A 615,7 ; B 607,3 | idem |
| L4 ≤ 1,10 (non bloquant) | ≤ 1,023 | ≤ 1,072 | idem |
| L5 0 × 5xx, 100 % des codes attendus | 0 × 5xx, 200 × 800 (+ 400 concurrents) | 0 × 5xx, 201 × 800 (+ 400 concurrents) | ✅ (ne dépend pas de la stabilité) |
| `GET /api/me` p95 < 50 ms | — | — | 14,6 et 19,0 ms ; chaque bloc < 50 ms ; même réserve |

## 6. Écarts au protocole

1. **Alimentation** : le poste était sur batterie pendant M3. Le protocole exige le secteur.
2. **Charge concurrente notable** :
   - moyenne de charge de 9 à 06 h 30, puis 50 à 07 h 06 ;
   - dans la même VM Docker, le conteneur PHP-FPM d'un autre projet Docker sans lien avec Taskora tournait à environ 325 % CPU (relevé à 07 h 06) ;
   - le rendu de Docker Desktop tournait à environ 140 % CPU (06 h 30) ;
   - Chrome, Slack et l'indexation Spotlight étaient actifs.

   Le protocole exige un poste « sans autre charge notable ». Ces processus n'appartiennent pas à la recette et n'ont pas été arrêtés.
3. **Conséquence** : la règle des 5 % invalide les deux séries. Un seul rejeu a été fait, puis la mesure a été arrêtée.
4. **Surcharge compose** `env_file: !reset []` sur `api`, pour ne pas charger le `backend/.env` présent dans la copie de travail (§2). Elle n'a aucun effet sur les ressources.
5. **Images issues du cache BuildKit** : mêmes entrées que `42a8ee7`, donc même contenu qu'un build neuf. Ce n'est pas un `--no-cache`.
6. **Re-bascule complète (gel, `run`, `verify`) avant chaque bloc identity** et attente de la vidange de l'outbox avant chaque bloc Symfony. C'est nécessaire pour alterner les amonts tout en mesurant les inscriptions, et c'est conforme à §5 et §6.2.
7. `GET /api/me` mesuré aussi sur Symfony (1 000 par bloc), pour comparaison seulement.

## 7. Constats

### Bloquant (pour le verdict, pas pour le code)
- **M3-1 — Mesure PERF-IDENT-1 invalide** : le p95 dépasse l'écart de 5 % entre blocs dans les 8 couples, sur deux séries consécutives. Cause attribuée à l'environnement (§6, écarts 1 et 2), pas à identity : les pointes touchent les deux amonts sans ordre fixe, et le p50 reste stable. **Renvoi : Manuel et BackAli** pour rejouer M0–M3 et M6, avec :
  - poste sur secteur ;
  - les autres projets Docker arrêtés, ou une VM ou un hôte dédié ;
  - le tableau de bord Docker Desktop fermé, sans navigateur ni indexation actifs ;
  - les ressources Docker Desktop consignées.

  Le rejeu peut réutiliser tel quel le déroulé de ce compte rendu.

### Non bloquants
- **N1 — Indice favorable sur B1** : sur les deux séries, la médiane d'identity égale celle de Symfony (ratio p50 de 0,94 à 1,01, contre 1,32–1,33 au constat B1). Le moteur natif est en service (M2). Le p95 n'a pas pu être départagé à cause du bruit.
- **N2 — Moteur bcrypt non journalisé** : rien dans les journaux d'identity ne nomme le moteur actif. La preuve a dû passer par l'environnement et `/proc/1/maps`. Proposition à BackAli : une ligne `INFO` au démarrage (« moteur bcrypt : native »), sans secret. Cela simplifierait la recette et la surveillance de production.
- **N3 — Vidange de l'outbox après retour arrière** : 45 à 65 s avant `PENDING` = 0, à cause des réclamations de rattrapage dues à +60 s. Conforme au constat N3 du 26/11.
- **N4 — Domaine des comptes e2e** : la suite e2e crée ses comptes en `@example.com` (8 comptes ici), pas en `@exemple.test`. Ils sont synthétiques et sans incidence. À harmoniser si la règle « `@exemple.test` uniquement » doit valoir pour toute la recette (FrontPierre).
- **N5 — Coupure à chaque commutation** : 11 recréations de web, chacune de 0,9 à 2,2 s jusqu'au premier 200. Constat N1 du 26/11, inchangé.

## 8. Suites
1. **Paul** : prendre acte de M3 invalide. M2, M4 et M5 sont conformes. La répétition §6.3 n'est pas déclarée réussie à ce stade.
2. **Manuel et BackAli** : rejouer M0–M3 et M6 sur un poste au calme et sur secteur (M3-1). Rejouer M4 et M5 sur la même stack, par cohérence.
3. **BackAli** : N2 (journal du moteur bcrypt), par une petite évolution avec test d'abord.
