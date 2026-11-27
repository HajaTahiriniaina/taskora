# Décisions de l'utilisateur

## 2026-11-26 : phase 1 (identity-service)
Les recommandations de Paul sont validées par l'utilisateur.

| Réf. | Décision |
|---|---|
| U1 | **B** : schéma propre `identity`, reprise des données existantes, projection dans la même transaction vers `taskora.user` (lue par Symfony pendant la cohabitation). |
| U2 | **a** : appel interne synchrone à un endpoint Symfony transitoire (`POST /internal/invitations/claim`, réseau interne, secret partagé), rejoué via l'outbox. `UserRegistered` est aussi publié dans l'outbox. Pas de RabbitMQ en phase 1. |
| U3 | Compatibilité stricte en phase 1 : E9 (limitation des tentatives de connexion) et E10 (révocation des jetons) ne sont pas traités maintenant. E9 sera traité avec la gateway. |
| U4 | Bascule avec coupure des écritures de moins de 5 minutes, annoncée. |
| E1 | Un non-membre reçoit **404** au lieu de 403, sur tous les services migrés. Le backend Symfony reste en 403 jusqu'à la migration de board-service. |
| E4 | Règle métier **confirmée** : tout membre peut modifier le tableau, ses listes, étiquettes et champs personnalisés. |
| E14 | **Corrigé** dans le backend Symfony : après un import Trello, `card_seq` vaut le plus grand numéro réellement attribué. |

## 2026-11-27 : latence et bascule d'identity-service

| Réf. | Décision |
|---|---|
| U5 | Les critères de latence L1 à L4 (PERF-IDENT-1) ne bloquent plus la bascule : « Tant que ça fonctionne, on règlera les mesures plus tard. » Ils deviennent un suivi après la bascule. L5 (aucune 5xx) reste exigé. Risque accepté : p95 de bout en bout non prouvé ; parades : `BcryptLatencyIT` en CI, retour arrière en ~1 s. Détail : [sortie de phase 1](../reviews/2026-11-27-identity-phase1-exit-paul.md). |

## 2026-11-26 : écarts corrigés dans identity-service (au lieu d'être reproduits)
Décision de l'utilisateur : **corriger** ces écarts dans identity-service. Le backend Symfony n'est pas modifié. Les tests `compat` excluent ces cas, qui sont des divergences voulues.

| Réf. | Comportement Symfony | Comportement identity-service |
|---|---|---|
| E15 | Mot de passe de plus de 4096 octets à l'inscription ou dans `newPassword` → 500 | **400** `{"error":"Le mot de passe ne doit pas dépasser 4096 octets."}`. À la connexion, la réponse reste 401 (identifiants invalides). |
| E16 | Corps `[]` accepté comme `{}` | `POST /api/auth/register` et `PATCH /api/me` exigent un **objet JSON** ; sinon **400** `{"error":"JSON invalide."}` |
| E17 | Conversions PHP `(string)` : nombre → texte, `true` → `"1"`, `false`/`null` → `""`, tableau → `"Array"` | Un champ présent (`email`, `password`, `name`, `avatarColor`, `currentPassword`, `newPassword`) doit être une **chaîne JSON** ; sinon **400** `{"error":"Le champ \"<nom>\" doit être une chaîne de caractères."}`. Exception pour `PATCH /api/me` : une valeur `null` vaut « absente », comme aujourd'hui. |
| E18 | `avatarColor` invalide ignorée en silence ; `\n` final accepté | `avatarColor` doit correspondre **exactement** à `#RRGGBB` (`\A#[0-9a-fA-F]{6}\z`) ; sinon **400** `{"error":"Couleur d'avatar invalide."}` |

## 2026-11-26 : interprétations de BackAli corrigées
Décision de l'utilisateur : **corriger** les trois points qui reprenaient encore le comportement de Symfony.

| Réf. | Comportement Symfony | Comportement identity-service |
|---|---|---|
| E19 | Connexion : messages anglais de `json_login` (`The key "email" must be provided.`, `… must be a string.`, `Invalid JSON.`) | `POST /api/auth/login` suit les mêmes règles que l'inscription, en français :<br>• corps qui n'est pas un objet JSON → 400 `JSON invalide.` (E16) ;<br>• champ absent → 400 `Le champ "email" est obligatoire.` / `Le champ "password" est obligatoire.` ;<br>• champ qui n'est pas une chaîne, `null` compris → 400 `Le champ "<nom>" doit être une chaîne de caractères.` (E17).<br>Identifiants faux → toujours 401 au format actuel. |
| E20 | Corps vide traité comme `{}` | Un corps vide sur `register`, `login` ou `PATCH /api/me` → 400 `JSON invalide.` |
| E21 | `newPassword: "0"` = « pas de changement » (`empty` de PHP) | Seuls l'absence, `null` et `""` signifient « pas de changement ». Toute autre chaîne, `"0"` compris, est un vrai nouveau mot de passe, soumis aux contrôles habituels (mot de passe actuel, puis longueur). Le front n'envoie `newPassword` que s'il fait au moins 8 caractères. |
