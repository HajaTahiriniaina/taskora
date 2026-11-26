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

## 2026-11-26 : écarts corrigés dans identity-service (au lieu d'être reproduits)
Décision de l'utilisateur : **corriger** ces écarts dans identity-service. Le backend Symfony n'est pas modifié. Les tests `compat` excluent ces cas, qui sont des divergences voulues.

| Réf. | Comportement Symfony | Comportement identity-service |
|---|---|---|
| E15 | Mot de passe de plus de 4096 octets à l'inscription ou dans `newPassword` → 500 | **400** `{"error":"Le mot de passe ne doit pas dépasser 4096 octets."}`. À la connexion, la réponse reste 401 (identifiants invalides). |
| E16 | Corps `[]` accepté comme `{}` | `POST /api/auth/register` et `PATCH /api/me` exigent un **objet JSON** ; sinon **400** `{"error":"JSON invalide."}` |
| E17 | Conversions PHP `(string)` : nombre → texte, `true` → `"1"`, `false`/`null` → `""`, tableau → `"Array"` | Un champ présent (`email`, `password`, `name`, `avatarColor`, `currentPassword`, `newPassword`) doit être une **chaîne JSON** ; sinon **400** `{"error":"Le champ \"<nom>\" doit être une chaîne de caractères."}`. Exception pour `PATCH /api/me` : une valeur `null` vaut « absente », comme aujourd'hui. |
| E18 | `avatarColor` invalide ignorée en silence ; `\n` final accepté | `avatarColor` doit correspondre **exactement** à `#RRGGBB` (`\A#[0-9a-fA-F]{6}\z`) ; sinon **400** `{"error":"Couleur d'avatar invalide."}` |
