# Pièces jointes, fichiers du texte riche et visionneuses

Service cible : **attachment-service** (contrôle d'accès au tableau par appel synchrone à board-service).
Code actuel : `AttachmentController`, `UploadController`, `Uploads`, `DocumentPreview`, `CommentAttachments`, `Storage/*` (S3 ou local), `Attachment` ; nginx (`location ^~ /uploads/`) ; front : `card-modal.component.ts`, `core/attachment-uploads.service.ts`, `shared/file-viewer.component.ts`, `shared/json-node.component.ts`, `shared/rich-editor.component.ts`.

## 1. Contexte et règles métier

### Stockage
- Pilote `s3` (bucket **privé**, SSE-S3, préfixe optionnel, point d'accès compatible S3 possible) ou `local` (volume `uploads`).
- Clé : `AAAA/MM/<32 hex aléatoires>/<nom-slugifié.ext>` (nom ≤ 100 caractères avant l'extension, extension ≤ 10 caractères alphanumériques) ; anciennes images : `AAAA/MM/<32 hex>.<png|jpg|gif|webp>`. Motif accepté : `\d{4}/\d{2}/[a-f0-9]{32}(?:\.(?:png|jpg|gif|webp)|/[A-Za-z0-9][A-Za-z0-9._-]{0,119})`.
- URL enregistrée dans les contenus : toujours `/uploads/<clé>`, indépendante du stockage.
- Taille maximale : **100 Mo** (`Uploads::MAX = 104 857 600` octets) ; nginx accepte 105 Mo.
- **Type détecté sur le contenu** (`finfo`), jamais sur le nom fourni :
  - image réelle PNG/JPEG/GIF/WebP → extension forcée (`png jpg gif webp`) ;
  - extension « affichable » (`png jpg gif webp pdf`) dont le contenu ne correspond pas → extension remplacée par `.bin` ;
  - le reste garde son extension.

### Diffusion `/uploads/<clé>` (hors `/api`, **sans authentification**, écart E5)
- Affichés dans le navigateur (`inline`) : `png jpg gif webp pdf` et médias `mp4 m4v mov webm ogv mp3 m4a aac wav oga ogg opus flac weba` (type forcé d'après l'extension) ; `?download=1` force le téléchargement.
- Tout le reste : `Content-Disposition: attachment`, `Content-Type: application/octet-stream`.
- Nom de téléchargement : nom de la pièce jointe (accents conservés, repli ASCII), sinon nom de la clé.
- En-têtes : `X-Content-Type-Options: nosniff` (API et nginx) ; nginx ajoute `Content-Security-Policy: default-src 'none'; img-src 'self'`.
- S3 : redirection **302** vers une URL présignée valable **1 h** (cache privé 600 s) ; avec `AWS_S3_PUBLIC_URL` (CloudFront) et contenu affichable : **301** public (cache 1 jour). Requêtes `Range` supportées (lecture vidéo).
- Clé hors motif → 404.

### Pièce jointe (`Attachment`)
`id, card, uploader, name (≤ 255), storageKey, mimeType, size, thumbKey, previewKey, comment, createdAt`. Représentation : `{id, name, url, mime, size, image, thumbUrl, ext, commentId, uploader, createdAt}`.
- Ajout : tout **membre** ; renommer / supprimer : l'**auteur** ou un **gestionnaire** (sinon 400, écart E3).
- **Miniature** WebP qualité 80, côté le plus long 640 px, générée si l'image dépasse 640 px ou pèse ≥ 200 000 octets ; images de plus de 60 mégapixels non décodées ; orientation EXIF corrigée (JPEG) ; un échec n'empêche pas l'ajout.
- **Aperçu PDF** des documents bureautiques (`doc docx docm dot dotx odt ott rtf wpd pages ppt pptx pptm pps ppsx pot potx odp otp key xls xlsx xlsm xlsb ods ots numbers`) par Gotenberg (LibreOffice), au premier appel puis mis en cache (`previewKey`) ; délai 240 s ; Gotenberg n'est joignable que par l'API (réseau Docker interne sans Internet).
- Suppression : miniature et aperçu toujours supprimés ; l'original est conservé **s'il est affiché dans un commentaire** ; la couverture est retirée si besoin ; activité `attachment_removed`.

## 2. Routes HTTP actuelles

| Méthode et route | Droit | Succès | Erreurs |
|---|---|---|---|
| `GET /api/cards/{id}/attachments` | membre | 200 `[Attachment]` | 403 ; 404 |
| `POST /api/cards/{id}/attachments` (multipart `file`) | membre | 201 `Attachment` ; activité `attachment {name}` | 400 `Fichier manquant ou trop volumineux (100 Mo max).` ; 400 `« nom » dépasse 100 Mo.` ; 403 ; 404 ; 500 stockage indisponible |
| `PATCH /api/attachments/{id}` `{name}` | auteur ou gestionnaire | 200 (extension d'origine ajoutée si le nouveau nom n'en a pas) | 400 nom vide ; 400 `Seul l’auteur de la pièce jointe ou un administrateur du tableau peut la modifier.` ; 403 ; 404 |
| `DELETE /api/attachments/{id}` | auteur ou gestionnaire | 204 | 400 (idem) ; 403 ; 404 |
| `GET /api/attachments/{id}/content` | membre | 200 flux brut, `application/octet-stream`, `Content-Length`, `nosniff`, cache privé 3 600 s | 403 ; 404 |
| `GET /api/attachments/{id}/preview` | membre | 200 `{url:"/uploads/<clé du PDF>"}` | **422** `Aucun aperçu PDF pour ce type de fichier.` ; **502** `La conversion du document a échoué. Téléchargez-le pour l’ouvrir.` ; 403 ; 404 |
| `POST /api/uploads` (multipart `file`) | **connecté** (aucun tableau, écart E6) | 201 `{url, name, mime, size, image}` | 400 ; 401 |
| `GET|HEAD /uploads/{clé}` | **public** | 302 / 301 / 200 (local) | 404 |

## 3. Scénarios

### SC-ATTACH-001 — Joindre un fichier à une carte (critique)
Étant donné un membre et un fichier `Rapport final.pdf` de 2 Mo (vrai PDF)
Quand il l'envoie en multipart
Alors 201 ; `name` = `Rapport final.pdf`, `mime` = `application/pdf`, `ext` = `pdf`, `url` = `/uploads/AAAA/MM/<hex>/Rapport-final.pdf` ; activité `attachment` ; `attachmentCount` +1
Validation : API, intégration (LocalStack) [back] ; e2e bouton « Pièce jointe » avec barre de progression [front] — Acceptation : objet présent dans le bucket, métadonnées exactes

### SC-ATTACH-002 — Limite de 100 Mo (critique)
Étant donné des fichiers de 100 Mo exactement, 100 Mo + 1 octet et 0 octet
Quand ils sont envoyés
Alors 100 Mo → 201 ; 100 Mo + 1 → 400 `« nom » dépasse 100 Mo.` ; fichier vide → HYPOTHÈSE: 201 (à figer par test miroir)
Limites : champ `file` absent → 400 `Fichier manquant ou trop volumineux (100 Mo max).` ; envoi interrompu → 400, aucun objet orphelin attendu
Validation : API, intégration [back] ; e2e (message d'erreur) [front] ; CI (limite nginx/gateway ≥ 105 Mo) [Manuel] — Acceptation : statuts exacts, aucun objet créé en cas de refus

### SC-ATTACH-003 — Type détecté sur le contenu (critique)
Étant donné un fichier HTML renommé `photo.png`, un SVG `logo.svg`, un vrai JPEG nommé `image.jpeg.exe`, un PDF nommé `doc.pdf`
Quand ils sont envoyés puis ouverts via `/uploads/…`
Alors `photo.png` est stocké en `.bin` et servi en téléchargement `application/octet-stream` ; le SVG est téléchargé (jamais affiché) ; le JPEG est stocké `.jpg` et affiché ; le PDF est affiché
Validation : unitaire (`Uploads::delivery`, détection), sécurité, intégration [back] — Acceptation : aucun contenu actif servi `inline`

### SC-ATTACH-004 — Fichier inséré dans le texte riche (important)
Étant donné un utilisateur connecté
Quand il colle une image dans une description (`POST /api/uploads`)
Alors 201 `{url, name, mime, size, image:true}` ; l'image s'affiche dans l'éditeur
Limites : aucun contrôle d'appartenance à un tableau (écart E6) ; anonyme → 401
Validation : API [back] ; e2e (bouton, coller, glisser-déposer) [front] — Acceptation : URL utilisable immédiatement

### SC-ATTACH-005 — Miniatures (important)
Étant donné une image 4000×3000 JPEG prise en portrait (EXIF orientation 6), une image 300×200 de 50 Ko et une image de 80 mégapixels
Quand elles sont jointes
Alors la première a une miniature WebP 480×640 correctement orientée ; la deuxième n'a pas de miniature (`thumbUrl` null) ; la troisième non plus (non décodée), sans erreur
Validation : unitaire, intégration [back] — Acceptation : dimensions et absence d'erreur

### SC-ATTACH-006 — Diffusion d'un fichier via S3 (critique)
Étant donné le stockage S3 privé
Quand on demande `GET /uploads/<clé d'un PDF>` puis `?download=1`
Alors 302 vers une URL présignée (expiration 1 h) dont la réponse S3 porte `Content-Disposition: inline; filename=…` puis `attachment` ; `Content-Type: application/pdf`
Limites : nom avec accents `Échéancier été.pdf` → `filename*=UTF-8''…` et repli ASCII ; `AWS_S3_PUBLIC_URL` défini → 301 pour un contenu affichable, présignée pour un téléchargement
Validation : intégration (LocalStack), API [back] — Acceptation : en-têtes exacts

### SC-ATTACH-007 — Clé invalide (important)
Étant donné n'importe qui
Quand il demande `/uploads/../../etc/passwd`, `/uploads/2026/11/abc/x`, `/uploads/2026/11/<32 hex>/.htaccess`
Alors 404 ; aucune lecture hors du stockage
Validation : sécurité [back] — Acceptation : 404 sur tout le corpus de traversée

### SC-ATTACH-008 — Aperçu PDF d'un document bureautique (important)
Étant donné un `budget.xlsx` joint
Quand on appelle `GET /attachments/{id}/preview` deux fois
Alors la première conversion appelle Gotenberg et enregistre `previewKey` ; la seconde renvoie la même URL sans appel
Limites : type non pris en charge (`.zip`) → 422 ; Gotenberg en panne ou délai dépassé → 502, aucun `previewKey` enregistré ; deux appels simultanés → au plus deux conversions, une seule clé retenue (HYPOTHÈSE)
Validation : intégration (Gotenberg en Testcontainers ou simulé), API [back] ; e2e visionneuse Word/PowerPoint/Excel [front] — Acceptation : un seul appel Gotenberg sur deux requêtes successives

### SC-ATTACH-009 — Contenu brut pour les visionneuses (important)
Étant donné un fichier CSV, JSON, texte, EML
Quand la visionneuse appelle `GET /attachments/{id}/content`
Alors flux complet, `application/octet-stream`, `Content-Length` = taille, `nosniff`
Limites : non-membre → 403 ; carte dans la corbeille → 404 ; fichier de 100 Mo → diffusé en flux sans charger en mémoire
Validation : API, intégration [back] — Acceptation : octets identiques à l'original

### SC-ATTACH-010 — Visionneuses intégrées (important)
Étant donné des pièces jointes image, PDF, DOCX, XLSX (plusieurs feuilles), CSV, code, JSON, EML avec HTML et images distantes, MP4
Quand l'utilisateur clique dessus
Alors chacune s'ouvre dans la visionneuse adaptée : onglets de feuilles et vue « Mise en page », arbre JSON repliable, e-mail avec en-têtes et pièces jointes, HTML de l'e-mail nettoyé dans un cadre isolé sans scripts, **images distantes bloquées par défaut** ; ← → pour naviguer, Échap pour fermer
Validation : composant, e2e [front] — Acceptation : chaque type rendu, aucun script exécuté dans l'e-mail

### SC-ATTACH-011 — Renommer une pièce jointe (secondaire)
Étant donné `rapport.pdf` déposé par Bob
Quand Bob le renomme « Rapport final »
Alors le nom devient `Rapport final.pdf` (extension conservée) et le téléchargement utilise ce nom
Limites : nom vide → 400 ; nom avec une autre extension (`final.docx`) → conservé tel quel
Validation : API [back] ; e2e [front] — Acceptation : nom exact

### SC-ATTACH-012 — Droits de modification (critique)
Étant donné une pièce jointe de Bob
Quand Carla (membre) la renomme ou la supprime
Alors 400 `Seul l’auteur de la pièce jointe ou un administrateur du tableau peut la modifier.` (écart E3)
Quand un admin ou Bob le fait
Alors 200 / 204
Limites : pièce jointe d'un autre tableau → 403 (non-membre) ; pièce jointe importée de Trello sans auteur rapproché → seul un gestionnaire peut la modifier
Validation : API, sécurité [back] ; e2e (actions masquées) [front] — Acceptation : statuts exacts

### SC-ATTACH-013 — Supprimer une pièce jointe (critique)
Étant donné une image de couverture avec miniature et une pièce jointe issue d'un commentaire
Quand on supprime la première
Alors 204 ; original, miniature et aperçu supprimés du stockage ; la carte n'a plus de couverture ; activité `attachment_removed`
Quand on supprime la seconde
Alors 204 ; l'original reste sur le stockage (le commentaire l'affiche toujours)
Limites : panne de stockage → 204, fichiers orphelins tolérés
Validation : intégration (LocalStack), API [back] — Acceptation : objets supprimés / conservés comme attendu

### SC-ATTACH-014 — Glisser-déposer et progression (important)
Étant donné la fiche carte ouverte
Quand l'utilisateur glisse 3 fichiers dont un de 120 Mo
Alors 2 envois avec barre de progression, 1 refus avec message ; un dépôt hors zone n'ouvre pas le fichier dans le navigateur
Validation : e2e [front] — Acceptation : 2 pièces jointes, message pour la troisième

### SC-ATTACH-015 — URL de fichier sans authentification (important)
Étant donné une URL `/uploads/<clé>` d'un tableau d'Alice
Quand une personne extérieure l'ouvre
Alors le fichier est servi (écart E5 : la clé de 128 bits fait office de secret) ; la clé n'est jamais devinable (générateur cryptographique) et n'apparaît que dans les réponses aux membres
Validation : sécurité (entropie, absence de listing) [back] — Acceptation : comportement documenté, décision utilisateur tracée

### SC-ATTACH-016 — Panne du stockage à l'envoi (important)
Étant donné S3 indisponible
Quand un fichier est envoyé
Alors 500 `Erreur interne du serveur.` sans détail d'infrastructure dans la réponse ; aucune pièce jointe créée en base
Validation : intégration (panne simulée) [back] — Acceptation : aucune ligne orpheline

### SC-ATTACH-017 — Accès d'un non-membre (critique)
Étant donné Bob non membre
Quand il appelle les routes `/api/cards/{id}/attachments` et `/api/attachments/{id}/**` d'un tableau d'Alice
Alors 403 (écart E1)
Validation : sécurité [back] — Acceptation : aucune donnée renvoyée

## 4. Cas critiques et limites
- Tailles : 0, 100 Mo, 100 Mo + 1 ; images de 60 mégapixels et plus.
- Contenu actif : HTML, SVG, JS, faux PNG/PDF → toujours téléchargés.
- Services externes : S3 (indisponible, lent), Gotenberg (panne, 240 s, document corrompu).
- Coexistence : attachment-service devra servir `/uploads/<clé>` à l'identique (mêmes clés, même bucket) ; les URL déjà enregistrées dans les contenus ne changent jamais.

## 5. Exigences non fonctionnelles
- Envoi de 100 Mo sans charger le fichier en mémoire (flux) ; délai d'envoi ≥ 300 s côté proxy.
- `GET /uploads/<clé>` p95 < 50 ms (simple redirection).
- Accessibilité : visionneuse modale au clavier (← → Échap), boutons d'action avec libellés.
