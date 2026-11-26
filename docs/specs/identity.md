# Identité : authentification, inscription, JWT, profil

Service cible : **identity-service** (premier service migré, voir [phase-1-identity.md](phase-1-identity.md)).
Code actuel : `AuthController`, `User`, `UserRepository`, `JwtSuccessListener`, `config/packages/security.yaml`, `config/packages/lexik_jwt_authentication.yaml` ; front : `pages/auth/*`, `pages/profile/*`, `core/auth.service.ts`, `core/auth.interceptor.ts`, `core/auth.guard.ts`.

## 1. Contexte et règles métier

### Modèle `User` (table `user`)
| Champ | Type / contrainte | Règle |
|---|---|---|
| `id` | INT auto-incrément | Exposé tel quel ; référencé par toutes les autres tables (membres, cartes, notifications…) |
| `email` | VARCHAR(180), **unique** | `trim` + minuscules à l'écriture ; identifiant de connexion ; **non modifiable** par l'API |
| `name` | VARCHAR(120) | `trim` ; obligatoire |
| `password` | VARCHAR(255) | Hash produit par le hasher Symfony `auto` ; jamais exposé |
| `avatar_color` | VARCHAR(20) | Tirée au hasard à la création dans la palette `#6366f1 #ec4899 #14b8a6 #f59e0b #8b5cf6 #ef4444 #0ea5e9 #22c55e` ; modifiable avec une couleur `#RRGGBB` |
| `roles` | JSON | `ROLE_USER` toujours ajouté ; aucun autre rôle utilisé aujourd'hui |
| `created_at` | DATETIME | Date d'inscription |

Représentation publique d'un utilisateur (`Presenter::user`), reprise partout : `{"id", "name", "email", "avatarColor"}`.

### Rôles
- **Anonyme** : accès à `/api/auth/*` et `/api/health` uniquement.
- **Utilisateur authentifié** (`ROLE_USER`) : tout le reste de `/api`. Il n'y a pas de rôle d'administration global.
- Les rôles propriétaire / admin / membre sont propres à chaque tableau (voir [boards-members.md](boards-members.md)).

### JWT (LexikJWTAuthenticationBundle)
- Sans état (`stateless: true`), transmis dans `Authorization: Bearer <jeton>`.
- Durée de vie : **604 800 s (7 jours)**, sans jeton de rafraîchissement ni révocation.
- Signature asymétrique avec la paire de clés générée au démarrage du conteneur `api` (`lexik:jwt:generate-keypair --skip-if-exists`, volume `jwt_keys`, clé privée protégée par `JWT_PASSPHRASE`).
  HYPOTHÈSE: algorithme **RS256** (valeur par défaut du bundle, aucun `signature_algorithm` configuré).
- Revendications (claims) : `iat`, `exp`, `roles`, `username` (= **e-mail** de l'utilisateur, `user_id_claim` par défaut). HYPOTHÈSE: aucune revendication `sub`, `iss`, `aud` ni identifiant numérique.
- À chaque requête, l'utilisateur est rechargé **par e-mail** depuis la base (provider `entity`, propriété `email`) : un jeton dont l'e-mail n'existe plus est refusé.
- Le front lit `exp` pour considérer un jeton expiré comme une déconnexion (`AuthService.isExpired`).

### Hachage des mots de passe
- `password_hashers: auto` (Symfony 8.1, PHP 8.4). HYPOTHÈSE: hashes **bcrypt** `$2y$` coût 13 ; des hashes `$argon2id$` sont possibles si l'extension sodium était présente. BackAli doit compter les préfixes en base (requête d'agrégat sur `LEFT(password, 7)`, sans afficher les hashes).
- Environnement de test : coût réduit (bcrypt 4).
- `UserRepository` n'implémente pas `PasswordUpgraderInterface` : aucun re-hachage à la connexion.

### Effet de bord de l'inscription (couplage avec le domaine tableaux)
À l'inscription, les **invitations en attente** (table `invitation`, même e-mail) sont transformées en adhésions (rôle `member`), une notification `board_invite` est créée pour le nouvel utilisateur (acteur = l'inviteur) et les invitations sont supprimées, dans la même transaction.

## 2. Routes HTTP actuelles (contrat à conserver)

| Méthode et route | Accès | Corps | Succès | Erreurs |
|---|---|---|---|---|
| `POST /api/auth/register` | public | `{"name","email","password"}` | **201** `{"token","user"}` | 400 `JSON invalide.` ; 400 `Le champ "name" est obligatoire.` ; 400 `Le champ "email" est obligatoire.` ; 400 `Adresse e-mail invalide.` ; 400 `Le mot de passe doit contenir au moins 8 caractères.` ; **409** `Un compte existe déjà avec cette adresse e-mail.` ; identity-service seulement (E15–E17 corrigés) : 400 `Le mot de passe ne doit pas dépasser 4096 octets.`, 400 `Le champ "<nom>" doit être une chaîne de caractères.`, 400 `JSON invalide.` pour un corps vide (E20) ou un JSON qui n'est pas un objet |
| `POST /api/auth/login` | public | `{"email","password"}` (JSON) | **200** `{"token","user"}` | **401** `{"code":401,"message":"Invalid credentials."}` (HYPOTHÈSE: message par défaut Lexik) ; 400 si champ manquant ou JSON invalide (Symfony : messages anglais de `json_login`, voir architecture §8.4) ; identity-service (E19, E20 corrigés) : 400 `JSON invalide.`, `Le champ "<nom>" doit être une chaîne de caractères.`, `Le champ "<nom>" est obligatoire.` |
| `GET /api/me` | JWT | — | **200** utilisateur | 401 |
| `PATCH /api/me` | JWT | `{"name"?, "avatarColor"?, "currentPassword"?, "newPassword"?}` | **200** utilisateur | 400 `Le champ "name" est obligatoire.` ; 400 `Mot de passe actuel incorrect.` ; 400 `Le mot de passe doit contenir au moins 8 caractères.` ; 400 `JSON invalide.` ; 401 ; identity-service seulement (E15–E18, E20, E21 corrigés) : 400 `Le mot de passe ne doit pas dépasser 4096 octets.`, 400 `Le champ "<nom>" doit être une chaîne de caractères.`, 400 `Couleur d'avatar invalide.`, 400 `JSON invalide.` pour un corps vide ou un JSON qui n'est pas un objet ; `newPassword` `"0"` contrôlé comme un vrai mot de passe |
| `GET /api/health` | public | — | **200** `{"status":"ok","app":"Taskora"}` | — |
| toute autre route `/api/**` sans jeton valide | — | — | — | **401** `{"code":401,"message":"JWT Token not found" \| "Expired JWT Token" \| "Invalid JWT Token"}` (HYPOTHÈSE: messages Lexik) |

Ordre des contrôles de `register` (Symfony) : JSON → `name` → `email` (obligatoire, puis format) → longueur du mot de passe → unicité.
Ordre dans identity-service (décisions E15–E17, E20) : corps = objet JSON (corps vide → 400 `JSON invalide.`) → type des champs présents, dans l'ordre `name`, `email`, `password` (chaîne JSON, `null` compris refusé) → `name` → `email` → longueur minimale puis maximale (4096 octets UTF-8) du mot de passe → unicité.
Ordre des contrôles de `PATCH /me` (Symfony) : `name` (si la clé est présente) → `avatarColor` (ignorée si invalide) → mot de passe (si `newPassword` non vide : mot de passe actuel, puis longueur). L'enregistrement n'a lieu qu'à la fin : **une erreur n'applique aucune modification**.
Ordre de `login` dans identity-service (E19, E20) : corps = objet JSON (vide → 400 `JSON invalide.`) → type de `email` puis `password` (chaîne JSON, `null` refusé) → présence de `email` puis `password` (clé absente ou `""` → `Le champ "<nom>" est obligatoire.`) → recherche de l'e-mail tel que saisi et vérification → 401 si refus.
Ordre de `PATCH /me` dans identity-service (décisions E15–E18, E20, E21) : corps = objet JSON (vide → 400) → type des champs présents et non `null`, dans l'ordre `name`, `avatarColor`, `currentPassword`, `newPassword` → `name` → `avatarColor` (exactement `#RRGGBB`, sinon 400) → mot de passe (si `newPassword` est présent, non `null` et différent de `""`, `"0"` compris : mot de passe actuel, longueur minimale, longueur maximale). Toujours sans modification partielle.

## 3. Comportements attendus
1. Un visiteur crée un compte et est connecté immédiatement (jeton renvoyé), puis redirigé vers `/boards`.
2. Un utilisateur se connecte par e-mail et mot de passe ; le jeton et le profil sont stockés dans `localStorage` (`taskora.token`, `taskora.user`).
3. Toute réponse **401** hors `/auth/` déconnecte le front et affiche « Votre session a expiré, reconnectez-vous. »
4. L'utilisateur modifie son nom, la couleur de son avatar et son mot de passe depuis `/profile`.
5. La déconnexion est locale (suppression du jeton) ; elle désabonne d'abord ce navigateur des notifications push (voir notifications-push.md).

## 4. Scénarios

### SC-IDENT-001 — Inscription nominale (critique)
Étant donné aucun compte pour `camille@exemple.fr`
Quand un visiteur envoie `POST /api/auth/register` avec `{"name":"  Camille Martin ","email":" Camille@Exemple.FR ","password":"motdepasse"}`
Alors la réponse est 201 avec un `token` JWT valide et `user` = `{"id", "name":"Camille Martin", "email":"camille@exemple.fr", "avatarColor"}` ; `avatarColor` appartient à la palette ; le mot de passe est stocké haché
Limites : le corps de réponse ne contient jamais `password` ni hash
Validation : unitaire (domaine `User`, normalisation), API, intégration (persistance) [back] ; e2e page Inscription [front] — Acceptation : statut, schéma OpenAPI et normalisation vérifiés ; le hash en base commence par un préfixe reconnu (`$2y$`, `$2a$`, `$2b$` ou `$argon2id$`)

### SC-IDENT-002 — Inscription avec un e-mail déjà utilisé (critique)
Étant donné un compte `camille@exemple.fr`
Quand un visiteur s'inscrit avec `CAMILLE@exemple.fr`
Alors la réponse est 409 `{"error":"Un compte existe déjà avec cette adresse e-mail."}` et aucun compte n'est créé
Limites : deux inscriptions simultanées avec le même e-mail → un seul compte (contrainte unique) ; la seconde reçoit 409, jamais 500
Validation : API, intégration (concurrence) [back] ; e2e (message affiché sous le formulaire) [front] — Acceptation : 409 dans les deux cas, une seule ligne en base

### SC-IDENT-003 — Validation des champs d'inscription (critique)
Étant donné un visiteur
Quand il s'inscrit avec : `name` vide ou composé d'espaces ; `email` absent ; `email` = `pas-un-email` ; `password` de 7 caractères ; corps `"texte"` non JSON
Alors la réponse est 400 avec respectivement `Le champ "name" est obligatoire.`, `Le champ "email" est obligatoire.`, `Adresse e-mail invalide.`, `Le mot de passe doit contenir au moins 8 caractères.`, `JSON invalide.`
Limites : mot de passe de **8** caractères exactement → accepté ; la longueur est comptée en caractères Unicode (`mb_strlen`) : `ééééééé` (7) refusé ; maximum technique de 4096 octets (voir SC-IDENT-028) ; au-delà de 72 octets, Symfony pré-hache le mot de passe (`base64(sha512)`) : pas de troncature silencieuse, comportement reproduit (architecture A2)
Validation : unitaire (validateurs), API (tests paramétrés) [back] ; composant (bouton désactivé si `minlength` non atteint) [front] — Acceptation : chaque cas renvoie le message exact

### SC-IDENT-004 — Longueurs maximales à l'inscription (important)
Étant donné un visiteur
Quand il s'inscrit avec un `name` de 150 caractères
Alors le compte est créé avec le nom tronqué à 120 caractères (pas d'erreur)
Limites : `email` de plus de 180 caractères → tronqué à 180 **avant** la validation de format (peut donc devenir invalide → 400) ; HYPOTHÈSE: la troncature silencieuse est conservée pour la compatibilité
Validation : API [back] — Acceptation : longueur stockée = 120

### SC-IDENT-005 — L'inscription consomme les invitations en attente (critique)
Étant donné une invitation en attente pour `camille@exemple.fr` sur les tableaux « Sprint » (invitée par Alice) et « Roadmap »
Quand Camille s'inscrit avec cet e-mail
Alors Camille est membre (`member`) des deux tableaux, reçoit une notification `board_invite` par tableau (« Alice vous a ajouté au tableau « Sprint » »), et les invitations sont supprimées
Limites : tout ou rien (si l'adhésion échoue, le compte n'est pas créé, ou l'opération est rejouable de façon idempotente : décision d'architecture, voir phase-1) ; un tableau dans la corbeille reçoit quand même l'adhésion (comportement actuel)
Validation : intégration (transaction), API, compatibilité (pendant la coexistence avec Symfony) [back] ; e2e (inscription puis tableau visible sur `/boards`) [front] — Acceptation : adhésions, notifications et suppression des invitations constatées

### SC-IDENT-006 — Invitation dont l'auteur n'existe plus (secondaire)
Étant donné une invitation dont `invited_by` est NULL
Quand l'invité s'inscrit
Alors il devient membre et la notification indique « Quelqu'un vous a ajouté au tableau « … » » avec un acteur nul
Validation : intégration [back] — Acceptation : message exact

### SC-IDENT-007 — Connexion nominale (critique)
Étant donné un compte `camille@exemple.fr` / `motdepasse`
Quand elle envoie `POST /api/auth/login` `{"email":"camille@exemple.fr","password":"motdepasse"}`
Alors la réponse est 200 `{"token","user"}` ; le jeton donne accès à `GET /api/me`
Limites : e-mail saisi avec une autre casse (`Camille@Exemple.fr`) → connexion acceptée (HYPOTHÈSE: grâce à la collation `utf8mb4_unicode_ci` ; la cible doit normaliser explicitement en minuscules) ; espaces autour de l'e-mail : HYPOTHÈSE refusés aujourd'hui (pas de `trim` à la connexion) — à confirmer par test miroir
Validation : API, compatibilité [back] ; e2e page Connexion [front] — Acceptation : 200 et jeton accepté par **les deux** backends pendant la coexistence

### SC-IDENT-008 — Connexion refusée sans fuite d'information (critique)
Étant donné un compte existant
Quand on se connecte avec un mauvais mot de passe, ou avec un e-mail inconnu
Alors la réponse est 401 avec **le même corps** dans les deux cas ; le front affiche « E-mail ou mot de passe incorrect. »
Limites : temps de réponse comparable dans les deux cas (pas d'oracle temporel flagrant : écart médian < 50 %) ; le mot de passe n'apparaît pas dans les journaux
Validation : API, sécurité [back] ; e2e [front] — Acceptation : corps identiques ; aucun mot de passe dans les logs capturés

### SC-IDENT-009 — Connexion avec un corps incomplet (important)
Étant donné un visiteur
Quand il envoie `POST /api/auth/login` sans `password`, ou avec un corps non JSON
Alors la réponse est 400
Limites : Symfony répond avec les messages anglais de `json_login` (architecture §8.4) ; identity-service applique les règles françaises de SC-IDENT-032 et SC-IDENT-033 (décisions E19, E20) : seul le **statut** 400 est commun, les corps sont une divergence voulue, exclue des tests `compat`
Validation : API [back] — Acceptation : 400 dans tous les cas, messages de SC-IDENT-032/033 pour identity-service

### SC-IDENT-010 — Contenu et signature du JWT (critique)
Étant donné une connexion réussie
Quand on décode le jeton
Alors l'en-tête indique l'algorithme asymétrique attendu (HYPOTHÈSE: RS256), `exp − iat = 604800`, `username` = e-mail, `roles` contient `ROLE_USER`
Limites : un jeton émis par identity-service est accepté par Symfony et inversement pendant la coexistence (même paire de clés) ; revendications ajoutées par identity-service (ex. `uid`) ignorées par Symfony
Validation : unitaire (émetteur), intégration, compatibilité [back] — Acceptation : test croisé vert dans les deux sens

### SC-IDENT-011 — Accès sans jeton (critique)
Étant donné un visiteur sans en-tête `Authorization`
Quand il appelle une route protégée (`GET /api/me`, `GET /api/boards`…)
Alors la réponse est 401 ; `/api/auth/register`, `/api/auth/login` et `/api/health` restent accessibles
Validation : API, sécurité (parcours de toutes les routes de l'OpenAPI) [back] — Acceptation : 401 sur 100 % des routes protégées

### SC-IDENT-012 — Jeton invalide (critique)
Étant donné un jeton expiré, un jeton à la signature altérée, un jeton `alg: none`, un jeton HS256 signé avec la clé publique, ou un jeton signé par une autre clé
Quand il est utilisé
Alors la réponse est 401 dans tous les cas
Validation : sécurité [back] — Acceptation : aucun de ces jetons n'est accepté

### SC-IDENT-013 — Jeton d'un compte disparu (important)
Étant donné un jeton valide dont l'e-mail n'existe plus en base
Quand il est utilisé
Alors la réponse est 401
Limites : HYPOTHÈSE: comportement du provider `entity` ; la cible doit vérifier l'existence du compte (au moins dans identity-service ; la gateway peut se contenter de la signature)
Validation : API, sécurité [back] — Acceptation : 401

### SC-IDENT-014 — Lire son profil (critique)
Étant donné un utilisateur connecté
Quand il appelle `GET /api/me`
Alors il reçoit `{"id","name","email","avatarColor"}` de **son** compte uniquement
Validation : API [back] ; composant (`AuthService.refreshProfile`) [front] — Acceptation : schéma conforme

### SC-IDENT-015 — Modifier son nom (important)
Étant donné un utilisateur connecté
Quand il envoie `PATCH /api/me` `{"name":"  Camille M. "}`
Alors le nom devient `Camille M.` et la réponse 200 contient le profil à jour ; le nom affiché dans l'en-tête et sur les tableaux change
Limites : `{"name":""}` → 400 `Le champ "name" est obligatoire.` ; clé absente → nom inchangé ; 150 caractères → tronqué à 120 ; `{"name":null}` → nom inchangé (`isset`)
Validation : API [back] ; e2e page Profil [front] — Acceptation : persistance et messages exacts

### SC-IDENT-016 — Modifier la couleur de l'avatar (secondaire)
Étant donné un utilisateur connecté
Quand il envoie `PATCH /api/me` `{"avatarColor":"#0EA5E9"}`
Alors la couleur est enregistrée telle quelle
Limites : valeur invalide (`"red"`, `"#12345"`, `"#1234567"`) → Symfony l'**ignore** (200, couleur inchangée) ; identity-service répond **400** (décision E18, voir SC-IDENT-031)
Validation : API [back] ; e2e page Profil [front] — Acceptation : valeur stockée ou inchangée selon le cas

### SC-IDENT-017 — Changer son mot de passe (critique)
Étant donné un utilisateur dont le mot de passe est `ancienmdp1`
Quand il envoie `PATCH /api/me` `{"currentPassword":"ancienmdp1","newPassword":"nouveaumdp1"}`
Alors la réponse est 200 ; la connexion avec `nouveaumdp1` réussit et celle avec `ancienmdp1` échoue (401)
Validation : API, intégration [back] ; e2e page Profil (toast « Mot de passe modifié », champs vidés) [front] — Acceptation : les deux connexions donnent le résultat attendu

### SC-IDENT-018 — Changement de mot de passe refusé (critique)
Étant donné un utilisateur connecté
Quand il envoie un `currentPassword` faux ; ou un `newPassword` de 7 caractères avec le bon mot de passe actuel
Alors 400 `Mot de passe actuel incorrect.` ; ou 400 `Le mot de passe doit contenir au moins 8 caractères.` ; le mot de passe n'est pas modifié
Limites : `newPassword` absent, `null` ou `""` → aucune vérification, aucun changement ; `"0"` → contrôlé comme tout mot de passe dans identity-service (SC-IDENT-034, E21) ; `currentPassword` absent avec `newPassword` → 400 `Mot de passe actuel incorrect.`
Validation : unitaire, API [back] ; composant (bouton désactivé tant que `newPassword` < 8) [front] — Acceptation : hash inchangé en base

### SC-IDENT-019 — Une modification de profil en erreur n'est pas appliquée partiellement (important)
Étant donné un utilisateur connecté nommé « Camille »
Quand il envoie `{"name":"Camille M.","currentPassword":"faux","newPassword":"nouveaumdp1"}`
Alors la réponse est 400 et le nom reste « Camille »
Validation : intégration [back] — Acceptation : aucune donnée modifiée

### SC-IDENT-020 — L'e-mail n'est pas modifiable (important)
Étant donné un utilisateur connecté
Quand il envoie `PATCH /api/me` `{"email":"autre@exemple.fr"}`
Alors la réponse est 200 et l'e-mail est inchangé (le JWT, fondé sur l'e-mail, reste valide)
Validation : API, sécurité [back] — Acceptation : e-mail inchangé

### SC-IDENT-021 — Jetons existants après changement de mot de passe (secondaire)
Étant donné un utilisateur connecté sur deux navigateurs
Quand il change son mot de passe sur l'un
Alors le jeton de l'autre navigateur reste valide jusqu'à son expiration (comportement actuel, écart E10)
Validation : API [back] — Acceptation : comportement documenté et testé ; à inverser seulement sur décision

### SC-IDENT-022 — Point de santé (secondaire)
Étant donné le service démarré
Quand on appelle `GET /api/health` sans jeton
Alors 200 `{"status":"ok","app":"Taskora"}`
Limites : identity-service expose en plus `/actuator/health` pour Docker (non routé publiquement)
Validation : API [back] ; CI (healthcheck Docker) [Manuel] — Acceptation : 200 sans authentification

### SC-IDENT-023 — Compatibilité des mots de passe migrés (critique)
Étant donné des comptes créés par Symfony (hashes existants, tous préfixes présents en base)
Quand ils se connectent via identity-service après la reprise des données
Alors la connexion réussit avec le mot de passe d'origine ; un compte créé ou modifié par identity-service peut se connecter via Symfony (tant que Symfony sert encore `/api/auth/login`, si c'est le cas)
Limites : jeux d'essai couvrant `$2y$` coût 13, `$2y$` coût 4, `$2a$`, `$2b$` et `$argon2id$` ; hash inconnu ou corrompu → 401, jamais 500
Validation : unitaire (encodeur délégant), intégration (base reprise), compatibilité [back] — Acceptation : 100 % des comptes de l'échantillon se connectent

### SC-IDENT-024 — Les secrets ne fuient pas (critique)
Étant donné n'importe quelle réponse de l'API ou ligne de journal
Quand on y cherche le mot de passe saisi, le hash ou la clé privée JWT
Alors on ne les trouve jamais ; les erreurs 5xx renvoient `Erreur interne du serveur.` sans trace
Validation : sécurité (capture des logs pendant les tests API), revue de code [back] — Acceptation : aucune occurrence

### SC-IDENT-025 — Expiration de session côté front (important)
Étant donné un utilisateur connecté dont le jeton expire ou est refusé
Quand une requête API reçoit 401 (hors `/auth/`)
Alors le front supprime le jeton, redirige vers `/login` et affiche « Votre session a expiré, reconnectez-vous. »
Limites : un 401 sur `/api/auth/login` n'affiche pas ce toast (message propre au formulaire)
Validation : composant (intercepteur), e2e [front] — Acceptation : redirection et toast constatés

### SC-IDENT-026 — Gardes de navigation (important)
Étant donné un visiteur non connecté (ou dont le jeton stocké est expiré)
Quand il ouvre `/boards`, `/boards/12` ou `/profile`
Alors il est redirigé vers `/login` ; inversement un utilisateur connecté qui ouvre `/`, `/login` ou `/register` est redirigé vers `/boards`
Limites : jeton illisible dans `localStorage` → considéré comme expiré ; `localStorage` indisponible → pas d'erreur bloquante
Validation : composant (guards), e2e [front] — Acceptation : redirections constatées

### SC-IDENT-027 — Limitation des tentatives de connexion (secondaire)
Étant donné un attaquant
Quand il envoie un grand nombre de tentatives de connexion
Alors aujourd'hui rien ne les limite (écart E9). Cible proposée, **sous réserve de décision** : 429 après N échecs par IP et par e-mail sur une fenêtre glissante, appliqué par la gateway
Validation : sécurité [back], CI [Manuel] — Acceptation : à définir après décision

### SC-IDENT-028 — Mot de passe trop long (important, écart E15 corrigé)
Étant donné un visiteur, ou un utilisateur connecté dont le mot de passe actuel est correct
Quand il s'inscrit avec un `password` de 4097 octets UTF-8, ou envoie `PATCH /api/me` avec un `newPassword` de 4097 octets
Alors identity-service répond 400 `{"error":"Le mot de passe ne doit pas dépasser 4096 octets."}` ; aucun compte n'est créé, le mot de passe n'est pas modifié
Limites : 4096 octets exactement → accepté ; la limite est en **octets** (2049 `é` = 4098 octets → refus) ; contrôle après la longueur minimale ; `PATCH` : après la vérification du mot de passe actuel ; à la connexion, un mot de passe de plus de 4096 octets → 401 `Invalid credentials.` (inchangé) ; Symfony répond 500 : cas exclu des tests `compat`
Validation : unitaire (domaine), API [back] — Acceptation : 400 et message exact, rien d'écrit

### SC-IDENT-029 — Le corps doit être un objet JSON (important, écart E16 corrigé)
Étant donné un visiteur ou un utilisateur connecté
Quand il envoie à `POST /api/auth/register` ou `PATCH /api/me` le corps `[]`, `[1,2]`, `"texte"`, `42` ou `true`
Alors identity-service répond 400 `{"error":"JSON invalide."}` et rien n'est écrit
Limites : corps vide → voir SC-IDENT-033 ; `{}` accepté (`register` → 400 `Le champ "name" est obligatoire.`, `PATCH` → 200 sans changement) ; s'applique aussi à `POST /api/auth/login` (E19 : 400 `JSON invalide.` au lieu de `Invalid JSON.`) ; Symfony accepte un tableau JSON : cas exclu des tests `compat`
Validation : API [back] — Acceptation : 400 et message exact pour chaque corps

### SC-IDENT-030 — Les champs texte doivent être des chaînes JSON (important, écart E17 corrigé)
Étant donné un visiteur ou un utilisateur connecté
Quand un champ présent parmi `name`, `email`, `password` (inscription) ou `name`, `avatarColor`, `currentPassword`, `newPassword` (`PATCH /api/me`) est un nombre, un booléen, un tableau ou un objet
Alors identity-service répond 400 `{"error":"Le champ \"<nom>\" doit être une chaîne de caractères."}` (ex. `Le champ "name" doit être une chaîne de caractères.`) et rien n'est écrit
Limites : inscription : `null` est aussi refusé avec ce message ; `PATCH /api/me` : `null` vaut « absent » (inchangé) ; plusieurs champs invalides → message du premier dans l'ordre indiqué ; ce contrôle précède les contrôles de contenu (`{"name":123,"email":"x"}` → message sur `name`) ; chaîne vide → règles existantes (`Le champ "name" est obligatoire.`) ; clés inconnues ignorées quel que soit leur type ; s'applique aussi à `POST /api/auth/login` pour `email` et `password`, `null` compris (E19, voir SC-IDENT-032) ; Symfony convertit à la manière de PHP : cas exclus des tests `compat`
Validation : unitaire (lecture du corps), API (tests paramétrés : champ × type) [back] — Acceptation : message exact pour chaque couple champ × type

### SC-IDENT-031 — Couleur d'avatar invalide refusée (secondaire, écart E18 corrigé)
Étant donné un utilisateur connecté nommé « Camille »
Quand il envoie `PATCH /api/me` avec `avatarColor` = `"red"`, `"#12345"`, `"#1234567"`, `""`, `"#0EA5E9\n"`, `" #0EA5E9"` ou `"#GGGGGG"`
Alors identity-service répond 400 `{"error":"Couleur d'avatar invalide."}` ; ni la couleur ni aucun autre champ envoyé (ex. `name`) n'est modifié
Limites : `#0ea5e9` et `#0EA5E9` acceptés et stockés tels quels ; `null` ou clé absente → couleur inchangée ; ordre : après `name`, avant le mot de passe (`{"name":"","avatarColor":"red"}` → message sur `name`) ; Symfony ignore la valeur : cas exclu des tests `compat`
Validation : unitaire (`AvatarColor`), API [back] ; e2e page Profil (le sélecteur n'envoie que des couleurs valides) [front] — Acceptation : 400, message exact, profil inchangé

### SC-IDENT-032 — Connexion : corps et champs validés en français (important, écart E19 corrigé)
Étant donné un visiteur
Quand il envoie `POST /api/auth/login` avec : `[]` ou `"texte"` ; `{"email":123,"password":"x"}` ; `{"email":"a@b.c","password":null}` ; `{"password":"motdepasse"}` ; `{"email":"camille@exemple.fr"}` ; `{"email":"","password":"x"}`
Alors identity-service répond 400 avec respectivement `JSON invalide.` ; `Le champ "email" doit être une chaîne de caractères.` ; `Le champ "password" doit être une chaîne de caractères.` ; `Le champ "email" est obligatoire.` ; `Le champ "password" est obligatoire.` ; `Le champ "email" est obligatoire.`
Limites : ordre = forme du corps → type de `email` → type de `password` → présence de `email` → présence de `password` (`{"email":123}` → message de type sur `email`) ; `"password":""` → `Le champ "password" est obligatoire.` ; l'e-mail n'est ni tronqué ni mis en minuscules (recherche par collation, SC-IDENT-007) : `" camille@exemple.fr"` → 401 ; identifiants faux → 401 `{"code":401,"message":"Invalid credentials."}` inchangé (SC-IDENT-008) ; le `Content-Type` n'est pas contrôlé (plus de 404) ; Symfony répond en anglais : cas exclus des tests `compat`
Validation : unitaire (`JsonLoginRequestParser`), API (paramétré) [back] — Acceptation : message exact pour chaque cas

### SC-IDENT-033 — Corps vide refusé (important, écart E20 corrigé)
Étant donné un visiteur, ou un utilisateur connecté
Quand il envoie un corps vide (0 octet) à `POST /api/auth/register`, `POST /api/auth/login` ou `PATCH /api/me`
Alors identity-service répond 400 `{"error":"JSON invalide."}` et rien n'est écrit
Limites : un corps ne contenant que des espaces est aussi du JSON invalide → même réponse ; `PATCH /api/me` sans jeton → 401 d'abord ; `{}` reste accepté (règles de SC-IDENT-029) ; Symfony traite le corps vide comme `{}` (et `Invalid JSON.` au login) : cas exclus des tests `compat`
Validation : API [back] — Acceptation : 400 et message exact sur les trois routes

### SC-IDENT-034 — `newPassword` "0" est un vrai mot de passe (secondaire, écart E21 corrigé)
Étant donné un utilisateur connecté dont le mot de passe est `ancienmdp1`
Quand il envoie `PATCH /api/me` `{"currentPassword":"ancienmdp1","newPassword":"0"}`, puis `{"newPassword":"0"}`
Alors la première requête répond 400 `Le mot de passe doit contenir au moins 8 caractères.`, la seconde 400 `Mot de passe actuel incorrect.` ; le mot de passe n'est pas modifié
Limites : seuls `newPassword` absent, `null` ou `""` signifient « pas de changement » ; `" "` (espace) est contrôlé comme les autres chaînes ; Symfony ignore `"0"` (200) : cas exclu des tests `compat` ; le front n'envoie `newPassword` que s'il fait au moins 8 caractères
Validation : unitaire (`ProfileUpdate`), API [back] — Acceptation : messages exacts, hash inchangé

## 5. Cas critiques et limites (synthèse)
- Valeurs vides : `name` d'espaces, mot de passe vide → 400 attendus (SC-IDENT-003) ; corps vide → 400 `JSON invalide.` sur `register`, `login`, `PATCH /me` (SC-IDENT-033) ; `newPassword` `"0"` = vrai mot de passe (SC-IDENT-034).
- Valeurs maximales : nom 120, e-mail 180 (SC-IDENT-004) ; mot de passe 4096 octets (SC-IDENT-028).
- Types et formes : corps non objet (SC-IDENT-029), champ non textuel (SC-IDENT-030), couleur invalide (SC-IDENT-031) : écarts corrigés dans identity-service, exclus des tests `compat`.
- Concurrence : double inscription simultanée (SC-IDENT-002) ; inscription concurrente avec un ajout de membre sur le même e-mail (l'invitation et l'adhésion ne doivent pas être créées deux fois).
- IDOR : `/api/me` ne prend aucun identifiant ; il n'existe aucune route de lecture d'un autre utilisateur.
- Coexistence : jetons croisés, hashes croisés (SC-IDENT-010, SC-IDENT-023).
- Services externes : aucun (pas d'envoi d'e-mail aujourd'hui).

## 6. Exigences non fonctionnelles
- **Performance** : `POST /api/auth/login` p95 < 500 ms (hachage compris, coût 13) ; `GET /api/me` p95 < 50 ms hors réseau ; validation du JWT sans appel réseau (clé publique en mémoire).
- **Sécurité** : clé privée lue depuis un volume ou un secret, jamais dans l'image ni dans Git ; comparaison de hash à temps constant ; dépendances sans CVE critique (OWASP Dependency-Check) ; CORS limité à l'origine du front.
- **Accessibilité** : formulaires avec `<label for>` ; bouton « Afficher le mot de passe » avec `aria-label` ; messages d'erreur annoncés (`role="alert"` à ajouter : HYPOTHÈSE, absent aujourd'hui) ; navigation au clavier complète.
