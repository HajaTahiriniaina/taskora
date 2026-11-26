# Revue BackReviewer — identity-service, 2e passage (boucle de correction n° 1)

- Date : 26/11/2026
- Branche : `feat/identity-service`
- Revue précédente : `docs/reviews/2026-11-26-identity-service.md` (commit `2a50f3f`, REFUSÉ).
- Liste de contrôle : `docs/specs/phase-1-identity-review-fixes.md` §5.
- Commits revus :
  - `a12b424` : BackAli, versions, A16–A18, contrat 413, ordre de travail ;
  - `9c76404` : Manuel, garde NVD, nginx 64 Kio, digests, limites ;
  - `c2c3dbf` : BackTesteur, formatage, rewrite, correctif des tests instables, 035/036 ;
  - `5a61a74` : BackCodeur, `src/main`, A18, m5–m8, Symfony 8.1.12 ;
  - `fe53ac6` : BackTesteur, `ManagementPortIT`. **Référence d'intégrité finale.**
- Aucun secret, hash, jeton ni donnée personnelle dans ce rapport.

## 1. Verdict : **REFUSÉ** (2 points bloquants légers)

Tous les bloquants de la revue 1 qui ne dépendent pas d'un push sont levés :
- `verify` vert deux fois de suite ;
- Spotless, Checkstyle et Nullness à 0 ;
- OpenRewrite sans changement ;
- PHPUnit vert ;
- `composer audit` sans avis ;
- intégrité respectée.

Il reste un seul outil rouge : **le quality gate Sonar** (« Sonar way », exigé par A12 et §5.2) échoue sur deux conditions :
- `new_coverage` à 68,3 % pour un seuil de 80 % ;
- `new_violations` à 1.

La première cache une vraie lacune : la logique de rejeu sur interblocage, réécrite dans cette boucle, n'est couverte par **aucun** test. La seconde vient du constat S5998, accepté en revue 1 (m6) mais jamais statué dans Sonar ni dans A16. Les deux corrections sont petites (R1, R2).

## 2. Résultats des outils

Toutes les commandes Maven ont été lancées depuis `services/` dans `eclipse-temurin:25-jdk`, avec `./mvnw -B -ntp -pl identity-service -am`, avec un `clean` entre chaque build.

| # | Outil | Commande | Statut | Mesures |
|---|---|---|---|---|
| 1 | Tests + JaCoCo, passage 1 | `clean verify` | ✅ | Surefire 573/573, Failsafe 248/248, « All coverage checks have been met », 0 `SQLTimeoutException`. |
| 1b | Tests + JaCoCo, passage 2 | `clean verify` | ✅ | Identique : 573/573, 248/248, 0 `SQLTimeoutException`. B7 local est réglé (pool root unique). |
| 1c | Couverture JaCoCo (fusionnée) | — | ✅ | domaine : lignes 100 %, branches 100 % ; application : lignes 99,0 %, branches 95,0 % ; module : lignes 89,4 % (seuils A12 : 90/80 sur le cœur, 80 sur le module). |
| 2 | Spotless (gjf 2.0.0) | `clean spotless:check` | ✅ | 0 fichier à reformater. |
| 3 | Checkstyle + Nullness + Spotless | `clean -Pquality verify -DskipTests` | ✅ | « You have 0 Checkstyle violations » sur les 2 modules ; compilation forkée de 94 sources avec le Nullness Checker : 0 erreur. |
| 4 | OpenRewrite (6.37.1 / 3.42.1 / 2.41.1, `UpgradeSpringBoot_4_0`) | `clean rewrite:dryRun` | ✅ | « Applying recipes would make no changes. No patch file generated. » |
| 5 | OWASP dependency-check 13 | — | ⏳ **à prouver en CI après push (accord utilisateur)** | La clé NVD n'existe que dans GitHub. La garde du workflow est conforme (§4.3). |
| 6 | Sonar (SonarQube local 26.10, analyse avec tests et couverture) | `clean verify sonar:sonar` | ❌ **gate FAILED** | `new_coverage` 68,3 % < 80 ; `new_violations` 1 > 0 ; duplication du nouveau code 0 %. Globalement : 0 vulnérabilité (note de sécurité A), 0 hotspot, 0 code smell, 1 bug MAJOR (S5998), couverture 86,3 %, 3536 lignes. Les exclusions `sonar.issue.ignore.multicriteria` se limitent à `e1` (S4502, `SecurityConfig.java`) et `e2` (S2077, `JdbcLegacyUserSource.java`), conformément à A16. Jeton admin local révoqué, SonarQube arrêté (`stop`). |
| 7 | PHPUnit | `docker compose run --rm --no-deps -e APP_ENV=test … vendor/bin/phpunit` | ✅ | 13 tests, 60 assertions. |
| 8 | composer audit | même conteneur | ✅ | « No security vulnerability advisories found ». `symfony/http-kernel` est en v8.1.12 ; 26 paquets Symfony en 8.1.12 dans `composer.lock`, les autres sans version plus récente. |

Référence du gate : la « nouvelle » période locale part de l'analyse de la revue 1, faite **sans** couverture. Tout le code modifié dans cette boucle compte donc comme nouveau, ce qui explique le chiffre de 68,3 %. Le détail par fichier (§3) montre cependant de vrais trous.

## 3. Points bloquants

| Réf. | Propriétaire | Fichier:ligne | Constat | Correction attendue |
|---|---|---|---|---|
| **R1** | **BackTesteur** | `adapter/out/persistence/JpaUnitOfWork.java:31-58` ; `adapter/in/cli/LegacyImportRunner.java` | Gate `new_coverage` à 68,3 %. Deux fichiers expliquent l'essentiel de l'écart : <br>• `JpaUnitOfWork` : 0 % du nouveau code, 0 branche sur 12 au total (JaCoCo) ; aucun test ne le référence ; <br>• `LegacyImportRunner` : 0 % des lignes neuves, 8/38 lignes au total. <br>Le rejeu sur interblocage est un écart de BackCodeur (§5) qui doit être prouvé. | Test unitaire de `JpaUnitOfWork` avec un `PlatformTransactionManager` factice : <br>• `SQLException` 1213 et SQLSTATE `40001` dans la chaîne → rejoué, puis succès ; <br>• 3 interblocages → l'exception remonte au 3e essai ; <br>• `CannotAcquireLockException` sans 1213/40001 (attente de verrou) → **pas** de rejeu ; <br>• autre exception → pas de rejeu. <br>Couvrir aussi les nouvelles branches de `LegacyImportRunner` (options inconnues, rapport incohérent → code 1). Critère : `new_coverage` ≥ 80 % au prochain passage. |
| **R2** | **BackAli** (décision), **BackCodeur** si réécriture | `domain/model/PhpEmailValidator.java:24` | Gate `new_violations` = 1 : bug S5998 (MAJOR), signalé comme nouveau parce que la ligne a bougé au découpage de l'expression régulière. Le commentaire ajouté (borne de 320 octets) ne suffit pas pour Sonar. Comme A16 se limite à `e1` et `e2`, aucune exclusion n'est possible sans nouvelle décision. | Soit amender A16 avec un `e3` (`java:S5998` sur `PhpEmailValidator.java` uniquement, justifié par la borne de 320 octets avant l'expression régulière et la fidélité au golden PHP, à 100 %) ; soit marquer le constat « Accepted » dans le SonarQube de CI avec cette justification. Une réécriture de l'expression régulière (quantificateurs possessifs) est possible, mais risquée pour le golden `php-filter-validate-email.json` ; je ne la recommande pas. |

Les points qui dépendent d'un push (B5 et B7, §6) ne sont **pas** bloquants pour cette boucle.

## 4. Contrôles de la liste §5

### 4.1 Intégrité des tests ✅
- **`c2c3dbf`** : 45 fichiers de test modifiés et 2 ajoutés, aucun supprimé. J'ai normalisé chaque fichier modifié (espaces, commentaires, `import` et `package` retirés) puis comparé `6d86299` à `c2c3dbf`. **37 fichiers sont strictement identiques**, ce qui confirme la déclaration de BackTesteur. Les 8 autres diffèrent :
  - 5 fichiers OpenRewrite attendus :
    - `RegistrationInvitationClaimIT` : `get(0)` → `getFirst()`, `equals` inversé ;
    - `RegisterUserServiceTest` : `getFirst()` ;
    - `MirrorCompatibilityTest` : `equals` inversé, import `Set` retiré ;
    - `MirrorScopeTest` : `equals` inversé ;
    - `EmailTest` : parenthèses redondantes.
    Les valeurs attendues, les assertions et les `@DisplayName` sont inchangés.
  - `support/ApiTestBase` (`@AfterAll closeRootPool`) et `support/MariaDbContainerSupport` (pool Hikari root unique : 2 connexions, délai de connexion de 30 s) : c'est le correctif des tests instables (B7).
  - `support/OpenApiValidation` : **ajout** d'un utilitaire `assertResponseConforms(...)` pour valider des réponses HTTP réelles, utilisé par la nouvelle classe 035. C'est un ajout pur (rien de retiré ni de relâché), lié aux nouveaux tests. Accepté.
  - Nouvelles classes : `RequestBodyLimitApiIT` (SC-IDENT-035, 15 tests) et `InternalSecretStartupTest` (SC-IDENT-036, 7 tests), toutes deux vertes.
- **`5a61a74`** : aucun fichier sous `src/test`, `backend/tests`, aucun POM, ni `phpunit.dist.xml`, `.env.test` ou `composer.json`. Seuls `composer.lock` et le `Dockerfile` sortent de `src/main`, et ils sont autorisés.
- **`fe53ac6`** : seulement `ManagementPortIT` :
  - ajout du secret interne de test (nécessaire depuis A18) ;
  - clé contrôlée `server.error.include-stacktrace` → `spring.web.error.include-stacktrace`, ce qui suit le renommage de Boot 4 avec la même valeur (`never`).
  Ce n'est pas un affaiblissement.
- Aucun `@Disabled`, `assume*` ni skip. Les seuils JaCoCo et les inclusions/exclusions Surefire/Failsafe sont inchangés. Le seul ajout sur Surefire et Failsafe est l'`argLine` de l'agent Mockito (B4).

### 4.2 Points A18 ✅
- **Secret** : `IdentityProperties.Symfony` refuse `null`, `""` et un secret blanc avec exactement `INTERNAL_API_SECRET doit être défini et non vide.` ; `IdentityProperties` refuse une section absente ; `toString` est surchargé. `InternalSecretStartupTest` vérifie que la cause ne contient pas la valeur testée.
- **Corps > 64 Kio** : `RequestBodies` vérifie d'abord `Content-Length`, puis `readNBytes(65 537)`, ce qui couvre `chunked` ; le refus donne 413 avec le message du contrat. `PATCH` sans jeton renvoie 401 en premier (testé). nginx : `client_max_body_size 64k` et 413 JSON sur `/api/auth/` et `= /api/me`.
- **Import** : `targetEmailConflicts()` bloque avant l'`INSERT … ON DUPLICATE KEY UPDATE`, et la métrique figure dans le rapport (`JdbcLegacyUserSource.java:53-57, 116-121`).
- **Journaux SQL** : `org.hibernate.orm.jdbc.error` et `SqlExceptionHelper` sont à `"OFF"`.
- **m6, m7, m8** : `requireNonNull` sur les `single()`, `orElseThrow()`, hash factice calculé dans le constructeur, Javadoc de la passphrase.

### 4.3 Configuration et CI ✅
- **Suppressions** : aucun `@SuppressWarnings("checkstyle…")`, `CHECKSTYLE.OFF`, `NOSONAR` ni `@SuppressWarnings("nullness")`. Les 2 seuls `@SuppressWarnings("initialization.fields.uninitialized")` sont sur les constructeurs `protected` JPA, avec le commentaire « Constructeur réservé à JPA ».
- **Spring Boot 4** : `spring.web.error.*` remplace `server.error.*`, ce qui rend la configuration A4 effective.
- **Workflow OWASP** : `identity-security.yml` échoue explicitement si le secret manque, et la clé passe par `-DnvdApiKeyEnvironmentVariable` (plus en argument, jamais affichée).
- **Conteneurs** : images épinglées par digest ; `identity` avec `mem_limit 768m` et `pids_limit 256`.
- **Ports** : la base et l'API sont publiées sur `127.0.0.1` par défaut (`DB_BIND`, `API_BIND`), ce qui règle m9.

## 5. Décisions demandées

### 5.1 A17 (pas de `MissingJavadocMethod` dans `adapter` et `config`) : **acceptable, ce n'est pas un affaiblissement**
- La décision est tracée dans une ADR et appliquée par une seule suppression de portée, versionnée (`services/config/checkstyle/checkstyle-suppressions.xml`).
- Aucune règle n'est désactivée et aucun seuil ne baisse : `failOnViolation`, `violationSeverity=warning`, 0 toléré.
- La règle reste complète là où se trouve le contrat de l'hexagone (`domain`, `application`, ports). `MissingJavadocType`, `LineLength`, `SummaryJavadoc` et toutes les autres règles restent actives partout.
- Les méthodes publiques des adapters (contrôleurs, beans `@Bean`, implémentations de ports) ont leur contrat ailleurs : OpenAPI, Javadoc du port implémenté, propriétés typées. Exiger leur Javadoc produirait de la paraphrase.
- A12 visait « 0 violation de la configuration retenue » ; A17 précise cette configuration au lieu de la contourner.
- Condition : toute nouvelle entrée de suppression passe par une ADR, ce que le commentaire du fichier exige déjà.

### 5.2 Écarts de BackCodeur

| Écart | Décision | Motif |
|---|---|---|
| `JpaUnitOfWork` intercepte `PessimisticLockingFailureException` mais ne rejoue que sur 1213 / SQLSTATE `40001` | **ACCEPTÉ**, mais à tester (R1) | Plus juste que ma recommandation m5 : `DeadlockLoserDataAccessException` est dépréciée, et Hibernate traduit l'interblocage InnoDB en `CannotAcquireLockException`, tout comme l'attente de verrou. Seul l'examen de la cause SQL distingue les deux. Une attente de verrou n'est plus rejouée, ce qui supprime le risque de 3 × `innodb_lock_wait_timeout`. Faute de test (0/12 branches), le comportement n'est pas prouvé : c'est R1. |
| `AuthController` passe un champ absent sous la forme `""` | **ACCEPTÉ** | Comportement identique : à `6d86299`, `Email.fromRegistration`, `PersonName.of` et `PlainPassword.of` convertissaient déjà `null` en `""` avant toute vérification. Les messages (obligatoire, longueur minimale) sont inchangés, `RegisterApiIT` est vert, et le contrôle de type E17 reste fait avant (`requiredString`). Cela supprime un `null` du contrat `RegisterUserCommand` (Nullness). |
| 2 × `@SuppressWarnings("initialization.fields.uninitialized")` sur les constructeurs JPA `protected` | **ACCEPTÉ** | C'est le cas prévu par §2.3 : ciblé (constructeur seul, pas la classe), commenté, champs facultatifs annotés `@Nullable`, et le constructeur métier reste vérifié. |
| `Objects.toString(x, "absent")` dans `LegacyImportService` | **ACCEPTÉ** | Il remplace le `String.valueOf` signalé par OpenRewrite (sur un `String` qui peut être `null` côté Nullness). La métrique devient explicite (`absent` au lieu de `"null"`) et ne contient ni personne ni valeur. Le rapport de reprise n'est lu que par l'opérateur (A14). |

### 5.3 `@Pure` et classes compilées avec `-Pquality`
- **Constat reproduit** (`clean -Pquality verify -DskipITs`) : `HexagonalArchitectureTest.domainIsPure` et `applicationDependsOnDomainOnly` échouent. Le Checker Framework ajoute `org.checkerframework.dataflow.qual.Pure` dans le bytecode des accesseurs de records (par exemple `UserProfileUpdated.avatarColor()`), ce qui crée une dépendance `domain → org.checkerframework` que la règle ArchUnit refuse.
- **Le job CI `identity-quality` (`-Pquality verify -DskipTests`) ne court pas ce risque** :
  - Surefire est sauté, donc ArchUnit ne tourne pas sur ces classes ;
  - le job de tests compile sans le profil, et l'image Docker est construite sans `-Pquality`.
- **Constat annexe** : avec `-DskipTests`, Failsafe a **quand même** exécuté les 248 tests d'intégration sur les classes compilées en qualité (tous verts). Le job qualité refait donc les IT : c'est plus lent, mais sûr.
- **Recommandation (BackAli et Manuel, non bloquant)** :
  1. documenter dans A12 et `docs/ci/README.md` : « les classes compilées avec `-Pquality` ne servent ni aux tests unitaires ni à l'image ; ne jamais combiner `-Pquality` avec les tests Surefire » ;
  2. ajouter `-DskipITs` au job `identity-quality`.
  Ne **pas** assouplir la règle ArchUnit pour `org.checkerframework..`, sauf décision explicite dans une ADR.

## 6. B5 et B7 : conditions avant la bascule, pas bloquants de la boucle

- **B5** (rapport OWASP, CVSS < 7) et **B7** (run `identity-service` vert sur GitHub Actions) ne peuvent être produits qu'après un **push**, qui demande l'accord de l'utilisateur (CLAUDE.md, ordre de travail §4.5). La clé NVD n'existe que dans GitHub, et dependency-check 13 refuse de tourner sans elle (constaté en revue 1).
- Tout ce que l'équipe pouvait faire sans push est fait et vérifié :
  - le workflow échoue explicitement sans clé, et la clé passe par une variable d'environnement ;
  - la cause locale de B7 est corrigée : deux `verify` complets verts, 0 timeout.
- Refuser la boucle pour ces deux points bloquerait le circuit sur une action qui n'appartient qu'à l'utilisateur, sans rien apprendre de plus sur le code. Je les classe donc en **conditions d'entrée de la recette BackAli et de la bascule**, à lever avant toute mise en production :
  1. **C1 (Manuel, après accord de push)** : run `identity-security` vert, sans dépendance CVSS ≥ 7, résumé dans `docs/ci/requests/backend.md`.
  2. **C2 (Manuel, après accord de push)** : runs `identity-service` (tests, qualité, image) et `identity-compat` verts. `identity-compat` prouve aussi les tests miroir SC-001…018 et croisés SC-010/023, non exécutés en local.
  3. **C3 (utilisateur)** : m11, changer la passphrase JWT présente dans l'historique partout où elle a servi.
  4. **C4** : quality gate Sonar vert sur le serveur de CI (même contrôle que R1/R2, sur la référence de CI).

## 7. Points mineurs

- **n1 (BackTesteur)** `docs/specs/traceability.md` : SC-IDENT-035 et 036 sont encore « test écrit (rouge) », alors qu'ils sont verts (15 et 7 tests).
- **n2 (BackAli et Manuel)** §5.3 : documenter la règle `-Pquality` sans tests et ajouter `-DskipITs` au job qualité.
- **n3 (BackAli)** A16 `e2` exclut S2077 pour **tout** `JdbcLegacyUserSource.java`. Une future concaténation réellement dangereuse dans ce fichier ne serait plus signalée. Il faut garder la contrainte « constantes + `LegacySchema` uniquement » dans la revue de code de ce fichier.
- **n4 (Manuel)** Le profil `security-scan` hérite du même comportement Failsafe : `-DskipTests` n'empêche pas les IT. Ajouter `-DskipITs` dans `identity-security.yml` pour ne pas relancer Testcontainers dans le job OWASP.
- **n5** Couverture du module en légère baisse (lignes 90,2 → 89,4 %), due surtout au nouveau code d'adapters peu testé (R1). Le seuil de 80 % reste respecté.

## 8. Synthèse des points de la revue 1

| Réf. revue 1 | État |
|---|---|
| B1 Spotless / gjf | ✅ levé (2.0.0, 0 écart) |
| B2 Checkstyle | ✅ levé (0, avec A17 acceptée) |
| B3 Nullness | ✅ levé (`checker-qual`, 0 erreur) |
| B4 OpenRewrite | ✅ levé (versions résolues, patch vide) |
| B5 OWASP | ⏳ condition C1 (push) |
| B6 Sonar S4502 / S2077 | ✅ levé (A16 `e1`/`e2`, 0 vulnérabilité), mais gate rouge, d'où R1 et R2 |
| B7 `verify` | ✅ local (2 passages verts) ; ⏳ condition C2 (CI) |
| m1–m4, m6–m8 | ✅ levés (A18 et corrections) |
| m5 | ✅ levé (variante acceptée, à tester : R1) |
| m9, m10 | ✅ levés (ports en `127.0.0.1`, digests, limites) |
| m11 | ⏳ condition C3 (utilisateur) |
| m12 | ✅ levé (Symfony 8.1.12, audit vide) |
| m13 | ✅ en grande partie (n1 reste) |
| m14, m15 | notés, sans action |
