# Spécifications Taskora : index et conventions

Auteur : Paul (test planner). Source de vérité : comportement du backend Symfony (`backend/`) et du frontend Angular (`frontend/`) au 26/11/2026, tant qu'une spec ne le remplace pas.
Objectif : permettre la migration vers les microservices Spring Boot (`docs/architecture/backend-hexagonal.md`) **sans régression fonctionnelle ni rupture de contrat HTTP**.

## Fichiers

| Fichier | Domaine | Préfixe des scénarios | Service cible |
|---|---|---|---|
| [identity.md](identity.md) | Authentification, inscription, JWT, profil | `SC-IDENT-` | identity-service |
| [boards-members.md](boards-members.md) | Tableaux, favoris, membres, invitations | `SC-BOARD-` | board-service |
| [lists.md](lists.md) | Listes (colonnes) | `SC-LIST-` | board-service |
| [cards.md](cards.md) | Cartes, numéros, déplacement, étiquettes, checklist, champs personnalisés, assignation, échéance, priorité, couverture, filtres | `SC-CARD-` | board-service |
| [comments-mentions.md](comments-mentions.md) | Commentaires, @mentions, texte riche | `SC-COMMENT-` | board-service |
| [activity.md](activity.md) | Historique des cartes | `SC-ACTIVITY-` | board-service |
| [archives-trash.md](archives-trash.md) | Archives et corbeille (30 jours, purge) | `SC-ARCHIVE-`, `SC-TRASH-` | board-service (+ attachment-service pour les fichiers) |
| [attachments.md](attachments.md) | Pièces jointes, uploads, `/uploads/…`, miniatures, aperçus, visionneuses | `SC-ATTACH-` | attachment-service |
| [notifications-push.md](notifications-push.md) | Notifications internes, Web Push | `SC-NOTIF-`, `SC-PUSH-` | notification-service |
| [trello-import.md](trello-import.md) | Import Trello et transfert des fichiers | `SC-IMPORT-` | import-service |
| [pages.md](pages.md) | Pages et vues du frontend | — | FrontPierre |
| [traceability.md](traceability.md) | Matrice scénario → test → statut | — | tous |
| [phase-1-identity.md](phase-1-identity.md) | Plan d'exécution de la migration d'identity-service | — | BackAli, FrontPierre, Manuel |
| [phase-3-board-service.md](phase-3-board-service.md) | Migration de board-service : périmètre, tranches T0–T5, données, autorisation, scénarios de cohabitation, questions ouvertes | `SC-BOARD-020+`, `SC-LIST-011+`, `SC-CARD-032+`, `SC-COMMENT-013+`, `SC-ACTIVITY-008`, `SC-TRASH-011+` | board-service |

## Conventions communes

### Format d'un scénario
```
### SC-<DOMAINE>-<NNN> — Titre (critique | important | secondaire)
Étant donné …
Quand …
Alors …
Limites : …
Validation : <types> — Acceptation : <critère vérifiable>
```
- **Identifiant stable** : il ne change jamais, même si le scénario est réécrit. Un scénario abandonné est marqué `ABANDONNÉ`, son numéro n'est pas réutilisé.
- Le `@DisplayName` (JUnit) ou le titre du `it()` / `test()` (Angular, Playwright) **commence par l'identifiant**, par exemple `@DisplayName("SC-IDENT-001 — Inscription nominale")`.
- **Priorités** : `critique` (bloque la bascule, test obligatoire avant merge), `important` (requis avant la bascule du service), `secondaire` (souhaitable, peut suivre).

### Types de validation et pôle responsable
| Type | Ce qu'il prouve | Pôle (auteur) | Outils |
|---|---|---|---|
| unitaire | Règle du domaine / cas d'utilisation isolé | backend (BackTesteur) ; frontend (FrontTesteur) pour la logique TS | JUnit 5, AssertJ, Mockito ; Vitest/Jasmine |
| intégration | Persistance, migrations Flyway, RabbitMQ, S3 (LocalStack), Gotenberg | backend (BackTesteur) | Testcontainers |
| API | Contrat HTTP : route, statut, corps, en-têtes, conformité OpenAPI | backend (BackTesteur) | MockMvc / RestAssured + validation OpenAPI |
| sécurité | Accès refusé, IDOR, JWT invalide, injection, SSRF, XSS | backend (BackTesteur) ; frontend pour XSS d'affichage | Spring Security Test, OWASP Dependency-Check |
| compatibilité | Même réponse que Symfony pour la même requête (tests « miroir ») | backend (BackTesteur) | RestAssured contre les deux backends |
| composant | Composant ou service Angular isolé | frontend (FrontTesteur) | TestBed |
| e2e | Parcours utilisateur complet dans le navigateur | frontend (FrontTesteur) | Playwright (`frontend/e2e/`) |
| CI | Le job tourne et bloque en cas d'échec | DevOps (Manuel) | GitHub Actions |

### Règles HTTP transverses (contrat actuel à conserver)
- Toutes les routes métier sont sous `/api`. Les fichiers sont servis sous `/uploads/<clé>` (hors `/api`, voir attachments.md).
- Corps JSON. Un corps non vide qui n'est pas un objet ou tableau JSON → **400** `{"error":"JSON invalide."}`. Un corps vide équivaut à `{}`.
- **Format d'erreur** : `{"error": "<message en français>"}` pour toute erreur sous `/api` (ApiExceptionListener). Les 5xx renvoient `"Erreur interne du serveur."` hors mode debug.
  Exceptions : les erreurs d'authentification émises par LexikJWT (401) ont la forme `{"code":401,"message":"…"}` (HYPOTHÈSE: forme par défaut du bundle, à confirmer par un test miroir).
- Champs texte obligatoires (`requireString`) : la valeur est `trim`, vide → 400 `Le champ "<nom>" est obligatoire.` ; une valeur trop longue est **tronquée silencieusement** à la longueur maximale (pas d'erreur).
- Identifiants : entiers auto-incrémentés exposés tels quels. Les dates sont en ISO 8601 avec fuseau (`DateTimeInterface::ATOM`, ex. `2026-11-26T10:00:00+00:00`).
- Contrôle d'accès à un tableau (`BoardAccess`) :
  - tableau inexistant → **404** `Tableau introuvable.` ; tableau dans la corbeille → **404** `Ce tableau est dans la corbeille.` ;
  - utilisateur non membre → **403** `Vous n'êtes pas membre de ce tableau.` ;
  - action réservée aux gestionnaires (propriétaire ou admin) → **403** `Action réservée aux administrateurs du tableau.`
- Ressource dans la corbeille (directement ou via sa liste / son tableau) → **404** `Cet élément est dans la corbeille.` ; ressource inexistante → **404** `Ressource introuvable.`
- Suppression réussie → **204** sans corps. Création → **201**.

## Registre des écarts et décisions à prendre

Comportements actuels discutables. **Par défaut, la migration les conserve à l'identique** (compatibilité). Toute correction est une décision de l'utilisateur, publiée dans `docs/contracts/` avant d'être implémentée.

| # | Comportement actuel | Risque | Proposition | Scénarios |
|---|---|---|---|---|
| E1 | Non-membre d'un tableau existant → **403** (le message révèle l'existence du tableau). Le modèle de `workflow.md` suggère 404. | Énumération des identifiants de tableaux (faible : ids séquentiels) | **Décidé : 404** (voir decisions.md) | SC-BOARD-006, SC-CARD-030 |
| E2 | Supprimer le commentaire d'un autre (non gestionnaire) → **400** au lieu de 403 | Sémantique HTTP | Garder 400 en phase de migration, corriger ensuite (403) | SC-COMMENT-008 |
| E3 | Renommer/supprimer la pièce jointe d'un autre (non gestionnaire) → **400** | idem | idem | SC-ATTACH-012 |
| E4 | Un simple membre peut modifier titre, description, fond et options d'un tableau, créer/supprimer listes, étiquettes et champs personnalisés | Droits larges | **Confirmé** : règle métier conservée (voir decisions.md) | SC-BOARD-010 |
| E5 | `/uploads/<clé>` est **public** (pas de JWT) : la sécurité repose sur la clé aléatoire de 128 bits | URL partageable hors tableau | Conserver (les balises `<img>` ne portent pas de jeton) | SC-ATTACH-015 |
| E6 | `POST /api/uploads` (images du texte riche) n'est rattaché à aucun tableau : tout utilisateur connecté peut téléverser | Stockage abusif | Conserver ; quota/limitation à étudier | SC-ATTACH-004 |
| E7 | Archiver une carte/liste déjà archivée → **200** idempotent (pas 409) | — | Conserver (le modèle de `workflow.md` cite 409 à titre d'exemple seulement) | SC-ARCHIVE-002 |
| E8 | `dueDate` invalide (ex. `"abc"`) → exception PHP → **500** | Erreur serveur sur entrée client | Corriger en **400** `Date invalide.` (écart toléré) — décision utilisateur | SC-CARD-014 |
| E9 | Pas de limitation de débit sur `/api/auth/login` | Force brute | Ajouter une limitation à la gateway (429) — décision utilisateur | SC-IDENT-027 |
| E10 | Changer de mot de passe n'invalide pas les JWT déjà émis (7 jours, sans révocation) | Session volée persistante | Conserver en phase 1 ; révocation à étudier | SC-IDENT-021 |
| E11 | Supprimer un commentaire supprime en cascade ses pièces jointes en base, mais **pas** leurs fichiers sur S3 | Objets orphelins (coût) | Conserver ; nettoyage asynchrone à étudier | SC-COMMENT-009 |
| E12 | Les routes `/api/cards/{id}`, `/api/lists/{id}`… n'ont pas de contrainte `\d+` : un id non numérique donne une erreur de conversion | Statut indéterminé | Cible : **404** pour tout id non numérique | SC-CARD-031 |
| E14 | Import Trello : une carte sans `idShort` reçoit `max+1` mais `card_seq` reste à `max` → la prochaine carte créée entre en collision (500) | Bug | **Corrigé** (voir decisions.md) | SC-IMPORT-002 |
| E13 | Les invitations en attente n'expirent jamais et un utilisateur existant est ajouté sans consentement | Métier | Conserver — à confirmer | SC-BOARD-013 |
