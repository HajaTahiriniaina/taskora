# Listes (colonnes)

Service cible : **board-service**.
Code actuel : `ListController`, `BoardController::addList/reorderLists/cards`, `BoardList`, `CardRepository::page/totals/shift` ; front : `pages/board/board.component.ts` (virtualisation, chargement paresseux, glisser-déposer des colonnes, sélecteur de colonnes, « Déplacer en position… »).
Archivage et corbeille des listes : voir [archives-trash.md](archives-trash.md).

## 1. Contexte et règles métier
- `BoardList` : `id`, `board`, `title` (≤ 160, obligatoire), `position` (entier), `color` (chaîne libre ou `null`), `archivedAt`, `deletedAt`/`deletedBy`.
- Une liste est **ouverte** si elle n'est ni archivée ni dans la corbeille ; seules les listes ouvertes apparaissent sur le tableau, triées par `position`.
- Tout **membre** du tableau peut créer, renommer, colorer, réordonner, archiver, restaurer et mettre à la corbeille une liste.
- Cartes d'une liste : 30 par page par défaut, **200 au maximum**, triées par `position` puis `id`, hors cartes archivées ou dans la corbeille.

## 2. Routes HTTP actuelles

| Méthode et route | Droit | Succès | Erreurs |
|---|---|---|---|
| `POST /api/boards/{id}/lists` `{title}` | membre | 201 `List` (`position` = max des positions de **toutes** les listes du tableau + 1, `cardCount: 0`) | 400 titre ; 403 ; 404 |
| `POST /api/boards/{id}/lists/reorder` `{ids:[…]}` | membre | 200 `{"ok":true}` | 403 ; 404 |
| `PATCH /api/lists/{id}` `{title?, color?}` | membre | 200 `List` | 400 titre vide ; 403 ; 404 (inexistante ou corbeille) |
| `DELETE /api/lists/{id}` | membre | 204 (corbeille) | 403 ; 404 |
| `GET /api/lists/{id}/cards?offset=&limit=&<filtres>` | membre | 200 `CardPage {items, total, offset, hasMore}` | 403 ; 404 |
| `GET /api/boards/{id}/cards?lists=1,2&limit=&<filtres>` | membre | 200 `{"<listId>": CardPage}` (premières pages de plusieurs listes) | 403 ; 404 |
| `POST /api/lists/{id}/archive`, `POST /api/lists/{id}/restore` | membre | voir archives-trash.md | |

`List` : `{id, title, position, color, archivedAt, cardCount}`. Filtres : voir [cards.md](cards.md) § Filtres.

## 3. Scénarios

### SC-LIST-001 — Créer une liste (critique)
Étant donné un tableau avec 4 listes (positions 0..3) et une liste archivée en position 4
Quand un membre crée « Bloqué »
Alors 201 ; la liste est en position **5** (après toutes les listes, archivées comprises) avec `cardCount: 0`
Limites : titre vide → 400 `Le champ "title" est obligatoire.` ; 200 caractères → tronqué à 160 ; tableau sans liste → position 0
Validation : API [back] ; e2e « Ajouter une liste » [front] — Acceptation : position et réponse exactes

### SC-LIST-002 — Renommer et colorer une liste (important)
Étant donné une liste
Quand un membre envoie `PATCH /api/lists/{id}` `{"title":"En test","color":"#22c55e"}`
Alors 200 avec titre et couleur à jour et `cardCount` exact
Limites : `color:""` ou `null` → `null` ; couleur non validée (comportement actuel ; HYPOTHÈSE: la cible peut accepter toute chaîne ≤ 20 caractères) ; `title:""` → 400
Validation : API [back] ; e2e menu de liste [front] — Acceptation : valeurs persistées

### SC-LIST-003 — Réordonner les listes (critique)
Étant donné les listes A(0) B(1) C(2)
Quand un membre envoie `reorder` `{"ids":[C,A,B]}`
Alors les positions deviennent C=0, A=1, B=2 et `GET /api/boards/{id}` renvoie C, A, B
Limites : identifiant d'une liste d'un autre tableau → ignoré (pas d'IDOR) ; identifiant inconnu → ignoré ; liste ouverte absente du tableau `ids` → position inchangée (doublon de position possible, ordre alors départagé par l'ordre de chargement : HYPOTHÈSE, la cible doit trier par `position, id`) ; listes archivées non concernées
Validation : unitaire (réordonnancement), API, sécurité [back] ; e2e glisser-déposer de colonne [front] — Acceptation : ordre persistant après rechargement

### SC-LIST-004 — Déplacer une colonne loin sans la faire glisser (secondaire)
Étant donné un tableau de 300 colonnes
Quand l'utilisateur choisit « Déplacer en position… » 150 sur la colonne 3
Alors le front envoie le nouvel ordre complet et la colonne apparaît en 150e position ; la vue saute à cette colonne
Validation : e2e [front] — Acceptation : position constatée après rechargement

### SC-LIST-005 — Pagination des cartes d'une liste (critique)
Étant donné une liste de 75 cartes ouvertes, 3 archivées et 2 dans la corbeille
Quand on appelle `GET /api/lists/{id}/cards?offset=0`, puis `offset=30`, puis `offset=60`
Alors les pages contiennent 30, 30 et 15 cartes triées par position ; `total` = 75 ; `hasMore` = true, true, false
Limites : `limit=500` → 200 ; `limit=0` ou négatif → 1 ; `offset` négatif → 0 ; `offset` au-delà du total → `items: []`, `hasMore: false`
Validation : API, intégration [back] ; e2e défilement infini (page suivante 400 px avant la fin) [front] — Acceptation : contenu et compteurs exacts

### SC-LIST-006 — Chargement groupé des premières pages (critique)
Étant donné un tableau T avec les listes 1, 2, 3 (dont 3 archivée) et une liste 9 d'un autre tableau
Quand un membre appelle `GET /api/boards/T/cards?lists=1,2,3,9&limit=30`
Alors la réponse contient les clés `"1"` et `"2"` seulement (ni la liste archivée, ni la liste étrangère)
Limites : au plus **20** listes traitées par requête (les suivantes ignorées) ; `lists` vide → `{}` ; valeurs non numériques ignorées
Validation : API, sécurité (IDOR) [back] ; e2e (colonnes révélées ensemble = une seule requête) [front] — Acceptation : aucune donnée d'un autre tableau

### SC-LIST-007 — Liste dans la corbeille ou tableau dans la corbeille (important)
Étant donné une liste mise à la corbeille, ou une liste d'un tableau mis à la corbeille
Quand un membre appelle `PATCH`, `DELETE`, `GET /cards` ou `POST /cards` sur cette liste
Alors 404 `Cet élément est dans la corbeille.`
Validation : API [back] — Acceptation : 404 partout

### SC-LIST-008 — Accès d'un non-membre (critique)
Étant donné Bob non membre
Quand il appelle n'importe quelle route `/api/lists/{id}/**` d'un tableau d'Alice
Alors 403 sans modification (écart E1)
Validation : sécurité [back] — Acceptation : 403 partout

### SC-LIST-009 — Virtualisation et chargement paresseux (important)
Étant donné un tableau de 300 colonnes et 9 000 cartes
Quand l'utilisateur ouvre le tableau et fait défiler horizontalement
Alors seules les colonnes visibles ± 3 sont dans le DOM (≈ 4 000 nœuds au plus) ; les cartes d'une colonne sont chargées quand elle approche à 600 px ; les cartes des colonnes éloignées de plus de 15 colonnes sont libérées puis rechargées au retour
Validation : e2e (mesure du DOM et des requêtes) [front] — Acceptation : nombre de nœuds et de requêtes dans les bornes

### SC-LIST-010 — Sélecteur de colonnes (secondaire)
Étant donné un tableau de 300 colonnes
Quand l'utilisateur tape le nom d'une colonne dans le sélecteur « colonnes 150–154 / 300 »
Alors la vue saute à cette colonne
Validation : e2e [front] — Acceptation : colonne visible

## 4. Cas critiques et limites
- Positions : la création utilise le max **de toutes** les listes (archivées et corbeille comprises) ; la restauration place la liste à la fin des listes **ouvertes** (archives-trash.md).
- Concurrence : deux créations simultanées peuvent obtenir la même position (pas de verrou) ; HYPOTHÈSE: toléré, l'ordre est départagé par `id`.
- Volume : 300 colonnes, 9 000 cartes (`app:seed-demo`) ; aucun N+1 (pages hydratées en 1 requête + 2 requêtes de compteurs).

## 5. Exigences non fonctionnelles
- `GET /api/lists/{id}/cards` p95 < 150 ms pour une liste de 6 000 cartes (index `card(list_id, position)`).
- `GET /api/boards/{id}/cards` (20 listes) p95 < 400 ms.
- Accessibilité : menu de liste au clavier ; glisser-déposer doublé par « Déplacer en position… ».
