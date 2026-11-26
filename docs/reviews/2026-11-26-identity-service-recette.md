# Recette BackAli — identity-service, phase 1

- Date : 26/11/2026
- Auteur : BackAli (chef du pôle backend), étape 6 du circuit (`docs/team/workflow.md`)
- Périmètre : fusion `86b3b07` dans `main` (PR #1, branche `feat/identity-service`) ; recette faite sur `chore/identity-phase1-acceptance`, coupée de cette fusion
- Références :
  - ordre de travail `docs/specs/phase-1-identity-workorder.md` ;
  - boucle de correction `docs/specs/phase-1-identity-review-fixes.md` ;
  - ADR A1–A18 `docs/architecture/backend-hexagonal.md` §9 ;
  - revues BackReviewer `2026-11-26-identity-service.md` (REFUSÉ), `-r2.md` (REFUSÉ), `-r3.md` (**ACCEPTÉ**) ;
  - bascule et conditions C1–C4 `docs/architecture/identity-migration.md` §5.
- Aucun secret, hash, jeton, valeur de `.env` ni donnée personnelle dans ce rapport.

## 1. Verdict : **ACCEPTÉ**

La phase 1 d'identity-service est réceptionnée côté backend :
- tous les livrables de l'ordre de travail et de la boucle de correction sont présents ;
- la dernière revue (r3) est sans point bloquant ;
- l'intégrité des tests est respectée ;
- les 14 scénarios critiques sont couverts par des tests verts ;
- les ADR A1–A18 sont respectées dans le code relu ;
- la CI de la PR #1 est verte.

**Ce verdict n'autorise pas la bascule.** La bascule (identity-migration.md §5) reste soumise à :
- **C3** et **C4**, qui attendent une action de l'utilisateur (§4) ;
- la répétition du retour arrière (§6.3), à planifier avec Paul (§6).

## 2. Preuves

### 2.1 CI de la PR #1 (fusion `86b3b07`)
Constat transmis par le coordinateur. `gh` n'est pas installé sur ce poste : je n'ai pas réinterrogé GitHub moi-même.

| Workflow | Jobs | Résultat |
|---|---|---|
| `identity-service` | build + tests (`verify`), qualité (`-Pquality`), image | ✅ vert |
| `identity-service` | `identity-sonar` | ⏭️ sauté : `SONAR_TOKEN` et `SONAR_HOST_URL` ne sont pas configurés (C4) |
| `identity-compat` | `MirrorCompatibilityTest`, `CrossTokenCompatibilityTest`, `CrossPasswordCompatibilityTest`, `MirrorScopeTest` | ✅ 32/32 |
| `identity-security` | OWASP Dependency-Check 13 | ✅ aucune dépendance CVSS ≥ 7 |
| `backend` | PHPUnit (`backend-phpunit`) | ✅ vert |

### 2.2 Exécution locale de contrôle
- Commande, depuis `services/`, dans `eclipse-temurin:25-jdk` avec le socket Docker pour Testcontainers : `./mvnw -B -ntp -pl identity-service -am clean verify`. Build de tests seul, sans `-Pquality` (A12).
- Résultat : **BUILD SUCCESS** en 3 min 32 s.
  - Surefire : **596/596** ;
  - Failsafe : **250/250** ;
  - aucun test sauté ;
  - « All coverage checks have been met » ;
  - aucune `SQLTimeoutException`.
- Ces chiffres sont identiques à ceux de la revue r3.
- Le compat n'a pas été relancé (déjà prouvé en CI), et la stack `taskora` n'a été ni démarrée ni arrêtée.

### 2.3 Intégrité des tests
- **Référence** : `6e617bb` (BackTesteur, tests R1), retenue par la revue r3.
- **Tests de production depuis la référence** :
  - `git diff 6e617bb..86b3b07` sur `services/identity-service/src/test`, `backend/tests`, `backend/phpunit.dist.xml`, `backend/.env.test`, `services/pom.xml` et `services/identity-service/pom.xml` : un seul commit, `71cebca` (§3, N1) ;
  - aucun POM touché.
- **Code de production** : `git diff 5a61a74..86b3b07` sur `services/identity-service/src/main`, `backend/src` et les deux `Dockerfile` est **vide**. Le code fusionné est donc exactement celui que la revue r3 a accepté.
- **Affaiblissements** : aucun `@Disabled`, `assume*`, `markTestSkipped`, `NOSONAR`, `CHECKSTYLE.OFF`, ni `@SuppressWarnings("checkstyle…"|"nullness")` dans `services/identity-service/src` et `backend/tests`.
- **Commits CI** postérieurs à r3, tous deux sans effet sur un seuil ou une porte :
  - `ab7691e` : `actions/checkout` avant `dorny/test-reporter` ;
  - `18832a6` : permission `actions: read` et `continue-on-error` sur le **seul** téléversement SARIF. Le seuil CVSS reste appliqué par l'étape Dependency-Check.

### 2.4 Ordre de travail (`phase-1-identity-workorder.md`)
| § | Livrable | Constat |
|---|---|---|
| 2.2 | Parent Maven, module, wrapper, profils `quality`, `security-scan`, `compat`, `sonar` | présents ; seuils JaCoCo et Surefire/Failsafe inchangés depuis la référence |
| 2.3 | Squelette hexagonal (domaine, ports in/out, services) | complet ; conforme à A6 (liste des classes de `src/main` relue) |
| 2.4 | Tests unitaires, API, intégration, sécurité, `compat` | présents ; traçabilité SC-IDENT-001…036 dans `docs/specs/traceability.md` |
| 2.5 | PHPUnit de l'endpoint transitoire | `backend/tests/Controller/InternalInvitationControllerTest.php` ; vert en CI |
| 3.1–3.2 | Domaine, application, adapters (web, sécurité, scheduling, cli, persistance, legacy, config) | tous présents (§2.6) |
| 3.3 | Symfony : `InvitationClaimer`, `InternalInvitationController` (`hash_equals`, secret vide → refus), `ApiExceptionListener` étendu à `/internal`, `INTERNAL_API_SECRET` vide dans `backend/.env.dist` | présents |
| 3.4 | `application.yml` (A13), `Dockerfile` multi-étapes non root avec healthcheck | présents ; images de base épinglées par digest |
| 4 | Revue BackReviewer | 3 passages ; le dernier est ACCEPTÉ, sans bloquant |

### 2.5 Boucle de correction (`phase-1-identity-review-fixes.md`)
| Point | Objet | Constat |
|---|---|---|
| B1 | Spotless avec gjf 2.0.0 | r3 : 0 écart |
| B2 | Checkstyle, exception A17 seulement | r3 : 0 violation |
| B3 | Nullness | r3 : 0 erreur |
| B4 | OpenRewrite | r3 : « would make no changes » |
| B5 | `NVD_API_KEY` obligatoire en CI | le job OWASP a tourné et il est vert |
| B6 | Exclusions Sonar `e1`–`e3` seulement (A16) | r3 : vérifié, 0 problème ouvert |
| B7 | `verify` vert de bout en bout | r3 : vert en local ; CI verte (PR #1) |
| m1 | Démarrage refusé sans secret interne | `IdentityProperties`, message `INTERNAL_API_SECRET doit être défini et non vide.` ; SC-IDENT-036 vert |
| m2 | Corps limité à 65 536 octets, 413 | `RequestBodies` (`Content-Length` puis `readNBytes(65 537)`) ; nginx 64k ; SC-IDENT-035 vert |
| m3 | Conflit « même e-mail, autre id » avant l'import | `JdbcLegacyUserSource` ; `LegacyImportIT#targetEmailConflictAborts` |
| m4 | Journaux SQL Hibernate à `OFF` | `application.yml` |
| m5 | Rejeu sur interblocage seulement | `JpaUnitOfWork` : 1213 / `40001`, 3 essais ; `JpaUnitOfWorkTest` 10 cas |
| m6, m7 | Bugs Sonar ; hash factice | hash factice calculé au constructeur de `SymfonyCompatiblePasswordHasher` |
| m8 | Passphrase en `String` | accepté, documenté |
| m11 | Passphrase dans l'historique | devient **C3** (utilisateur) |
| m12 | Symfony 8.1.12 | r2/r3 : `composer audit` vide |
| R1, R2 (r2) | Couverture de `JpaUnitOfWork` et `LegacyImportRunner` ; S5998 | levés en r3 |

### 2.6 ADR A1–A18 (contrôles ciblés dans le code fusionné)
| ADR | Contrôle | Résultat |
|---|---|---|
| A1 | `NimbusTokenVerifier` : expiration d'abord (précédence §8.1), `alg` = RS256 vérifié avant toute opération cryptographique, `Clock` injectée sans tolérance ; filtre maison, pas de `BearerTokenAuthenticationFilter` | ✅ |
| A2 | `SymfonyCompatiblePasswordHasher` : `$2y$` coût configurable, hash factice prêt au démarrage, préfixes argon2 vérifiés | ✅ (golden `php-hashes.json` vert) |
| A3, A7, A8 | id `INT` explicite ; index unique avec la collation `utf8mb4_unicode_ci` ; profil appliqué en une transaction | ✅ (`ConcurrentRegistrationIT`, `MeApiIT#atomic`) |
| A4 | `ApiErrorHandler` unique ; `spring.web.error.*` à `never` | ✅ |
| A5 | aucune dépendance `spring-boot-starter-data-rest`, aucun `@RepositoryRestResource` | ✅ (et règle ArchUnit) |
| A6 | `domain` et `application` sans import `org.springframework` ni `jakarta` | ✅ (grep vide, ArchUnit vert) |
| A9 | projection sans `DELETE` ni `ON DUPLICATE KEY UPDATE`. Le seul ODKU est celui de la reprise (`JdbcLegacyUserSource`, A14/A18), précédé du contrôle de conflit | ✅ |
| A10, A11 | outbox (3 messages), livraison après validation, `SKIP LOCKED`, backoff | ✅ (`OutboxRelayIT`, `RegistrationInvitationClaimIT`) |
| A12 | JaCoCo (domaine 100 %, application 99 %, module 93,2 %) ; builds qualité et tests séparés (`-DskipTests -DskipITs`) | ✅ |
| A13 | toutes les valeurs passent par des variables d'environnement, sans valeur réelle par défaut pour les secrets | ✅ |
| A14 | reprise par une commande ré-exécutable (`LegacyImportRunner`), Flyway limité à V1/V2 | ✅ |
| A15 | `INTENTIONAL_DIVERGENCES` : 7 entrées, une par écart E15…E21, chacune référencée ; rien d'autre | ✅ |
| A16 | exclusions Sonar `e1`–`e3` seulement ; borne de 320 octets avant l'expression régulière | ✅ (r3) |
| A17 | exception Javadoc limitée à `adapter` et `config` | ✅ (r3) |
| A18 | secret obligatoire, 413, contrôle de reprise, journaux SQL à `OFF` | ✅ |

### 2.7 Scénarios
- **Critiques** (001, 002, 003, 005, 007, 008, 010, 011, 012, 014, 017, 018, 023, 024) : tous couverts, statut `vert` dans la traçabilité. Le volet `compat` de 001, 007, 008, 010, 014–018 et 023 est désormais prouvé par `identity-compat` en CI.
- **Importants et secondaires** du back (004, 006, 009, 013, 015, 016, 019–022, 028–036) : verts.
- **Hors périmètre backend** :
  - SC-IDENT-025 et 026 : front, FrontTesteur ;
  - SC-IDENT-027 : hors phase 1 (U3, gateway).

## 3. Constats

### Bloquants
**Aucun.**

### Non bloquants
| Réf. | Constat | Suite |
|---|---|---|
| N1 | **Commit de test après la revue r3** : `71cebca` modifie `MirrorCompatibilityTest` et `MirrorScopeTest` sans revue BackReviewer. Je l'ai relu. La normalisation de `avatarColor` passe de « toute couleur `#[0-9a-f]{6}` sauf `#0ea5e9` » à « couleur de `AvatarColor.PALETTE` seulement ». Le cas SC-IDENT-016 utilise `#123ABC`, hors palette, comparé exactement, et `MirrorScopeTest` interdit désormais une couleur explicite prise dans la palette. Le contrôle est donc **renforcé**, pas affaibli : moins de valeurs normalisées, et une couleur de palette divergente côté Symfony serait toujours comparée exactement. Les palettes Java et PHP (`User.php`) sont identiques. La conservation de la casse reste testée (`#123ABC`, `AvatarColorTest#validKeptAsIs`). | Accepté. **Nouvelle référence d'intégrité : `71cebca`.** Rappel de procédure : tout commit de test postérieur à une revue doit être relu par BackReviewer avant la fusion. |
| N2 | `backend/Dockerfile` : images non épinglées, `dunglas/frankenphp:1-php8.4-alpine` et `composer:2` (étiquettes flottantes, sans digest). Les images d'identity (`eclipse-temurin:25-jdk` et `25-jre`), de mariadb, gotenberg, sonarqube, node et nginx sont épinglées par digest. La mention « `eclipse-temurin` reste à épingler » de `docs/ci/requests/backend.md` (réponse de Manuel, m10) est périmée. | Manuel : épingler les deux images du backend par digest, et mettre la note m10 à jour. |
| N3 | Un 3e interblocage consécutif donne **500** `Erreur interne du serveur.` au lieu de **503** avec `Retry-After` (revue r3 §5). Il n'y a ni écriture partielle ni fuite, et SC-IDENT-002 reste à 0 × 500. | Décision reportée **après la bascule**, selon la métrique d'interblocages. Passer à 503 exigerait d'ajouter la réponse au contrat OpenAPI et un scénario de Paul. |
| N4 | Conflit de reprise : il sort par l'exception de démarrage de Spring (code ≠ 0), sans code dédié. | Amélioration d'exploitation (BackCodeur, plus tard) : code de sortie dédié, sans pile. |
| N5 | `LegacyImportRunner` : 12 branches couvertes sur 13. | Mineur, pas d'action. |
| N6 | `docs/specs/traceability.md` porte encore « tests `compat` non exécutés » pour 001, 007, 008, 010, 014–018 et 023, alors qu'`identity-compat` est vert en CI (32/32). | BackTesteur : mettre ces statuts à jour avec la référence du run. |
| N7 | Condition C1 : le job OWASP est vert, mais le résumé chiffré (nombre de dépendances analysées, CVE éventuelles sous 7) n'est pas encore reporté dans `docs/ci/requests/backend.md`, comme le demande la condition. | Manuel : reporter le résumé à partir de l'artefact du run de la PR #1. |
| N8 | Téléversement SARIF en `continue-on-error` (dépôt privé sans GitHub Advanced Security). Le seuil CVSS reste bloquant ; seul l'affichage dans l'onglet Security est perdu. | Accepté. |

## 4. Conditions préalables à la bascule (identity-migration.md §5)
| Réf. | Statut | Preuve ou action attendue |
|---|---|---|
| **C1** | ✅ **remplie** | `identity-security` vert sur la PR #1 : aucune dépendance CVSS ≥ 7. Résumé chiffré à reporter (N7). |
| **C2** | ✅ **remplie** | `identity-service` (build, tests, qualité, image) et `identity-compat` (32/32 : tests miroir SC-IDENT-001…018 hors E15–E21, tests croisés SC-IDENT-010 et 023) verts sur la PR #1. |
| **C3** | ⏳ **en attente de l'utilisateur** | La passphrase JWT figure dans l'historique git. Il faut régénérer la paire de clés avec une **nouvelle** passphrase partout où l'ancienne a servi, puis annoncer l'invalidation des jetons existants. |
| **C4** | ⏳ **en attente de l'utilisateur** | Créer la variable de dépôt `SONAR_HOST_URL` et le secret `SONAR_TOKEN` sur GitHub, relancer `identity-service`, puis vérifier un quality gate vert avec les seules exclusions `e1`–`e3` (BackReviewer). |

La colonne « Statut » a été ajoutée au tableau des conditions dans `docs/architecture/identity-migration.md`.

## 5. Risques résiduels
- **Clés JWT** : tant que C3 n'est pas faite, quiconque a accès à l'historique du dépôt peut déchiffrer la clé privée, s'il obtient aussi le fichier PEM. Après la régénération, Symfony et identity doivent lire la **même** nouvelle paire (A1). Sinon, les tests croisés SC-IDENT-010 tomberont.
- **Analyse Sonar** : aucune analyse sur un serveur de CI pour l'instant. Seules les analyses locales de BackReviewer (r3, gate vert) existent.
- **Interblocages** : N3 (500 au 3e interblocage). À surveiller pendant les 24 h qui suivent la bascule.
- **Supply chain** : les images flottantes du backend Symfony (N2) peuvent changer d'une construction à l'autre.

## 6. Suites
1. **Utilisateur** : C3 (nouvelle paire de clés et nouvelle passphrase), puis C4 (`SONAR_HOST_URL`, `SONAR_TOKEN`).
2. **Manuel** :
   - N2 : digests du backend ;
   - N7 : résumé OWASP ;
   - après C4 : un run `identity-sonar` vert.
3. **BackTesteur** : N6 (statuts `compat` dans la traçabilité).
4. **Paul et BackAli** : planifier la répétition du retour arrière (identity-migration.md §6.3) sur une copie de la base. C'est un critère de sortie, résultat à consigner dans `docs/reviews/`.
5. **Paul** : validation contre les scénarios (étape 7 du circuit).
6. **Bascule** : seulement quand C1–C4 sont consignées comme remplies et que la répétition est faite.
