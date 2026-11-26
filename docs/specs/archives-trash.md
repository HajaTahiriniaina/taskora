# Archives et corbeille

Service cible : **board-service** ; la suppression des fichiers lors des purges concerne **attachment-service** (événements `BoardPurged`, `ListPurged`, `CardPurged`).
Code actuel : `CardController::archive/restore`, `ListController::archive/restore/delete`, `BoardController::delete/archived/archivedLists`, `TrashController`, `Trash`, `TrashPurgeCommand`, `docker/entrypoint.sh` (purge toutes les 6 h) ; front : `board-settings.component.ts` (onglets Archives et Corbeille), `dashboard.component.ts` (corbeille des tableaux), menus « ⋯ » de carte et de liste.

## 1. Contexte et règles métier

### Archives (retrait du tableau sans suppression)
- **Carte archivée** : `archivedAt` renseigné, `position = -1`, les cartes suivantes remontent. Elle disparaît des colonnes, compteurs, filtres et recherches, reste lisible et modifiable (fiche), mais **ne peut pas être déplacée**. Restauration : en bas de sa liste, impossible si sa liste est archivée.
- **Liste archivée** : `archivedAt` renseigné ; elle quitte le tableau **avec ses cartes** (qui gardent leur position). Restauration : à droite de la dernière liste ouverte.
- Tout **membre** archive et restaure. Les opérations sont **idempotentes** (écart E7 : 200 et non 409).
- Les cartes et listes archivées dans Trello sont importées archivées.

### Corbeille (suppression douce, 30 jours)
- Supprimer une **carte**, une **liste** (avec ses cartes) ou un **tableau** les marque `deletedAt` / `deletedBy`. Ils deviennent invisibles partout (toutes les routes répondent 404 `Cet élément est dans la corbeille.`), y compris leurs enfants.
- Rétention : `RETENTION_DAYS = 30` ; `purgeAt = deletedAt + 30 jours`.
- Droits :

| Action | Carte / liste | Tableau |
|---|---|---|
| Mettre à la corbeille | tout membre | propriétaire |
| Voir la corbeille | tout membre (`GET /boards/{id}/trash`) | propriétaire (`GET /trash/boards`) |
| Restaurer | tout membre | propriétaire |
| Supprimer définitivement | **gestionnaire** (owner/admin) | propriétaire |

- Restaurer une carte : impossible si sa liste (ou son tableau) est dans la corbeille (400) ; une carte non archivée revient **en bas de sa liste** ; une carte archivée revient **dans les archives** ; activité `restored {list}`.
- Restaurer une liste : revient à droite des listes ouvertes (si elle n'est pas archivée), avec ses cartes.
- **Purge** (définitive) : suppression en cascade des lignes **puis** des fichiers (original, miniature, aperçu PDF) sur le stockage ; les erreurs de stockage sont ignorées (objet orphelin sans conséquence). La couverture est détachée avant la cascade.
- **Purge automatique** : `app:trash:purge` (option `--days`, défaut 30, minimum 0) toutes les 6 h (premier passage 5 min après le démarrage) : tableaux, puis listes, puis cartes dont `deletedAt` < maintenant − 30 jours.

## 2. Routes HTTP actuelles

| Méthode et route | Droit | Succès | Erreurs |
|---|---|---|---|
| `POST /api/cards/{id}/archive` | membre | 200 `CardDetail` | 403 ; 404 |
| `POST /api/cards/{id}/restore` | membre | 200 `CardDetail` | 400 `La liste « X » est archivée : restaurez-la d’abord.` ; 403 ; 404 |
| `POST /api/lists/{id}/archive` | membre | 200 `List` | 403 ; 404 |
| `POST /api/lists/{id}/restore` | membre | 200 `List` | 403 ; 404 |
| `GET /api/boards/{id}/archived-lists` | membre | 200 `[List]` (archivées hors corbeille, plus récemment archivées d'abord, `cardCount`) | 403 ; 404 |
| `GET /api/boards/{id}/archived?q=&offset=` | membre | 200 `{items:[Card + listTitle], total, hasMore}` (30 par page ; recherche titre, description ou `#n`/`n`) | 403 ; 404 |
| `DELETE /api/cards/{id}` · `DELETE /api/lists/{id}` · `DELETE /api/boards/{id}` | voir tableau | 204 | 403 ; 404 |
| `GET /api/boards/{id}/trash` | membre | 200 `{retentionDays:30, lists:[List + deletedAt, deletedBy, purgeAt], cards:[{id, number, title, listId, listTitle, listDeleted, archived, deletedAt, deletedBy, purgeAt}]}` (cartes : 300 au plus, plus récentes d'abord) | 403 ; 404 |
| `POST /api/trash/cards/{id}/restore` | membre | 200 `CardDetail` | 400 `La liste « X » est dans la corbeille : restaurez-la d’abord.` ; 404 `Cet élément n’est pas dans la corbeille.` |
| `DELETE /api/trash/cards/{id}` | gestionnaire | 204 | 403 `Seuls les administrateurs du tableau peuvent supprimer définitivement.` ; 404 |
| `POST /api/trash/lists/{id}/restore` | membre | 200 `List` | 404 |
| `DELETE /api/trash/lists/{id}` | gestionnaire | 204 | 403 ; 404 |
| `GET /api/trash/boards` | connecté | 200 `[{id, title, background, deletedAt, deletedBy, purgeAt}]` (mes tableaux possédés) | 401 |
| `POST /api/trash/boards/{id}/restore` | propriétaire | 200 `{id}` | 403 `Seul le propriétaire du tableau peut le restaurer ou le supprimer.` ; 404 `Ce tableau n’est pas dans la corbeille.` |
| `DELETE /api/trash/boards/{id}` | propriétaire | 204 | 403 ; 404 |

`deletedBy` est le **nom** de l'utilisateur (pas un objet).

## 3. Scénarios — archives

### SC-ARCHIVE-001 — Archiver une carte (critique)
Étant donné la liste A, B, C
Quand un membre archive B
Alors B a `archivedAt`, position -1 ; A=0, C=1 ; activité `archived` ; B disparaît de la colonne, du `cardCount`, des filtres et apparaît dans `GET /archived`
Validation : unitaire, API, intégration [back] ; e2e menu « ⋯ » → « Archiver la carte » [front] — Acceptation : positions et visibilité

### SC-ARCHIVE-002 — Archivage idempotent (important)
Étant donné une carte déjà archivée
Quand on l'archive de nouveau
Alors 200, aucune nouvelle activité, aucune position modifiée (écart E7)
Validation : API [back] — Acceptation : état inchangé

### SC-ARCHIVE-003 — Restaurer une carte archivée (critique)
Étant donné une carte archivée dont la liste compte 4 cartes ouvertes
Quand un membre la restaure
Alors elle revient en position 4 ; activité `unarchived {list}`
Limites : liste archivée → 400 `La liste « X » est archivée : restaurez-la d’abord.` ; carte non archivée → 200 sans changement
Validation : API [back] ; e2e onglet Archives [front] — Acceptation : position et erreurs

### SC-ARCHIVE-004 — Une carte archivée ne se déplace pas (important)
Étant donné une carte archivée
Quand on appelle `POST /move`
Alors 400 `Restaurez la carte avant de la déplacer.`
Validation : API [back] — Acceptation : 400

### SC-ARCHIVE-005 — Archiver et restaurer une liste (critique)
Étant donné les listes ouvertes A(0) B(1) C(2), B contenant 10 cartes
Quand on archive B
Alors B et ses cartes quittent le tableau ; `GET /archived-lists` contient B avec `cardCount: 10`
Quand on restaure B
Alors B revient en position 3 (après C) avec ses 10 cartes dans leur ordre
Limites : archiver deux fois → `archivedAt` d'origine conservé ; restaurer une liste non archivée → 200 sans changement
Validation : API, intégration [back] ; e2e menu de liste + onglet Archives [front] — Acceptation : position et cartes

### SC-ARCHIVE-006 — Recherche dans les cartes archivées (important)
Étant donné 70 cartes archivées dont #12 « Paiement »
Quand on cherche `q=paie`, `q=#12`, `q=12`, puis on pagine sans `q` (`offset=0,30,60`)
Alors la carte est trouvée par titre et par numéro ; pages 30, 30, 10 ; `total` 70
Limites : cartes archivées mais dans la corbeille ou dont la liste est dans la corbeille → exclues ; `offset` négatif → 0
Validation : API, intégration [back] ; e2e onglet Archives [front] — Acceptation : résultats exacts

### SC-ARCHIVE-007 — Ouvrir une carte archivée (secondaire)
Étant donné une carte archivée
Quand on l'ouvre depuis les archives
Alors la fiche s'affiche avec un bandeau « archivée » et le bouton « Restaurer » (HYPOTHÈSE: libellés à confirmer par FrontPierre)
Validation : e2e [front] — Acceptation : fiche ouverte

## 4. Scénarios — corbeille

### SC-TRASH-001 — Mettre une carte à la corbeille (critique)
Étant donné la liste A, B, C
Quand un membre supprime B
Alors 204 ; A=0, C=1 ; `GET /api/cards/B` → 404 `Cet élément est dans la corbeille.` ; B est listée dans `GET /boards/{id}/trash` avec `deletedBy` = son nom et `purgeAt` = +30 jours
Limites : carte archivée supprimée → aucune position décalée ; sa restauration la renvoie dans les archives
Validation : unitaire, API [back] ; e2e (confirmation puis disparition) [front] — Acceptation : 404 et contenu de la corbeille

### SC-TRASH-002 — Mettre une liste à la corbeille (critique)
Étant donné une liste de 10 cartes
Quand un membre la supprime
Alors 204 ; la liste et ses 10 cartes renvoient 404 sur toutes leurs routes (lecture, modification, commentaires, pièces jointes…) ; la corbeille affiche la liste avec `cardCount: 10`
Validation : API [back] ; e2e [front] — Acceptation : 404 en cascade

### SC-TRASH-003 — Restaurer une carte (critique)
Étant donné une carte dans la corbeille, dans une liste ouverte de 4 cartes
Quand un membre la restaure
Alors 200 `CardDetail` ; position 4 ; activité `restored {list}`
Limites : sa liste est dans la corbeille → 400 `La liste « X » est dans la corbeille : restaurez-la d’abord.` ; carte non présente dans la corbeille → 404 `Cet élément n’est pas dans la corbeille.`
Validation : API [back] ; e2e onglet Corbeille [front] — Acceptation : position et erreurs

### SC-TRASH-004 — Restaurer une liste (important)
Étant donné une liste dans la corbeille
Quand un membre la restaure
Alors elle revient à droite des listes ouvertes avec ses cartes ; une liste archivée puis supprimée revient dans les archives
Validation : API [back] ; e2e [front] — Acceptation : position

### SC-TRASH-005 — Purge définitive réservée aux gestionnaires (critique)
Étant donné une carte avec 3 pièces jointes (dont une couverture avec miniature) dans la corbeille
Quand un simple membre appelle `DELETE /api/trash/cards/{id}`
Alors 403 `Seuls les administrateurs du tableau peuvent supprimer définitivement.`
Quand un admin l'appelle
Alors 204 ; la carte, ses commentaires, checklist, valeurs, activités et pièces jointes sont supprimés ; les originaux, miniatures et aperçus PDF sont supprimés du stockage
Limites : stockage indisponible → 204 quand même (fichiers orphelins tolérés, journalisés)
Validation : API, sécurité, intégration (LocalStack) [back] ; e2e (confirmation « irréversible ») [front] — Acceptation : aucune ligne ni objet restant (hors panne simulée)

### SC-TRASH-006 — Purger une liste ou un tableau (critique)
Étant donné une liste (ou un tableau) dans la corbeille contenant 50 cartes avec fichiers
Quand un gestionnaire (ou le propriétaire pour un tableau) la purge
Alors toutes les lignes sont supprimées en une transaction, puis tous les fichiers des cartes
Validation : intégration [back] — Acceptation : aucune ligne ni objet restant

### SC-TRASH-007 — Corbeille des tableaux (critique)
Étant donné Alice propriétaire d'un tableau mis à la corbeille, Bob admin de ce tableau
Quand Bob appelle `GET /api/trash/boards`, puis `POST /api/trash/boards/{id}/restore`
Alors la liste de Bob ne contient pas le tableau ; la restauration → 403
Quand Alice restaure
Alors 200 `{id}` ; le tableau réapparaît pour tous ses membres avec tout son contenu
Limites : tableau non supprimé → 404 `Ce tableau n’est pas dans la corbeille.`
Validation : API, sécurité [back] ; e2e bouton « Corbeille » de l'accueil (jours restants) [front] — Acceptation : droits et restauration

### SC-TRASH-008 — Purge automatique après 30 jours (critique)
Étant donné un tableau supprimé il y a 31 jours, une liste il y a 30 jours et 1 minute, une carte il y a 29 jours
Quand la purge automatique s'exécute
Alors le tableau et la liste sont purgés (lignes et fichiers), la carte reste ; le rapport indique `1 tableau(x), 1 liste(s), 0 carte(s)`
Limites : exécution concurrente de deux instances → pas de double suppression en erreur (HYPOTHÈSE: la cible utilise un verrou de planification type ShedLock) ; `--days=0` purge tout
Validation : unitaire (calcul de l'échéance, horloge injectée), intégration [back] ; CI (tâche planifiée déclarée) [Manuel] — Acceptation : sélection exacte

### SC-TRASH-009 — Droits sur la corbeille d'un tableau (critique)
Étant donné Bob non membre
Quand il appelle `GET /boards/{id}/trash` ou restaure / purge un élément de ce tableau
Alors 403 (écart E1)
Validation : sécurité [back] — Acceptation : aucune écriture

### SC-TRASH-010 — Affichage des jours restants (secondaire)
Étant donné un élément dont `purgeAt` est dans 3 jours et 2 heures
Quand la corbeille est affichée
Alors « 4 jours » restants (arrondi supérieur, jamais négatif)
Validation : unitaire (`daysLeft`) [front] — Acceptation : arrondi

## 5. Cas critiques et limites
- Corbeille transitive : un élément dont le parent est dans la corbeille est lui-même invisible (`Trash::isTrashed`).
- Limite de 300 cartes affichées dans la corbeille d'un tableau.
- Concurrence : restauration pendant la purge automatique → l'une des deux échoue proprement (404).
- Services externes : panne S3 pendant une purge → lignes supprimées, fichiers orphelins.
- Migration : les événements de purge doivent porter la liste des clés de stockage (board-service ne lit pas la base d'attachment-service : à arbitrer par BackAli).

## 6. Exigences non fonctionnelles
- Purge d'un tableau de 9 000 cartes < 60 s, sans bloquer les requêtes utilisateur.
- Accessibilité : confirmations destructives explicites (« Cette action est irréversible »), boutons au clavier.
