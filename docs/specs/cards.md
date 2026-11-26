# Cartes : numéros, déplacement, étiquettes, checklist, champs personnalisés, assignation, échéance, priorité, couverture, filtres

Service cible : **board-service** (les images de couverture sont des pièces jointes : attachment-service).
Code actuel : `CardController`, `ListController::addCard`, `BoardController::addLabel/filters`, `ListController::updateLabel/deleteLabel`, `CustomFieldController`, `Card`, `Label`, `ChecklistItem`, `CustomField`, `CustomFieldValue`, `CardRepository` ; front : `card-item.component.ts`, `card-modal.component.ts`, `filter-panel.component.ts`, `board-settings.component.ts` (onglets Étiquettes, Champs), `card-format.ts`.
Commentaires : [comments-mentions.md](comments-mentions.md). Historique : [activity.md](activity.md). Archives et corbeille : [archives-trash.md](archives-trash.md).

## 1. Contexte et règles métier

### Modèle `Card`
| Champ | Règle |
|---|---|
| `number` | Numéro humain **unique dans le tableau**, attribué de façon atomique (`board.card_seq + 1`), **jamais réutilisé** (même après suppression) ; affiché `#12` si `showCardNumbers` |
| `title` | Obligatoire, ≤ 255 (tronqué) |
| `description` | Texte riche nettoyé (voir comments-mentions.md § Texte riche) ou `null` |
| `position` | Contiguë `0..n-1` parmi les cartes ouvertes de la liste ; `-1` si archivée ou dans la corbeille |
| `dueDate` | Date-heure ou `null` |
| `completed` | Booléen (terminé / rouvert) |
| `priority` | `none` (défaut), `low`, `medium`, `high`, `urgent` |
| `cover` | Couleur (chaîne ≤ 20) ou `null` |
| `coverAttachment` | Image jointe **à cette carte** servant de couverture ; prioritaire sur la couleur ; les deux s'excluent |
| `labels` | Étiquettes **du même tableau** |
| `assignees` | Membres **du tableau** |
| `customValues` | Valeurs des champs personnalisés du tableau |
| `createdBy`, `createdAt`, `archivedAt`, `deletedAt`/`deletedBy` | |

Tout **membre** du tableau peut créer, lire, modifier, déplacer, archiver et mettre à la corbeille une carte, et gérer étiquettes et champs personnalisés.

### Représentations
- `Card` (aperçu) : `id, listId, number, title, description, position, dueDate, completed, archivedAt, cover, coverImage, coverAttachmentId, priority, labelIds[], assignees[], commentCount, attachmentCount, checklistTotal, checklistDone, customValues{fieldId: valeur}, createdAt`. `coverImage` = `/uploads/<miniature ou original>`.
- `CardDetail` : `Card` + `listTitle, listArchived, createdBy, comments[], checklist[], attachments[]`.

### Champs personnalisés (`CustomField`)
Types : `text` (≤ 2 000, tronqué), `number` (numérique sinon 400 `Valeur numérique attendue.`), `date` (normalisée `Y-m-d`), `checkbox` (booléen), `select` (identifiant d'option existant sinon 400 `Option inconnue.`), `url` (URL valide sinon 400 `URL invalide.`). Valeur `null` ou `""` → suppression de la valeur.
Définition : `name` ≤ 80 obligatoire, `type` (défaut `text`, inconnu → 400 `Type de champ invalide.`), `options` `[{id, label ≤ 60, color (défaut #94a3b8)}]` (options sans libellé ignorées, `id` généré si absent : 8 caractères hexadécimaux), `showOnCard` (défaut `true`), `position` = nombre de champs existants.

### Étiquettes (`Label`)
`name` ≤ 60 (obligatoire à la création), `color` libre (défaut `#6366f1`). La suppression retire l'étiquette de toutes les cartes.

## 2. Routes HTTP actuelles

| Méthode et route | Succès | Erreurs |
|---|---|---|
| `POST /api/lists/{id}/cards` `{title}` | 201 `Card` (fin de liste, numéro suivant, activité `created`) | 400 titre ; 403 ; 404 |
| `GET /api/cards/{id}` | 200 `CardDetail` | 403 ; 404 (inexistante, corbeille) |
| `PATCH /api/cards/{id}` `{title?, description?, dueDate?, completed?, cover?, coverAttachmentId?, priority?, labelIds?}` | 200 `CardDetail` | 400 titre vide ; 400 `Priorité invalide.` ; 400 `Seule une image jointe à cette carte peut servir de couverture.` ; 500 date invalide (écart E8) |
| `DELETE /api/cards/{id}` | 204 (corbeille) | 403 ; 404 |
| `POST /api/cards/{id}/move` `{listId, position}` | 200 `{id, listId, position}` | 400 `Restaurez la carte avant de la déplacer.` ; 400 `Impossible de déplacer une carte vers une liste archivée.` ; 400 `Impossible de déplacer une carte vers un autre tableau.` ; 404 liste inexistante/corbeille |
| `POST /api/cards/{id}/assignees` `{userId}` (bascule) | 200 `CardDetail` | 400 `Cet utilisateur n'est pas membre du tableau.` ; 404 utilisateur |
| `POST /api/cards/{id}/checklist` `{content}` | 201 `ChecklistItem {id, content, done, position}` | 400 |
| `PATCH /api/checklist/{id}` `{done?, content?}` | 200 `ChecklistItem` | 400 contenu vide ; 403 ; 404 |
| `DELETE /api/checklist/{id}` | 204 | 403 ; 404 |
| `PUT /api/cards/{id}/custom-fields/{fieldId}` `{value}` | 200 `CardDetail` | 400 (selon type) ; 404 `Champ introuvable sur ce tableau.` |
| `POST /api/boards/{id}/custom-fields` | 201 `CustomField` | 400 |
| `PATCH /api/custom-fields/{id}` `{name?, options?, showOnCard?}` | 200 | 400 ; 403 ; 404 |
| `DELETE /api/custom-fields/{id}` | 204 (valeurs supprimées en cascade) | 403 ; 404 |
| `POST /api/boards/{id}/labels` `{name, color?}` | 201 `Label` | 400 |
| `PATCH /api/labels/{id}` `{name?, color?}` | 200 | 400 ; 403 ; 404 |
| `DELETE /api/labels/{id}` | 204 | 403 ; 404 |
| Filtres : `GET /api/lists/{id}/cards` et `GET /api/boards/{id}/cards` (voir § 4) | | |

Le type d'un champ personnalisé n'est **pas** modifiable après création.

## 3. Scénarios — cycle de vie et numéros

### SC-CARD-001 — Créer une carte (critique)
Étant donné une liste de 5 cartes sur un tableau dont `card_seq` = 41
Quand un membre crée « Corriger le login »
Alors 201 ; `number` = 42, `position` = 5, `createdBy` = lui ; une activité `created {list}` est enregistrée ; compteurs à 0
Limites : titre vide → 400 ; 300 caractères → tronqué à 255 ; création dans une liste archivée → acceptée (HYPOTHÈSE: comportement actuel, la carte est invisible jusqu'à restauration de la liste)
Validation : unitaire, API [back] ; e2e « Ajouter une carte » [front] — Acceptation : numéro et position exacts

### SC-CARD-002 — Numéros uniques sous concurrence (critique)
Étant donné un tableau
Quand 50 cartes sont créées en parallèle dans plusieurs listes
Alors les numéros sont 50 valeurs distinctes et consécutives ; aucun 500 (contrainte unique `board_id, number`)
Validation : intégration (concurrence, MariaDB réel) [back] — Acceptation : aucune collision

### SC-CARD-003 — Numéros jamais réutilisés (critique)
Étant donné la carte #42, la plus récente, purgée de la corbeille
Quand une nouvelle carte est créée
Alors elle reçoit #43
Limites : après un import Trello, la numérotation continue après le plus grand `idShort` (trello-import.md)
Validation : intégration [back] — Acceptation : #43

### SC-CARD-004 — Lire le détail d'une carte (critique)
Étant donné une carte avec commentaires, checklist, pièces jointes, étiquettes, assignés et champs
Quand un membre appelle `GET /api/cards/{id}`
Alors il reçoit `CardDetail` complet : commentaires du plus récent au plus ancien, checklist par position, pièces jointes de la plus récente à la plus ancienne, `listTitle`, `listArchived`
Limites : carte archivée → lisible (200) ; carte dans la corbeille ou dont la liste/le tableau est dans la corbeille → 404
Validation : API [back] ; e2e fiche carte (ouverture par `?card=<id>`) [front] — Acceptation : schéma OpenAPI

### SC-CARD-005 — Renommer et décrire une carte (important)
Étant donné une carte
Quand un membre envoie `{"title":"Nouveau titre","description":"<p>Texte <script>alert(1)</script></p>"}`
Alors le titre change ; la description est nettoyée (`<script>` supprimé) ; activités `renamed {from,to}` et `description`
Limites : `title:""` → 400 ; description réduite à `<p></p>` → `null` ; description identique → pas d'activité
Validation : unitaire (nettoyage), API, sécurité (XSS) [back] ; e2e éditeur riche (⌘/Ctrl + Entrée) [front] — Acceptation : HTML stocké sans script

### SC-CARD-006 — Déplacer une carte dans la même liste (critique)
Étant donné une liste A, B, C, D, E (positions 0..4)
Quand on déplace B en position 3
Alors l'ordre devient A, C, D, B, E (positions contiguës) ; aucune activité `moved` ni notification
Limites : position > n-1 → ramenée à n-1 ; position négative → 0 ; même position → aucun changement
Validation : unitaire (algorithme de décalage), intégration (2 UPDATE groupés), API [back] ; e2e glisser-déposer [front] — Acceptation : positions contiguës après chaque déplacement

### SC-CARD-007 — Déplacer une carte vers une autre liste (critique)
Étant donné la carte X en position 1 de la liste « À faire » (3 cartes), assignée à Bob, et la liste « En cours » (2 cartes)
Quand Alice la déplace dans « En cours » en position 0
Alors « À faire » se resserre (positions 0..1), « En cours » a X en 0 puis les autres ; activité `moved {from:"À faire", to:"En cours"}` ; Bob reçoit `card_moved` (« Alice a déplacé « X » vers « En cours » »)
Limites : position > n → n (fin de liste) ; liste cible archivée → 400 ; liste d'un autre tableau → 400 ; carte archivée → 400 ; liste cible dans la corbeille ou inexistante → 404 ; opération transactionnelle (aucune position dupliquée en cas d'échec)
Validation : unitaire, intégration, API, sécurité [back] ; e2e glisser-déposer entre colonnes [front] — Acceptation : positions des deux listes cohérentes, notification exacte

### SC-CARD-008 — Déplacements concurrents (important)
Étant donné deux membres
Quand ils déplacent simultanément deux cartes de la même liste
Alors les positions restent contiguës et sans doublon
Limites : HYPOTHÈSE: aucun verrou explicite aujourd'hui ; la cible doit sérialiser par liste (verrou pessimiste ou version)
Validation : intégration (concurrence) [back] — Acceptation : invariant de contiguïté respecté sur 100 exécutions

## 4. Scénarios — attributs

### SC-CARD-009 — Assigner et désassigner un membre (critique)
Étant donné Bob membre du tableau
Quand Alice envoie `POST /assignees` `{"userId": Bob}` puis le renvoie
Alors Bob est assigné (activité `member_added {name}`, notification `card_assigned` « Alice vous a assigné la carte « … » ») puis désassigné (activité `member_removed`, pas de notification)
Limites : s'assigner soi-même → aucune notification ; utilisateur non membre → 400 ; utilisateur inexistant → 404
Validation : API, intégration (notification) [back] ; e2e menu Membres de la fiche [front] — Acceptation : bascule et notification

### SC-CARD-010 — Étiquettes d'une carte (important)
Étant donné les étiquettes 1, 2 du tableau et l'étiquette 9 d'un autre tableau
Quand on envoie `PATCH` `{"labelIds":[1,9]}` sur une carte qui avait [2]
Alors la carte a [1] ; l'étiquette 9 est ignorée silencieusement ; activités `label_added` (1) et `label_removed` (2)
Validation : API, sécurité (IDOR) [back] ; e2e [front] — Acceptation : aucune étiquette étrangère

### SC-CARD-011 — Gérer les étiquettes du tableau (important)
Étant donné un membre
Quand il crée « Client » `#0ea5e9`, la renomme puis la supprime
Alors 201, 200, 204 ; après suppression, aucune carte ne porte plus l'étiquette
Limites : nom vide → 400 ; nom 80 caractères → tronqué à 60 ; couleur absente → `#6366f1`
Validation : API, intégration [back] ; e2e onglet Étiquettes [front] — Acceptation : cascade constatée

### SC-CARD-012 — Checklist (important)
Étant donné une carte
Quand on ajoute « Écrire les tests », on la coche, la décoche, la renomme puis la supprime
Alors l'élément est créé en dernière position ; activités `checklist_added`, `checklist_done`, `checklist_undone`, `checklist_removed {item}` ; renommer ne crée pas d'activité ; `checklistTotal`/`checklistDone` exacts sur l'aperçu
Limites : contenu vide → 400 ; envoyer `done` identique à l'état actuel → pas d'activité ; 300 caractères → tronqué à 255
Validation : API [back] ; e2e fiche carte (barre de progression) [front] — Acceptation : activités et compteurs exacts

### SC-CARD-013 — Échéance (critique)
Étant donné une carte assignée à Bob
Quand Alice envoie `{"dueDate":"2026-12-01T17:00:00+01:00"}`
Alors l'échéance est enregistrée ; activité `due_set {due}` ; Bob reçoit `card_due` (« Alice a fixé l'échéance de « … » au 01/12/2026 »)
Limites : `dueDate:null` → `due_removed`, pas de notification ; renvoyer la même date → **notification renvoyée** mais pas d'activité (comportement actuel)
Validation : API, intégration [back] ; e2e sélecteur de date [front] — Acceptation : activité et notification

### SC-CARD-014 — Date d'échéance invalide (important)
Étant donné une carte
Quand on envoie `{"dueDate":"pas-une-date"}`
Alors aujourd'hui 500 (écart E8) ; cible proposée **400** `Date invalide.` sans modification
Validation : API [back] — Acceptation : selon décision ; en aucun cas une donnée partiellement modifiée

### SC-CARD-015 — Terminer et rouvrir (important)
Étant donné une carte ouverte
Quand on envoie `{"completed":true}` puis `{"completed":false}`
Alors activités `completed` puis `reopened` ; le filtre statut reflète l'état
Validation : API [back] ; e2e case « Terminé » [front] — Acceptation : activités exactes

### SC-CARD-016 — Priorité (secondaire)
Étant donné une carte
Quand on envoie `{"priority":"urgent"}`
Alors priorité enregistrée, activité `priority {to:"urgent"}`
Limites : `"critique"` → 400 `Priorité invalide.`
Validation : API [back] ; e2e [front] — Acceptation : valeur et erreur

### SC-CARD-017 — Couverture couleur (secondaire)
Étant donné une carte avec une image de couverture
Quand on envoie `{"cover":"#ec4899"}`
Alors la couleur remplace l'image (`coverAttachmentId` = null) ; activité `cover_set {color}` ; `{"cover":null}` → `cover_removed`
Validation : API [back] ; e2e menu Couverture [front] — Acceptation : exclusivité couleur / image

### SC-CARD-018 — Couverture image (important)
Étant donné une image jointe à la carte X et une image jointe à la carte Y
Quand on envoie sur X `{"coverAttachmentId": <image de Y>}`
Alors 400 `Seule une image jointe à cette carte peut servir de couverture.`
Quand on envoie l'image de X
Alors elle devient la couverture, la couleur est effacée, une miniature WebP est générée si absente (cas des images postées en commentaire), activité `cover_set {image}` ; `coverImage` pointe vers la miniature
Limites : pièce jointe non image (PDF) → 400 ; `coverAttachmentId:null` → couverture retirée (`cover_removed`) ; échec de génération de miniature → l'original sert de couverture
Validation : API, intégration (S3/LocalStack), sécurité (IDOR) [back] ; e2e (bouton sur la pièce jointe, visionneuse, menu Couverture avec import d'image) [front] — Acceptation : IDOR bloqué ; miniature ≤ 640 px

## 5. Scénarios — champs personnalisés

### SC-CARD-019 — Définir un champ personnalisé (important)
Étant donné un membre
Quand il crée `{"name":"Client","type":"select","options":[{"label":"ACME","color":"#ef4444"},{"label":""}]}`
Alors 201 ; une seule option (libellé vide ignoré) avec un `id` généré ; `showOnCard` = true ; `position` = nombre de champs existants
Limites : `type:"json"` → 400 `Type de champ invalide.` ; nom vide → 400 ; nom 100 caractères → tronqué à 80 ; libellé d'option 100 → tronqué à 60
Validation : API [back] ; e2e onglet Champs [front] — Acceptation : options nettoyées

### SC-CARD-020 — Valeurs typées (critique)
Étant donné un champ de chaque type
Quand on envoie `PUT /cards/{id}/custom-fields/{fieldId}` avec : number `"12.5"` ; number `"abc"` ; date `"2026-12-01T10:00:00Z"` ; checkbox `1` ; select id inconnu ; url `"javascript:alert(1)"` ; url `"https://exemple.fr"` ; text de 3 000 caractères
Alors respectivement : `12.5` ; 400 `Valeur numérique attendue.` ; `"2026-12-01"` ; `true` ; 400 `Option inconnue.` ; HYPOTHÈSE: acceptée par `FILTER_VALIDATE_URL` (risque XSS au clic, le front doit refuser les schémas non http(s)) ; acceptée ; tronquée à 2 000
Limites : `value: null` ou `""` → valeur supprimée, activité `field_cleared` ; sinon activité `field_set {field, value affichée}` (Oui/Non, date `d/m/Y`, libellé d'option, texte tronqué à 80)
Validation : unitaire (normalisation par type), API, sécurité [back] ; composant (affichage sur l'aperçu si `showOnCard`) [front] — Acceptation : tableau de cas paramétré vert

### SC-CARD-021 — Champ d'un autre tableau (critique)
Étant donné un champ du tableau B
Quand on l'utilise sur une carte du tableau A
Alors 404 `Champ introuvable sur ce tableau.`
Validation : sécurité [back] — Acceptation : aucune valeur créée

### SC-CARD-022 — Modifier ou supprimer un champ (important)
Étant donné un champ select utilisé par 10 cartes
Quand on retire une option, puis on supprime le champ
Alors retirer l'option ne modifie pas les valeurs existantes (HYPOTHÈSE: valeur orpheline affichée par son id) ; supprimer le champ supprime les 10 valeurs
Validation : intégration [back] ; e2e [front] — Acceptation : cascade constatée

## 6. Scénarios — filtres côté serveur

Paramètres de `GET /api/lists/{id}/cards` et `GET /api/boards/{id}/cards` (logique **OU dans une section, ET entre sections**) :
- `q` (≤ 100 caractères) : titre, description, nom d'étiquette, nom ou e-mail d'un assigné, valeur de champ, et `#12`/`12` = numéro ;
- membres : `mine=1` (assigné à moi), `noMember=1`, `members=1,2` (≤ 100) ;
- étiquettes : `noLabel=1`, `labels=3,4` (≤ 100) ;
- `status=done|open` (autre valeur ignorée) ;
- `due=none,overdue,day,week,month` (`overdue` = échéance passée **et** non terminée ; `day`/`week`/`month` = entre maintenant et +1/+7/+30 jours) ;
- `cf[<fieldId>]=opt1,opt2` (≤ 50 valeurs ; `true` pour une case à cocher).
Le `total` de chaque page tient compte des filtres.

### SC-CARD-023 — Recherche par mot-clé (critique)
Étant donné des cartes dont l'une a le titre « Paiement », une autre l'étiquette « paiement », une autre un assigné « Paul », une autre la valeur de champ « paiement-v2 », et la carte #12
Quand on filtre `q=paiement`, puis `q=#12`, puis `q=12`
Alors les trois premières correspondances sont trouvées (insensible à la casse) ; `#12` et `12` trouvent la carte #12 (et toute carte contenant « 12 » dans un texte)
Limites : `q` avec `%` ou `_` → traités littéralement ; `q` de 300 caractères → tronqué à 100 ; carte archivée ou dans la corbeille jamais renvoyée
Validation : intégration (requêtes réelles), API, sécurité (injection) [back] ; e2e panneau Filtre [front] — Acceptation : résultats et totaux exacts

### SC-CARD-024 — Combinaison des filtres (critique)
Étant donné un jeu de cartes couvrant toutes les combinaisons
Quand on filtre `mine=1&noMember=1&labels=3&status=open&due=overdue,day`
Alors on obtient les cartes (assignées à moi **ou** sans membre) **et** (étiquette 3) **et** (ouvertes) **et** (en retard **ou** dues dans 24 h)
Validation : unitaire (construction de la spécification de requête), intégration [back] ; e2e [front] — Acceptation : ensemble attendu identique à l'oracle calculé en mémoire

### SC-CARD-025 — Filtres sur champs personnalisés (important)
Étant donné un champ select « Client » et une case à cocher « Bloquant »
Quand on filtre `cf[Client]=acme,globex&cf[Bloquant]=true`
Alors cartes (ACME **ou** Globex) **et** bloquantes
Limites : `cf[0]` ou `cf[abc]` → ignoré ; plus de 50 valeurs → 50 premières
Validation : intégration [back] ; e2e [front] — Acceptation : ensemble attendu

## 7. Scénarios — accès et robustesse

### SC-CARD-026 — Glisser des fichiers sur une carte du tableau (important)
Étant donné le tableau ouvert
Quand l'utilisateur dépose deux fichiers sur une carte (sans l'ouvrir)
Alors deux pièces jointes sont créées avec une barre de progression ; le compteur 📎 augmente
Validation : e2e [front] (API couverte par attachments.md) — Acceptation : compteur à jour

### SC-CARD-027 — Mises à jour immuables et rendu (secondaire)
Étant donné un tableau de 6 000 cartes
Quand une carte est modifiée
Alors seule cette carte est re-rendue (composant `OnPush`, inputs signals)
Validation : composant [front] — Acceptation : un seul rendu mesuré

### SC-CARD-028 — Lien direct vers une carte (important)
Étant donné l'URL `/boards/12?card=345`
Quand un membre l'ouvre
Alors le tableau s'affiche avec la fiche de la carte 345 ouverte ; fermer la fiche retire `card` de l'URL ; cliquer sur le numéro copie le lien
Limites : carte d'un autre tableau ou supprimée → message d'erreur, tableau affiché
Validation : e2e [front] — Acceptation : fiche ouverte, URL à jour

### SC-CARD-029 — Masquer les numéros (secondaire)
Étant donné `showCardNumbers:false`
Quand on affiche le tableau
Alors les aperçus n'affichent plus `#n` ; la fiche carte l'affiche toujours (HYPOTHÈSE)
Validation : e2e [front] — Acceptation : affichage conforme

### SC-CARD-030 — Accès d'un non-membre aux cartes (critique)
Étant donné Bob non membre
Quand il appelle toute route `/api/cards/{id}/**`, `/api/checklist/{id}`, `/api/custom-fields/{id}`, `/api/labels/{id}` d'un tableau d'Alice
Alors 403 (écart E1), aucune écriture, aucune donnée de la carte dans le corps
Validation : sécurité (matrice) [back] — Acceptation : 100 % des routes refusées

### SC-CARD-031 — Identifiant non numérique (secondaire)
Étant donné un utilisateur connecté
Quand il appelle `GET /api/cards/abc`
Alors cible : 404 (écart E12 ; HYPOTHÈSE: statut actuel indéterminé, à figer par test miroir)
Validation : API [back] — Acceptation : jamais de 500

## 8. Cas critiques et limites (synthèse)
- Invariants : positions contiguës par liste ; numéros uniques et croissants par tableau ; couverture image ⇒ image de la même carte ; étiquettes, assignés et champs du même tableau.
- IDOR : étiquette, champ, pièce jointe de couverture, utilisateur assigné, liste cible d'un déplacement appartenant à un autre tableau.
- Archivées / corbeille : une carte archivée est lisible et modifiable mais pas déplaçable ; une carte dans la corbeille est 404.
- Concurrence : création (numéros), déplacements (positions).
- Valeurs maximales : titre 255, champ texte 2 000, `q` 100, 20 listes par requête groupée, 200 cartes par page.

## 9. Exigences non fonctionnelles
- Déplacement p95 < 100 ms quelle que soit la taille de la liste (2 UPDATE groupés au plus).
- Pages hydratées sans N+1 (1 requête + 2 requêtes de compteurs).
- Accessibilité : fiche carte en dialogue modal (`Échap`), deux colonnes empilées sur mobile, contrôles au clavier ; glisser-déposer doublé d'une alternative (menu « Déplacer »).
