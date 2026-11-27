# Phase 3 : migration de board-service — spécification, tranches et plan de validation

Auteur : Paul (chef d'équipe, test planner). Date : 27/11/2026. Statut : **proposé**, en attente des décisions marquées « DÉCISION UTILISATEUR » (§6.1 et §6.2).
Branche : `feat/board-service` (base `main` `af1e1aa`).
Spécifications fonctionnelles de référence (inchangées, sauf surcharges du §5.5) : [boards-members.md](boards-members.md), [lists.md](lists.md), [cards.md](cards.md), [comments-mentions.md](comments-mentions.md), [activity.md](activity.md), [archives-trash.md](archives-trash.md). Voir aussi [attachments.md](attachments.md), [notifications-push.md](notifications-push.md), [trello-import.md](trello-import.md) pour les frontières.
Décisions déjà prises : [decisions.md](decisions.md) (U1–U5, E1, E4, E14, E15–E21). Architecture : `docs/architecture/backend-hexagonal.md` ; modèle de cohabitation éprouvé en phase 1 : `docs/architecture/identity-migration.md`.
Sources lues pour ce document (comportement réel) : `backend/src/Controller/{Board,List,Card,CustomField,Trash,InternalInvitation}Controller.php`, `ApiController.php`, `Service/{BoardAccess,Trash,ActivityLogger,Notifier,RichText,CommentAttachments,InvitationClaimer,Presenter}.php`, `Repository/{Card,Board,Activity}Repository.php`, `Entity/*`, `migrations/*`, `backend/docker/entrypoint.sh`, `frontend/nginx.conf`, `frontend/src/app/pages/board/*`.
Ce document ne contient ni secret, ni valeur de `.env`, ni donnée personnelle.

## 1. Objectif

Remplacer, tranche par tranche, le backend Symfony par `services/board-service` (Spring Boot, hexagonal) pour les tableaux, membres, invitations, listes, cartes, étiquettes, champs personnalisés, checklists, commentaires, historique, archives et corbeille, **sans changement visible pour le front** (mêmes routes, mêmes corps), sauf les divergences décidées par l'utilisateur (E1 et les écarts du §6.2 qu'il choisira de corriger).

Chaque tranche doit pouvoir :
1. coexister avec Symfony, qui continue de servir les autres routes sur **les mêmes données** ;
2. être basculée par une simple commutation de routage nginx ;
3. revenir à Symfony en quelques secondes, **sans perte de données**, et ce retour arrière doit être **répété en recette** avant la bascule réelle (comme en phase 1).

## 2. Périmètre et frontière

### 2.1 Ce qui part dans board-service

| Agrégat / domaine | Routes (contrat actuel conservé) | Tranche |
|---|---|---|
| Tableaux, favoris | `GET/POST /api/boards` ; `GET/PATCH/DELETE /api/boards/{id}` ; `POST /api/boards/{id}/favorite` | T1 |
| Membres et invitations | `POST /api/boards/{id}/members` ; `PATCH/DELETE /api/boards/{id}/members/{userId}` ; `DELETE /api/boards/{id}/invitations/{invId}` ; interne `POST /internal/invitations/claim` (appelé par identity-service) | T1 |
| Corbeille des tableaux (lecture, restauration) | `GET /api/trash/boards` ; `POST /api/trash/boards/{id}/restore` | T1 |
| Listes | `POST /api/boards/{id}/lists` ; `POST /api/boards/{id}/lists/reorder` ; `PATCH/DELETE /api/lists/{id}` ; `POST /api/lists/{id}/archive` et `/restore` | T2 |
| Cartes (cycle de vie, positions, numéros, assignation, échéance, priorité, couverture, étiquettes portées) | `GET/POST /api/lists/{id}/cards` ; `GET /api/boards/{id}/cards` ; `GET/PATCH/DELETE /api/cards/{id}` ; `POST /api/cards/{id}/move`, `/archive`, `/restore`, `/assignees` | T2 |
| Archives et corbeille douce des listes et cartes | `GET /api/boards/{id}/archived`, `/archived-lists`, `/trash` ; `POST /api/trash/cards/{id}/restore` ; `POST /api/trash/lists/{id}/restore` | T2 |
| Historique | `GET /api/cards/{id}/activity` (et écriture de toutes les entrées produites par les routes migrées) | T2 |
| Étiquettes du tableau | `POST /api/boards/{id}/labels` ; `PATCH/DELETE /api/labels/{id}` | T3 |
| Champs personnalisés (définitions et valeurs) | `POST /api/boards/{id}/custom-fields` ; `PATCH/DELETE /api/custom-fields/{id}` ; `PUT /api/cards/{id}/custom-fields/{fieldId}` | T3 |
| Checklists | `POST /api/cards/{id}/checklist` ; `PATCH/DELETE /api/checklist/{id}` | T3 |
| Commentaires et mentions | `POST /api/cards/{id}/comments` ; `DELETE /api/comments/{id}` | T4 |
| Suppression définitive et purge automatique | `DELETE /api/trash/cards/{id}`, `/api/trash/lists/{id}`, `/api/trash/boards/{id}` ; tâche planifiée (aujourd'hui `app:trash:purge` toutes les 6 h dans `entrypoint.sh`) | T5 |

### 2.2 Ce qui reste dans Symfony pendant toute la phase 3 (puis part dans d'autres services)

| Périmètre | Routes | Service cible | Raison |
|---|---|---|---|
| Pièces jointes, téléversements, diffusion des fichiers, miniatures, aperçus | `/api/cards/{id}/attachments`, `/api/attachments/**`, `/api/uploads`, `/uploads/**` | attachment-service (phase 4) | Stockage S3, binaires de 100 Mo, Gotenberg, cycle de vie propre ; aucun lien avec les positions ni la numérotation |
| Notifications internes et Web Push | `/api/notifications/**`, `/api/push/**`, envoi après la réponse (`PushSender`) | notification-service (phase 5) | Boîte de réception centrée sur l'utilisateur, clés VAPID, envoi asynchrone ; board-service **produit** des événements, il ne stocke pas de notification |
| Import Trello et transfert des fichiers | `/api/import/**`, `/api/boards/{id}/trello-files/**`, `app:import-trello` | import-service (phase 6) | Intégration externe longue (jeton Trello, reprises, limitation de débit) ; crée des tableaux et des cartes « en masse » |
| Santé, démonstration | `GET /api/health`, `app:seed-demo` | — | Outils |
| Identité | `/api/auth/**`, `/api/me` | identity-service (phase 1, déjà prêt) | — |

### 2.3 Justification de la frontière
1. **Une seule autorité pour les invariants du tableau.** Les positions contiguës des cartes (déplacement, archivage, corbeille, restauration), la numérotation `card_seq` et les droits par rôle sont modifiés par les routes T1–T5. Les garder dans un seul service rend possible un verrouillage cohérent (SC-CARD-033).
2. **Commentaires, historique, archives et corbeille font partie du cycle de vie de la carte** : l'historique est écrit dans la même transaction que la modification (`ActivityLogger`), l'archivage et la corbeille déplacent des positions, la purge supprime la carte en cascade. Les séparer imposerait des transactions distribuées.
3. **Pièces jointes, notifications et import restent dehors** parce qu'ils ont des dépendances techniques lourdes (S3, Gotenberg, VAPID, API Trello) et un cycle de vie différent. board-service leur parle par des **ports** (§4.5) : en phase 3, ces ports sont implémentés par des appels aux endpoints internes transitoires de Symfony ; plus tard, par RabbitMQ ou par les nouveaux services, sans toucher au domaine.
4. **Tranches** : chaque tranche regroupe tous les écrivains d'un même invariant. En particulier, **toutes** les opérations qui décalent des positions de cartes (création, déplacement, archivage, restauration, corbeille) basculent ensemble en T2 : sinon Symfony (sans verrou) et board-service écriraient les mêmes positions en même temps.

### 2.4 Dépendances croisées constatées dans le code

| Dépendance | Sens | Traitement pendant la phase 3 |
|---|---|---|
| Clés étrangères `attachment.card_id`, `notification.board_id/card_id`, `trello_import_file.board_id/card_id`, `card.cover_attachment_id` | tables d'autres domaines → tables du tableau | Les lignes du tableau restent dans les **tables existantes** (U6-A recommandé, §4.2) ; les cascades InnoDB continuent de s'appliquer |
| Clés étrangères vers `taskora.user` (`board.owner_id`, `board_member.user_id`, `card_assignee.user_id`, `comment.author_id`, `activity.actor_id`, `*.deleted_by_id`, `invitation.invited_by_id`, `card.created_by_id`) | tableau → identité | `taskora.user` reste tenue à jour par la projection d'identity (U1-B, ADR A9). **La projection doit être conservée pendant toute la phase 3** (l'ADR A9 prévoit de la supprimer « à la migration de board-service » : à réviser par BackAli) |
| Fiche carte : `attachments[]`, `attachmentCount`, `coverImage` (clé de miniature) | board → pièces jointes | Lecture de la table `attachment` (lecture seule) pendant la cohabitation |
| `PATCH /api/cards/{id}` `coverAttachmentId` : génération de la miniature si absente (`Uploads::thumbnailFor`) | board → pièces jointes | Appel synchrone à un endpoint interne transitoire de Symfony (§4.5) |
| Commentaire : les `<img>`/`<a>` vers `/uploads/…` deviennent des pièces jointes **dans la même transaction**, après vérification de l'objet dans le stockage (`CommentAttachments`) | board → pièces jointes | ADR à trancher par BackAli (§6.3, B-ADR-6) ; contrainte : contrat de réponse et atomicité conservés (SC-COMMENT-013) |
| Notifications `board_invite`, `member_joined`, `member_removed`, `card_assigned`, `card_moved`, `card_due`, `card_comment` + Web Push | board → notifications | Outbox de board-service → endpoint interne transitoire de Symfony qui crée les notifications et envoie le push (U7-a recommandé) |
| Purge : suppression des fichiers (original, miniature, aperçu) après suppression des lignes | board → stockage | Clés lues avant la suppression, transmises par l'outbox à un endpoint interne de Symfony (T5) |
| `GET /api/boards/{id}` : `trelloFiles` (fichiers Trello restant à copier) | board → import | Lecture de `trello_import_file` (lecture seule) |
| Pièces jointes ajoutées par Symfony : entrée d'historique `attachment` / `attachment_removed`, couverture retirée | pièces jointes → tableau | Symfony continue d'écrire dans `activity` et `card` (tables partagées) ; formats identiques exigés (SC-ACTIVITY-008) |
| Import Trello : crée tableau, membres, listes, cartes (avec `card_seq`), commentaires, historique ; transfert : réécrit descriptions et commentaires, pose la couverture | import → tableau | Symfony continue d'écrire dans les tables partagées ; numérotation par le même incrément atomique (SC-CARD-032) |
| Inscription : consommation des invitations (`/internal/invitations/claim`, U2-a) | identité → tableau | Servie par board-service dès T1 (SC-BOARD-030) ; identity change d'URL interne |

## 3. Tranches recommandées

| Tranche | Contenu | Scénarios existants rejoués | Nouveaux scénarios (§7) | Prérequis |
|---|---|---|---|---|
| **T0 — Socle** (aucune route publique basculée) | Module `services/board-service`, validation JWT, résolution de l'utilisateur, politique d'accès (E1), mapping des tables partagées, outbox, endpoints internes transitoires côté Symfony, routage nginx par tranche (variables d'amont), commande `verify`, harnais de tests miroir | — | SC-BOARD-020 à 028 | Décisions U6–U9 ; contrats OpenAPI publiés |
| **T1 — Tableaux et membres** | §2.1 lignes T1 | SC-BOARD-001 à 018 (019 est front seul, rejoué en e2e), SC-TRASH-007 (lecture et restauration), SC-IDENT-005 (inscription d'un invité) | SC-BOARD-029 à 035 | T0 vert |
| **T2 — Listes, cartes, archives, corbeille douce, historique** | §2.1 lignes T2 | SC-LIST-001 à 010 ; SC-CARD-001 à 010, 013 à 018, 023 à 031 ; SC-ARCHIVE-001 à 007 ; SC-TRASH-001 à 004, 009, 010 ; SC-ACTIVITY-001 à 007 | SC-LIST-011 à 014 ; SC-CARD-032 à 043 ; SC-ACTIVITY-008 | T1 basculée et stable |
| **T3 — Étiquettes, champs personnalisés, checklists** | §2.1 lignes T3 | SC-CARD-011, 012, 019 à 022 (et SC-CARD-025, 020 en non-régression des filtres) | SC-CARD-044 à 047 | T2 basculée |
| **T4 — Commentaires et mentions** | §2.1 ligne T4 | SC-COMMENT-001 à 012 | SC-COMMENT-013 à 016 | T2 basculée ; ADR B-ADR-6 décidé |
| **T5 — Suppression définitive et purge automatique** | §2.1 ligne T5 | SC-TRASH-005, 006, 008, SC-TRASH-007 (purge) | SC-TRASH-011 à 015 | T2 basculée |

Ordre : T0 → T1 → T2 → (T3, T4, T5 dans cet ordre, ou T3 et T5 en parallèle une fois T2 stable). Une tranche n'est basculée que lorsque la précédente a passé **sa période d'observation** (proposition : 48 h sans 5xx ni retour arrière).

Pourquoi ce découpage :
- T1 est la plus petite tranche qui exerce tout le socle (JWT, accès, outbox, notification, invitation) sans toucher aux positions.
- T2 est la plus grosse, mais on ne peut pas la couper sans séparer les écrivains de positions (§2.3, point 4).
- T3 est indépendante des positions, simple, et fixe l'encodage JSON des valeurs (SC-CARD-044) avant que les filtres ne dépendent d'elle.
- T4 dépend du couplage avec les pièces jointes (point le plus délicat) : à part.
- T5 isole le risque destructif (S3, suppression définitive, tâche planifiée).

## 4. Stratégie de données

### 4.1 Constats
1. **Clés étrangères croisées** (§2.4) : `attachment`, `notification`, `trello_import_file` référencent `card` et `board` avec `ON DELETE CASCADE`. Ces domaines restent dans Symfony pendant la phase 3 : les lignes de cartes et de tableaux **doivent** exister dans le schéma `taskora`.
2. **Écrivains multiples** : en phase 3, Symfony continue d'écrire dans les tables du tableau (import Trello, transfert des fichiers Trello, pièces jointes → `activity` et `card.cover_attachment_id`, purge jusqu'à T5).
3. **Numérotation** : `UPDATE board SET card_seq = LAST_INSERT_ID(card_seq + 1) WHERE id = ?` est atomique sur une ligne unique. Deux copies de `card_seq` (une par schéma) ne peuvent pas être tenues atomiquement.
4. **Positions** : aucun verrou côté Symfony (les décalages sont des `UPDATE` groupés sans `SELECT … FOR UPDATE`).
5. **Dates** : toutes les colonnes `DATETIME` sont écrites par PHP en heure locale **`Europe/Paris`**, sans fuseau (`docker/php.ini`), sauf `card.due_date`, qui reçoit en pratique l'heure **UTC** du client (le front envoie `toISOString()`, Doctrine écrit l'heure murale du décalage reçu) : voir E31.
6. **Encodages JSON** stockés et relus par l'autre backend : `custom_field_value.value` (`"opt_id"`, `true`, `12.5`, `"2026-12-01"`), `custom_field.options`, `activity.data`.
7. **Schéma** : Symfony applique ses migrations Doctrine au démarrage (`doctrine:migrations:migrate`, `entrypoint.sh`).

### 4.2 Options — DÉCISION UTILISATEUR U6

| Option | Description | Avantages | Inconvénients |
|---|---|---|---|
| **A. Tables partagées en place** | board-service lit et écrit **directement** les tables existantes du tableau dans `taskora` (utilisateur MariaDB dédié aux droits limités). Ses tables techniques (outbox, verrou de planification) vont dans un schéma propre `board`. Le déplacement des tables dans `board` se fait à la fin de la cohabitation (après les phases 4–6) | Aucune copie ni synchronisation ; `card_seq` et clés étrangères inchangées ; **retour arrière immédiat et sans perte** (les deux backends lisent les mêmes lignes) ; tranches indépendantes | Déroge temporairement à « une base par service » ; deux backends écrivent les mêmes tables (règles du §4.3 obligatoires) |
| **B. Schéma propre `board` + projection** (comme U1-B) | Copie initiale dans `board`, puis projection transactionnelle vers `taskora` ; les écritures de Symfony (import, pièces jointes, purge) doivent être recopiées dans l'autre sens | Respecte « une base par service » dès maintenant | Synchronisation **bidirectionnelle** sur une vingtaine de tables ; `card_seq` impossible à tenir atomiquement en deux exemplaires ; reprise à chaque retour arrière ; volume (9 000 cartes, historique) ; risque élevé de divergence |
| C. Schéma propre sans projection | Symfony lirait `board` | — | Rejetée : les clés étrangères et le code Symfony (pièces jointes, notifications, import) visent `taskora` |

**Recommandation de Paul : A.** En phase 1, l'option B était raisonnable (une table, peu d'écritures, un seul écrivain). Ici, B multiplierait les risques sans bénéfice visible pour l'utilisateur, et A rend chaque retour arrière trivial. La règle « une base par service » est atteinte à la fin de la cohabitation (§4.7).

### 4.3 Règles de cohabitation sur les tables partagées (si U6-A)
| # | Règle | Vérification |
|---|---|---|
| R1 | **Symfony reste le seul propriétaire du DDL du schéma `taskora`** pendant la phase 3. board-service n'exécute aucun DDL sur `taskora` ; Flyway ne gère que le schéma `board`. Tout index ou colonne nécessaire passe par une migration Doctrine **additive**, décidée par BackAli | Revue (BackReviewer) ; test d'intégration : le schéma `taskora` après démarrage de board-service est identique à celui de Symfony |
| R2 | Utilisateur MariaDB `board` : `SELECT, INSERT, UPDATE, DELETE` sur `board`, `board_list`, `board_member`, `invitation`, `card`, `card_assignee`, `card_label`, `label`, `custom_field`, `custom_field_value`, `checklist_item`, `comment`, `activity` ; `SELECT` seul sur `user`, `attachment`, `trello_import_file` ; tous droits sur le schéma `board`. Jamais `DELETE` sur `user`. (Les cascades InnoDB vers `attachment`, `notification`, `trello_import_file` s'appliquent sans droit sur ces tables : à confirmer par un test d'intégration) | Script d'initialisation (Manuel) ; test d'intégration des droits |
| R3 | Identifiants : `INT` auto-incrémentés existants, exposés tels quels ; aucune ré-identification | Tests miroir |
| R4 | Dates : écrites et lues en heure murale `Europe/Paris` comme PHP (même règle que l'ADR A9), **sauf** `card.due_date` (selon E31) | SC-BOARD-028, SC-CARD-042 |
| R5 | Encodages JSON identiques à ceux de PHP (`json_encode` sans option : `/` échappé, non-ASCII en `\uXXXX`, entiers sans `.0`) pour `custom_field_value.value`, `custom_field.options`, `activity.data` : l'autre backend doit relire, filtrer et afficher les mêmes valeurs | SC-CARD-044, SC-ACTIVITY-008 |
| R6 | Numérotation : uniquement par l'incrément atomique sur `board.card_seq` (même requête ou `SELECT … FOR UPDATE` sur la ligne `board`) | SC-CARD-002, 003, 032 |
| R7 | Positions des cartes : après la bascule de T2, board-service est le **seul** écrivain de positions sur les tableaux existants (l'import ne crée que des tableaux neufs). Il sérialise par liste (verrou de ligne `board_list` pris dans l'ordre croissant des ids) | SC-CARD-033, 034 |
| R8 | Ordre déterministe partout où Symfony ne l'est pas : `position, id` (listes, cartes, checklist, champs) ; membres par `board_member.id` ; tableaux par `created_at DESC, id DESC` (HYPOTHÈSE : ordre de Symfony à figer par test miroir) | SC-BOARD-029, SC-LIST-014 |
| R9 | Aucune écriture de board-service ne dépend d'un état mis en cache entre deux requêtes (Symfony peut avoir écrit entre-temps) | Revue |
| R10 | Les migrations Doctrine générées par `doctrine:migrations:diff` ne doivent jamais supprimer un objet ajouté pour board-service : interdiction de générer un diff automatique sur ces tables pendant la cohabitation | Revue (BackReviewer, `git diff backend/migrations`) |

### 4.4 Données par tranche

| Tranche | Tables écrites par board-service | Tables lues seulement | Effets sortants (outbox) | Retour arrière |
|---|---|---|---|---|
| T0 | schéma `board` (outbox, verrou) | toutes celles du §4.3 R2 | — | rien à faire |
| T1 | `board`, `board_member`, `invitation`, `label` et `board_list` (contenu par défaut à la création), `card_assignee` (désassignation au retrait d'un membre) | `user`, `board_list`, `label`, `custom_field`, `card` (compteurs), `trello_import_file` | `NotificationRequested` (`board_invite`, `member_joined`, `member_removed`) ; événements `BoardCreated`, `MemberAdded`, `MemberRemoved`, `BoardTrashed`, `BoardRestored` en `HELD` | routage T1 → Symfony ; identity revient à l'URL interne de Symfony ; vidange de l'outbox |
| T2 | `board_list`, `card`, `card_assignee`, `card_label`, `activity` | `attachment` (fiche, compteurs, couverture), `comment`, `checklist_item`, `custom_field(_value)`, `label` | `NotificationRequested` (`card_assigned`, `card_moved`, `card_due`) ; appel synchrone « miniature » ; `CardMoved`, `CardAssigned`, `CardDueSet`, `CardTrashed`… en `HELD` | routage T2 → Symfony ; aucune donnée à recopier ; les positions restent contiguës (invariant vérifié par `verify`) |
| T3 | `label`, `card_label`, `custom_field`, `custom_field_value`, `checklist_item`, `activity` | — | — | routage T3 → Symfony |
| T4 | `comment`, `activity` (+ `attachment` selon B-ADR-6) | `attachment` | `NotificationRequested` (`card_comment`) ; `CommentPosted`, `MemberMentioned` en `HELD` | routage T4 → Symfony |
| T5 | suppression `board`, `board_list`, `card` (cascades) | `attachment` (clés à libérer) | `StorageKeysReleased {keys[]}` vers Symfony ; `BoardPurged`, `ListPurged`, `CardPurged` en `HELD` | routage T5 → Symfony **et** réactivation de la boucle de purge de Symfony (SC-TRASH-012) |

### 4.5 Ports sortants, outbox et endpoints internes (si U7-a)
- **Outbox** `board.outbox_message`, même modèle que l'ADR A11 (statuts `PENDING`, `DELIVERED`, `FAILED`, `HELD`, relais avec `SKIP LOCKED`, rejeu idempotent par `eventId`). Écrite dans **la même transaction** que la modification ; une requête en échec ne produit aucun message.
- **Destinations transitoires** (endpoints Symfony, réseau interne, secret partagé `X-Internal-Secret`, `hash_equals`, refus si secret vide, erreurs `{"error"}`) :

| Endpoint Symfony (à créer) | Usage | Idempotence | Mode |
|---|---|---|---|
| `POST /internal/notifications` `{eventId, type, actorId, recipientIds[], message, boardId?, cardId?}` | crée les notifications et met en file le Web Push (comme `Notifier::notify` : jamais l'acteur, sans doublon) | `eventId` déjà traité → 200 sans effet | asynchrone (outbox), livraison tentée juste après la validation de la transaction |
| `POST /internal/storage/release` `{eventId, keys[]}` | supprime les objets du stockage (erreurs ignorées et journalisées, comme `Uploads::deleteKeys`) | objet absent = succès | asynchrone (outbox) |
| `POST /internal/attachments/{id}/thumbnail` | garantit la miniature d'une image (couverture, SC-CARD-018/037) ; renvoie `thumbKey` ou `null` | naturelle | **synchrone**, délai court, échec → l'original sert de couverture |
| Collecte des fichiers d'un commentaire | selon B-ADR-6 | — | — |

- **Endpoint servi par board-service** : `POST /internal/invitations/claim`, **même contrat** que `docs/contracts/openapi/identity-service.internal.yaml` (champs `eventId`, `userId`, `email` ; 200 `{claimed, boardIds}` ; 400 ; 401 ; 404 utilisateur pas encore visible ; 409 e-mail différent). À la bascule de T1, identity pointe son URL interne vers board-service ; au retour arrière, vers Symfony. Les deux implémentations étant idempotentes et équivalentes, un message en attente peut être livré à l'une ou à l'autre.
- Les événements de domaine destinés au futur broker sont écrits en `HELD` (aucun transport RabbitMQ en phase 3 si U7-a).

### 4.6 Commande de contrôle `verify` (lecture seule, rapport chiffré sans donnée personnelle)
Exécutable à tout moment (`--board.verify`), avant et après chaque bascule et chaque retour arrière. Sortie non nulle si un invariant est violé :

| # | Invariant |
|---|---|
| V1 | Pour chaque liste, les positions des cartes ouvertes (non archivées, hors corbeille) forment exactement `0..n-1` |
| V2 | Cartes archivées ou dans la corbeille : position `-1` (sauf cartes d'une liste dans la corbeille, qui gardent la leur) |
| V3 | Pour chaque tableau, `card_seq ≥ MAX(card.number)` ; aucun doublon `(board_id, number)` |
| V4 | Exactement un membre `owner` par tableau, et c'est `board.owner_id` |
| V5 | Aucun assigné qui n'est pas membre du tableau de la carte |
| V6 | Aucune étiquette, valeur de champ ou couverture appartenant à un autre tableau que celui de la carte |
| V7 | `card.board_id` = tableau de sa liste |
| V8 | Outbox : nombre de messages `PENDING` de plus d'une minute, `FAILED` |
Les écarts **hérités** (données créées par Symfony avant la migration, par exemple des positions en doublon dans les checklists) sont comptés à part au premier passage (« état initial ») ; la bascule exige qu'ils n'augmentent pas.

### 4.7 Fin de la cohabitation (hors phase 3, pour mémoire)
Quand attachment-, notification- et import-service seront en service : remplacer les lectures croisées par des appels ou des projections, retirer les clés étrangères inter-domaines, déplacer les tables du tableau dans le schéma `board` (`RENAME TABLE`, atomique), supprimer la projection `taskora.user` et les endpoints internes de Symfony.

## 5. Authentification et autorisation

### 5.1 Jeton
- board-service valide les JWT **RS256** avec la **clé publique** seule (montée en lecture seule ; jamais la clé privée) : signature, `exp` obligatoire, `iat` non futur, aucune tolérance d'horloge (comme Lexik, `backend-hexagonal.md` §8.1). Jetons émis par identity-service **et** par Symfony acceptés (cohabitation).
- Erreurs 401 au format Lexik `{"code":401,"message":"…"}` et en-tête `WWW-Authenticate: Bearer`, avec la même précédence des messages (`JWT Token not found`, `Invalid JWT Token`, `Expired JWT Token`).

### 5.2 Résolution de l'utilisateur
- Revendication `uid` si présente (jetons d'identity), sinon `username` (= e-mail, jetons de Symfony) recherché dans `taskora.user` avec la collation de la colonne (`utf8mb4_unicode_ci`), comme le fournisseur d'utilisateurs de Symfony.
- Les deux revendications présentes mais incohérentes → 401 `Invalid JWT Token`. Compte disparu → 401 (même corps que SC-IDENT-013).
- Nom, e-mail et couleur d'avatar sont toujours relus dans `taskora.user` (jamais pris dans le jeton) : SC-BOARD-027.

### 5.3 Politique d'accès (E1 appliqué)
Contrôlée **dans chaque cas d'utilisation** (jamais seulement par le routage), **avant** toute validation du corps : un non-membre n'apprend aucun message de validation.

| Situation | Statut et corps |
|---|---|
| Tableau inexistant, **ou** l'appelant n'en est pas membre (tableau ouvert ou dans la corbeille) | 404 `{"error":"Tableau introuvable."}` (routes `/api/boards/{id}/**`) |
| Membre, tableau dans la corbeille | 404 `{"error":"Ce tableau est dans la corbeille."}` |
| Ressource enfant (`/api/lists/{id}`, `/api/cards/{id}`, `/api/labels/{id}`, `/api/custom-fields/{id}`, `/api/checklist/{id}`, `/api/comments/{id}`) inexistante, **ou** appartenant à un tableau dont l'appelant n'est pas membre | 404 `{"error":"Ressource introuvable."}` |
| Membre, ressource enfant dans la corbeille (directement ou par sa liste / son tableau) | 404 `{"error":"Cet élément est dans la corbeille."}` (ou `Ce tableau est dans la corbeille.` si c'est le tableau, comme aujourd'hui) |
| Routes `/api/trash/cards|lists/{id}/**` : élément absent de la corbeille **ou** non-membre | 404 `{"error":"Cet élément n’est pas dans la corbeille."}` |
| Routes `/api/trash/boards/{id}/**` : tableau absent de la corbeille **ou** non-membre | 404 `{"error":"Ce tableau n’est pas dans la corbeille."}` ; membre non propriétaire → 403 (message actuel) |
| Membre sans droit de gestion sur une action de gestionnaire | 403 `{"error":"Action réservée aux administrateurs du tableau."}` (inchangé) |
| Actions réservées au propriétaire, purge réservée aux gestionnaires | 403, messages actuels (inchangés) |
Invariant de sécurité : pour un non-membre, **statut, corps et en-têtes** sont identiques à ceux d'une ressource inexistante (SC-BOARD-021).

### 5.4 Endpoints internes
- Préfixe `/internal/**`, non routé par nginx, secret partagé obligatoire, jamais de JWT ; port de gestion (`/actuator/health`) séparé et non routé.
- Le port HTTP de board-service n'est pas publié sur l'hôte en dehors du développement (constat phase 1 : le port de l'API Symfony est publié, d'où le secret obligatoire).

### 5.5 Surcharges des scénarios existants pour board-service
Les scénarios suivants gardent leur identifiant ; pour board-service, l'attendu « 403 » des non-membres devient **404** selon le §5.3 (décision E1) : SC-BOARD-006, SC-LIST-008, SC-CARD-030, SC-ACTIVITY-007, SC-TRASH-009, ainsi que les limites « non-membre → 403 » de SC-COMMENT-008, SC-BOARD-016 (quitter un tableau dont on n'est pas membre) et SC-TRASH-007 (non-membre). Les tests miroir excluent ces cas (divergence voulue, §9.3), sauf si U8 aligne Symfony.

## 6. Décisions à prendre

### 6.1 Décisions structurantes — DÉCISION UTILISATEUR

| # | Question | Options | Recommandation de Paul |
|---|---|---|---|
| **U6** | Données pendant la cohabitation | A. tables partagées en place + schéma `board` technique ; B. schéma propre + projection bidirectionnelle (§4.2) | **A** (retour arrière sans perte, `card_seq` et clés étrangères intactes) |
| **U7** | Effets vers les domaines restés dans Symfony (notifications, push, fichiers) | a. outbox + endpoints internes transitoires de Symfony, pas de RabbitMQ (prolonge U2-a) ; b. RabbitMQ dès maintenant + consommateurs Symfony Messenger transitoires | **a** : les consommateurs sont encore dans Symfony ; RabbitMQ arrive avec notification-service, où il remplacera la destination sans toucher au domaine |
| **U8** | E1 côté Symfony pendant les tranches | a. passer `BoardAccess` de Symfony en 404 (mêmes messages que §5.3) **à la bascule de T1**, pour que toutes les routes répondent pareil quel que soit l'amont ; b. laisser Symfony en 403 jusqu'à ce que chaque route soit migrée (réponses mixtes pendant des semaines) | **a** : la décision E1 prévoyait 403 « jusqu'à la migration de board-service », qui commence à T1 ; une petite modification testée (PHPUnit) évite des réponses incohérentes et rend le retour arrière neutre pour la sécurité |
| **U9** | Fenêtre de bascule par tranche | a. **sans gel des écritures** (aucune copie de données si U6-A : simple commutation nginx, ~1 s), hors heures de pointe, annoncée ; b. gel court comme U4 | **a** si U6-A ; **b** obligatoire si U6-B |

Latence (U5) : les exigences de latence des specs (p95) restent un **suivi après bascule**, non bloquant, comme décidé le 27/11/2026. Seuls restent exigés : 0 × 5xx pendant les répétitions (L5) et l'absence de N+1 prouvée par des tests d'intégration qui **comptent les requêtes SQL** (critère déterministe, pas une mesure de temps). Paul ne demande pas d'exception.

### 6.2 Comportements de Symfony qui ressemblent à des bugs — DÉCISION UTILISATEUR
Par défaut, board-service **reproduit** Symfony (compatibilité). Chaque correction décidée devient une divergence voulue, exclue des tests miroir, publiée dans `docs/contracts/` et ajoutée à `decisions.md`. Constats faits à la lecture du code ; ceux marqués « à confirmer » seront figés par un test miroir avant toute décision d'implémentation.

| # | Comportement actuel (Symfony) | Risque | Options | Recommandation de Paul | Scénarios |
|---|---|---|---|---|---|
| **E22** | Types JSON non contrôlés : un tableau ou un objet là où une chaîne est attendue (`description` de tableau ou de carte, `color` de liste ou d'étiquette, `cover`, `content`) → `TypeError` → **500** ; nombre ou booléen convertis en texte (`true` → `"1"`) ; `ids` non tableau dans `lists/reorder` → 500 ; couleur de plus de 20 caractères → erreur SQL → **500** | 5xx sur entrée client ; données absurdes | a. reproduire ; b. **400** `Le champ "<nom>" doit être une chaîne de caractères.` (règle E17) ; `ids` non tableau → 400 `Le champ "ids" doit être un tableau.` ; couleur > 20 caractères → 400 `Couleur invalide.` (aucun contrôle de format, pour accepter les données existantes) | **b** (cohérent avec E17 ; le front envoie déjà des types corrects) | SC-CARD-043, SC-LIST-011 |
| **E23** | Booléens convertis par PHP : `"false"`, `"0 "`, `[1]` → `true` ; `"0"`, `""`, `[]` → `false`, pour `showCardNumbers`, `withDefaults`, `completed`, `done`, `showOnCard`, valeur de case à cocher | Contresens silencieux (`"false"` coche la carte) | a. reproduire ; b. booléen JSON exigé → 400 `Le champ "<nom>" doit être un booléen.` | **b** | SC-CARD-043 |
| **E8 (rappel, étendu)** | Date invalide → **500** : `dueDate` (déjà au registre) **et** valeur d'un champ personnalisé de type date (`new \DateTimeImmutable` sans contrôle) | 5xx | a. reproduire ; b. **400** `Date invalide.` sans aucune modification | **b** | SC-CARD-014, SC-CARD-020 |
| **E24** | Une invitation par e-mail (compte inexistant) avec `role: "admin"` donne un simple `member` à l'inscription : le rôle choisi est perdu, sans avertissement | Surprise pour le gestionnaire | a. conserver ; b. mémoriser le rôle dans l'invitation (colonne additive, migration Doctrine) et l'appliquer à la consommation | **a** en phase 3 (aucun changement de schéma pendant la cohabitation), à reprendre plus tard ; si **b**, Symfony devra aussi l'appliquer (retour arrière) | SC-BOARD-031 |
| **E25** | Positions de checklist et de champs personnalisés = **nombre** d'éléments existants : après une suppression, deux éléments peuvent avoir la même position (ordre ensuite indéterminé) | Ordre qui change d'un affichage à l'autre | a. reproduire ; b. position = max + 1, tri par `position, id` | **b** (aucun effet visible sauf un ordre stable) | SC-CARD-046 |
| **E26** | `PUT /cards/{id}/custom-fields/{fieldId}` écrit une entrée d'historique **même si la valeur ne change pas**, et `field_cleared` même quand il n'y avait pas de valeur (contraire à SC-ACTIVITY-001 « valeur identique → aucune entrée ») | Historique bruité | a. reproduire ; b. aucune entrée si la valeur normalisée est identique | **b** | SC-CARD-047 |
| **E27** | `POST /cards/{id}/move` vers une liste d'un **autre** tableau : 400 `Impossible de déplacer une carte vers un autre tableau.` ; et le contrôle « liste archivée » passe **avant** le contrôle du tableau : un membre apprend l'existence et l'état d'une liste d'un tableau dont il n'est pas membre | Fuite d'existence (contraire à l'esprit d'E1) | a. reproduire ; b. liste inexistante, d'un autre tableau ou inaccessible → **404** `Ressource introuvable.` ; liste du même tableau archivée → 400 (inchangé) | **b** | SC-CARD-036 |
| **E28** | Énumération des comptes : `POST /cards/{id}/assignees` avec un `userId` inexistant → 404 `Ressource introuvable.`, mais existant et non membre → 400 ; `PATCH/DELETE /members/{userId}` : inexistant → 404 `Ressource introuvable.`, existant non membre → 404 `Membre introuvable.` ; invitation d'un autre tableau → 404 `{"error":"Erreur"}` | Un membre peut sonder quels identifiants de comptes existent | a. reproduire ; b. réponses identiques : assignation → 400 `Cet utilisateur n'est pas membre du tableau.` ; membres → 404 `Membre introuvable.` ; invitation → 404 `Ressource introuvable.` | **b** | SC-BOARD-035 |
| **E29** | Réordonner les listes avec une liste partielle : les listes absentes de `ids` gardent leur position, d'où des doublons ; `ids` en double : seule la première occurrence compte | Ordre indéterminé | a. reproduire (tri `position, id` pour départager) ; b. renuméroter `0..n-1` toutes les listes ouvertes : d'abord celles de `ids` dans l'ordre donné, puis les autres dans leur ordre actuel | **a** en phase 3 (le front envoie toujours l'ordre complet) ; b plus tard si besoin | SC-LIST-011 |
| **E30** | Purge en cascade : une liste (ou un tableau) purgée après 30 jours emporte les cartes mises individuellement à la corbeille plus récemment (elles perdent les jours restants affichés) | Perte plus tôt qu'annoncé | a. conserver et documenter ; b. ne purger le conteneur qu'après ses éléments | **a** (cas rare ; l'affichage reste celui du conteneur) | SC-TRASH-015 |
| **E31** | **Échéances décalées d'un fuseau** (à confirmer par test) : le front envoie l'échéance en UTC (`toISOString()`), Doctrine écrit l'heure murale UTC sans fuseau, puis la relit comme heure de Paris : une échéance fixée à 17:00 (Paris) s'affiche ensuite à 16:00 (18:00 → 16:00 en été). Le filtre `due=overdue` compare aussi avec l'heure de Paris | Échéances fausses d'1 à 2 h ; filtres « en retard » décalés | a. reproduire ; b. board-service lit et écrit `card.due_date` en **UTC** (cohérent avec toutes les données existantes, écrites en UTC par le front et l'import Trello) ; Symfony, en cas de retour arrière, garde son décalage actuel | **b** (corrige sans migration de données ; le retour arrière ne dégrade rien de plus qu'aujourd'hui) | SC-CARD-042 |
| **E2 (rappel)** | Supprimer le commentaire d'un autre sans être gestionnaire → **400** | Sémantique | a. garder 400 en phase 3 (registre) ; b. 403 dès board-service | **b** (même logique que les corrections de phase 1 ; le front masque déjà le bouton) | SC-COMMENT-016 |
| **E12 (rappel, précisé)** | Id non numérique : `/api/boards/abc` → 404 avec le message anglais du routeur (`No route found for …`) qui expose l'URL ; `/api/cards/abc` → statut non figé | Fuite de détails, incohérence | cible déjà retenue : 404 ; préciser le message | **404 `{"error":"Ressource introuvable."}`** pour toute route du périmètre | SC-BOARD-024 |
| **E13 (rappel)** | Invitations sans expiration ; un compte existant est ajouté sans consentement | Métier | conserver / changer | **conserver** en phase 3 (registre « à confirmer ») | SC-BOARD-013 |
| **E32** | Une même échéance renvoyée → la notification `card_due` est renvoyée (aucune entrée d'historique) | Bruit | a. reproduire (SC-CARD-013 le documente) ; b. notifier seulement si la date change | **b** | SC-CARD-013, SC-CARD-038 |

Déjà tranchés par les specs (pas de décision à prendre, divergence voulue contre Symfony) : adhésions et invitations concurrentes → 409 et jamais 500 (SC-BOARD-017, SC-BOARD-031) ; déplacements sérialisés par liste (SC-CARD-008) ; tri `position, id` (SC-LIST-003).

### 6.3 À trancher par BackAli (ADR dans `backend-hexagonal.md`, numérotation à la suite de A18)
| Réf. proposée | Sujet | Contraintes posées par la spec |
|---|---|---|
| B-ADR-1 | Mapping des tables partagées (JPA ou JDBC), utilisateur MariaDB, Flyway limité au schéma `board` | §4.3 R1–R10 |
| B-ADR-2 | Validation JWT et résolution de l'utilisateur | §5.1, §5.2 ; clé publique seule |
| B-ADR-3 | Politique d'accès dans le domaine (`BoardAccessPolicy`) | §5.3, matrice SC-BOARD-021 |
| B-ADR-4 | Verrouillage des positions et de la numérotation ; reprise sur interblocage | R6, R7 ; SC-CARD-033, 034 |
| B-ADR-5 | Outbox, destinations, endpoints internes Symfony (contrats `docs/contracts/openapi/board-service.internal.yaml` et section Symfony) | §4.5, U7 |
| B-ADR-6 | **Fichiers postés dans un commentaire** : comment créer les pièces jointes en conservant l'atomicité et la réponse `attachments[]` | SC-COMMENT-007, SC-COMMENT-013 ; aucune écriture de board-service dans `attachment` sans ADR explicite |
| B-ADR-7 | Nettoyage HTML (bibliothèque Java) et preuve d'équivalence avec la configuration `app.rich_text` | SC-CARD-041, SC-COMMENT-014 |
| B-ADR-8 | Lectures : pas de Spring Data REST sur les routes publiques en phase 3 (le contrat JSON doit rester identique, sans HAL) | §2.1 ; tests miroir |
| B-ADR-9 | Purge planifiée (verrou de planification, lots, désactivation de la boucle Symfony) | SC-TRASH-012, 014 |
| B-ADR-10 | Révision de l'ADR A9 : la projection `taskora.user` est conservée pendant toute la phase 3 | §2.4 |

## 7. Scénarios nouveaux

Format et conventions : [README.md](README.md). « [back] » = BackTesteur (Java, sauf mention PHPUnit pour Symfony), « [front] » = FrontTesteur, « [CI] » = Manuel. Les identifiants continuent la numérotation existante.

### 7.1 T0 — Socle

### SC-BOARD-020 — Jetons d'identity et de Symfony acceptés (critique)
Étant donné un jeton émis par identity-service (avec `uid`) et un jeton émis par Symfony (sans `uid`) pour le même compte
Quand chacun appelle `GET /api/boards` servi par board-service
Alors 200 et la même liste de tableaux
Limites : jeton expiré → 401 `{"code":401,"message":"Expired JWT Token"}` ; `alg:none`, HS256 avec la clé publique, autre clé, `iat` futur → 401 `Invalid JWT Token` ; en-tête absent → 401 `JWT Token not found` ; `uid` et `username` incohérents → 401 ; compte disparu → 401 ; en-tête `WWW-Authenticate: Bearer` présent
Validation : unitaire (résolution), sécurité, compatibilité (corps 401 identiques à Symfony) [back] — Acceptation : 100 % des cas du tableau, corps 401 identiques à ceux de Symfony

### SC-BOARD-021 — Non-membre : réponse indiscernable d'une ressource inexistante (critique)
Étant donné Bob, membre d'aucun tableau d'Alice, et des identifiants inexistants
Quand il appelle chaque route du périmètre (§2.1, toutes tranches basculées) avec l'identifiant d'une ressource d'Alice, puis avec un identifiant inexistant, avec un corps valide puis invalide
Alors les deux réponses sont identiques (statut, corps, en-têtes) selon le §5.3 ; aucune écriture en base ni message d'outbox
Limites : tableau d'Alice dans la corbeille → `Tableau introuvable.` pour Bob (et `Ce tableau est dans la corbeille.` pour un membre) ; carte d'Alice dans la corbeille → `Ressource introuvable.` pour Bob ; un membre retiré pendant sa session reçoit la réponse du non-membre dès la requête suivante ; actions de gestion pour un simple membre → 403 inchangé
Validation : sécurité (matrice route × {owner, admin, member, non-membre, anonyme} générée depuis le contrat OpenAPI), API [back] ; PHPUnit si U8-a (même matrice contre Symfony) — Acceptation : matrice complète verte, 0 écriture

### SC-BOARD-022 — Routage par tranche et retour arrière (critique)
Étant donné la stack complète et une variable d'amont par tranche (`BOARD_T1_UPSTREAM` … `BOARD_T5_UPSTREAM`, `api:80` ou `board:8080`)
Quand Manuel commute une tranche vers board-service puis la remet sur Symfony
Alors seules les routes de la tranche changent d'amont (les routes voisines, par exemple `/api/cards/{id}/attachments` pendant T2, restent sur Symfony) ; la commutation prend quelques secondes sans reconstruction ; les données écrites par l'un sont lues par l'autre
Limites : requête en cours pendant la commutation → terminée par son amont d'origine ; journal d'accès nginx exporté avant la recréation du conteneur (constat N2 de la phase 1)
Validation : CI et recette (table route → amont attendu, vérifiée par des requêtes marquées) [CI] ; e2e `@board` sur les deux amonts [front] — Acceptation : table de routage conforme ; commutation ≤ 5 s

### SC-BOARD-023 — Écritures croisées sur les tables partagées (critique)
Étant donné T1 basculée (tableaux sur board-service, cartes encore sur Symfony)
Quand Alice crée un tableau via board-service puis y crée une carte (Symfony), et qu'un import Trello (Symfony) crée un autre tableau
Alors la carte est créée dans le tableau neuf (numéro 1) ; le tableau importé apparaît immédiatement dans `GET /api/boards` (board-service) avec ses compteurs ; aucune donnée en cache n'est servie périmée
Limites : idem dans l'autre sens après un retour arrière ; aucune contrainte d'intégrité violée ; `verify` vert avant et après
Validation : intégration (deux backends sur la même base), compatibilité [back] ; e2e accueil → tableau → carte sur amonts mixtes [front] — Acceptation : données visibles des deux côtés, `verify` vert

### SC-BOARD-024 — Format des erreurs, corps vide et identifiants non numériques (important)
Étant donné un utilisateur connecté
Quand il envoie un corps qui n'est pas du JSON, un corps vide sur `POST /api/boards/{id}/favorite` et `/archive`, un identifiant `abc`, et provoque une erreur serveur simulée
Alors respectivement : 400 `{"error":"JSON invalide."}` ; corps vide traité comme `{}` (le front envoie ces requêtes **sans corps** : la règle E20 de phase 1 ne s'applique pas ici) ; 404 `{"error":"Ressource introuvable."}` (E12) ; 500 `{"error":"Erreur interne du serveur."}` sans trace
Limites : champs texte obligatoires : `trim`, vide → 400 `Le champ "<nom>" est obligatoire.`, trop long → tronqué ; création → 201, suppression → 204 sans corps
Validation : API [back] — Acceptation : statuts et corps exacts

### SC-BOARD-025 — Notifications produites par board-service et livrées par l'outbox (critique)
Étant donné Symfony disponible, puis Symfony indisponible pendant 2 minutes
Quand Alice ajoute Bob (compte existant) au tableau
Alors la réponse est 201 dans les deux cas ; Bob reçoit `board_invite` et les autres membres `member_joined` avec les textes exacts de SC-BOARD-009, une seule fois chacun, jamais Alice ; dans le second cas, les notifications arrivent après le rétablissement ; le Web Push part comme aujourd'hui
Limites : rejeu du même `eventId` → aucun doublon ; la requête échoue (par exemple 409) → aucun message ; 401 de l'endpoint interne (secret faux) → `FAILED`, journal `ERROR`, aucune boucle infinie
Validation : intégration (WireMock simulant Symfony : succès, 500, délai, 401 ; rejeu) [back] ; PHPUnit de `POST /internal/notifications` (idempotence, refus sans secret, jamais l'acteur) [back] — Acceptation : exactement une notification par destinataire

### SC-BOARD-026 — Journaux sans données personnelles ni secrets, aucune 5xx (important)
Étant donné les parcours e2e `@board` complets
Quand on analyse les journaux de board-service et de nginx
Alors 0 jeton, secret interne, mot de passe ; aucun e-mail en `INFO` (identifiant numérique à la place) ; contenu des commentaires et descriptions jamais journalisé ; 0 × 5xx
Validation : sécurité (recherche de marqueurs de test dans les journaux capturés) [back] ; recette [CI] — Acceptation : 0 occurrence

### SC-BOARD-027 — Profil utilisateur à jour dans les tableaux (important)
Étant donné Bob membre d'un tableau d'Alice
Quand Bob change son nom et sa couleur via `PATCH /api/me` (identity-service)
Alors la requête suivante `GET /api/boards/{id}` (board-service) montre le nouveau nom et la nouvelle couleur dans `members`, les assignés et l'auteur des commentaires
Validation : intégration (projection `taskora.user` relue) [back] — Acceptation : aucune donnée de profil périmée

### SC-BOARD-028 — Dates écrites comme PHP (critique)
Étant donné des écritures faites par board-service (création de tableau, adhésion, archivage, corbeille, historique, commentaire)
Quand Symfony relit ces lignes (retour arrière) et inversement
Alors les dates exposées sont identiques à la seconde près, dans le format ATOM avec le décalage de Paris (`+01:00` ou `+02:00`), comme aujourd'hui ; `purgeAt` = `deletedAt` + 30 jours
Limites : changement d'heure (dernier dimanche d'octobre et de mars) : aucune entrée d'historique ne change d'ordre ; `card.due_date` suit E31 (SC-CARD-042)
Validation : intégration (horloge injectée, deux fuseaux de test), compatibilité [back] — Acceptation : égalité des dates dans les deux sens

### 7.2 T1 — Tableaux, membres, invitations

### SC-BOARD-029 — Accueil et coquille identiques à Symfony (critique)
Étant donné un jeu de données de référence (favoris, rôles, listes archivées et dans la corbeille, cartes archivées, invitations, fichiers Trello en attente)
Quand on appelle `GET /api/boards` et `GET /api/boards/{id}` sur les deux amonts
Alors les corps JSON sont sémantiquement identiques : compteurs `listCount`, `cardCount`, `trelloFiles`, `members` (rôle, `joinedAt`), `lists` (ordre, `cardCount`), `labels`, `customFields`, `invitations`
Limites : ordre des membres par `board_member.id`, des tableaux par `created_at DESC, id DESC` (HYPOTHÈSE à figer par ce test) ; 200 tableaux et 300 listes sans N+1 (nombre de requêtes SQL borné et indépendant du volume)
Validation : compatibilité, intégration (compteur de requêtes) [back] ; e2e accueil et page Tableau sur les deux amonts [front] — Acceptation : 0 différence hors divergences listées au §9.3

### SC-BOARD-030 — Consommation des invitations servie par board-service (critique)
Étant donné une invitation en attente pour `invite@exemple.test` sur deux tableaux, dont un dans la corbeille
Quand l'invité s'inscrit via identity-service, qui appelle `POST /internal/invitations/claim` sur board-service
Alors 200 `{"claimed":2,"boardIds":[…]}` ; l'invité est `member` des deux tableaux ; les invitations sont supprimées ; une notification `board_invite` par tableau (via l'outbox) ; le tableau ouvert est visible dès `/boards`
Limites : rejeu du même message → 200, aucun doublon ; sans secret ou secret faux → 401 ; utilisateur pas encore visible → 404 (identity rejoue) ; e-mail différent de celui du compte → 409 ; adhésion déjà présente → invitation supprimée, pas de notification ; retour arrière : identity pointe de nouveau vers Symfony et les messages en attente sont livrés sans perte
Validation : API, intégration (contrat identique à `identity-service.internal.yaml`, rejeu croisé Symfony ↔ board-service) [back] ; e2e inscription d'un invité (SC-IDENT-005) [front] — Acceptation : adhésions exactes, aucun doublon

### SC-BOARD-031 — Invitations : normalisation, collation et concurrence (important)
Étant donné un tableau d'Alice
Quand deux gestionnaires invitent en même temps ` Nouveau@Exemple.test ` (aucun compte), puis qu'Alice invite l'adresse d'un compte existant écrite avec une autre casse
Alors une seule invitation `nouveau@exemple.test` (réponses 202 et 409, jamais 500) ; le compte existant est ajouté directement (201), recherché avec la même collation que Symfony
Limites : e-mail de 200 caractères → tronqué à 180 puis 400 `Adresse e-mail invalide.` si invalide ; invitation avec `role:"admin"` → rôle selon E24 ; invitation puis inscription avant que l'invitation soit visible → rattrapage par le message différé d'identity (+60 s)
Validation : intégration (concurrence, MariaDB réel), API [back] — Acceptation : une ligne, statuts exacts

### SC-BOARD-032 — Propriétaire unique : jamais de tableau sans gestionnaire (critique)
Étant donné Alice propriétaire, Bob et Carla admins
Quand Bob rétrograde Carla, Carla rétrograde Bob au même moment, puis Bob se rétrograde lui-même
Alors les trois requêtes réussissent ; Alice reste `owner` et seule gestionnaire possible ; le tableau garde toujours au moins un gestionnaire (le propriétaire)
Limites : `role:"owner"` en ajout → `member` ; en modification → 400 `Rôle invalide.` ; modifier, retirer ou faire quitter le propriétaire → 403 (messages actuels) ; aucune route ne transfère la propriété ; `verify` V4 vert après 100 opérations concurrentes
Validation : unitaire (domaine), API, intégration (concurrence) [back] ; e2e onglet Membres (actions masquées pour le propriétaire) [front] — Acceptation : exactement un `owner` par tableau

### SC-BOARD-033 — Retrait d'un membre pendant qu'il travaille (important)
Étant donné Bob membre assigné à des cartes, qui a la fiche d'une carte ouverte
Quand un gestionnaire retire Bob pendant qu'un autre membre assigne Bob à une nouvelle carte
Alors à la fin, Bob n'est plus membre **et** n'est assigné à aucune carte du tableau (jamais d'assigné non membre) ; la requête suivante de Bob reçoit 404 (§5.3) ; Bob reçoit `member_removed` ; quitter soi-même ne notifie personne
Limites : l'assignation concurrente reçoit 400 `Cet utilisateur n'est pas membre du tableau.` ou est annulée par le retrait ; `verify` V5 vert
Validation : intégration (concurrence, verrou sur l'adhésion) [back] ; e2e (toast puis retour à l'accueil pour Bob) [front] — Acceptation : invariant V5 respecté sur 100 exécutions

### SC-BOARD-034 — Corbeille transitive cohérente entre les deux backends (critique)
Étant donné T1 basculée et T2 encore sur Symfony
Quand Alice (propriétaire) met le tableau à la corbeille via board-service
Alors toutes les routes de ses listes et cartes, servies par Symfony, répondent 404 `Ce tableau est dans la corbeille.` aux membres ; le tableau apparaît dans `GET /api/trash/boards` d'Alice seulement ; après restauration, tout réapparaît avec son contenu
Limites : restauration par un admin → 403 ; tableau non supprimé → 404 `Ce tableau n’est pas dans la corbeille.` ; purge (`DELETE /api/trash/boards/{id}`) encore servie par Symfony jusqu'à T5
Validation : intégration, compatibilité [back] ; e2e corbeille de l'accueil [front] — Acceptation : 404 en cascade sur les deux amonts

### SC-BOARD-035 — Pas d'énumération des comptes par les routes de membres (important)
Étant donné Alice gestionnaire, un identifiant de compte existant non membre et un identifiant inexistant
Quand elle appelle `PATCH` et `DELETE /api/boards/{id}/members/{userId}`, `POST /api/cards/{id}/assignees`, `DELETE /api/boards/{id}/invitations/{invId}` (invitation d'un autre tableau ou inexistante) avec ces identifiants
Alors selon E28 : réponses identiques pour « existant non membre » et « inexistant » (option b) ; ou comportement actuel figé (option a)
Validation : sécurité, API [back] — Acceptation : conforme à la décision E28

### 7.3 T2 — Listes, cartes, archives, corbeille douce, historique

### SC-LIST-011 — Réordonnancement : entrées invalides et concurrence (important)
Étant donné les listes A(0) B(1) C(2)
Quand deux membres envoient en même temps `{"ids":[C,A,B]}` et `{"ids":[B,C,A]}`, puis un membre envoie `{"ids":"A"}` et `{"ids":[A,A,C]}`
Alors l'ordre final est l'un des deux ordres envoyés, complet, sans doublon de position ; `"A"` → selon E22 (400 ou comportement actuel) ; `[A,A,C]` → première occurrence retenue (E29)
Limites : identifiants d'un autre tableau ou inconnus ignorés ; listes archivées ou dans la corbeille non concernées
Validation : unitaire, intégration (concurrence) [back] — Acceptation : ordre complet et cohérent après rechargement

### SC-LIST-012 — Créations concurrentes de listes (secondaire)
Étant donné un tableau de 4 listes
Quand 10 listes sont créées en parallèle
Alors 10 listes créées, positions distinctes et supérieures à toutes les positions existantes (archivées et corbeille comprises), grâce au verrou sur la ligne `board`
Limites : données héritées avec positions en doublon → affichage trié par `position, id`
Validation : intégration (concurrence) [back] — Acceptation : aucune position en double parmi les créations

### SC-LIST-013 — Archiver ou mettre à la corbeille une liste pendant un déplacement de carte (important)
Étant donné la liste L ouverte contenant 5 cartes
Quand un membre archive (ou met à la corbeille) L pendant qu'un autre déplace une carte vers L
Alors soit le déplacement passe avant (la carte part avec la liste), soit il est refusé ensuite (400 `Impossible de déplacer une carte vers une liste archivée.` ou 404 corbeille) ; jamais de carte orpheline ni de position en double
Validation : intégration (concurrence) [back] — Acceptation : `verify` V1 et V7 verts sur 100 exécutions

### SC-LIST-014 — Pagination stable avec des positions à égalité (secondaire)
Étant donné une liste de 75 cartes dont 10 ont des positions en double (données héritées)
Quand on pagine par 30
Alors aucune carte n'apparaît deux fois ni ne manque ; ordre `position, id`
Validation : intégration [back] — Acceptation : union des pages = ensemble des cartes ouvertes

### SC-CARD-032 — Numérotation partagée et continuité après import (critique)
Étant donné un tableau importé par Symfony dont le plus grand `idShort` est 248 avec une carte sans `idShort` (numéro 249, E14 corrigé), et un tableau T où board-service et Symfony (pendant une répétition de retour arrière) créent des cartes en même temps
Quand board-service crée une carte dans le tableau importé, puis 50 cartes sont créées en parallèle dans T par les deux backends
Alors la carte du tableau importé reçoit 250 ; dans T, 50 numéros distincts et consécutifs ; jamais de 500 ; `card_seq` = plus grand numéro
Limites : carte purgée → numéro jamais réutilisé (SC-CARD-003)
Validation : intégration (deux backends, MariaDB réel) [back] — Acceptation : `verify` V3 vert, aucune collision

### SC-CARD-033 — Positions contiguës sous opérations concurrentes mixtes (critique)
Étant donné deux listes de 20 cartes
Quand 100 opérations concurrentes mêlent création, déplacement dans la liste et entre les listes, archivage, restauration d'archive, mise à la corbeille et restauration de la corbeille
Alors après chaque exécution, les positions des cartes ouvertes de chaque liste forment `0..n-1` ; aucune carte perdue ; aucune 5xx (un interblocage est rejoué en interne)
Limites : extension de SC-CARD-008 à toutes les opérations qui décalent des positions
Validation : intégration (concurrence, 20 répétitions) [back] — Acceptation : `verify` V1 et V2 verts à chaque répétition

### SC-CARD-034 — Déplacements croisés sans interblocage visible (important)
Étant donné les listes A et B
Quand on déplace en même temps une carte de A vers B et une carte de B vers A, 50 fois
Alors toutes les requêtes répondent 200 ; positions contiguës dans A et B
Limites : verrous pris dans l'ordre croissant des ids de liste
Validation : intégration [back] — Acceptation : 0 erreur, invariant respecté

### SC-CARD-035 — Restaurations concurrentes en bas de liste (important)
Étant donné une liste de 4 cartes ouvertes, une carte archivée X et une carte Y dans la corbeille
Quand X (archives) et Y (corbeille) sont restaurées en même temps
Alors positions 4 et 5 (dans un ordre quelconque), activités `unarchived {list}` et `restored {list}`
Limites : restauration d'une carte dont la liste est archivée → 400 (messages actuels) ; restauration répétée → 200 sans changement (E7)
Validation : intégration [back] — Acceptation : positions distinctes et contiguës

### SC-CARD-036 — Déplacement vers une liste d'un autre tableau ou inaccessible (important)
Étant donné Alice membre du tableau A, une liste L2 d'un tableau B dont elle n'est pas membre, une liste archivée L3 de B, une liste archivée L4 de A
Quand elle déplace une carte de A vers L2, L3, puis L4
Alors selon E27 : option b → 404 `Ressource introuvable.` pour L2 et L3, 400 `Impossible de déplacer une carte vers une liste archivée.` pour L4 ; option a → comportement actuel (400 pour L2, 400 « archivée » pour L3)
Validation : sécurité, API [back] — Acceptation : conforme à la décision E27, aucune modification

### SC-CARD-037 — Couverture image : miniature déléguée à Symfony (important)
Étant donné une image de 4 000 px postée dans un commentaire (sans miniature) et jointe à la carte
Quand un membre la choisit comme couverture (`PATCH` `coverAttachmentId`) via board-service
Alors board-service demande la miniature à Symfony (endpoint interne synchrone) ; la réponse contient `coverImage` vers la miniature ; la couleur est effacée ; activité `cover_set {image}`
Limites : Symfony indisponible ou échec de génération → l'original sert de couverture (200, comme aujourd'hui) ; pièce jointe d'une autre carte ou non image → 400 (message actuel), aucun appel interne
Validation : intégration (WireMock), API [back] ; PHPUnit de l'endpoint interne [back] ; e2e menu Couverture [front] — Acceptation : couverture et `coverImage` exacts

### SC-CARD-038 — Notifications des cartes via l'outbox (critique)
Étant donné une carte assignée à Bob et Carla
Quand Alice assigne Dan, déplace la carte vers une autre liste, puis fixe l'échéance
Alors Dan reçoit `card_assigned`, Bob, Carla et Dan reçoivent `card_moved` puis `card_due`, avec les textes exacts de cards.md ; jamais Alice ; un déplacement dans la même liste ne notifie pas
Limites : requête en erreur → aucune notification ; même échéance renvoyée → selon E32 ; Symfony indisponible → livraison différée
Validation : intégration (outbox, WireMock) [back] — Acceptation : destinataires et textes exacts

### SC-CARD-039 — Fiche carte : données des autres domaines (important)
Étant donné une carte avec pièces jointes (dont une couverture avec miniature, et des fichiers postés en commentaire), commentaires, checklist, champs et une pièce jointe ajoutée par Symfony pendant que la fiche est ouverte
Quand on appelle `GET /api/cards/{id}` sur les deux amonts
Alors les corps sont identiques (`attachments[]` avec `commentId`, `attachmentCount`, `coverImage`, `commentCount`, `checklistTotal/Done`, `customValues`)
Limites : pièce jointe ajoutée par Symfony visible dès la requête suivante de board-service
Validation : compatibilité [back] — Acceptation : 0 différence

### SC-CARD-040 — Filtres : parité exacte avec Symfony (critique)
Étant donné un jeu de cartes couvrant toutes les combinaisons de SC-CARD-023 à 025 (dont `q` avec `%`, `_`, `#12`, e-mail d'un assigné, valeur de champ JSON, accents)
Quand on appelle `GET /api/lists/{id}/cards` et `GET /api/boards/{id}/cards` avec chaque combinaison sur les deux amonts
Alors mêmes cartes, même ordre, mêmes `total` et `hasMore`
Limites : filtres `due` : selon E31 (divergence voulue si b) ; comparaison de texte avec la collation de MariaDB (pas de comparaison Java) ; requêtes paramétrées (aucune injection)
Validation : compatibilité (oracle), intégration, sécurité (injection) [back] — Acceptation : 0 différence hors E31

### SC-CARD-041 — Nettoyage HTML de la description : parité (critique)
Étant donné un corpus commun : vecteurs XSS de SC-COMMENT-005, contenus produits par l'éditeur Tiptap (titres, listes de tâches, mentions, couleurs, alignements, images `/uploads/…`), Markdown hérité
Quand la description est enregistrée via board-service puis via Symfony
Alors le HTML stocké est **équivalent** (même arbre après analyse : éléments, attributs, styles conservés) ; aucun gestionnaire d'événement, `javascript:`, `url(`, `iframe`, `svg` ; `rel="noopener noreferrer"` et `target="_blank"` forcés ; Markdown hérité conservé tel quel ; `<p></p>` → `null` ; entrée de plus de 200 000 caractères refusée comme aujourd'hui (à figer)
Limites : un écart de sérialisation (guillemets, ordre des attributs, entités) est toléré ; tout attribut ou style en plus ne l'est pas
Validation : unitaire (corpus partagé), compatibilité [back] ; composant `rich-view` sur les sorties de board-service [front] — Acceptation : 0 différence d'arbre sur le corpus, 0 exécution de script

### SC-CARD-042 — Échéance : l'instant choisi est conservé (critique)
Étant donné un utilisateur à Paris qui choisit le 01/12/2026 à 17:00 (le front envoie `2026-12-01T16:00:00.000Z`), et une échéance existante écrite par Symfony
Quand il relit la carte via board-service
Alors selon E31 : option b → `dueDate` = `2026-12-01T16:00:00+00:00` (même instant), l'échéance existante est relue comme UTC, le filtre `overdue` est juste à la minute près ; option a → comportement actuel figé
Limites : passage à l'heure d'été ; `dueDate:null` → `due_removed`
Validation : unitaire, intégration, compatibilité (divergence documentée) [back] ; e2e sélecteur de date (valeur réaffichée identique après rechargement) [front] — Acceptation : instant identique après aller-retour

### SC-CARD-043 — Types des champs JSON (important)
Étant donné une carte, une liste et un tableau
Quand on envoie `{"title":["x"]}`, `{"description":{"a":1}}`, `{"cover":"#ec4899ec4899ec4899ec4899"}`, `{"completed":"false"}`, `{"showCardNumbers":"0"}` (tableau), `{"color":12}` (liste)
Alors selon E22 et E23 : option b → 400 avec le message du champ, aucune modification ; option a → comportement actuel figé, sauf qu'**aucune 5xx** n'est acceptée (les 500 actuels deviennent 400 dans tous les cas)
Validation : API (tests paramétrés) [back] — Acceptation : jamais de 5xx, aucune écriture partielle

### SC-ACTIVITY-008 — Historique mêlé des deux backends (important)
Étant donné une carte modifiée par board-service (titre, déplacement) et par Symfony (pièce jointe ajoutée, transfert Trello) dans la même seconde et sur plusieurs jours
Quand on lit `GET /api/cards/{id}/activity` sur les deux amonts
Alors les entrées sont ordonnées par `createdAt` puis `id`, sans doublon ni trou, avec les mêmes `data` (clés et valeurs), acteurs et dates
Limites : pagination par curseur `before` sur des entrées des deux origines ; acteur externe (Trello) et acteur supprimé
Validation : intégration, compatibilité [back] — Acceptation : listes identiques

### 7.4 T3 — Étiquettes, champs personnalisés, checklists

### SC-CARD-044 — Encodage des valeurs de champs compatible (critique)
Étant donné un champ de chaque type
Quand board-service enregistre `12`, `"12.50"`, `"1e3"`, une option `"a1b2c3d4"`, `true`, `"2026-12-01T10:00:00Z"`, un texte avec `/`, accents et emoji
Alors `custom_field_value.value` contient exactement ce qu'écrirait PHP (`12`, `12.5`, `1000`, `"a1b2c3d4"`, `true`, `"2026-12-01"`, texte avec `\/` et `\uXXXX`) ; les filtres `cf[...]` trouvent ces cartes sur les deux amonts ; Symfony les affiche à l'identique après retour arrière
Limites : valeurs héritées écrites par Symfony relues et filtrées par board-service ; options sans `id` → identifiant de 8 caractères hexadécimaux
Validation : unitaire (encodeur), intégration, compatibilité [back] — Acceptation : octets identiques pour chaque cas du tableau

### SC-CARD-045 — Supprimer une étiquette ou un champ pendant qu'une carte l'utilise (important)
Étant donné l'étiquette E et le champ F utilisés par 10 cartes
Quand un membre supprime E et F pendant qu'un autre envoie `PATCH` `labelIds:[E]` et `PUT` sur F pour une de ces cartes
Alors aucune 5xx : soit la modification passe avant et la suppression nettoie ensuite, soit elle arrive après (E ignorée silencieusement ; F → 404 `Ressource introuvable.`) ; aucune carte ne garde E ni de valeur de F
Validation : intégration (concurrence) [back] — Acceptation : 0 × 5xx, cascade complète

### SC-CARD-046 — Positions de checklist et de champs (secondaire)
Étant donné une checklist de 3 éléments (positions 0, 1, 2)
Quand on supprime le deuxième puis on en ajoute un quatrième
Alors selon E25 : option b → nouvel élément en position 3 et ordre stable ; option a → position 2 (doublon), ordre départagé par `id`
Validation : API [back] — Acceptation : conforme à la décision, ordre identique d'un appel à l'autre

### SC-CARD-047 — Historique des champs inchangés (secondaire)
Étant donné une carte dont le champ « Client » vaut ACME
Quand on renvoie ACME, puis on efface un champ vide
Alors selon E26 : option b → aucune entrée d'historique ; option a → `field_set` puis `field_cleared` comme aujourd'hui
Validation : API [back] — Acceptation : conforme à la décision

### 7.5 T4 — Commentaires et mentions

### SC-COMMENT-013 — Un commentaire et ses fichiers, tout ou rien (critique)
Étant donné deux fichiers téléversés (`POST /api/uploads`, Symfony)
Quand Alice poste un commentaire qui les contient via board-service, d'abord normalement, puis avec une panne simulée après l'écriture du commentaire
Alors cas normal : 201 avec `attachments` (identique à SC-COMMENT-007), activités `attachment {name}` ; cas de panne : aucun commentaire, aucune pièce jointe, aucune activité, aucune notification (ou, si B-ADR-6 retient une autre garantie, elle est écrite dans l'ADR et ce scénario est ajusté **avant** les tests)
Validation : intégration (panne injectée), API, compatibilité [back] — Acceptation : jamais d'état partiel

### SC-COMMENT-014 — Nettoyage HTML des commentaires : parité (critique)
Étant donné le corpus de SC-CARD-041 et les mentions (attributs dans les deux ordres)
Quand il est posté en commentaire sur les deux amonts
Alors contenus stockés équivalents ; mentions conservées ; `Le commentaire est vide.` dans les mêmes cas ; troncature à 100 000 caractères avant nettoyage
Validation : unitaire, compatibilité [back] — Acceptation : 0 différence d'arbre

### SC-COMMENT-015 — Notifications de commentaire et de mention via l'outbox (critique)
Étant donné une carte créée par Carla, assignée à Bob, Dan membre et Eve non membre
Quand Alice commente en mentionnant Dan (puce), Eve (puce) et « @carla … » (texte)
Alors Bob, Carla et Dan reçoivent une seule `card_comment` chacun (texte exact, 80 caractères) ; ni Alice ni Eve
Limites : créateur supprimé → ignoré ; deux commentaires simultanés → deux notifications distinctes
Validation : unitaire (extraction), intégration (outbox) [back] — Acceptation : destinataires exacts

### SC-COMMENT-016 — Supprimer un commentaire : droits et fichiers (important)
Étant donné un commentaire de Bob qui a créé deux pièces jointes
Quand Carla (membre) tente de le supprimer, puis un admin le supprime
Alors Carla : selon E2 (400 actuel ou 403) ; admin : 204, les deux pièces jointes disparaissent de la carte (cascade), les objets restent dans le stockage (E11)
Limites : non-membre → 404 (§5.3) ; commentaire d'une carte dans la corbeille → 404
Validation : API, sécurité [back] — Acceptation : statuts conformes, cascade constatée

### 7.6 T5 — Suppression définitive et purge automatique

### SC-TRASH-011 — Purge : lignes supprimées, fichiers libérés par l'outbox (critique)
Étant donné une carte dans la corbeille avec 3 pièces jointes (original, miniature, aperçu PDF)
Quand un admin la purge via board-service, puis quand la même opération échoue au milieu (panne simulée)
Alors cas normal : 204 ; aucune ligne restante (carte, commentaires, checklist, valeurs, activités, pièces jointes, notifications liées) ; un message `StorageKeysReleased` avec les 7 clés est livré à Symfony, qui supprime les objets ; cas de panne : rien n'est supprimé, aucun message
Limites : stockage indisponible → 204 quand même, objets supprimés au rejeu ; clé déjà absente → succès ; couverture détachée avant la cascade
Validation : intégration (LocalStack via Symfony, WireMock), PHPUnit de `POST /internal/storage/release` [back] — Acceptation : aucune ligne ni objet restant, jamais de clé libérée pour une transaction annulée

### SC-TRASH-012 — Une seule purge planifiée active (critique)
Étant donné T5 basculée
Quand le temps passe
Alors la purge de board-service s'exécute (premier passage 5 min après le démarrage, puis toutes les 6 h, rapport `n tableau(x), n liste(s), n carte(s)`), une seule fois même avec deux instances ; la boucle de `entrypoint.sh` de Symfony est **désactivée** par une variable d'environnement
Limites : retour arrière de T5 → boucle Symfony réactivée et purge de board-service désactivée **dans la même opération** ; jamais les deux actives
Validation : intégration (verrou de planification, horloge injectée) [back] ; CI et recette (variable vérifiée au démarrage des deux conteneurs) [CI] — Acceptation : exactement un exécutant

### SC-TRASH-013 — Restauration pendant la purge (important)
Étant donné une carte supprimée il y a 30 jours et 1 minute
Quand un membre la restaure pendant que la purge automatique s'exécute
Alors l'une des deux opérations gagne : restaurée (la purge relit `deletedAt` dans sa transaction et l'ignore) ou purgée (la restauration répond 404 `Cet élément n’est pas dans la corbeille.`) ; jamais une carte restaurée sans ses pièces jointes
Validation : intégration (concurrence) [back] — Acceptation : état final cohérent sur 50 exécutions

### SC-TRASH-014 — Purge d'un grand tableau sans bloquer les autres (important)
Étant donné un tableau de 300 listes et 9 000 cartes avec fichiers, dans la corbeille depuis 31 jours, et des membres qui travaillent sur d'autres tableaux
Quand la purge s'exécute
Alors elle se termine ; les requêtes sur les autres tableaux ne reçoivent ni 5xx ni délai d'attente de verrou ; toutes les clés sont libérées
Limites : durée mesurée en suivi (U5), non bloquante
Validation : intégration (volume `app:seed-demo`) [back] — Acceptation : 0 × 5xx concurrente, aucune ligne restante

### SC-TRASH-015 — Purge en cascade d'éléments plus récents (secondaire)
Étant donné une liste supprimée il y a 31 jours contenant une carte supprimée individuellement il y a 5 jours
Quand la purge s'exécute
Alors selon E30 : option a → la carte est purgée avec la liste (comportement actuel, documenté) ; option b → la carte reste jusqu'à son échéance
Validation : intégration [back] — Acceptation : conforme à la décision

## 8. Cas critiques et limites (synthèse)
- **Concurrence** : positions (SC-CARD-033, 034, 035, SC-LIST-011, 012, 013), numéros (SC-CARD-002, 032), adhésions et invitations (SC-BOARD-017, 031), retrait de membre contre assignation (SC-BOARD-033), rôles (SC-BOARD-032), purge contre restauration (SC-TRASH-013), suppression d'étiquette ou de champ (SC-CARD-045).
- **Numérotation** : incrément atomique unique, continuité après import (E14), jamais de réutilisation (SC-CARD-002, 003, 032).
- **Archives et corbeille** : idempotence (E7), corbeille transitive à travers les deux backends (SC-BOARD-034), purge et fichiers (SC-TRASH-011), une seule purge planifiée (SC-TRASH-012), 30 jours (SC-TRASH-008).
- **Droits** : 404 indiscernable (SC-BOARD-021), propriétaire unique et absence de « dernier administrateur » à protéger (SC-BOARD-032), retrait de membre (SC-BOARD-015, 033), invitations (SC-BOARD-011, 012, 030, 031), énumération (SC-BOARD-035).
- **Cohabitation** : JWT croisés, écritures croisées, dates, encodages JSON, historique mêlé, routage et retour arrière (SC-BOARD-020, 022, 023, 028, SC-CARD-044, SC-ACTIVITY-008).
- **Services externes** : Symfony (notifications, miniatures, stockage) indisponible → réponse inchangée et rejeu (SC-BOARD-025, SC-CARD-037, SC-TRASH-011).
- **Valeurs maximales** : titres 160/255, nom d'étiquette 60, nom de champ 80, option 60, texte de champ 2 000, commentaire 100 000, `q` 100, 20 listes par requête groupée, 200 cartes par page, 300 cartes dans la corbeille affichée, 50 fichiers par commentaire.

## 9. Stratégie de validation

### 9.1 Types de tests
| Type | Contenu | Auteur | Acceptation |
|---|---|---|---|
| Architecture | ArchUnit (5 règles de `backend-hexagonal.md` §3) + aucune dépendance du domaine vers JDBC/JPA | BackTesteur | règles vertes |
| Unitaire (domaine) | politique d'accès, rôles, positions (algorithme de décalage), normalisation des champs, encodeur JSON, différentiel d'historique, nettoyage HTML (corpus) | BackTesteur | lignes ≥ 90 %, branches ≥ 80 % sur `domain` et `application` (A12) |
| API | toutes les routes du §2.1, statuts et messages exacts, validation par le contrat OpenAPI | BackTesteur | 100 % des opérations du contrat couvertes |
| Sécurité | matrice route × rôle générée depuis le contrat (SC-BOARD-021), JWT, IDOR, injection dans les filtres, XSS, journaux | BackTesteur | 0 accès indu |
| Intégration | Testcontainers MariaDB avec le **schéma `taskora` créé par les migrations Doctrine** de `backend/migrations` (pas par un script réécrit), concurrence, compteur de requêtes SQL (N+1), outbox avec WireMock, `verify` | BackTesteur | invariants V1–V8 verts |
| Compatibilité (miroir) | mêmes requêtes contre Symfony et board-service sur la même base, comparaison statut + JSON sémantique (§9.3) ; jeu de données de référence versionné | BackTesteur | 0 différence hors liste §9.3 |
| PHPUnit (Symfony transitoire) | endpoints `/internal/notifications`, `/internal/storage/release`, `/internal/attachments/{id}/thumbnail`, désactivation de la purge, `BoardAccess` en 404 (si U8-a) | BackTesteur (`backend/tests/**`) | verts, aucune régression des tests existants |
| e2e | Playwright, étiquette `@board`, fichiers `frontend/e2e/board-*.spec.ts`, exécutés **contre les deux amonts** de chaque tranche | FrontTesteur | verts sur les deux amonts |
| CI | jobs du §13 | Manuel | requis par `ci-gate` |

### 9.2 Jeu de données de référence
Fixture versionnée, comptes fictifs `@exemple.test`, aucune donnée réelle : 3 tableaux (dont un importé avec `idShort` manquant, un dans la corbeille), 4 rôles, invitations, listes ouvertes/archivées/corbeille, cartes de toutes les combinaisons de filtres, valeurs de champs de chaque type écrites **par Symfony**, historique avec acteurs externes et supprimés, pièces jointes (dont couverture et fichiers de commentaire), positions en doublon héritées (checklist). Le volume (`app:seed-demo`, 300 listes, 9 000 cartes) sert aux tests de compteurs de requêtes et à SC-TRASH-014.

### 9.3 Divergences voulues exclues des tests miroir
E1 (404 non-membre, §5.3) — sauf si U8-a, auquel cas Symfony est aligné et le miroir les compare ; E12 (id non numérique) ; adhésions et invitations concurrentes (409 au lieu de 500) ; puis, **uniquement si l'utilisateur les décide**, E2, E8, E22, E23, E25, E26, E27, E28, E31, E32. Chaque exclusion cite sa référence dans le code du test.

### 9.4 Non-régression des autres domaines à chaque bascule
SC-IDENT-005 (inscription d'un invité), SC-ATTACH-001, 011 à 014 (pièces jointes servies par Symfony sur des cartes gérées par board-service), SC-NOTIF-001 (panneau 🔔), SC-PUSH-005, SC-IMPORT-001, 002 (import puis édition par board-service).

## 10. Bascule et retour arrière par tranche

### 10.1 Bascule (si U6-A et U9-a)
1. T-24 h : annonce (pas de coupure attendue).
2. T-10 min : board-service déployé, non routé pour la tranche ; `verify` → état initial consigné.
3. T0 : commutation de `BOARD_Tn_UPSTREAM` vers `board:8080` (et, pour T1, URL interne d'identity vers board-service ; pour T5, purge Symfony désactivée et purge board-service activée).
4. T0+1 : contrôles à chaud : parcours de la tranche avec un compte de recette, requêtes marquées pour vérifier le routage, `verify`.
5. T0+5 → T+48 h : surveillance (5xx, 404 inhabituels, outbox `PENDING` > 1 min, `FAILED`).

### 10.2 Retour arrière
1. Commutation inverse de la variable d'amont (et des réglages associés de l'étape 3).
2. Rien à recopier (tables partagées). Laisser board-service démarré jusqu'à `PENDING` = 0 dans son outbox (les messages vont à Symfony).
3. `verify` vert.
4. Avant une nouvelle bascule : refaire 10.1 depuis l'étape 2.

### 10.3 Répétition en recette (critère de sortie de chaque tranche)
Sur une stack de recette neuve (`taskora-rehearsal`), données synthétiques remplies par l'API Symfony, jamais la stack de travail :

| # | Étape | Attendu |
|---|---|---|
| RB0 | Stack neuve, jeu de données de référence | stack saine |
| RB1 | `verify` (état initial) | rapport consigné |
| RB2 | Bascule de la tranche | routage conforme (SC-BOARD-022) |
| RB3 | Parcours e2e `@board` de la tranche via board-service, avec écritures | verts |
| RB4 | Retour arrière | commutation ≤ 5 s ; outbox vidée |
| RB5 | Lecture **et** écriture via Symfony des données créées en RB3 (et inversement en RB7) | verts ; numérotation continue (SC-CARD-032) |
| RB6 | `verify` | vert, aucun écart nouveau |
| RB7 | Re-bascule, parcours e2e | verts |
| RB8 | Journaux | 0 fuite, 0 × 5xx (L5) |
| RB9 | Nettoyage | aucune ressource de recette restante |
Compte rendu : `docs/reviews/<date>-board-tn-rollback-rehearsal.md` (BackAli, Manuel), uniquement des nombres.

## 11. Critères de sortie

### 11.1 Pour chaque tranche (avant sa bascule réelle)
1. Scénarios **critiques** de la tranche (existants rejoués du §3 et nouveaux du §7) : `validé` dans la matrice.
2. Scénarios **importants** de la tranche : `vert` au moins (README : requis avant la bascule).
3. Tests miroir de la tranche verts contre les deux amonts en CI (`board-compat`), divergences limitées au §9.3.
4. e2e `@board` de la tranche verts sur les deux amonts.
5. Rapport BackReviewer sans point bloquant ; tests intacts depuis leur commit (`git diff <commit test>..HEAD` sur les fichiers de test vide).
6. Répétition du retour arrière RB0–RB9 réussie.
7. `verify` vert ; outbox sans `FAILED`.
8. Pour T1 : PHPUnit des endpoints internes verts ; identity pointe vers board-service en recette, SC-IDENT-005 vert.
9. Pour T5 : SC-TRASH-012 prouvé en recette (bascule et retour arrière de la purge).
Latence : suivi après bascule (U5), non bloquant ; L5 exigé.

### 11.2 Pour la phase 3
1. T1 à T5 basculées, chacune après sa période d'observation, sans retour arrière en cours.
2. Tous les scénarios critiques du périmètre (§3, §7) `validé` ; matrice à jour.
3. Aucune route du périmètre servie par Symfony depuis au moins 7 jours (journal nginx).
4. Décisions §6.1 et §6.2 consignées dans `decisions.md`, contrats publiés.
5. ADR B-ADR-1 à 10 rédigés, A9 révisé.
6. Le code Symfony du périmètre reste en place (retour arrière possible) jusqu'à la phase 4 ; son retrait est une décision ultérieure.

## 12. Impact front et pages
- **Aucune page ne change** : mêmes routes, mêmes corps. `pages.md` n'est pas modifié.
- Effet visible accepté par E1 : un non-membre voit le toast « Tableau introuvable. » au lieu de « Vous n'êtes pas membre de ce tableau. », puis revient à l'accueil (comportement actuel du composant Tableau en cas d'erreur de chargement, ce qui confirme l'HYPOTHÈSE de `pages.md` §6).
- Selon les décisions : E31 corrige l'affichage des échéances (aucun changement de code front attendu) ; E2 fait passer la suppression interdite d'un commentaire de 400 à 403 (le bouton est déjà masqué).
- FrontTesteur : e2e `@board` paramétrés par amont (comme `@identity`), par tranche ; proxy de développement : entrées par tranche vers board-service, avant l'entrée `/api`.

## 13. Besoins CI et Docker (Manuel, à confirmer par la demande de BackAli dans `docs/ci/requests/backend.md`)
- Service `board` (image multi-étapes, non root, `/actuator/health` sur un port de gestion non routé), clé **publique** JWT montée en lecture seule, secret interne par variable d'environnement (valeur factice dans `.env.example`).
- Base : schéma `board` et utilisateur `board` avec les droits du §4.3 R2 (script d'initialisation idempotent).
- nginx : une variable d'amont par tranche (`BOARD_T1_UPSTREAM` … `BOARD_T5_UPSTREAM`, défaut `api:80`), emplacements par expression régulière **avant** `location /api/` ; les sous-routes restées dans Symfony (pièces jointes, fichiers Trello) explicitement exclues ; tests de routage (SC-BOARD-022).
- Symfony : variable de désactivation de la boucle de purge (`entrypoint.sh`), endpoints internes, et URL interne d'identity paramétrable vers board-service.
- Jobs : `board-service` (tests, qualité, image), `board-security` (OWASP), `board-compat` (stack complète, deux amonts), `frontend-e2e-board`, PHPUnit Symfony ; tous requis par `ci-gate`.

## 14. Exigences non fonctionnelles
- Performance : objectifs des specs de domaine (p95) **en suivi après bascule** (U5) ; exigé : aucun N+1 (compteur de requêtes), 0 × 5xx.
- Sécurité : contrôle d'accès dans chaque cas d'utilisation, matrice SC-BOARD-021, secrets hors dépôt, journaux sans données personnelles (SC-BOARD-026), requêtes paramétrées.
- Fiabilité : outbox, idempotence par `eventId`, `verify`, retour arrière répété.
- Accessibilité : inchangée (aucune page modifiée).

## 15. Suite attendue (étape 2 du circuit : BackAli)
1. Recueillir les décisions U6–U9 et E2, E8, E22–E32 de l'utilisateur (Paul les consigne dans `decisions.md`).
2. Étape 0 « faits vérifiés » (comme `backend-hexagonal.md` §8), en lecture seule et en agrégats : positions en doublon existantes (cartes, listes, checklists, champs), `card_seq` contre `MAX(number)`, répartition des fuseaux de `due_date` (confirmation d'E31), collation des colonnes, `sql_mode`, volume par table, comportement réel des cas « à confirmer ».
3. ADR B-ADR-1 à 10 et révision de A9.
4. Contrats : `docs/contracts/openapi/board-service.yaml` (par tranche, `x-scenarios`), `board-service.internal.yaml` (claim) et contrat des endpoints internes Symfony.
5. Demande CI (`docs/ci/requests/backend.md`, section board).
6. Ordre de travail T0 + T1 (Testeur → Codeur → Reviewer), puis une tranche à la fois.
