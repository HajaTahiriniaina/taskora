# Notifications internes et Web Push

Service cible : **notification-service** (consomme les événements de domaine : `MemberJoined`, `CardAssigned`, `CommentPosted`, `MemberMentioned`, `CardMoved`, `CardDueSet`…).
Code actuel : `Notifier`, `NotificationController`, `PushController`, `PushSender`, `PushFlushSubscriber`, `Notification`, `PushSubscription`, `PushKeysCommand` ; front : `pages/shell/shell.component.ts` (panneau 🔔, bandeau d'activation), `core/notification.service.ts`, `core/push.service.ts`, `public/push-sw.js`, `public/manifest.webmanifest`, `pages/profile` (section Notifications du navigateur).

## 1. Contexte et règles métier

### Notification interne
`Notification {id, recipient, actor, type, message (≤ 500), board, card, read, createdAt}` ; représentation `{id, type, message, read, actor, boardId, boardTitle, cardId, createdAt}`.

| Type | Déclencheur | Destinataires | Message |
|---|---|---|---|
| `board_invite` | ajout d'un membre existant ; inscription d'un invité | le nouveau membre | « X vous a ajouté au tableau « T » » |
| `member_joined` | ajout d'un membre | les autres membres | « Y a rejoint le tableau « T » » |
| `member_removed` | retrait par un gestionnaire | le membre retiré (sans tableau lié) | « X vous a retiré du tableau « T » » |
| `card_assigned` | assignation | l'assigné | « X vous a assigné la carte « C » » |
| `card_comment` | commentaire (et mention) | assignés + créateur + mentionnés | « X a commenté « C » : <80 caractères>… » |
| `card_moved` | changement de liste | assignés | « X a déplacé « C » vers « L » » |
| `card_due` | échéance fixée (non nulle) | assignés | « X a fixé l'échéance de « C » au JJ/MM/AAAA » |

Règles : **jamais l'acteur lui-même** ; un destinataire au plus une fois par action ; destinataires nuls ignorés. La suppression du tableau ou de la carte (purge) supprime ses notifications (cascade) ; la suppression de l'acteur le met à `null`.

### Web Push (VAPID, sans service tiers)
- Abonnement d'un **appareil** (navigateur) : `PushSubscription {id, user, kind="web", endpoint (≤ 2 000), endpointHash (SHA-256, unique), p256dh, auth, device (≤ 120), createdAt, lastSuccessAt}`.
- **Liste blanche des services push** (anti-SSRF) : `https` sans port ni identifiants, hôtes `fcm.googleapis.com`, `android.googleapis.com`, `updates.push.services.mozilla.com`, `push.services.mozilla.com`, `*.push.apple.com`, `*.notify.windows.com`.
- Clés : `p256dh` `^[A-Za-z0-9_\-=]{20,200}$`, `auth` `^[A-Za-z0-9_\-=]{8,100}$`.
- Même navigateur, autre compte : l'abonnement est **rattaché au compte courant**.
- Envoi : chaque notification interne est aussi mise en file pour les appareils du destinataire (si VAPID est configuré) et **envoyée après la réponse HTTP** (aucune attente pour l'utilisateur). Charge utile `{title: titre du tableau ou "Taskora", body: message, url: "/boards/{b}?card={c}" | "/boards/{b}" | "/boards", tag: "card-{c}" | type, type}` ; TTL 24 h, urgence normale, lots de 200, délai 8 s, pas de redirection.
- Rapport : succès → `lastSuccessAt` ; **404/410 (abonnement expiré) → abonnement supprimé** ; autre refus → journalisé ; exception → journalisée, jamais propagée.
- Service worker : affiche la bannière, prévient les onglets ouverts ; un clic ouvre ou focalise un onglet sur l'URL (navigation Angular sans rechargement).
- Déconnexion : le front désabonne ce navigateur avant de supprimer le jeton.
- HTTPS obligatoire (sauf `localhost`) ; iPhone/iPad : uniquement depuis l'application ajoutée à l'écran d'accueil.
- Préparé pour `kind = fcm` (mobile) plus tard.

## 2. Routes HTTP actuelles

| Méthode et route | Succès | Erreurs |
|---|---|---|
| `GET /api/notifications` | 200 `{unread, items:[…40 plus récentes]}` | 401 |
| `GET /api/notifications/unread-count` | 200 `{unread}` | 401 |
| `POST /api/notifications/{id}/read` | 200 `Notification` | 404 (inexistante **ou d'un autre utilisateur**) |
| `POST /api/notifications/read-all` | 200 `{unread:0}` | 401 |
| `DELETE /api/notifications/{id}` | 204 | 404 (idem) |
| `GET /api/push` | 200 `{configured, publicKey, devices:[{id, kind, device, endpointHash, createdAt, lastSuccessAt}]}` | 401 |
| `POST /api/push/subscriptions` `{endpoint, keys:{p256dh, auth}, device}` | 201 `{id}` | 400 `Service de notification du navigateur non reconnu.` ; 400 `Clés de notification invalides.` |
| `DELETE /api/push/subscriptions` `{endpoint}` | 204 (idempotent, uniquement si l'abonnement m'appartient) | 401 |
| `DELETE /api/push/devices/{id}` | 204 | 404 `Appareil introuvable.` (inexistant ou d'un autre) |
| `POST /api/push/test` `{endpoint}` | 200 `{sent:true}` | 404 `Ce navigateur n’est pas abonné aux notifications.` ; 400 `Le service de notification du navigateur a refusé le message.` |

## 3. Scénarios — notifications internes

### SC-NOTIF-001 — Recevoir et lister ses notifications (critique)
Étant donné 45 notifications pour Bob dont 5 non lues, et des notifications pour Carla
Quand Bob appelle `GET /api/notifications`
Alors `unread` = 5 et `items` = ses 40 plus récentes, triées par date décroissante ; aucune de Carla
Validation : API, sécurité [back] ; e2e panneau 🔔 (badge, filtre « non lues ») [front] — Acceptation : contenu et isolation

### SC-NOTIF-002 — Jamais l'acteur, jamais en double (critique)
Étant donné Alice, créatrice et assignée d'une carte, qui se mentionne elle-même et mentionne Bob deux fois
Quand elle commente
Alors seule une notification pour Bob est créée
Validation : unitaire (calcul des destinataires) [back] — Acceptation : exactement 1 notification

### SC-NOTIF-003 — Catalogue des déclencheurs (critique)
Étant donné chaque action du tableau § 1
Quand elle est effectuée
Alors la notification attendue (type, destinataires, message, `boardId`, `cardId`) est créée
Limites : pendant la migration, chaque action est un événement publié par board-service et consommé par notification-service : la notification doit apparaître en moins de 2 s (cohérence à terme)
Validation : intégration (un test par type ; RabbitMQ en Testcontainers après migration) [back] — Acceptation : 7 types couverts

### SC-NOTIF-004 — Marquer comme lue (important)
Étant donné une notification de Bob et une de Carla
Quand Bob marque la sienne, puis tente de marquer celle de Carla
Alors 200 `read:true` ; puis **404** (pas de fuite d'existence)
Limites : la supprimer suit les mêmes règles (204 / 404)
Validation : API, sécurité (IDOR) [back] ; e2e (clic sur la notification → lue et ouverture de la carte) [front] — Acceptation : IDOR bloqué

### SC-NOTIF-005 — Tout marquer comme lu (important)
Étant donné 12 notifications non lues pour Bob et 3 pour Carla
Quand Bob appelle `read-all`
Alors `{unread:0}` ; celles de Carla restent non lues
Validation : API [back] ; e2e [front] — Acceptation : isolation

### SC-NOTIF-006 — Actualisation toutes les 15 s (important)
Étant donné le front ouvert
Quand une nouvelle notification arrive
Alors au plus 15 s plus tard le badge augmente via `GET /unread-count` (erreur de ce point silencieuse, sans toast) ; l'actualisation s'arrête à la déconnexion
Validation : composant (minuterie simulée), e2e [front] — Acceptation : badge à jour, aucune requête après déconnexion

### SC-NOTIF-007 — Notification sans tableau ou tableau purgé (secondaire)
Étant donné une notification `member_removed` (sans tableau) et une notification d'un tableau purgé
Quand on liste
Alors la première a `boardId: null` et un clic mène à `/boards` ; la seconde n'existe plus
Validation : intégration [back] ; composant [front] — Acceptation : comportement exact

## 4. Scénarios — Web Push

### SC-PUSH-001 — Activer les notifications du navigateur (critique)
Étant donné VAPID configuré et un navigateur compatible
Quand l'utilisateur clique « Activer les notifications » (profil ou bandeau du panneau 🔔) et accepte la permission
Alors le service worker est enregistré, l'abonnement est envoyé (`POST /api/push/subscriptions` → 201) et l'appareil apparaît dans la liste avec « cet appareil »
Limites : permission refusée → état « bloquées » avec explication ; VAPID absent → `configured:false`, état « serveur non configuré » ; navigateur incompatible ou iPhone hors écran d'accueil → message dédié
Validation : API [back] ; e2e (permission simulée) [front] — Acceptation : abonnement persisté, états affichés

### SC-PUSH-002 — Liste blanche anti-SSRF (critique)
Étant donné un utilisateur connecté
Quand il s'abonne avec un `endpoint` `http://fcm.googleapis.com/…`, `https://fcm.googleapis.com:8443/…`, `https://user@fcm.googleapis.com/…`, `https://169.254.169.254/…`, `https://evil.com/fcm.googleapis.com`, `https://fcm.googleapis.com.evil.com/…`, ou de plus de 2 000 caractères
Alors 400 `Service de notification du navigateur non reconnu.` dans tous les cas ; aucun appel sortant n'est jamais fait vers un hôte hors liste
Validation : unitaire (`isAllowedEndpoint`), sécurité [back] — Acceptation : corpus entièrement refusé

### SC-PUSH-003 — Clés invalides (important)
Étant donné un endpoint valide
Quand `p256dh` fait 10 caractères ou contient `+/`, ou `auth` fait 7 caractères
Alors 400 `Clés de notification invalides.`
Validation : unitaire, API [back] — Acceptation : messages exacts

### SC-PUSH-004 — Même navigateur, autre compte (important)
Étant donné un navigateur abonné pour Alice
Quand Bob se connecte sur ce navigateur (réabonnement automatique au démarrage)
Alors l'abonnement (même `endpointHash`) appartient désormais à Bob ; Alice ne reçoit plus de bannière sur ce navigateur
Validation : API, intégration [back] ; e2e [front] — Acceptation : une seule ligne, propriétaire Bob

### SC-PUSH-005 — Envoi après la réponse (critique)
Étant donné Bob abonné sur deux appareils
Quand Alice l'assigne à une carte
Alors la réponse HTTP d'Alice n'attend pas les services push ; chaque appareil reçoit la charge utile `{title, body, url:"/boards/{b}?card={c}", tag:"card-{c}", type:"card_assigned"}`
Limites : VAPID non configuré → aucune mise en file, aucune erreur ; service push lent (> 8 s) → abandon journalisé
Validation : unitaire (charge utile), intégration (serveur push simulé type WireMock) [back] — Acceptation : temps de réponse indépendant du service push, charge utile exacte

### SC-PUSH-006 — Abonnements expirés supprimés (important)
Étant donné un appareil dont le service push répond 410
Quand une notification lui est envoyée
Alors l'abonnement est supprimé ; un refus 400/500 est seulement journalisé ; un succès met à jour `lastSuccessAt`
Validation : intégration (serveur push simulé) [back] — Acceptation : état final exact

### SC-PUSH-007 — Notification de test (important)
Étant donné ce navigateur abonné
Quand l'utilisateur clique « Envoyer un test »
Alors 200 `{sent:true}` et une bannière « Les notifications sont activées sur cet appareil 🎉 » (lien `/profile`)
Limites : endpoint non abonné ou d'un autre compte → 404 ; refus du service → 400 `Le service de notification du navigateur a refusé le message.`
Validation : API, intégration [back] ; e2e [front] — Acceptation : statuts exacts

### SC-PUSH-008 — Désactiver, retirer un appareil, se déconnecter (critique)
Étant donné Bob abonné sur deux appareils
Quand il clique « Désactiver » sur l'un, retire l'autre depuis la liste, puis tente de retirer l'appareil `id` d'Alice
Alors les deux abonnements sont supprimés (204) ; l'appareil d'Alice → 404 `Appareil introuvable.` ; à la déconnexion, le navigateur est désabonné avant la suppression du jeton
Limites : `DELETE /api/push/subscriptions` avec l'endpoint d'un autre utilisateur → 204 sans effet
Validation : API, sécurité (IDOR) [back] ; e2e [front] — Acceptation : IDOR bloqué, désabonnement à la déconnexion

### SC-PUSH-009 — Clic sur une bannière (important)
Étant donné une bannière de carte
Quand l'utilisateur clique dessus, Taskora ouvert ou fermé
Alors un onglet Taskora existant est focalisé et navigue vers `/boards/{b}?card={c}` sans rechargement ; sinon une fenêtre s'ouvre sur cette URL
Validation : e2e (service worker) [front] — Acceptation : fiche carte ouverte

### SC-PUSH-010 — Clés VAPID conservées (critique)
Étant donné des navigateurs abonnés avec la clé publique VAPID actuelle
Quand notification-service remplace Symfony
Alors les mêmes clés VAPID (lues depuis la configuration, jamais commitées) sont utilisées et les abonnements existants continuent de recevoir les bannières sans réabonnement
Validation : intégration, compatibilité [back] ; CI (secret injecté) [Manuel] — Acceptation : envoi réussi vers un abonnement repris

## 5. Cas critiques et limites
- IDOR : notifications, appareils, endpoints d'autres utilisateurs (SC-NOTIF-004, SC-PUSH-008).
- SSRF : endpoint push (SC-PUSH-002) ; redirections désactivées.
- Services externes : services push lents, en erreur, abonnements expirés.
- Volume : un tableau de 50 membres → 49 notifications `member_joined` et jusqu'à 49 × appareils envois push par ajout.
- Idempotence : un événement consommé deux fois ne doit pas créer deux notifications (clé d'idempotence = identifiant d'événement × destinataire) après migration.

## 6. Exigences non fonctionnelles
- Délai de bout en bout (action → bannière) < 5 s hors service push.
- Aucune donnée sensible dans la charge utile au-delà du message (pas d'e-mail, pas de jeton).
- Accessibilité : panneau 🔔 en `role="dialog"`, badge annoncé (« 5 notifications non lues »).
