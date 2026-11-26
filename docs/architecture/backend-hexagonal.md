# Architecture cible du backend — Spring Boot hexagonal, microservices

Statut : **proposée** (à valider par l'équipe). Elle remplace le backend Symfony actuel (`backend/`).
Responsable du document : BackAli. Les spécifications fonctionnelles viennent de Paul (`docs/specs/`).

## 1. Socle technique

| Élément | Choix | Remarque |
|---|---|---|
| Langage | Java 25 (LTS) | Build et exécution dans Docker (`eclipse-temurin:25`) |
| Framework | Spring Boot 4.2.x | Version la plus récente sur Maven Central au 26/11/2026 |
| Spring Cloud | 2026.0.x (Gateway, Config si besoin) | Compatibilité avec Boot 4.2 à vérifier par BackAli avant de figer |
| Build | Maven multi-modules (`services/<nom>/pom.xml`) | Parent commun `services/pom.xml` |
| API REST | Spring Web MVC pour les commandes, **Spring Data REST** pour les lectures (voir §4) | |
| Persistance | Spring Data JPA, MariaDB 11.8 : une base (schéma) par service | Migrations Flyway par service |
| Messagerie | RabbitMQ (Spring AMQP), événements de domaine | Outbox transactionnelle pour la fiabilité |
| Sécurité | Spring Security, JWT (resource server) validé par la gateway **et** chaque service | |
| Stockage fichiers | S3 (AWS SDK v2), URLs présignées | Repris à l'identique du backend Symfony |
| Conversion Office → PDF | Gotenberg 8 (réseau Docker interne) | Inchangé |
| Web Push | VAPID (bibliothèque `nl.martijndwars:web-push` ou équivalent maintenu) | Clés VAPID existantes conservées |

## 2. Découpage en microservices

Le découpage suit les contextes métier déjà présents dans le code Symfony.

| Service | Responsabilités (repris de) | Données propres |
|---|---|---|
| `gateway` | Point d'entrée unique `/api/**`, routage, validation JWT, limitation de débit, CORS | aucune |
| `identity-service` | Inscription, connexion, JWT, profil (`AuthController`, `User`) | users |
| `board-service` | Tableaux, membres, invitations, listes, cartes, étiquettes, checklists, champs personnalisés, commentaires, mentions, activité, archives, corbeille (`Board*`, `List*`, `Card*`, `CustomField*`, `TrashController`, `ActivityLogger`, `BoardAccess`, `Trash`) | boards, lists, cards, comments, activities… |
| `attachment-service` | Upload (100 Mo), S3, miniatures, aperçus Gotenberg, flux de lecture, couverture (`AttachmentController`, `UploadController`, `Uploads`, `DocumentPreview`, `CommentAttachments`) | attachments |
| `notification-service` | Notifications internes, abonnements et envoi Web Push (`NotificationController`, `PushController`, `Notifier`, `PushSender`) | notifications, push_subscriptions |
| `import-service` | Import Trello et transfert des pièces jointes (`ImportController`, `TrelloFilesController`, `TrelloImporter`, `TrelloFileTransfer`) | trello_import_files |

Communication :
- **Synchrone** (REST interne, via le réseau Docker) uniquement pour les lectures indispensables, par exemple le contrôle d'accès à un tableau depuis `attachment-service`.
- **Asynchrone** (RabbitMQ) pour tout le reste. Événements principaux : `CardAssigned`, `CommentPosted`, `MemberMentioned`, `CardMoved`, `BoardTrashed`, `BoardPurged`, `AttachmentUploaded`, `TrelloImportCompleted`.
- Chaque service possède ses données ; aucun service ne lit la base d'un autre.

## 3. Structure hexagonale d'un service

```
services/board-service/src/main/java/com/taskora/board/
├── domain/                  # Cœur métier, Java pur (aucune dépendance Spring/JPA)
│   ├── model/               # Agrégats, entités, value objects
│   ├── event/               # Événements de domaine
│   └── exception/
├── application/
│   ├── port/in/             # Cas d'utilisation (interfaces) : ArchiveCardUseCase…
│   ├── port/out/            # Ports sortants : CardRepositoryPort, EventPublisherPort…
│   └── service/             # Implémentations des cas d'utilisation
├── adapter/
│   ├── in/web/              # Contrôleurs REST (commandes), DTO, mappers
│   ├── in/messaging/        # Consommateurs RabbitMQ
│   ├── out/persistence/     # Entités JPA, repositories Spring Data, mappers, ressources Spring Data REST
│   └── out/messaging/       # Publication d'événements, outbox
└── config/                  # Assemblage Spring (beans, sécurité)
```

Règles imposées (vérifiées par des tests **ArchUnit** écrits par BackTesteur) :
1. `domain` ne dépend d'aucun autre paquet ni d'aucun framework.
2. `application` ne dépend que de `domain`.
3. Les `adapter` dépendent de `application` (ports), jamais l'inverse ; aucun adapter n'en appelle un autre directement.
4. Les entités JPA ne sortent jamais de `adapter.out.persistence` ; elles sont converties en modèle de domaine.
5. Aucun cycle entre paquets.

## 4. Place de Spring Data REST dans l'hexagone

Spring Data REST expose directement des repositories, ce qui contourne les cas d'utilisation. Pour respecter l'hexagone :
- Il est utilisé **dans `adapter/out/persistence` (ou un module `adapter/in/rest-query`) pour les lectures** : collections paginées, projections, recherche.
- Les méthodes d'écriture des repositories exportés sont désactivées (`@RestResource(exported = false)` sur `save`, `delete`…) ou les repositories exportés sont en lecture seule.
- Toute **écriture** passe par un contrôleur `adapter/in/web` qui appelle un cas d'utilisation (règles métier, droits, événements).
- La sécurité s'applique aussi aux ressources Spring Data REST : filtrage par appartenance au tableau, et pas d'exposition des identifiants internes.

## 5. Qualité et tests

| Vérification | Outil | Responsable |
|---|---|---|
| Tests unitaires du domaine et des cas d'utilisation | JUnit 5, AssertJ, Mockito | BackTesteur |
| Tests d'architecture | ArchUnit | BackTesteur |
| Tests d'intégration (base, RabbitMQ, S3) | Testcontainers (MariaDB, RabbitMQ, LocalStack) | BackTesteur |
| Tests d'API | MockMvc / RestAssured, contrat OpenAPI | BackTesteur |
| Tests de sécurité | Spring Security Test (accès refusé, IDOR, JWT invalide), OWASP Dependency-Check | BackTesteur |
| Formatage | Spotless + Google Java Format | BackReviewer |
| Style | Checkstyle (règles Google) | BackReviewer |
| Nullité et typage | Checker Framework (Nullness Checker) | BackReviewer |
| Refactorings automatiques et migrations | OpenRewrite (`rewrite:dryRun`) | BackReviewer |
| Analyse statique | SonarQube (moteur de SonarLint) via `sonar-maven-plugin` ; SonarLint lui-même est un plugin d'IDE sans CLI | BackReviewer |

## 6. Migration depuis Symfony

Le backend Symfony reste en service jusqu'à la bascule. Ordre proposé : `identity-service` → `board-service` → `attachment-service` → `notification-service` → `import-service` → `gateway` et bascule du frontend.

Pour chaque service :
- **Mêmes routes `/api/...`** que le backend actuel, sauf décision contraire publiée dans `docs/contracts/`.
- **Reprise des données** : script de migration depuis la base MariaDB actuelle.
- **Clés conservées** : mêmes clés S3 et mêmes clés VAPID.
