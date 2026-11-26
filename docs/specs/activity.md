# Historique (activité) des cartes

Service cible : **board-service**.
Code actuel : `ActivityLogger`, `Activity`, `ActivityRepository::page`, `CardController::activity/logChanges` ; front : `card-modal.component.ts` (fil « Commentaires et activité »), `pages/board/activity-text.ts`.

## 1. Contexte et règles métier
- Chaque modification d'une carte produit une entrée `Activity {id, card, actor, type, data, createdAt, externalActor}`, enregistrée **dans la même transaction** que la modification.
- Types et données :

| Type | Données | Déclencheur |
|---|---|---|
| `created` | `{list}` | création (et import Trello `createCard`) |
| `renamed` | `{from, to}` | titre modifié |
| `description` | — | description modifiée |
| `moved` | `{from, to}` | changement de liste |
| `completed` / `reopened` | — | `completed` modifié |
| `due_set` / `due_removed` | `{due}` / — | échéance modifiée (date différente) |
| `priority` | `{to}` | priorité modifiée |
| `cover_set` / `cover_removed` | `{color}` ou `{image}` | couverture modifiée |
| `label_added` / `label_removed` | `{name, color}` | étiquettes |
| `member_added` / `member_removed` | `{name}` | assignation |
| `checklist_added` / `checklist_done` / `checklist_undone` / `checklist_removed` | `{item}` | checklist |
| `field_set` / `field_cleared` | `{field, value}` / `{field, value:null}` | champ personnalisé |
| `attachment` / `attachment_removed` | `{name}` | pièce jointe ajoutée (bouton, commentaire, Trello) / supprimée |
| `archived` / `unarchived` | — / `{list}` | archives |
| `restored` | `{list}` | restauration depuis la corbeille |

- Les données sont des **instantanés** (noms au moment de l'action) : renommer ensuite une étiquette ne réécrit pas l'historique.
- Acteur : utilisateur ; supprimé → `{"id":0,"name":"Utilisateur supprimé",…}` ; externe (import Trello) → `{"id":0,"name":"<nom>","external":true,…}`.
- Lecture : du plus récent au plus ancien, pagination par curseur.

## 2. Route HTTP actuelle

| Méthode et route | Droit | Succès | Erreurs |
|---|---|---|---|
| `GET /api/cards/{id}/activity?before=<activityId>&limit=<n>` | membre | 200 `{items:[{id, type, data, actor, createdAt}], hasMore}` | 403 ; 404 |

`limit` : défaut 30, borné à `1..100`. `before` d'une autre carte ou inexistant → ignoré (première page).

## 3. Scénarios

### SC-ACTIVITY-001 — Journal des modifications d'une carte (critique)
Étant donné une carte
Quand on modifie en une seule requête `PATCH` le titre, l'échéance, la priorité et deux étiquettes
Alors 5 entrées sont créées (`renamed`, `due_set`, `priority`, `label_added` ×2) avec l'acteur et les données attendues
Limites : valeur identique → aucune entrée ; une requête en erreur → aucune entrée
Validation : unitaire (calcul du différentiel), intégration [back] — Acceptation : entrées exactes, atomicité

### SC-ACTIVITY-002 — Couverture : entrées exclusives (important)
Étant donné une carte avec couverture couleur
Quand on choisit une image de couverture, puis on retire la couverture
Alors une seule entrée `cover_set {image}` puis une seule `cover_removed` (pas de doublon couleur + image)
Validation : unitaire [back] — Acceptation : nombre d'entrées exact

### SC-ACTIVITY-003 — Pagination par curseur (critique)
Étant donné une carte avec 75 entrées
Quand on lit sans `before`, puis avec `before` = id de la dernière entrée reçue, deux fois
Alors 30, 30, 15 entrées sans doublon ni trou, `hasMore` true, true, false
Limites : `limit=0` → 1 ; `limit=1000` → 100 ; `before` d'une autre carte → première page (pas de fuite) ; entrées de même `createdAt` départagées par `id`
Validation : API, intégration, sécurité (IDOR) [back] ; e2e défilement du fil [front] — Acceptation : couverture complète et ordonnée

### SC-ACTIVITY-004 — Acteurs particuliers (important)
Étant donné une entrée dont l'acteur a été supprimé et une entrée importée de Trello
Quand on lit l'historique
Alors on obtient « Utilisateur supprimé » (id 0) et l'acteur externe (`external: true`)
Validation : API [back] ; composant (texte d'activité) [front] — Acceptation : représentations exactes

### SC-ACTIVITY-005 — Fil mixte et « Masquer les détails » (important)
Étant donné une carte avec commentaires et historique
Quand l'utilisateur ouvre la fiche puis clique « Masquer les détails »
Alors le fil mélange commentaires et activités par date ; masquer cache l'historique (pas les commentaires) ; la préférence est mémorisée pour les fiches suivantes
Validation : composant, e2e [front] — Acceptation : préférence persistée (localStorage)

### SC-ACTIVITY-006 — Textes d'activité en français (secondaire)
Étant donné chaque type d'activité
Quand il est affiché
Alors le texte correspond au type (ex. `moved` → « a déplacé cette carte de « A » vers « B » »)
Validation : unitaire (`activity-text.ts`) [front] — Acceptation : un cas par type

### SC-ACTIVITY-007 — Accès (critique)
Étant donné Bob non membre
Quand il lit l'historique d'une carte d'Alice
Alors 403 (écart E1)
Validation : sécurité [back] — Acceptation : aucune entrée renvoyée

## 4. Cas critiques et limites
- Atomicité : modification et activité dans la même transaction (ou outbox si l'activité devient un événement).
- Volume : une carte importée de Trello peut avoir des centaines d'entrées ; pagination obligatoire.
- La purge d'une carte supprime son historique (cascade).

## 5. Exigences non fonctionnelles
- `GET /activity` p95 < 100 ms (index `card_id, created_at, id`).
- Accessibilité : fil lisible par lecteur d'écran (liste ordonnée, dates en `<time>`).
