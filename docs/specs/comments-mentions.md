# Commentaires, @mentions et texte riche

Service cible : **board-service** (notifications publiées vers notification-service ; fichiers joints : attachment-service).
Code actuel : `CardController::comment/deleteComment`, `Comment`, `CommentAttachments`, `RichText`, `config/packages/html_sanitizer.yaml` ; front : `card-modal.component.ts` (fil « Commentaires et activité »), `shared/rich-editor.component.ts` (Tiptap), `shared/rich-view.component.ts`, `shared/rich-text.ts` (DOMPurify, Markdown).

## 1. Contexte et règles métier

### Commentaire
`id`, `card`, `author` (utilisateur ; pour un import Trello non rapproché, `author` = importateur et `externalAuthor` = nom Trello), `content` (texte riche), `createdAt`. Représentation : `{id, content, author, createdAt}` ; un auteur externe est présenté `{"id":0,"name":"<nom Trello>","email":"","avatarColor":"#94a3b8","external":true}`.
- Tout **membre** commente.
- Supprimer : l'**auteur** ou un **gestionnaire** (owner/admin) ; sinon 400 (écart E2).
- Pas de modification d'un commentaire (aucune route).

### Texte riche (descriptions et commentaires)
- Le contenu est soit du HTML (éditeur Tiptap), soit du texte / Markdown hérité (anciennes données, import Trello) détecté par l'absence de balise ouvrante connue au début.
- **Nettoyage à l'écriture** (Symfony HtmlSanitizer `app.rich_text`) : éléments autorisés `p h1 h2 h3 br strong b em i u s code pre blockquote hr ul ol li label input div span mark a img` avec attributs limités ; schémas de liens `http https mailto` ; médias `http https` ; liens et médias relatifs autorisés (`/uploads/…`) ; `rel="noopener noreferrer"` et `target="_blank"` forcés sur `<a>` ; entrée ≤ 200 000 caractères.
- Styles : seuls `color`, `background-color` (couleur hexadécimale, `rgb()`/`rgba()`, `var(--…)` ou mot-clé) et `text-align` (`left|right|center|justify`) sont conservés.
- Un éditeur vide (`<p></p>`) → `null`.
- **Nettoyage à l'affichage** (DOMPurify, URI `https?:`, `mailto:` ou chemin relatif `/…`), Markdown converti, cases de liste de tâches forcées en lecture seule.

### Mentions et destinataires
- Mention par **puce** insérée depuis la liste « @ » : `<span data-type="mention" data-id="<userId>" …>` ; ou texte brut `@Nom Complet` (insensible à la casse).
- Seuls les **membres du tableau** peuvent être mentionnés (un `data-id` d'un non-membre est ignoré).
- Destinataires d'un commentaire : assignés de la carte + créateur de la carte + membres mentionnés ; **sans l'auteur** ; sans doublon. Type unique `card_comment` ; message « <Auteur> a commenté « <titre> » : <texte brut tronqué à 80 caractères>… ».
- « Répondre » dans le fil insère une mention de l'auteur.

### Fichiers postés dans un commentaire
Les `<img src="/uploads/…">` et `<a href="/uploads/…">` deviennent des **pièces jointes de la carte**, liées au commentaire (`commentId`) : au plus 50 par commentaire, uniquement si l'objet existe dans le stockage, jamais en double (clé déjà connue). Nom = `alt` ou texte du lien (préfixe « 📎 » retiré), sinon nom de fichier. Activité `attachment {name}` par fichier.

## 2. Routes HTTP actuelles

| Méthode et route | Droit | Succès | Erreurs |
|---|---|---|---|
| `POST /api/cards/{id}/comments` `{content}` | membre | **201** `{id, content, author, createdAt, attachments[]}` | 400 `Le champ "content" est obligatoire.` ; 400 `Le commentaire est vide.` ; 403 ; 404 |
| `DELETE /api/comments/{id}` | auteur ou gestionnaire | 204 | **400** `Vous ne pouvez supprimer que vos propres commentaires.` (écart E2) ; 403 non-membre ; 404 |

Contenu tronqué à 100 000 caractères **avant** nettoyage.

## 3. Scénarios

### SC-COMMENT-001 — Commenter une carte (critique)
Étant donné une carte créée par Carla, assignée à Bob et Alice
Quand Alice poste `<p>Revue faite</p>`
Alors 201 ; le commentaire apparaît en tête du fil ; Bob et Carla reçoivent `card_comment` (« Alice a commenté « … » : Revue faite »), Alice non
Limites : 0 assigné et créateur = auteur → aucune notification ; créateur supprimé (`createdBy` null) → ignoré
Validation : API, intégration (notifications) [back] ; e2e fiche carte (⌘/Ctrl + Entrée pour envoyer) [front] — Acceptation : destinataires exacts

### SC-COMMENT-002 — Commentaire vide (important)
Étant donné une carte
Quand on poste `""`, `"   "` ou `"<p></p>"`, ou `"<p><script>x</script></p>"`
Alors 400 (`Le champ "content" est obligatoire.` pour les deux premiers, `Le commentaire est vide.` pour les suivants)
Validation : unitaire (nettoyage), API [back] — Acceptation : aucun commentaire créé

### SC-COMMENT-003 — Mention par puce (critique)
Étant donné Dan membre du tableau, non assigné, et Eve non membre
Quand Alice poste un commentaire contenant les puces de mention de Dan et d'Eve
Alors Dan reçoit `card_comment` ; Eve ne reçoit rien
Limites : attributs dans n'importe quel ordre (`data-id` avant ou après `data-type`) ; mentions répétées → une seule notification
Validation : unitaire (extraction), API, sécurité [back] ; e2e liste « @ » [front] — Acceptation : aucune notification hors tableau

### SC-COMMENT-004 — Mention en texte brut (important)
Étant donné Dan Martin membre
Quand Alice écrit « merci @dan martin »
Alors Dan reçoit la notification
Limites : un membre dont le nom est préfixe d'un autre (« Dan » et « Dan Martin ») → les deux sont notifiés (comportement actuel)
Validation : unitaire [back] — Acceptation : comportement reproduit

### SC-COMMENT-005 — Nettoyage XSS (critique)
Étant donné un membre malveillant
Quand il poste `<img src=x onerror=alert(1)>`, `<a href="javascript:alert(1)">x</a>`, `<p style="background:url(//evil)">x</p>`, `<iframe src=…>`, `<svg onload=…>`
Alors le contenu stocké ne contient ni gestionnaire d'événement, ni `javascript:`, ni `url(`, ni `iframe`/`svg` ; l'affichage (DOMPurify) ne déclenche aucun script
Validation : unitaire (corpus OWASP), sécurité [back] ; composant `rich-view` (même corpus) [front] — Acceptation : 0 exécution, 0 attribut interdit

### SC-COMMENT-006 — Liens forcés en nouvel onglet (secondaire)
Étant donné un commentaire avec `<a href="https://exemple.fr">`
Quand il est enregistré
Alors le lien porte `rel="noopener noreferrer" target="_blank"`
Validation : unitaire [back] — Acceptation : attributs présents

### SC-COMMENT-007 — Fichiers postés dans un commentaire (critique)
Étant donné deux fichiers téléversés via `POST /api/uploads`, dont l'un est déjà pièce jointe de la carte
Quand Alice poste un commentaire contenant une image et un lien vers ces fichiers, plus un lien vers une clé inexistante
Alors la réponse contient `attachments` = 1 nouvelle pièce jointe (le fichier déjà connu et la clé inexistante sont ignorés), liée au commentaire ; activité `attachment {name}` ; le compteur 📎 augmente
Limites : 60 fichiers → 50 retenus ; URL absolue sur le même hôte (`https://taskora…/uploads/…`) reconnue ; URL `/uploads/` d'une autre forme de clé → ignorée
Validation : intégration (stockage), API [back] ; e2e (coller / glisser une image dans le commentaire) [front] — Acceptation : pièces jointes exactes

### SC-COMMENT-008 — Supprimer un commentaire (critique)
Étant donné un commentaire de Bob
Quand Carla (membre) tente de le supprimer
Alors 400 `Vous ne pouvez supprimer que vos propres commentaires.` (écart E2)
Quand Bob, ou un admin, le supprime
Alors 204
Limites : non-membre → 403 ; commentaire d'une carte dans la corbeille → 404
Validation : API, sécurité [back] ; e2e (bouton visible seulement si autorisé) [front] — Acceptation : statuts exacts

### SC-COMMENT-009 — Suppression d'un commentaire avec fichiers (important)
Étant donné un commentaire qui a créé deux pièces jointes
Quand il est supprimé
Alors les deux pièces jointes disparaissent de la carte (cascade en base) ; les objets restent sur S3 (écart E11)
Validation : intégration [back] — Acceptation : comportement documenté

### SC-COMMENT-010 — Commentaires importés de Trello (secondaire)
Étant donné un commentaire importé dont l'auteur Trello n'a pas été rapproché
Quand on lit la carte
Alors l'auteur est présenté comme externe (`id: 0`, `external: true`) avec le badge « Trello »
Validation : API [back] ; composant [front] — Acceptation : représentation exacte

### SC-COMMENT-011 — Contenus hérités en Markdown (secondaire)
Étant donné une description en Markdown (import Trello)
Quand elle est affichée puis modifiée
Alors elle s'affiche mise en forme et est convertie en HTML à la première modification
Validation : composant, e2e [front] — Acceptation : rendu et conversion

### SC-COMMENT-012 — Longueur maximale (important)
Étant donné un commentaire de 150 000 caractères
Quand il est posté
Alors il est tronqué à 100 000 caractères avant nettoyage (HTML éventuellement coupé puis réparé par le nettoyeur), 201
Validation : API [back] — Acceptation : pas de 500

## 4. Cas critiques et limites
- XSS : double nettoyage (écriture et affichage) ; corpus commun back/front.
- Notification : jamais l'auteur, jamais un non-membre, jamais en double.
- Concurrence : deux commentaires simultanés → deux notifications distinctes.
- Coexistence : pendant la migration, la notification devient un événement `CommentPosted` / `MemberMentioned` consommé par notification-service (outbox).

## 5. Exigences non fonctionnelles
- `POST /comments` p95 < 300 ms hors envoi push (envoi après la réponse ou asynchrone).
- Accessibilité : éditeur utilisable au clavier, barre d'outils avec libellés, liste « @ » navigable aux flèches.
