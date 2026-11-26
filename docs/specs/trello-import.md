# Import Trello et transfert des pièces jointes

Service cible : **import-service** (crée le tableau via board-service, copie les fichiers via attachment-service ; événement `TrelloImportCompleted`).
Code actuel : `ImportController`, `TrelloFilesController`, `TrelloImporter`, `TrelloFileTransfer`, `TrelloImportFile`, `ImportTrelloCommand` ; front : `pages/dashboard` (fenêtre « Importer depuis Trello »), `shared/trello-access.component.ts`, `core/trello-transfer.service.ts`, barre de progression sur le tableau.

## 1. Contexte et règles métier

### Import d'un export JSON
- Source : export JSON d'un tableau Trello (menu → Imprimer, exporter et partager → Exporter au format JSON), ≤ **20 Mo**. Aussi en ligne de commande (`app:import-trello <fichier> <email>`).
- Validation : objet JSON avec `name`, `lists` (tableau) et `cards` (tableau), sinon 400 `Ce fichier n'est pas un export JSON de tableau Trello.`
- **Transaction unique** : en cas d'erreur, rien n'est créé.
- Tableau : titre = `name` (≤ 160, défaut « Import Trello »), description = `desc`, fond déduit de `prefs.background` (`blue→ocean`, `orange→sunset`, `green→forest`, `red→berry`, `purple→aurora`, `pink→peach`, `lime`/`sky→lagoon`, `grey→graphite`, sinon `midnight`) ; l'importateur est `owner`.
- **Membres** : rapprochés **par nom complet normalisé** (minuscules, espaces réduits, translittération ASCII) parmi l'importateur et les personnes qui partagent déjà un tableau avec lui ; rapprochés → membres `member` ; les autres sont seulement listés (`membersUnmatched`). Les exports Trello ne contiennent pas d'e-mails.
- **Étiquettes** : nom ≤ 60, couleur Trello convertie (table de 30 couleurs, défaut `#94a3b8`).
- **Listes** : toutes, dans l'ordre `pos` ; les listes fermées sont importées **archivées**.
- **Cartes** : celles d'une liste connue ; numéro = `idShort` (sinon numéro libre après le max) ; `card_seq` = plus grand `idShort` (la numérotation continue ensuite) ; titre ≤ 255 (défaut « Sans titre ») ; date de création déduite de l'identifiant Trello ; description ; échéance ; `dueComplete` → terminé ; couverture couleur ; étiquettes ; assignés rapprochés ; checklists aplaties dans l'ordre ; cartes fermées importées **archivées** (`archivedAt` = `dateLastActivity`).
- **Pièces jointes** : liens simples ajoutés en fin de description (« Liens (Trello) : ») ; fichiers déposés sur Trello (`isUpload` et URL `https://(api.)trello.com/1/cards/<24 hex>/attachments/<24 hex>/download/…`) enregistrés en **file d'attente** `TrelloImportFile` (copie ultérieure) ; fichiers signalés `isMalicious` jamais copiés.
- **Commentaires** : actions `commentCard` (auteur rapproché, sinon importateur + `externalAuthor`), date d'origine, badge « Trello ».
- **Historique** : actions `createCard`, `updateCard` (déplacement, terminé, échéance, renommage, description), `add/removeMemberToCard`, `addAttachmentToCard`, `add/removeLabelToCard`, `updateCheckItemStateOnCard` → activités, avec acteur externe si non rapproché. Limite Trello : 1 000 dernières actions.
- **Liens internes** : `https://trello.com/c/<shortLink>/…` d'une carte du même tableau → `/boards/{id}?card={cardId}` dans descriptions et commentaires.
- Rapport : `lists, cards, labels, comments, history, commentsInTrello, attachments, files, filesBytes, filesMalicious, cardLinks, assignments, archivedLists, skippedArchivedLists, archivedCards, skippedArchivedCards, membersMatched, membersUnmatched, nextCardNumber`.

### Transfert des fichiers (piloté par le navigateur de l'importateur)
- La clé d'API (`^[a-f0-9]{32}$`) et le jeton Trello **en lecture seule** (`^[A-Za-z0-9]{32,256}$`) restent dans la mémoire de l'onglet ; ils sont envoyés **à chaque requête** et **jamais enregistrés ni journalisés** côté serveur (en-tête `Authorization: OAuth …` vers Trello).
- Réservé aux **gestionnaires** du tableau.
- Chaque appel `transfer` travaille ~**40 s** puis renvoie la progression ; le front boucle (pause / reprise, nouvel essai des échecs, ou « Laisser sur Trello »).
- Par fichier : > 100 Mo (taille annoncée ou `Content-Length`) → échec « Plus de 100 Mo » ; 404 → « Introuvable sur Trello (supprimé ?) » ; autre statut → « Refusé par Trello (HTTP n) » ; erreur réseau → « Téléchargement impossible (réseau) » ; **401 → arrêt immédiat, 422** (pour ne pas déconnecter l'utilisateur de Taskora), aucun fichier marqué en échec ; **429** → arrêt et `retryAfter` (1..60 s, défaut 10).
- Succès : pièce jointe créée (auteur rapproché, date d'origine, miniature), couverture retrouvée si c'était la couverture Trello et que la carte n'en a pas, liens Trello vers ce fichier réécrits en `/uploads/<clé>` dans les descriptions et commentaires du tableau.
- Redirections : 3 au plus, HTTPS uniquement, l'en-tête `Authorization` n'est pas transmis à un autre hôte.
- « Laisser sur Trello » (`abandon`) : les fichiers restants (en attente ou en échec) sont ajoutés en liens à la description de leur carte (« Pièces jointes restées sur Trello : ») puis retirés de la file.

## 2. Routes HTTP actuelles

| Méthode et route | Droit | Succès | Erreurs |
|---|---|---|---|
| `POST /api/import/trello/check` `{key, token}` | connecté | 200 `{fullName, username}` | 400 `Clé ou jeton Trello mal formé : copiez-les à nouveau depuis Trello.` ; **422** `Trello a refusé la clé ou le jeton (expiré ou révoqué ?). Générez un nouveau jeton.` ; **502** `Trello ne répond pas correctement (HTTP n). Réessayez plus tard.` |
| `POST /api/import/trello` (multipart `file`) | connecté | **201** `{boardId, report}` | 400 `Fichier manquant ou trop volumineux (20 Mo max).` ; 400 `Fichier trop volumineux (20 Mo max).` ; 400 `JSON invalide.` ; 400 `Ce fichier n'est pas un export JSON de tableau Trello.` |
| `GET /api/boards/{id}/trello-files` | gestionnaire | 200 `{…stats, failures:[{id, name, error, cardId}]}` | 403 `Seuls les administrateurs du tableau peuvent transférer les pièces jointes Trello.` ; 404 |
| `POST /api/boards/{id}/trello-files/transfer` `{key, token}` | gestionnaire | 200 `{…stats, failures, transferred:[{name, cardId}], errors:[{name, error}], retryAfter}` | 400 identifiants mal formés ; 403 ; 404 ; 422 |
| `POST /api/boards/{id}/trello-files/retry` | gestionnaire | 200 état (échecs remis en attente) | 403 ; 404 |
| `DELETE /api/boards/{id}/trello-files` | gestionnaire | 200 état | 403 ; 404 |

`stats` = `{pending, done, failed, total, pendingBytes}` ; `failures` = 100 premiers échecs par `id` croissant. `trelloFiles` (dans `GET /api/boards/{id}`) = nombre de fichiers non copiés (en attente + en échec).

## 3. Scénarios

### SC-IMPORT-001 — Importer un export Trello complet (critique)
Étant donné un export de référence (fixture versionnée) : 5 listes dont 1 fermée, 40 cartes dont 3 fermées, étiquettes, checklists, 12 commentaires, membres dont 2 rapprochables, liens, 6 fichiers dont 1 malveillant
Quand Alice l'importe
Alors 201 `{boardId, report}` ; le tableau reproduit listes, ordre, cartes avec leur numéro Trello, descriptions, échéances, statuts, couvertures, étiquettes, checklists, commentaires (auteur et date), historique, assignations ; le rapport est exact ; 5 fichiers en file d'attente
Validation : intégration (fixture), API [back] ; e2e accueil → import → tableau [front] — Acceptation : comparaison champ à champ avec un instantané attendu

### SC-IMPORT-002 — Numérotation après import (critique)
Étant donné un export dont le plus grand `idShort` est 248 et une carte sans `idShort`
Quand il est importé puis qu'on crée une carte
Alors la carte sans `idShort` reçoit 249 ; la carte créée ensuite reçoit le numéro suivant le `card_seq` enregistré ; `nextCardNumber` = 249 ; aucun doublon (contrainte unique)
Limites : **anomalie constatée à la lecture du code** (`TrelloImporter`) : `card_seq` est fixé au plus grand `idShort` (248) alors que 249 est déjà attribué à la carte sans `idShort` → la création suivante viole la contrainte unique (500). BackTesteur écrit ce cas (rouge contre Symfony attendu) ; correction proposée : `card_seq` = plus grand numéro réellement attribué (écart E14, décision utilisateur)
Validation : intégration [back] — Acceptation : aucune collision

### SC-IMPORT-003 — Fichier refusé (critique)
Étant donné un fichier de 25 Mo, un fichier texte non JSON, un JSON sans `lists`, un JSON tableau `[]`
Quand ils sont importés
Alors 400 avec respectivement `Fichier trop volumineux (20 Mo max).`, `JSON invalide.`, `Ce fichier n'est pas un export JSON de tableau Trello.` (×2) ; aucun tableau créé
Validation : API [back] ; e2e (message dans la fenêtre d'import) [front] — Acceptation : aucun effet de bord

### SC-IMPORT-004 — Atomicité de l'import (critique)
Étant donné un export valide dont une carte provoque une erreur en cours d'import (donnée invalide simulée)
Quand il est importé
Alors aucune ligne n'est créée (ni tableau, ni liste, ni file de fichiers)
Validation : intégration [back] — Acceptation : base inchangée

### SC-IMPORT-005 — Rapprochement des membres (important)
Étant donné Alice partageant un tableau avec « Élodie Durand », et un membre Trello « elodie  durand » et un autre « Inconnu »
Quand Alice importe
Alors Élodie est membre du tableau et assignée à ses cartes ; « Inconnu » est dans `membersUnmatched` ; ses commentaires sont attribués à un auteur externe
Limites : un homonyme qui ne partage aucun tableau avec Alice n'est jamais rapproché (pas de fuite d'annuaire)
Validation : unitaire (normalisation), intégration, sécurité [back] — Acceptation : rapprochement exact

### SC-IMPORT-006 — Liens internes réécrits (important)
Étant donné des descriptions et commentaires contenant `https://trello.com/c/AbCd1234/12-titre` (carte du même tableau) et `https://trello.com/c/ZzZz9999` (autre tableau)
Quand l'import se termine
Alors le premier devient `/boards/{id}?card={cardId}`, le second reste inchangé ; `cardLinks` = nombre de réécritures
Validation : unitaire, intégration [back] — Acceptation : textes exacts

### SC-IMPORT-007 — Vérifier les identifiants Trello (important)
Étant donné la fenêtre d'import avec « Copier les pièces jointes dans Taskora » coché
Quand l'utilisateur colle une clé et un jeton
Alors `POST /check` renvoie le nom du compte Trello ; clé mal formée → 400 ; jeton révoqué → **422** (jamais 401, sinon le front déconnecterait l'utilisateur) ; Trello en panne → 502
Validation : API (Trello simulé, WireMock), sécurité [back] ; e2e (explications pour créer clé et jeton) [front] — Acceptation : statuts exacts, aucun appel réel à Trello en CI

### SC-IMPORT-008 — Les identifiants Trello ne sont jamais conservés (critique)
Étant donné un import avec transfert
Quand on inspecte la base, les journaux applicatifs, les messages d'erreur et les événements publiés après le transfert
Alors ni la clé ni le jeton n'y figurent ; les messages d'erreur réseau ne contiennent pas l'en-tête `Authorization`
Validation : sécurité (recherche de la clé de test dans logs, base et messages capturés) [back] — Acceptation : 0 occurrence

### SC-IMPORT-009 — Transférer les fichiers par tranches (critique)
Étant donné 5 fichiers en attente
Quand le front appelle `transfer` en boucle
Alors chaque appel dure au plus ~40 s ; les fichiers deviennent des pièces jointes (auteur rapproché, date d'origine, miniature) ; la progression affichée sur le tableau avance ; une fois la file vide, la barre disparaît (`trelloFiles` = 0)
Validation : intégration (Trello simulé + LocalStack), API [back] ; e2e (barre, pause / reprise) [front] — Acceptation : 5 pièces jointes, file vide

### SC-IMPORT-010 — Erreurs par fichier (important)
Étant donné un fichier annoncé à 150 Mo, un fichier 404 et un fichier 500 chez Trello
Quand on transfère
Alors trois échecs avec les messages « Plus de 100 Mo », « Introuvable sur Trello (supprimé ?) », « Refusé par Trello (HTTP 500) » ; les autres fichiers continuent
Limites : `Content-Length` > 100 Mo alors que la taille annoncée était faible → coupé dès les en-têtes
Validation : intégration (Trello simulé) [back] — Acceptation : messages exacts, transfert poursuivi

### SC-IMPORT-011 — Jeton expiré et limitation de débit (critique)
Étant donné un transfert en cours
Quand Trello répond 401
Alors 422 immédiatement, aucun fichier marqué en échec (reprise possible avec un nouveau jeton)
Quand Trello répond 429 avec `Retry-After: 30`
Alors la tranche s'arrête avec `retryAfter: 30` et le front attend 30 s avant de reprendre
Limites : `Retry-After: 600` → 60 ; absent → 10
Validation : intégration (Trello simulé) [back] ; composant (`TrelloTransferService`) [front] — Acceptation : états et délais exacts

### SC-IMPORT-012 — Anti-SSRF sur les URL de fichiers (critique)
Étant donné un export modifié dont des fichiers pointent vers `https://169.254.169.254/…`, `http://trello.com/1/cards/…`, `https://trello.com.evil.com/…`, ou une redirection Trello vers `http://…` ou vers un 4e saut
Quand on importe puis transfère
Alors ces URL ne sont jamais appelées (liens simples ou refus) ; aucune redirection non HTTPS suivie ; l'en-tête `Authorization` n'est jamais envoyé à un autre hôte
Validation : unitaire (`isTrelloFileUrl`), sécurité [back] — Acceptation : aucune requête sortante hors liste

### SC-IMPORT-013 — Couverture et liens réécrits après copie (important)
Étant donné un fichier qui était la couverture Trello d'une carte, et des descriptions qui y renvoient (`…/download/…` et `…/previews/…`)
Quand il est copié
Alors il devient la couverture de la carte (si elle n'en a pas) et les liens pointent vers `/uploads/<clé>`
Validation : intégration [back] — Acceptation : couverture et textes exacts

### SC-IMPORT-014 — Réessayer et abandonner (important)
Étant donné 2 fichiers en échec et 1 en attente
Quand on appelle `retry`
Alors les 2 échecs repassent en attente
Quand on appelle `DELETE` (« Laisser sur Trello »)
Alors les 3 fichiers sont ajoutés en liens à la description de leur carte puis retirés de la file
Validation : API, intégration [back] ; e2e [front] — Acceptation : file vide, descriptions complétées

### SC-IMPORT-015 — Droits sur le transfert (critique)
Étant donné Carla `member` et Bob non membre
Quand ils appellent les routes `/trello-files`
Alors 403 pour les deux (`Seuls les administrateurs…` pour Carla ; non-membre pour Bob)
Validation : sécurité [back] — Acceptation : aucun transfert déclenché

### SC-IMPORT-016 — Import en ligne de commande (secondaire)
Étant donné un fichier d'export et l'e-mail d'un utilisateur existant
Quand on lance la commande d'import
Alors le même résultat qu'en API ; e-mail inconnu → erreur explicite
Validation : intégration [back] — Acceptation : tableau créé (HYPOTHÈSE: la commande est reprise dans import-service, à confirmer par BackAli)

## 4. Cas critiques et limites
- Tailles : export 20 Mo ; fichiers 100 Mo ; tableaux Trello de plusieurs milliers de cartes (temps d'import < 60 s pour 5 000 cartes).
- Services externes : Trello 401, 404, 429, 5xx, réseau ; S3 indisponible pendant la copie (fichier en échec, reprise possible).
- Concurrence : deux onglets lancent `transfer` sur le même tableau → un fichier n'est jamais copié deux fois (contrainte unique `board_id, trello_id` et prise de fichier atomique : HYPOTHÈSE, non garanti aujourd'hui).
- Corbeille : tableau importé mis à la corbeille pendant le transfert → 404.

## 5. Exigences non fonctionnelles
- Sécurité : identifiants Trello uniquement en mémoire de requête ; appels sortants restreints à `trello.com` / `api.trello.com` (et redirections HTTPS) ; journalisation sans en-têtes.
- Performance : une tranche de transfert ≤ 45 s (sous le délai du proxy de 300 s).
- Accessibilité : fenêtre d'import et barre de progression annoncées (`aria-live`), boutons pause / reprise au clavier.
