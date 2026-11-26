# Taskora

SaaS de gestion de projet en kanban (type Trello) : tableaux, listes, cartes en glisser-déposer, membres, notifications et **champs personnalisés** sur chaque carte.

| Couche      | Technologie                                   |
|-------------|-----------------------------------------------|
| Frontend    | Angular 22 (standalone, signals, zoneless), Angular CDK drag & drop |
| Backend     | Symfony 8.1 (PHP 8.4), Doctrine ORM 3, JWT (LexikJWT) |
| Base        | MariaDB 11.8 LTS                              |
| Serveurs    | FrankenPHP (API), nginx (SPA + proxy `/api`)  |

## Démarrage

```bash
cp .env.example .env                 # optionnel : ports, mots de passe de la base
cp backend/.env.dist backend/.env    # configuration Symfony (S3, secrets…)
docker compose up -d --build
```

- Application : http://localhost:8080
- API : http://127.0.0.1:8081/api (publiée sur la boucle locale uniquement ; aussi accessible via http://localhost:8080/api)
- MariaDB : localhost:3307 (`taskora` / `taskora`)

Profils compose : `--profile identity` ajoute identity-service (migration en cours, `db-init` crée son schéma et son compte), `--profile quality` ajoute SonarQube (http://127.0.0.1:9000). L'amont nginx de `/api/auth/` et `/api/me` se choisit par `IDENTITY_UPSTREAM` (`api:80` par défaut, Symfony) puis `docker compose up -d web`, sans reconstruction.

CI GitHub Actions (`.github/workflows/`) : identity-service (build, qualité, Sonar, image, sécurité OWASP, compatibilité), backend PHPUnit, frontend (build). Secrets à créer dans GitHub : `NVD_API_KEY`, `SONAR_TOKEN` (+ variable `SONAR_HOST_URL`). Détails : [docs/ci/README.md](docs/ci/README.md).

Au démarrage, le conteneur `api` attend la base, génère les clés JWT (volume `jwt_keys`) et applique les migrations. Les images des textes et les pièces jointes sont stockées sur **Amazon S3** (ou dans le volume `uploads` tant que S3 n’est pas configuré, voir ci-dessous).

## Fonctionnalités

- **Inscription / connexion** (JWT, 7 jours), profil (nom, couleur d’avatar, mot de passe).
- **Tableaux** : 10 arrière-plans, favoris, colonnes et étiquettes par défaut.
- **Listes** : création, renommage, couleur, réordonnancement par glisser-déposer.
- **Cartes** : glisser-déposer entre listes, description, étiquettes, membres assignés, échéance, priorité, couverture, checklist, commentaires avec @mentions.
- **Fiche carte** en deux colonnes : détails à gauche, « Commentaires et activité » à droite (défilement indépendant, empilées sur mobile). Le fil mélange commentaires et **historique** : création, renommage, description, déplacements, terminé / rouvert, date limite, priorité, couverture, étiquettes, membres, checklist, champs personnalisés. « Masquer les détails » cache l’historique (préférence mémorisée), « Répondre » mentionne l’auteur. API : `GET /api/cards/{id}/activity?before=&limit=` (pagination par curseur).
- **Texte riche** (descriptions et commentaires, éditeur Tiptap) : titres, gras, italique, souligné, barré, couleur du texte, surlignage, listes à puces / numérotées / de tâches, alignement, liens, images et fichiers (bouton, coller ou glisser-déposer, ≤ 100 Mo), citations, blocs de code, séparateurs. ⌘/Ctrl + Entrée pour enregistrer ou envoyer. Les anciens textes et les imports Trello en Markdown sont affichés mis en forme et convertis à la première modification.
  Sécurité : HTML nettoyé à l’écriture côté serveur (Symfony HtmlSanitizer, styles limités à couleur / fond / alignement) et à l’affichage (DOMPurify) ; type des fichiers détecté sur leur contenu, servis avec `nosniff` et une CSP stricte.
- **Pièces jointes** : tout type de fichier (100 Mo max) ajouté par le bouton « Pièce jointe », en glissant des fichiers sur la carte ouverte ou directement sur une carte du tableau (barre de progression). Les fichiers et images postés dans un commentaire sont ajoutés automatiquement à la liste, avec un lien vers le commentaire. Aperçu des images, badge de type, renommage, téléchargement sous le nom d’origine (accents conservés), compteur 📎 sur les cartes, historique dans l’activité.
- **Import Trello avec les fichiers** : dans la fenêtre d’import, cochez « Copier les pièces jointes dans Taskora » et collez une clé d’API et un jeton Trello **en lecture seule** (la fenêtre explique comment les créer en 2 minutes). Les fichiers déposés sur Trello deviennent des pièces jointes des cartes (auteur et date d’origine, couvertures retrouvées), les liens vers ces fichiers et vers les autres cartes du tableau dans les descriptions et commentaires pointent vers Taskora. La copie tourne en arrière-plan avec une barre de progression sur le tableau (pause / reprise, nouvel essai des échecs, ou « Laisser sur Trello »). La clé et le jeton restent dans la mémoire de l’onglet : ils ne sont jamais enregistrés côté serveur.
- **Archives** : « Archiver la carte » (menu « ⋯ » d’une carte) ou « Archiver la liste » (menu d’une liste) retire la carte ou la liste entière du tableau (compteurs, filtres et recherches l’ignorent) sans la supprimer. Tout se retrouve dans le menu du tableau (icône archive ou Paramètres → Archives) : listes archivées (restauration à la fin du tableau avec leurs cartes, suppression définitive), cartes archivées (recherche par titre, description ou #numéro, ouverture, restauration en bas de leur liste, suppression définitive). Les cartes et listes archivées dans Trello sont importées archivées. Supprimer une liste supprime aussi les fichiers de ses cartes sur S3.
- **Corbeille** : supprimer un tableau, une liste ou une carte les place dans une corbeille pendant 30 jours. Les listes et cartes se restaurent depuis Paramètres → Corbeille du tableau (suppression définitive réservée aux administrateurs), les tableaux depuis le bouton « Corbeille » de l’accueil (propriétaire). Passé 30 jours, l’API les efface définitivement avec leurs fichiers S3 (`app:trash:purge`, lancé toutes les 6 h par le conteneur `api`).
- **Notifications du navigateur (Web Push)** : Profil → « Activer les notifications » (ou le bandeau du panneau 🔔). Les assignations, mentions, commentaires, échéances et déplacements arrivent en bannière même Taskora fermé ; un clic ouvre la carte. Liste des appareils notifiés, bouton de test, désabonnement à la déconnexion. Aucun service tiers : clés VAPID dans `backend/.env` (`app:push:keys` pour en générer), envoi après la réponse HTTP, appareils expirés supprimés automatiquement. HTTPS obligatoire en production ; sur iPhone/iPad, ajouter Taskora à l’écran d’accueil (manifeste fourni). Prévu pour accueillir plus tard les mobiles (FCM).
- **Couverture image** : n’importe quelle image jointe peut servir de couverture (bouton sur la pièce jointe, dans la visionneuse ou le menu Couverture, qui permet aussi d’importer une image). Une miniature WebP de 640 px est générée pour que le tableau reste léger.
- **Visionneuses intégrées** (clic sur une pièce jointe, ← → pour naviguer, Échap pour fermer) : images, PDF, Word / PowerPoint / Writer / Impress (DOC, DOCX, ODT, RTF, PPT, PPTX, ODP… convertis en PDF par le service `converter`), Excel / Calc / CSV (onglets de feuilles, tableau, ou vue « Mise en page »), texte et code, JSON (arbre repliable), e-mails EML (en-têtes, pièces jointes, HTML nettoyé dans un cadre isolé sans scripts, images distantes bloquées par défaut).
  Le service `converter` (Gotenberg / LibreOffice) n’est joignable que par l’API, sur un réseau Docker interne sans accès à Internet ni à la base ; chaque document n’est converti qu’une fois (PDF conservé sur S3).
  Sécurité : seuls les images (PNG/JPEG/GIF/WebP, contenu vérifié) et les PDF s’affichent dans le navigateur ; tout le reste (HTML, SVG, scripts, faux « .png »…) est forcé en téléchargement (`Content-Disposition: attachment`, `application/octet-stream`). Supprimer une carte supprime ses fichiers sur S3.
- **Champs personnalisés** par tableau : texte, nombre, date, case à cocher, liste déroulante (options colorées), lien ; affichables sur l’aperçu de la carte.
- **Membres** : invitation par e-mail (ajout immédiat si le compte existe, sinon invitation en attente consommée à l’inscription), rôles propriétaire / admin / membre.
- **Notifications** : ajout à un tableau, assignation, commentaire / mention, déplacement de carte, échéance. Badge non lu, marquer lu / tout lire, actualisation toutes les 15 s.
- **Filtres** (panneau « Filtre », appliqués côté serveur) : mot-clé (titre, description, étiquettes, membres, valeurs de champs), membres (aucun / moi / choisis), statut (terminé / non terminé), date limite (aucune, en retard, 24 h, semaine, mois), étiquettes (aucune / choisies), champs personnalisés liste et case à cocher. Logique « OU » dans une section, « ET » entre sections.
- Scroll horizontal au touchpad (ou Shift + molette) n’importe où sur le tableau, thème clair / sombre, responsive.

## Numéros de cartes & import Trello

- Chaque carte a un **numéro unique dans son tableau** (`#12`), attribué de façon atomique, jamais réutilisé (même après suppression). Il s’affiche devant le titre (désactivable dans Paramètres → Général), dans la fiche carte (clic = copie du lien) et se recherche avec le filtre mot-clé (`#12` ou `12`).
- **Import Trello** : bouton « Importer depuis Trello » sur le tableau de bord (export JSON : menu du tableau Trello → Imprimer, exporter et partager → Exporter au format JSON), ou en ligne de commande :

```bash
docker compose exec api php bin/console app:import-trello /chemin/export.json vous@email.com
```

Conservés : listes et cartes ouvertes **avec leur numéro Trello** (la numérotation continue ensuite après le plus grand numéro), ordre, descriptions, échéances, statut terminé, couvertures, étiquettes, checklists, commentaires (auteur et date d’origine, badge « Trello »), **historique d’activité** (création, déplacements, membres, pièces jointes, terminé, échéances, renommages), date de création, pièces jointes sous forme de liens.
Limites de l’export Trello : pas d’e-mails des membres (rattachés seulement si leur nom correspond à un utilisateur Taskora connu), historique limité aux 1000 dernières actions (des commentaires anciens peuvent manquer), fichiers joints hébergés chez Trello (liens). Les listes et cartes archivées ne sont pas importées.

## Performance (tableaux volumineux)

La vue kanban reste fluide avec des milliers de cartes :

- **Virtualisation des colonnes** : seules les colonnes visibles (+3 de chaque côté) existent dans le DOM ; des espaceurs conservent la largeur de défilement. Le DOM reste constant (~4 000 nœuds) même avec des centaines de colonnes. Les cartes des colonnes éloignées (au-delà de 15 colonnes) sont libérées de la mémoire et rechargées au retour.
- **Navigation** : sélecteur « colonnes 150–154 / 300 » pour sauter à une colonne par son nom, et action « Déplacer en position… » pour déplacer une colonne loin sans la faire glisser.
- **Lazy loading horizontal** : `GET /api/boards/{id}` ne renvoie que les colonnes et leurs compteurs. Les cartes d’une colonne sont chargées quand elle approche de l’écran (IntersectionObserver, marge de 600 px). Les colonnes révélées en même temps partagent une seule requête (`GET /api/boards/{id}/cards?lists=…`).
- **Scroll infini vertical** : 30 cartes par page (`GET /api/lists/{id}/cards?offset=…`), page suivante 400 px avant la fin de la colonne.
- **Filtres côté serveur** (recherche, « mes cartes », étiquette) : les totaux restent justes même si tout n’est pas chargé.
- **Rendu** : une carte = un composant `OnPush` à inputs signals, mises à jour immuables (seule la carte modifiée est re-rendue), `content-visibility: auto` sur les cartes hors écran, application zoneless.
- **SQL** : pages hydratées en 1 requête (assignés, étiquettes, champs perso) + 2 requêtes groupées pour les compteurs (fini le N+1), index `card(list_id, position)`, déplacement d’une carte en 2 `UPDATE` groupés quelle que soit la taille de la liste.

Générer un tableau de test (ex. `--lists=300 --cards=9000` pour des centaines de colonnes) :

```bash
docker compose exec api php bin/console app:seed-demo vous@mail.com --lists=12 --cards=6000
```

## Stockage des fichiers sur Amazon S3

Les images des descriptions et commentaires et les pièces jointes sont envoyées sur S3. Le bucket reste **privé** : l’URL enregistrée est toujours `/uploads/AAAA/MM/<aléatoire>/<nom>`, et l’API répond par une redirection vers une URL S3 **signée valable 1 h** (ou vers `AWS_S3_PUBLIC_URL` si vous servez le bucket par CloudFront). Changer de stockage ne modifie donc aucun contenu.

1. Renseigner `backend/.env` (modèle : `backend/.env.dist`, fichier ignoré par Git et exclu de l’image Docker ; le conteneur `api` le lit via `env_file`) :

```dotenv
STORAGE_DRIVER=s3
AWS_S3_BUCKET=mon-bucket-taskora
AWS_S3_REGION=eu-west-3
AWS_ACCESS_KEY_ID=AKIA...
AWS_SECRET_ACCESS_KEY=...
AWS_S3_PREFIX=taskora/        # optionnel
```

2. Redémarrer l’API puis vérifier (écriture, lecture, URL signée, suppression d’un fichier test) :

```bash
docker compose up -d api
docker compose exec api php bin/console app:storage:check
```

3. Copier sur S3 les images déjà envoyées en local (les URL ne changent pas ; `--delete-local` pour supprimer les copies locales ensuite) :

```bash
docker compose exec api php bin/console app:storage:migrate
```

Droits IAM minimaux pour la clé (remplacer le bucket / préfixe) :

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"],
    "Resource": "arn:aws:s3:::mon-bucket-taskora/taskora/*"
  }, {
    "Effect": "Allow",
    "Action": "s3:ListBucket",
    "Resource": "arn:aws:s3:::mon-bucket-taskora"
  }]
}
```

Le bucket peut garder « Bloquer tout accès public » activé ; aucune configuration CORS n’est nécessaire (les images sont chargées par des balises `<img>`). Les objets sont chiffrés côté serveur (SSE-S3). Sans `AWS_ACCESS_KEY_ID`, la chaîne d’identifiants AWS par défaut est utilisée (rôle IAM sur EC2/ECS). `AWS_S3_ENDPOINT` permet d’utiliser un service compatible S3 (Scaleway, OVH, Cloudflare R2…).

## Comptes de démonstration (environnement local)

Créés pendant les tests, présents tant que le volume `db_data` existe (`docker compose down -v` les efface) :

| Compte | Mot de passe | Tableaux |
|---|---|---|
| `alice12934@test.io` | `password123` | Sprint 1 (propriétaire), Démo charge 6000 cartes / 12 colonnes, Démo charge 9000 cartes / 300 colonnes |
| `bob12934@test.io` | `password123` | Sprint 1 (membre) |

Pour générer un tableau volumineux sur votre propre compte :

```bash
docker compose exec api php bin/console app:seed-demo votre@email.com --lists=300 --cards=9000
```

## Développement local (hors Docker)

Backend (PHP 8.4.1+) :

```bash
cd backend && composer install
docker compose up -d database
php bin/console lexik:jwt:generate-keypair --skip-if-exists
php bin/console doctrine:migrations:migrate -n
symfony serve --port=8081
```

Frontend (Node ≥ 22.22 ou ≥ 24.15, requis par Angular 22) :

```bash
cd frontend && npm install && npx ng serve   # proxy /api → localhost:8081
```

## API (extrait)

`POST /api/auth/register` · `POST /api/auth/login` · `GET|PATCH /api/me`
`GET|POST /api/boards` · `GET|PATCH|DELETE /api/boards/{id}` · `POST /api/boards/{id}/favorite`
`POST /api/boards/{id}/members` · `PATCH|DELETE /api/boards/{id}/members/{userId}`
`POST /api/boards/{id}/lists` · `POST /api/boards/{id}/lists/reorder` · `PATCH|DELETE /api/lists/{id}`
`GET /api/boards/{id}/cards?lists=1,2&limit=30&q=&mine=1&noMember=1&members=&noLabel=1&labels=&status=done|open&due=none,overdue,day,week,month&cf[fieldId]=…` · `GET|POST /api/lists/{id}/cards` · `GET|PATCH|DELETE /api/cards/{id}` · `POST /api/cards/{id}/move`
`POST /api/cards/{id}/assignees` · `POST /api/cards/{id}/comments` · `POST /api/cards/{id}/checklist`
`POST /api/boards/{id}/custom-fields` · `PATCH|DELETE /api/custom-fields/{id}` · `PUT /api/cards/{id}/custom-fields/{fieldId}`
`GET /api/notifications` · `POST /api/notifications/{id}/read` · `POST /api/notifications/read-all`
