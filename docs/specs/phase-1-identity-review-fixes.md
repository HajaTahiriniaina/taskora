# Ordre de travail — identity-service, boucle de correction n° 1

```
Origine : docs/reviews/2026-11-26-identity-service.md (verdict REFUSÉ, commit 2a50f3f) ; l'utilisateur a dit « continue la boucle »
Spec : docs/specs/identity.md (+ SC-IDENT-035, SC-IDENT-036)    Contrat : docs/contracts/openapi/identity-service.yaml (+ 413)
Architecture : docs/architecture/backend-hexagonal.md §7 (révisé), A4, A9, A12 (révisées), A16, A17, A18 (nouvelles) ; identity-migration.md §3
Branche : feat/identity-service (aucun push sans accord de l'utilisateur)
0. BackAli     — fait (ce commit) : versions, règles, contrat, ordre de travail
1. BackCodeur  — §2 (src/main, backend/composer.lock)     } en parallèle : fichiers disjoints (§1)
1. BackTesteur — §3 (src/test, traceability.md)           }
1. Manuel      — §4 (CI, nginx, rapport OWASP)            }
2. BackReviewer — §5 (nouvelle revue complète)
```

## 1. Règles de la boucle

### 1.1 Fichiers par agent (disjoints)
| Agent | Écrit | N'écrit jamais |
|---|---|---|
| BackCodeur | `services/identity-service/src/main/**` (y compris `application.yml`), `backend/composer.lock`, `backend/symfony.lock` si composer le modifie | `src/test/**`, `services/pom.xml`, `services/identity-service/pom.xml`, `services/config/**`, `backend/tests/**`, `backend/composer.json`, `.github/**`, `docker-compose*.yml`, `frontend/**`, `docs/**` |
| BackTesteur | `services/identity-service/src/test/**`, `docs/specs/traceability.md` | `src/main/**`, tous les POM, `services/config/**`, `backend/**`, `.github/**`, autres `docs/**` |
| Manuel | `.github/workflows/**`, `frontend/nginx.conf` (ou son gabarit), `docker-compose*.yml`, `docs/ci/requests/backend.md` (section réponse) | `services/**`, `backend/src/**`, `backend/tests/**` |
| BackAli (fait) | `services/pom.xml`, `services/config/checkstyle/checkstyle-suppressions.xml`, `docs/**` sauf `traceability.md` et `docs/reviews/**` | — |

### 1.2 Exécutions Maven
Les trois agents peuvent éditer en même temps, mais **une seule exécution Maven à la fois** dans l'arbre de travail (ils partagent `services/**/target`). Le coordinateur les enchaîne. Commande de référence (celle de la revue), depuis la racine du dépôt :
```bash
docker run --rm -v "$PWD":/w -w /w/services -v "$HOME/.m2":/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock eclipse-temurin:25-jdk \
  ./mvnw -B -ntp -pl identity-service -am <objectifs>
```
(`-v /var/run/docker.sock…` seulement pour les tests Testcontainers.)

### 1.3 Ce que BackAli a déjà vérifié (ce commit)
| Réf. | Changement dans `services/pom.xml` | Vérification sur `eclipse-temurin:25-jdk` |
|---|---|---|
| B1 | `google-java-format.version` = **2.0.0** (Spotless 3.12.0 inchangé) | `spotless:check` **s'exécute** (plus d'`InvocationTargetException`) : « 63 needs changes » (19 `src/main`, 44 `src/test`) |
| B3 | `checker-qual` 4.4.0 en `provided`, hérité par tous les modules (`<dependencies>` du parent) ; export ajouté `-J--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED` | `-Pquality clean compile` : le Nullness Checker tourne et signale **14 erreurs** (liste §2.3 ; plus que les 9 de la revue, car sans cet export le checker s'arrêtait plus tôt) |
| B4 | `rewrite-spring` **6.37.1**, `rewrite-migrate-java` **3.42.1**, `rewrite-static-analysis` **2.41.1** ; recette `org.openrewrite.java.spring.boot4.UpgradeSpringBoot_4_0` (+ `UpgradeToJava25`, `CommonStaticAnalysis`) ; agent Mockito `-javaagent:${org.mockito:mockito-core:jar}` dans l'`argLine` Surefire/Failsafe (`maven-dependency-plugin:properties`, `argLine` vide par défaut) | `rewrite:dryRun` **résolu et exécuté** : 9 fichiers (4 `src/main`, 5 `src/test`), **plus aucun POM** (la recette Mockito est satisfaite) ; `test -Dtest=EmailTest,RegisterUserServiceTest` : 30/30 verts avec le nouvel `argLine` |
| B2 | ADR A17 : `MissingJavadocMethod` hors `adapter` et `config` (fichier de suppression unique, aucune règle désactivée) | `checkstyle:check` : 164 → **97** violations, toutes à corriger (§2.2) |
| B6 | ADR A16 : `sonar.issue.ignore.multicriteria` (`e1` S4502 sur `SecurityConfig.java`, `e2` S2077 sur `JdbcLegacyUserSource.java`) | justification en commentaire dans le POM ; à confirmer par la prochaine analyse (§5) |

## 2. BackCodeur — `src/main` et backend (commit `fix(identity): review loop 1 — quality toolchain and robustness`)

Entrée : ce document, la revue, ADR A4, A9, A16–A18, contrat (413). Ordre conseillé :

### 2.1 Formatage (B1)
`./mvnw … spotless:apply -DspotlessFiles='.*/src/main/.*'` (le hook exige `-DspotlessFiles`) → 19 fichiers. Puis `spotless:check -DspotlessFiles='.*/src/main/.*'` vert.

### 2.2 Checkstyle (B2) — 97 violations après formatage, à ramener à 0
Relancer `checkstyle:check` **après** `spotless:apply` (une partie des longueurs disparaît). Le reste :
- **Javadoc** (`MissingJavadocMethod`, 64) sur toutes les méthodes et constructeurs publics de `domain` et `application` : modèle (`User` accesseurs et `rehydrate`, `PlainPassword`, `PersonName.of`, `Email.ofStored`, `AvatarColor.random`, `PhpEmailValidator.isValid`, constructeurs compacts de `Roles` et `ProfileUpdateResult`), `domain.service.PasswordHashing`, `domain.event.DomainEvent`, constructeurs des 6 exceptions et `TokenRejectedException.reason`, **tous** les ports `application.port.in` et `application.port.out`, constructeurs publics des services applicatifs, et `IdentityServiceApplication.main`. Javadoc utile (rôle, valeurs refusées, exceptions levées, renvoi à l'ADR ou au scénario), pas de paraphrase du nom.
- **`SummaryJavadoc`** (5) : `PhpString.java` lignes 11, 29, 37, `Email.java:11`, `RegisterUserService.java:51` (première phrase terminée par un point, sans `@return` seul).
- **`LineLength`** (22) : `SecurityConfig.java:17,25,28,40`, `ErrorMessages.java:6,8`, `PhpEmailValidator.java:15,17,19` (découper l'expression régulière en constantes concaténées, **sans** la modifier : le golden `compat/php-filter-validate-email.json` doit rester 100 % vert), `JdbcLegacyUserSource.java:119`, `JdbcLegacyUserProjection.java:14`, `User.java:79`, `RegisterUserService.java:79,128`, `RegisterUserCommand.java:3`, `ImportReport.java:4`, `OutboxJpaRepository.java:54`, `JpaUserRepositoryAdapter.java:72`, `JpaUnitOfWork.java:12`, `SymfonyInvitationClaimClient.java:76`, `LexikCompatibleJwtFilter.java:52`, `IdentityServiceApplication.java:13` (numéros d'avant formatage).
- **Autres** : renommer `ErrorMessages.notAString` → `notString` (`GoogleMethodName`, `AbbreviationAsWordInName`) ; `default` dans le `switch` de `OutboxDeliveryService.java:75` (lever `IllegalStateException`) ; `VariableDeclarationUsageDistance` dans `RegisterUserService.java:80` et `NimbusTokenVerifier.java:37` (rapprocher la déclaration, **sans changer l'ordre des contrôles** : SC-IDENT-003 et la précédence §8.1 sont testés) ; `JavadocParagraph` dans `LegacyImportRunner.java:23`.
- Interdit : `@SuppressWarnings("checkstyle:…")`, `CHECKSTYLE.OFF`, `CHECKSTYLE.SUPPRESS` : toute exception passe par une ADR.

### 2.3 Nullness (B3) — 14 erreurs, à ramener à 0
`-Pquality clean compile` (le `clean` est nécessaire, sinon la compilation est sautée).
| Fichier:ligne | Erreur | Correction attendue |
|---|---|---|
| `adapter/in/web/PhpJsonBody.java:60,69` | `return` nullable | retour `@Nullable String` |
| `adapter/in/web/MeController.java:47` | `isEmpty()` sur récepteur nullable | test `null` explicite avant (sémantique E21 inchangée) |
| `adapter/in/web/ApiErrorHandler.java:31` | argument nullable pour `message` | valeur par défaut ou paramètre `@Nullable` |
| `adapter/in/web/ApiErrorHandler.java:65` | `setAllow` avec ensemble nullable | ensemble vide si `null` |
| `adapter/in/web/security/LexikTokenExtractor.java:17,21` | `return` nullable | retour `@Nullable String` (le « jeton absent » reste distinct) |
| `adapter/in/cli/LegacyImportRunner.java:46` | argument nullable de `String.join` | filtrer ou valeur par défaut |
| `adapter/out/security/NimbusTokenVerifier.java:90` | `return` nullable | `@Nullable` ou exception, sans changer la précédence |
| `adapter/out/security/SymfonyCompatiblePasswordHasher.java:32` | `dummyHash` non initialisé | **calculer au constructeur** (règle aussi m7 et A2 : hash factice prêt au démarrage) |
| `adapter/out/persistence/UserAccountEntity.java:46,48`, `OutboxMessageEntity.java:56,58` | champs JPA non initialisés | champs facultatifs `@Nullable` (`id`, `updatedAt`, `lastError`, `deliveredAt`) ; constructeur protégé JPA annoté `@SuppressWarnings("initialization.fields.uninitialized")` avec un commentaire « constructeur réservé à JPA » |
Pas de `@SuppressWarnings("nullness")` global ni d'exclusion de fichier.

### 2.4 OpenRewrite (B4), partie `src/main`
Appliquer à la main (ou `rewrite:run` puis annuler tout changement hors `src/main`) les 4 changements du `dryRun` :
- `application.yml` : `server.error.*` → `spring.web.error.*` (`include-stacktrace: never`, `include-message: never`, `include-binding-errors: never`, `include-exception: false`, `whitelabel.enabled: false`) — A4 ;
- `OutboxJpaRepository.java` : `@Query(nativeQuery = true)` → `@NativeQuery` ;
- `LexikKeyLoader.java` : `if/else instanceof` → `switch` à motifs ;
- `LegacyImportService.java` : `String.valueOf` inutile retiré.
Critère : `rewrite:dryRun` ne liste plus **aucun** fichier `src/main` (les 5 de `src/test` sont pour BackTesteur).

### 2.5 Robustesse (ADR A18) et points mineurs
1. **m1** — `IdentityProperties` (`identity.symfony.internal-secret`) : vide, absent ou blanc → échec de démarrage, message contenant exactement `INTERNAL_API_SECRET doit être défini et non vide.`, jamais la valeur (SC-IDENT-036).
2. **m2** — `RequestBodies.read` : limite **65 536 octets** ; `Content-Length` > limite → refus immédiat ; sinon `readNBytes(65_537)` et refus si plus de 65 536. Exception dédiée → **413** `{"error":"Corps de requête trop volumineux."}` (`ErrorMessages`), contrat `PayloadTooLarge` (SC-IDENT-035). L'authentification de `PATCH /api/me` reste avant (401).
3. **m3** — `JdbcLegacyUserSource` : contrôle bloquant « même e-mail normalisé, id différent » avant l'`INSERT … ON DUPLICATE KEY UPDATE` (identity-migration.md §3) ; métrique `targetEmailConflicts` dans le rapport.
4. **m4** — `application.yml` : `org.hibernate.orm.jdbc.error` et `org.hibernate.engine.jdbc.spi.SqlExceptionHelper` à `OFF`.
5. **m5** — `JpaUnitOfWork` : rejouer seulement sur `DeadlockLoserDataAccessException` (pas sur toute `PessimisticLockingFailureException`).
6. **m6** — bugs Sonar : `Objects.requireNonNull` sur les `single()` de `JdbcLegacyUserSource.java:112,119,176` ; `orElseThrow()` au lieu de `get()` (`User.java:90`) ; commentaire sur S5998 (`PhpEmailValidator.java:19`) rappelant la borne de 320 octets appliquée avant l'expression régulière.
7. **m7** — voir 2.3 (`dummyHash` au constructeur).
8. **m8** — accepté : la passphrase reste une `String` dans `IdentityProperties` ; ajouter une ligne dans la Javadoc de la propriété.
9. **m12** — Symfony **8.1.12** : `docker compose run --rm --no-deps api composer update 'symfony/*' --with-all-dependencies --no-interaction` (contrainte `extra.symfony.require` = `8.1.*`, `composer.json` inchangé) ; `composer audit` doit ne plus lister les 10 avis 8.1.11 ; PHPUnit **vert** (`docker compose run --rm --no-deps -e APP_ENV=test … vendor/bin/phpunit`, 13 tests) ; reconstruire l'image `api` pour le contrôle manuel.
10. Pas de `// NOSONAR` à ajouter (A16 : exclusion dans le POM).

### 2.6 Critères de sortie BackCodeur
- `spotless:check -DspotlessFiles='.*/src/main/.*'`, `checkstyle:check`, `-Pquality clean compile` : **0** ;
- `rewrite:dryRun` : aucun fichier `src/main` ;
- `verify` : tests existants verts (les nouveaux tests 035/036 de BackTesteur doivent passer avec ce code) ;
- `git diff <commit test de cette boucle>..HEAD -- services/identity-service/src/test backend/tests` vide ;
- rapport : fichiers modifiés, mesures avant/après, tout test qui paraîtrait faux (sans le modifier).

## 3. BackTesteur — `src/test` (commit `test(identity): review loop 1 — formatting, rewrite and new scenarios`)

1. **Formatage** (B1) : `spotless:apply -DspotlessFiles='.*/src/test/.*'` → 44 fichiers. **Forme seulement** : aucune assertion, valeur attendue, nom de test ou `@DisplayName` changé (gjf 2.0.0 retire aussi un import inutilisé dans `MirrorCompatibilityTest`, accepté).
2. **OpenRewrite** (B4), 5 fichiers : `MirrorCompatibilityTest`, `MirrorScopeTest`, `RegistrationInvitationClaimIT` (`EqualsAvoidsNull`, `get(0)` → `getFirst()`), `RegisterUserServiceTest` (`getFirst()`), `EmailTest` (parenthèses redondantes). Changements sans effet sur la sémantique. Critère : `rewrite:dryRun` ne liste plus aucun fichier `src/test`. Rien à faire pour l'agent Mockito (réglé dans le parent).
3. **B7** — `support/ApiTestBase.query` : une seule connexion (ou une `DataSource` Hikari unique sur le conteneur) réutilisée pour toute la classe, fermée en `@AfterAll`, au lieu d'un nouveau socket par appel ; délai de connexion explicite. Critère : deux exécutions complètes de `verify` sans `SQLTimeoutException`.
4. **Nouveaux tests** (rouges jusqu'au code de BackCodeur) :
   - `adapter/in/web/RequestBodyLimitApiIT` — **SC-IDENT-035** : pour `register`, `login` et `PATCH /me` (avec jeton) : 65 537 octets avec `Content-Length` → 413 et message exact ; 65 537 octets en `chunked` → 413 ; 65 536 octets → pas 413 (400 attendu selon les règles habituelles) ; `PATCH` sans jeton et corps trop gros → 401 ; aucune écriture ; réponse validée contre l'OpenAPI.
   - `config/InternalSecretStartupTest` — **SC-IDENT-036** : `ApplicationContextRunner` (ou `SpringApplication` avec `identity.symfony.internal-secret` = `""`, `"   "`, absent) → échec du contexte, cause contenant `INTERNAL_API_SECRET doit être défini et non vide.` et **pas** la valeur testée ; secret non vide → démarrage (ou validation des propriétés) réussi.
5. **`docs/specs/traceability.md`** : lignes SC-IDENT au statut `vert` (fichier et méthode à jour), ajout de 035 et 036 (`rouge` puis `vert` après BackCodeur) ; corriger la mention « 5 test defects » (m14) dans le rapport, pas dans l'historique.
6. Critères : `spotless:check -DspotlessFiles='.*/src/test/.*'` vert ; compilation verte ; tests existants verts ; 035/036 rouges pour la bonne raison avant le code ; hash du commit dans le rapport (**nouvelle référence d'intégrité**).

## 4. Manuel — CI et infra (commit `ci(identity): review loop 1`)
1. **B5** — `.github/workflows/identity-security.yml` (lignes 49–55) : si `secrets.NVD_API_KEY` est vide, **échouer** avec un message clair (« NVD_API_KEY absent : dependency-check 13 ne peut pas s'exécuter ») au lieu d'annoncer une mise à jour lente ; sinon passer la clé par variable d'environnement (`NVD_API_KEY`) et `-DnvdApiKeyEnvironmentVariable=NVD_API_KEY` plutôt qu'en argument de ligne de commande ; jamais d'`echo` de la clé.
2. **Rapport OWASP local** : dependency-check 13 **refuse** de tourner sans clé (constaté par la revue). La clé n'existe que dans GitHub : soit l'utilisateur exporte sa propre clé dans **son** shell (`export NVD_API_KEY=…`, jamais écrite dans un fichier du dépôt ni affichée) et Manuel lance `-Psecurity-scan verify -DskipTests` (mise à jour NVD initiale longue), soit le rapport viendra du run GitHub. Critère : aucune dépendance CVSS ≥ 7 ; rapport HTML/JSON hors dépôt, résumé (nombres, CVE éventuelles) dans `docs/ci/requests/backend.md`.
3. **m2** — nginx : `client_max_body_size 64k` sur `location /api/auth/` et `location = /api/me`, et `error_page 413` renvoyant `{"error":"Corps de requête trop volumineux."}` en `application/json` (même corps que le service, SC-IDENT-035).
4. Recommandés (non bloquants) : **m9** `database` publié sur `127.0.0.1` ; **m10** images épinglées par digest, `mem_limit` et `pids_limit` sur `identity`.
5. **En attente de l'utilisateur** :
   - **B7** : un run `identity-service` vert sur GitHub Actions exige un **push**, que l'utilisateur doit approuver ;
   - **m11** : la passphrase JWT réelle présente dans l'historique (`backend/.env.dist` avant `aa27c2f`) doit être changée partout où elle a servi (décision et action de l'utilisateur).

## 5. BackReviewer — nouvelle revue (`docs/reviews/<date>-identity-service-loop1.md`)
Référence d'intégrité : le commit `test(identity): review loop 1 …` de BackTesteur.
1. Intégrité : seuls les changements de forme (Spotless), les 5 changements OpenRewrite, `ApiTestBase.query` et les deux nouvelles classes dans `src/test` depuis `6d86299` ; aucun affaiblissement ; aucun fichier de test dans le commit de BackCodeur.
2. Outils, tous verts : `verify` (deux passages complets), `-Pquality verify -DskipTests` (Spotless **2.0.0**, Checkstyle avec la seule exception A17, Nullness), `rewrite:dryRun` (patch vide), Sonar (quality gate vert ; vérifier que `sonar.issue.ignore.multicriteria` ne contient **que** `e1` et `e2` d'A16 ; 0 bug critique ou bloquant), PHPUnit, `composer audit` (plus d'avis 8.1.11), OWASP (rapport de Manuel, CVSS < 7).
3. Contrôler A18 : démarrage refusé sans secret (message sans valeur), 413 au-delà de 64 Kio (y compris `chunked`), contrôle « même e-mail, autre id » avant l'import, journaux SQL à `OFF`.
4. Contrôler la configuration : aucun `@SuppressWarnings("checkstyle…")`, `CHECKSTYLE.OFF`, `NOSONAR` ou `@SuppressWarnings("nullness")` hors du cas JPA documenté.
5. Points restés en attente (B7 par push, m11) : listés, **non** bloquants pour cette boucle s'ils dépendent de l'utilisateur.
