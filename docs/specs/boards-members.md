# Tableaux, membres et invitations

Service cible : **board-service**.
Code actuel : `BoardController`, `BoardAccess`, `Board`, `BoardMember`, `Invitation`, `BoardRepository`, `Presenter::board*` ; front : `pages/dashboard`, `pages/board/board.component.ts`, `pages/board/board-settings.component.ts` (onglets Membres et Général).

## 1. Contexte et règles métier

### Rôles sur un tableau (`BoardMember.role`)
| Rôle | Attribution | Droits spécifiques |
|---|---|---|
| `owner` (propriétaire) | Créateur du tableau (ou importateur Trello) ; unique ; non transférable | Tout ; seul à pouvoir supprimer (mettre à la corbeille), restaurer ou purger le tableau ; ne peut pas quitter ni être retiré ; son rôle n'est pas modifiable |
| `admin` | Donné par un gestionnaire | « Gestionnaire » (`canManage`) : inviter, changer les rôles, retirer des membres, annuler des invitations, purger listes/cartes de la corbeille, supprimer commentaires et pièces jointes des autres, transférer les fichiers Trello |
| `member` | Rôle par défaut | Lire et modifier tout le contenu du tableau ; quitter le tableau |
| non-membre | — | Aucun accès (403, écart E1) |

Il n'existe pas de rôle « invité » distinct : une **invitation** concerne un e-mail sans compte et devient une adhésion `member` à l'inscription.

### Modèle `Board`
`id`, `title` (≤ 160, obligatoire), `description` (texte libre, nullable), `background` (une des 10 valeurs `aurora sunset ocean forest midnight peach lagoon berry graphite sand`, défaut `aurora`), `showCardNumbers` (défaut `true`), `cardSeq` (dernier numéro de carte attribué), `owner`, `createdAt`, `deletedAt`/`deletedBy` (corbeille). Par membre : `favorite` (booléen propre à chaque membre), `joinedAt`.

### Création
- Le créateur devient `owner`.
- `withDefaults` (défaut `true`) : 4 listes « À faire », « En cours », « En revue », « Terminé » (positions 0 à 3) et 6 étiquettes : Urgent `#ef4444`, Bug `#f97316`, Feature `#6366f1`, Design `#ec4899`, Backend `#14b8a6`, Idée `#eab308`.
- `background` hors liste → ignoré (défaut).

## 2. Routes HTTP actuelles

| Méthode et route | Droit | Succès | Erreurs principales |
|---|---|---|---|
| `GET /api/boards` | connecté | 200 `[BoardSummary]` (tableaux dont je suis membre, hors corbeille) | 401 |
| `POST /api/boards` `{title, description?, background?, withDefaults?}` | connecté | 201 `Board` (coquille, listes à `cardCount: 0`) | 400 titre |
| `GET /api/boards/{id}` | membre | 200 `Board` : résumé + `trelloFiles`, `lists` (ouvertes, sans cartes, avec `cardCount`), `labels`, `customFields`, `invitations` | 404 inexistant / corbeille ; 403 non-membre |
| `PATCH /api/boards/{id}` `{title?, description?, background?, showCardNumbers?}` | **membre** (écart E4) | 200 `BoardSummary` | 400 titre vide ; 403 ; 404 |
| `DELETE /api/boards/{id}` | propriétaire | 204 (mise à la corbeille) | 403 `Seul le propriétaire peut supprimer le tableau.` ; 404 |
| `POST /api/boards/{id}/favorite` | membre | 200 `{"favorite": bool}` (bascule) | 403 ; 404 |
| `POST /api/boards/{id}/members` `{email, role?}` | gestionnaire | 201 `{"status":"added","member"}` ; **202** `{"status":"invited","message"}` | 400 e-mail obligatoire / invalide ; 403 ; 404 ; **409** déjà membre ; **409** invitation déjà en attente |
| `PATCH /api/boards/{id}/members/{userId}` `{role}` | gestionnaire | 200 `Member` | 400 `Rôle invalide.` ; 403 propriétaire ; 404 `Membre introuvable.` / utilisateur inexistant |
| `DELETE /api/boards/{id}/members/{userId}` | soi-même : membre ; autrui : gestionnaire | 204 | 403 `Le propriétaire ne peut pas quitter son tableau.` ; 404 |
| `DELETE /api/boards/{id}/invitations/{invId}` | gestionnaire | 204 | 404 (invitation d'un autre tableau) |

Représentations :
- `BoardSummary` : `id, title, description, background, showCardNumbers, favorite, role, members[], listCount, cardCount, createdAt`.
- `Member` : utilisateur + `role`, `joinedAt`.
- `Board` : `BoardSummary` + `trelloFiles` (nombre de fichiers Trello restant à copier), `lists[]`, `labels[]`, `customFields[]`, `invitations[] {id, email, createdAt}`.
- Les compteurs `listCount`/`cardCount` ignorent les listes et cartes archivées ou dans la corbeille.

## 3. Comportements attendus
1. L'accueil `/boards` liste mes tableaux (favoris en tête), avec recherche locale par titre ou description, compteurs et statistiques (cartes, collaborateurs).
2. Créer un tableau ouvre directement `/boards/{id}`.
3. Inviter par e-mail : ajout immédiat si le compte existe (notifications), sinon invitation en attente affichée dans l'onglet Membres et consommée à l'inscription.
4. Retirer un membre le désassigne de toutes les cartes du tableau.
5. Un membre peut quitter un tableau ; le propriétaire non.

## 4. Scénarios

### SC-BOARD-001 — Créer un tableau avec le contenu par défaut (critique)
Étant donné un utilisateur connecté
Quand il envoie `POST /api/boards` `{"title":"Sprint 12","background":"ocean"}`
Alors 201 ; il est `owner` ; le tableau a 4 listes (« À faire », « En cours », « En revue », « Terminé », positions 0..3) et 6 étiquettes par défaut ; `background` = `ocean` ; `showCardNumbers` = true
Limites : `withDefaults:false` → aucune liste ni étiquette ; `background:"rainbow"` → `aurora` ; titre de 200 caractères → tronqué à 160 ; titre vide → 400 `Le champ "title" est obligatoire.`
Validation : unitaire (fabrique de tableau), API [back] ; e2e accueil → création → tableau [front] — Acceptation : contenu par défaut exact

### SC-BOARD-002 — Lister mes tableaux (critique)
Étant donné Alice membre des tableaux A et B, et un tableau C dont elle n'est pas membre, et un tableau D d'Alice dans la corbeille
Quand elle appelle `GET /api/boards`
Alors elle reçoit A et B uniquement, chacun avec `role`, `favorite` (le sien), `members`, `listCount`, `cardCount` exacts
Limites : 0 tableau → `[]` ; performance avec 200 tableaux (compteurs en requêtes groupées, pas de N+1)
Validation : API, intégration (compteurs) [back] ; e2e accueil (état vide, chargement) [front] — Acceptation : liste et compteurs exacts

### SC-BOARD-003 — Ouvrir un tableau (critique)
Étant donné un membre du tableau
Quand il appelle `GET /api/boards/{id}`
Alors il reçoit la coquille : listes ouvertes triées par position **sans cartes** avec `cardCount`, étiquettes, champs personnalisés, invitations en attente, `trelloFiles`
Limites : listes archivées ou dans la corbeille absentes ; 300 listes → réponse < 500 ms
Validation : API [back] ; e2e page Tableau [front] — Acceptation : schéma OpenAPI et ordre des listes

### SC-BOARD-004 — Tableau inexistant ou dans la corbeille (critique)
Étant donné un tableau supprimé (corbeille) et un identifiant inexistant
Quand un ancien membre appelle `GET /api/boards/{id}` ou toute route du tableau, de ses listes ou de ses cartes
Alors 404 `Ce tableau est dans la corbeille.` (ou `Tableau introuvable.`)
Validation : API [back] ; e2e (message et retour à l'accueil) [front] — Acceptation : 404 sur toutes les routes enfants

### SC-BOARD-005 — Modifier un tableau (important)
Étant donné un membre
Quand il envoie `PATCH /api/boards/{id}` `{"title":"Sprint 13","description":"","background":"forest","showCardNumbers":false}`
Alors 200 ; titre changé ; description `null` (chaîne vide → null) ; fond changé ; numéros masqués sur les cartes
Limites : `title:""` → 400 ; `background` inconnu → ignoré ; clés absentes → inchangées
Validation : API [back] ; e2e onglet Général [front] — Acceptation : valeurs persistées

### SC-BOARD-006 — Non-membre (critique)
Étant donné Bob non membre du tableau 12 d'Alice
Quand il appelle `GET`, `PATCH`, `DELETE /api/boards/12`, `/favorite`, `/members`, `/lists`, `/cards`, `/labels`, `/custom-fields`, `/archived`, `/trash`, `/trello-files`
Alors 403 `Vous n'êtes pas membre de ce tableau.` partout, sans aucune modification (écart E1 : 403 ou 404 selon décision)
Validation : sécurité (matrice route × rôle) [back] — Acceptation : aucune réponse 2xx, aucune écriture

### SC-BOARD-007 — Supprimer un tableau (critique)
Étant donné Alice propriétaire et Bob admin du tableau
Quand Bob envoie `DELETE /api/boards/{id}`
Alors 403 `Seul le propriétaire peut supprimer le tableau.`
Quand Alice l'envoie
Alors 204 ; le tableau passe dans la corbeille (`deletedAt`, `deletedBy`), disparaît de `GET /api/boards` pour tous les membres et apparaît dans `GET /api/trash/boards` d'Alice (voir archives-trash.md)
Validation : API, sécurité [back] ; e2e (confirmation puis disparition) [front] — Acceptation : statuts et effets constatés

### SC-BOARD-008 — Favori propre à chaque membre (important)
Étant donné Alice et Bob membres du tableau
Quand Alice appelle deux fois `POST /api/boards/{id}/favorite`
Alors la première réponse est `{"favorite":true}`, la seconde `{"favorite":false}` ; le favori de Bob n'est jamais modifié
Validation : API [back] ; e2e (étoile sur l'accueil, section Favoris) [front] — Acceptation : bascule et isolation

### SC-BOARD-009 — Ajouter un membre existant (critique)
Étant donné Alice gestionnaire, Carla membre du tableau, et Bob inscrit non membre
Quand Alice envoie `POST /members` `{"email":" BOB@exemple.fr ","role":"admin"}`
Alors 201 `{"status":"added","member":{…,"role":"admin"}}` ; Bob reçoit `board_invite` (« Alice vous a ajouté au tableau « … » ») ; les autres membres sauf Alice (donc Carla) reçoivent `member_joined` (« Bob a rejoint le tableau « … » »)
Limites : `role:"owner"` ou rôle inconnu → `member` ; Bob déjà membre → 409 `Cet utilisateur est déjà membre du tableau.`
Validation : API, intégration (notifications) [back] ; e2e onglet Membres [front] — Acceptation : adhésion, rôle et notifications exacts

### SC-BOARD-010 — Simple membre et actions de gestion (critique)
Étant donné Carla `member`
Quand elle tente d'inviter, de changer un rôle, de retirer un autre membre ou d'annuler une invitation
Alors 403 `Action réservée aux administrateurs du tableau.`
Limites : en revanche elle peut modifier le tableau, ses listes, étiquettes et champs (écart E4, conservé)
Validation : sécurité [back] ; e2e (boutons de gestion masqués pour un membre) [front] — Acceptation : 403 et aucune modification

### SC-BOARD-011 — Inviter un e-mail sans compte (critique)
Étant donné Alice gestionnaire
Quand elle invite `nouveau@exemple.fr`
Alors **202** `{"status":"invited","message":"Invitation enregistrée : nouveau@exemple.fr rejoindra le tableau à son inscription."}` ; l'invitation apparaît dans `GET /api/boards/{id}` → `invitations`
Limites : même e-mail réinvité → 409 `Une invitation est déjà en attente pour cette adresse.` ; e-mail invalide → 400 `Adresse e-mail invalide.` ; e-mail vide → 400 ; aucun e-mail n'est envoyé (pas de service de messagerie)
Validation : API [back] ; e2e onglet Membres (invitation en attente listée) [front] — Acceptation : statut 202 et invitation persistée

### SC-BOARD-012 — Annuler une invitation (important)
Étant donné une invitation du tableau 12 et une invitation du tableau 13
Quand un gestionnaire du tableau 12 envoie `DELETE /api/boards/12/invitations/{id de l'invitation du tableau 13}`
Alors 404 (pas d'IDOR) ; avec l'invitation du tableau 12 → 204 et elle disparaît
Validation : API, sécurité [back] ; e2e [front] — Acceptation : IDOR bloqué

### SC-BOARD-013 — Invitations sans expiration (secondaire)
Étant donné une invitation créée il y a un an
Quand l'invité s'inscrit
Alors il rejoint le tableau (aucune expiration, écart E13)
Validation : intégration [back] — Acceptation : comportement documenté

### SC-BOARD-014 — Changer le rôle d'un membre (important)
Étant donné Alice propriétaire, Bob admin, Carla membre
Quand Bob envoie `PATCH /members/{Carla}` `{"role":"admin"}`
Alors 200 avec `role: admin`
Limites : cibler Alice (propriétaire) → 403 `Le rôle du propriétaire ne peut pas être modifié.` ; `role:"owner"` ou `""` → 400 `Rôle invalide.` ; utilisateur inexistant ou non membre → 404 ; un admin peut rétrograder un autre admin, et lui-même
Validation : API, sécurité [back] ; e2e [front] — Acceptation : rôle persisté, erreurs exactes

### SC-BOARD-015 — Retirer un membre (critique)
Étant donné Bob membre assigné à 3 cartes du tableau et à 1 carte d'un autre tableau
Quand un gestionnaire envoie `DELETE /members/{Bob}`
Alors 204 ; Bob n'est plus membre ; il est désassigné des 3 cartes (pas de l'autre tableau) ; il reçoit `member_removed` (« Alice vous a retiré du tableau « … » », sans lien vers le tableau)
Limites : propriétaire → 403 `Le propriétaire ne peut pas quitter son tableau.` ; non-membre → 404 `Membre introuvable.`
Validation : API, intégration (désassignation en masse) [back] ; e2e [front] — Acceptation : désassignation limitée au tableau

### SC-BOARD-016 — Quitter un tableau (important)
Étant donné Carla `member`
Quand elle envoie `DELETE /api/boards/{id}/members/{son id}`
Alors 204, sans notification ; le tableau disparaît de son accueil
Limites : le propriétaire qui tente de partir → 403
Validation : API [back] ; e2e (bouton « Quitter le tableau ») [front] — Acceptation : adhésion supprimée, aucune notification

### SC-BOARD-017 — Concurrence sur les adhésions (important)
Étant donné deux gestionnaires
Quand ils ajoutent simultanément le même utilisateur
Alors une seule adhésion existe (contrainte unique `board_id, user_id`) ; la seconde requête reçoit 409, jamais 500
Validation : intégration [back] — Acceptation : une ligne, statuts 201 + 409

### SC-BOARD-018 — Invitations visibles par tous les membres (secondaire)
Étant donné un simple membre
Quand il ouvre le tableau
Alors `invitations` contient les e-mails invités (comportement actuel ; HYPOTHÈSE: acceptable car les membres voient déjà les e-mails des autres membres)
Validation : API [back] — Acceptation : comportement documenté

### SC-BOARD-019 — Recherche locale de l'accueil (secondaire)
Étant donné 10 tableaux
Quand l'utilisateur tape « spr » dans la recherche de l'accueil
Alors seuls les tableaux dont le titre ou la description contient « spr » (insensible à la casse) sont affichés, favoris compris
Validation : composant, e2e [front] — Acceptation : filtrage correct

## 5. Cas critiques et limites
- Droits : matrice complète route × {owner, admin, member, non-membre, anonyme} (SC-BOARD-006, 010, 014, 015).
- IDOR : invitations et membres d'un autre tableau (SC-BOARD-012) ; `userId` d'un utilisateur d'un autre tableau.
- Corbeille : un tableau dans la corbeille rend toutes ses routes 404 pour tous, propriétaire compris, sauf les routes `/api/trash/boards/**`.
- Concurrence : ajouts simultanés (SC-BOARD-017) ; suppression du tableau pendant qu'un membre l'édite (les requêtes suivantes reçoivent 404).
- Longueurs : titre 160, e-mail 180.
- Migration : `board_member.user_id` référence `user.id` (identity-service) : l'identifiant utilisateur doit rester stable lors de la reprise.

## 6. Exigences non fonctionnelles
- `GET /api/boards` p95 < 200 ms pour 200 tableaux ; `GET /api/boards/{id}` p95 < 300 ms pour 300 listes (aucune carte chargée).
- Sécurité : contrôle d'appartenance dans chaque cas d'utilisation (pas seulement à la gateway) ; ressources Spring Data REST filtrées par appartenance.
- Accessibilité : dialogues de création et de paramètres avec focus piégé, `Échap` pour fermer, boutons d'icône avec `aria-label`.
