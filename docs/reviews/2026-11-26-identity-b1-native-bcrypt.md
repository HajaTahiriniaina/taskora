# Revue BackReviewer — identity-service, B1 : moteur bcrypt natif (libxcrypt via FFM)

- Date : 26/11/2026 (contrôles terminés le 27/11/2026)
- Branche : `perf/identity-bcrypt-latency` (base `main` `934dd2c`), non poussée
- Ordre de travail : `docs/specs/phase-1-identity-perf-b1-workorder.md` (§5.3)
- Critère : PERF-IDENT-1, `docs/specs/identity.md` §6.1 (branche `docs/identity-latency-threshold`, Paul)
- Commits revus :
  - `d19c98b` (BackAli) : diagnostic et ordre de travail ;
  - `85af897` (BackTesteur) : tests rouges, squelette, `--enable-native-access` dans l'`argLine` ;
  - `a15a534` (BackCodeur) : moteur natif ;
  - `f79d920` (BackTesteur) : `BcryptLatencyIT` neutre pour OpenRewrite. **Référence d'intégrité des tests.**
- Revue précédente : `docs/reviews/2026-11-26-identity-service-r3.md`.
- Aucun secret, hash réel, jeton ni donnée personnelle dans ce rapport. `.env` non lu.

## 1. Verdict : **ACCEPTÉ**

- Le moteur natif atteint la parité avec PHP : rapport **1,00** à la vérification (login) et **1,01** au hachage (inscription), pour un seuil de 1,15.
- Tous les tests sont verts, `LoginTimingIT` (SC-IDENT-008) compris. Les seuils JaCoCo sont atteints. Tous les outils de qualité exécutables en local sont verts.
- L'intégrité des tests est respectée, sans aucun affaiblissement.
- La revue mémoire du code FFM ne relève aucun défaut.

Aucun point bloquant. Il reste une **condition** et des points mineurs :
- **C-B1** : l'analyse Trivy de l'image n'a pas pu être exécutée en local à cause du réseau (§2.4). Elle doit être obtenue en CI avant la fusion.
- Les points mineurs sont listés au §5.

## 2. Résultats des outils

Les commandes Maven ont été lancées depuis `services/` dans le conteneur `eclipse-temurin:25-jdk` (Linux arm64, où `libcrypt.so.1` existe), avec `./mvnw -B -ntp -pl identity-service -am`. Le socket Docker était monté pour Testcontainers. Un `clean` a été fait entre le build de tests et le build qualité, ainsi qu'à la fin.

| # | Outil | Commande | Statut | Mesures |
|---|---|---|---|---|
| 1 | Tests + JaCoCo + ArchUnit | `clean verify` | ✅ BUILD SUCCESS (3 min 12 s) | Surefire **675/675** ; Failsafe **256/256** ; 0 échec, 0 erreur, 0 ignoré. « All coverage checks have been met ». `HexagonalArchitectureTest` : 10/10. |
| 1a | `BcryptLatencyIT` (PERF-IDENT-1) | idem | ✅ | **Vérification** : médiane identity 440,5 ms, PHP 439,2 ms, **rapport 1,00** (bloc 1 : 439,2 / 439,9 ; bloc 2 : 438,9 / 444,7). **Hachage** : identity 440,5 ms, PHP 437,2 ms, **rapport 1,01** (bloc 1 : 438,6 / 439,0 ; bloc 2 : 437,0 / 442,5). Seuil 1,15, n = 16 par implémentation. Le diagnostic avant correction donnait 1,35. L4 (objectif ≤ 1,10) est atteint lui aussi. |
| 1b | `LoginTimingIT` (SC-IDENT-008) | idem | ✅ | 1/1 (45,7 s), inchangé depuis `main`. |
| 1c | Nouveaux tests B1 | idem | ✅ | `NativeBcryptEngineCompatibilityTest` 79/79. `BcryptEngineDefaultIT` 2/2 : moteur natif par défaut dans le contexte Spring. `BcryptEngineJavaSelectableIT` 2/2. |
| 1d | Couverture (rapport fusionné) | — | ✅ | Module : lignes **92,8 %**, branches **82,2 %** (93,2 / 83,2 au 3e passage ; écart dû au code natif défensif inatteignable, voir m4). `LibxcryptBcryptEngine` : 45/51 lignes, 24/34 branches. `SymfonyCompatiblePasswordHasher` : 50/55, 34/38. `JavaBcryptEngine` : 2/4. |
| 2 | Spotless + Checkstyle + Checker Framework (Nullness) | `clean -Pquality verify -DskipTests -DskipITs` | ✅ | Spotless : 0 écart. Checkstyle : 0 violation (2 modules). Nullness : 97 sources compilées par le compilateur forké avec le checker, 0 erreur. Tests sautés comme prévu. |
| 2b | Spotless seul | `clean spotless:check` | ✅ | 154 fichiers propres, 0 à corriger. |
| 3 | OpenRewrite | `clean rewrite:dryRun` | ✅ | « Applying recipes would make no changes. No patch file generated. » |
| 4 | Sonar | — | ⏭️ **non exécuté** | Le conteneur `taskora-sonarqube-1` est arrêté. Le redémarrer ne toucherait que lui (`docker start`), mais une analyse demande un jeton : il faudrait s'authentifier avec le compte administrateur local, dont je n'ai pas les identifiants (et je ne lis pas `.env`). J'ai donc sauté l'étape. Le job `identity-sonar` de la CI analysera la PR (condition C4 existante). |
| 5 | Dépendances (OWASP) | diff des POM | ✅ aucune dépendance nouvelle | `git diff 934dd2c..f79d920` sur `services/pom.xml` et `services/identity-service/pom.xml` : seulement l'`argLine` Surefire/Failsafe (`--enable-native-access=ALL-UNNAMED`) et les `jvmArguments` de `spring-boot-maven-plugin`. Aucun `<dependency>` ni aucune version n'est ajouté ou modifié. OWASP Dependency-Check n'a pas été lancé (clé NVD absente) : sans nouvelle dépendance, il reste couvert par C1 en CI. |
| 6 | Trivy (image) | voir §2.4 | ⚠️ **non obtenu** (réseau) | Constat de repli sur `libcrypt1` : §2.4. |

### 2.4 Trivy et état de `libcrypt1`

Tentatives :
- image `aquasec/trivy:0.76.2` (la plus récente), figée par digest `sha256:8f6bb947dbc528902e11f2f5be52f7b519f6a39d5d148f41166f2040499768fb` (obtenu par `docker buildx imagetools inspect`) ;
- cible : l'image jetable `throwaway-b1-review:check`, construite par `docker build -f services/identity-service/Dockerfile services/`.

Cinq essais sur deux jours ont échoué, tous côté réseau :
- 3 essais : téléchargement de la base de vulnérabilités interrompu (`unexpected EOF`), depuis `mirror.gcr.io/aquasec/trivy-db:2` puis depuis `ghcr.io/aquasecurity/trivy-db:2` ;
- 2 essais : nouveau téléchargement de l'image Trivy refusé par Docker Hub (`unauthorized: authentication required`, limite anonyme probable).

Je n'ai pas contourné ces échecs.

Constat de repli, fait dans l'image jetable (OS Ubuntu 26.04.1 LTS, `apt-get update` lancé en root dans un conteneur `--rm`) :
- `libcrypt1` **1:4.5.1-1** est installé, et c'est aussi la version candidate de l'archive. Aucune mise à jour de sécurité n'est en attente pour la bibliothèque appelée par FFM.
- `/usr/lib/aarch64-linux-gnu/libcrypt.so.1` → `libcrypt.so.1.1.0` est présent.
- `JAVA_TOOL_OPTIONS` vaut `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError --enable-native-access=ALL-UNNAMED`.
- Hors périmètre B1 (image de base, préexistant) : mises à jour de sécurité Ubuntu en attente pour `libexpat1` (0ubuntu0.2 → 0ubuntu0.3), `perl-base` et `tzdata`. Voir m6.

Les images jetables (identity et Trivy) et le cache Trivy ont été supprimés. Il ne reste aucun conteneur jetable.

## 3. Intégrité des tests ✅

- **`85af897..a15a534`** (BackCodeur) : `git diff --name-only -- 'services/**/src/test/**'` est **vide**. Le commit ne touche que 7 fichiers de production ou de configuration : `Dockerfile`, `identity-service/pom.xml`, `LibxcryptBcryptEngine`, `SymfonyCompatiblePasswordHasher`, `IdentityProperties`, `UseCaseConfig` et `application.yml`.
- **`a15a534..f79d920`** (BackTesteur) : seul `BcryptLatencyIT.java` change (4 lignes ajoutées, 4 retirées) :
  - `.formatted(COST, COST)` avec `%d` devient `.replace("{COST}", …)` avec `{COST}` ;
  - `System.out.println` devient `IO.println`.

  Sémantique inchangée, prouvée : j'ai compilé les deux blocs de texte extraits de `85af897` et de `f79d920` dans une même classe Java 25 :
  - chaînes **égales** (`equals` = `true`, 699 caractères) ;
  - SHA-256 identique (`121eedc0…01ac4d`).

  Le script PHP envoyé au conteneur est donc identique octet pour octet. Il ne contenait aucun autre `%`. Sont inchangés :
  - protocole : blocs alternés PHP/Java, chauffe 3 / 5 / 2, 2 blocs × 8 ;
  - `MAX_RATIO = 1.15` et `n = 16` ;
  - image PHP figée par digest ;
  - vérifications croisées : hash PHP vérifié par identity, hash identity vérifié par PHP (code 0), préfixe `$2y$13$`, longueur 60.

  Écart sans effet avec l'ordre de travail (15 mesures demandées, 16 réalisées) : il existe depuis `85af897` et ne vient pas d'un affaiblissement.
- **`LoginTimingIT`** : `git diff 934dd2c f79d920` sur ce fichier est **vide**. Ses 30 essais et son seuil 0,5 sont donc inchangés.
- Recherche de motifs d'affaiblissement dans `934dd2c..f79d920` :
  - aucun `@Disabled`, `assume*`, `EnabledIf`/`DisabledIf`, `NOSONAR`, `CHECKSTYLE:OFF`, `skip`, exclusion Surefire/Failsafe/JaCoCo, ni seuil modifié ;
  - seule occurrence : `@SuppressWarnings("resource")` sur `BcryptLatencyIT.startPhp` (test). Elle est légitime : le `GenericContainer` est arrêté dans `@AfterAll`.
- La configuration de test dans `services/pom.xml` (propriété BackTesteur, `85af897`) ne fait qu'ajouter `--enable-native-access=ALL-UNNAMED` à l'`argLine`. Inclusions, groupes et seuils sont inchangés.

## 4. Revue manuelle du code

### 4.1 `LibxcryptBcryptEngine` (FFM) ✅

- **`struct crypt_data`** : vérifiée avec `gcc` contre `/usr/include/crypt.h` de `libcrypt-dev 1:4.5.1-1`, installé dans un conteneur jetable :
  - `sizeof = 32768`, `offsetof(output) = 0` ;
  - `CRYPT_OUTPUT_SIZE 384`, `CRYPT_MAX_PASSPHRASE_SIZE 512`, `CRYPT_DATA_RESERVED_SIZE 767`, `CRYPT_DATA_INTERNAL_SIZE 30720`.

  `CRYPT_DATA_SIZE = 32_768` et `CRYPT_OUTPUT_SIZE = 384` (`:33`, `:36`) sont exacts. La version installée dans l'image d'exécution est la même (4.5.1-1).
- **Signature** (`:69-79`) : `char *crypt_rn(const char *, const char *, void *, int)` correspond à `ADDRESS(ADDRESS, ADDRESS, ADDRESS, JAVA_INT)`. Aucun `Linker.Option.critical`. Le symbole est résolu une fois, dans `Arena.global()`.
- **Durée de vie des arènes** : `Arena.ofConfined()` en try-with-resources (`:95`). Le `finally` interne (`:112-116`) efface `phrase`, `salt` et `data` **avant** la fermeture de l'arène. La chaîne résultat est copiée sur le tas Java (`getString`) avant l'effacement. Aucun segment ne sort de la méthode : pas d'utilisation après fermeture.
- **Bornes et NUL** :
  - garde préalable (`:92`) : entrée ≤ 72 octets, sans NUL, réglage bcrypt uniquement (`$2a$`/`$2b$`/`$2y$`, 7 à 60 caractères ASCII imprimables). Aucun autre algorithme de libxcrypt n'est joignable ;
  - les tampons sont alloués à zéro avec un octet de plus (`input.length + 1L`, `setting.length() + 1L`), ce qui garantit la terminaison NUL ;
  - la longueur ASCII du réglage est égale au nombre de caractères grâce au filtre `0x21–0x7e` ;
  - le pointeur retourné n'est jamais déréférencé : il est seulement comparé à `data.address()` (`:106`), puisque libxcrypt renvoie `data->output` (offset 0) ou `NULL` ;
  - la lecture est bornée au champ `output` (`asSlice(0, 384)`) ;
  - un résultat vide ou commençant par `*` donne `null`.
- **Sûreté multi-thread** : aucun état mutable partagé. `MethodHandle` est immuable, et chaque appel utilise ses propres tampons. C'est prouvé par `concurrentVerifications` (16 fils × 8 tours).
- **Bibliothèque absente** : `IllegalStateException` dont le message nomme la bibliothèque, `libcrypt1` et `identity.password.bcrypt-engine=java`, sans donnée sensible (`:82-88`). Symbole absent : même message suffixé `(crypt_rn)`.
- **`--enable-native-access=ALL-UNNAMED`** est présent dans :
  - le `Dockerfile` (`JAVA_TOOL_OPTIONS`, vérifié dans l'image construite) ;
  - Surefire et Failsafe (`services/pom.xml`) ;
  - `spring-boot:run` (`identity-service/pom.xml`).

  Le build ne journalise aucun avertissement « restricted method ».

### 4.2 `SymfonyCompatiblePasswordHasher` et configuration ✅

- **Pré-hachage inchangé** (`bcryptInput`, `:165-171`, identique à `main`) : `base64(sha512)` au-delà de 72 octets UTF-8 ou en présence de NUL, puis troncature à 72 octets. La garde native ne fait donc jamais échouer une entrée légitime.
- Couverture par `sameOutputAsJavaEngine` :
  - sortie identique au moteur Java pour `$2y$`, `$2a$` et `$2b$` ;
  - coûts 4 et 13 ;
  - 72 et 73 octets, NUL, 100 octets, UTF-8 multi-octets.
- Les vecteurs golden PHP sont rejoués sur le moteur natif.
- **Hachage** : sel produit par `BCrypt.gensalt("$2y", cost, random)` avec un `SecureRandom` d'instance. Coût contrôlé dans [4, 31], 13 par défaut, inchangé. Résultat contrôlé (non `null`, 60 caractères), sinon `IllegalStateException`.
- **Vérification** : le hash est recalculé avec le hash stocké comme réglage, puis comparé par `MessageDigest.isEqual` (temps constant). Le comportement est identique à `password_verify`, y compris le refus d'un sel non canonique. Un hash corrompu donne `false` sans exception (`catch RuntimeException`, test `corruptHashNeverThrows`). Argon2 est inchangé.
- **Hash factice** (SC-IDENT-008) : calculé au démarrage **par le même moteur** (`:65`), puis vérifié par `bcryptMatches`, comme le vrai chemin. Le secret aléatoire est effacé, et les entrées `byte[]` sont effacées dans des `finally`.
- **Câblage** :
  - `identity.password.bcrypt-engine` vaut `${IDENTITY_BCRYPT_ENGINE:native}`, avec `@DefaultValue("native")` dans `IdentityProperties.Password` ;
  - `UseCaseConfig` fait un `switch` exhaustif ;
  - `native` sans bibliothèque lève une exception à la création du bean, donc le service **refuse de démarrer** ;
  - une valeur inconnue échoue au binding de l'énumération, donc refus de démarrer aussi.
- **Hexagonal** : le choix du moteur reste dans `config` et `adapter/out/security`. Les ports, le domaine et l'application sont intacts (ArchUnit 10/10).

## 5. Points

### Bloquants
Aucun.

### Condition avant fusion
- **C-B1 (Manuel, CI)** : obtenir l'analyse Trivy de l'image identity (job `identity-image` ou exécution manuelle avec une base Trivy disponible) : aucune vulnérabilité HIGH/CRITICAL corrigeable introduite par B1, et `libcrypt1` sans CVE ouverte. Le constat de repli (§2.4) est favorable, mais il ne remplace pas l'analyse.

### Mineurs
- **m1 (BackAli)** : l'ADR A2 de `docs/architecture/backend-hexagonal.md` décrit encore `BCryptPasswordEncoder` et dit la comparaison « assurée par les encodeurs Spring ». Il faut y décrire le moteur natif FFM, la propriété `identity.password.bcrypt-engine`, `--enable-native-access` et la comparaison `MessageDigest.isEqual`. La liste des variables de l'ADR A13 doit recevoir `IDENTITY_BCRYPT_ENGINE` (et `IDENTITY_BCRYPT_COST`, absente elle aussi). C'est prévu au §5.4 de l'ordre de travail. Signalé, car BackCodeur ne l'a pas fait (document de BackAli). Ajouter éventuellement la variable dans `.env.example` (Manuel), avec le défaut `native`.
- **m2 (BackTesteur, non bloquant, déjà noté par BackCodeur)** : `BcryptLatencyIT` mesure `new SymfonyCompatiblePasswordHasher(13)`, pas le bean Spring. Le lien entre les deux est couvert par `BcryptEngineDefaultIT#nativeEngineByDefault`, qui prouve que le bean utilise `LibxcryptBcryptEngine`. Risque résiduel faible ; la mesure de bout en bout reste R8.
- **m3 (BackTesteur)** : le refus de démarrer du **contexte** quand la bibliothèque est absente n'est prouvé qu'au niveau du constructeur (`missingLibraryFailsExplicitly`). Il en va de même pour une valeur inconnue de `bcrypt-engine`. On pourrait ajouter un test de démarrage du contexte, sur le modèle de `InternalSecretStartupTest` (par exemple avec un nom de bibliothèque injectable, ou une valeur `bcrypt-engine=inconnu`).
- **m4 (BackCodeur, information)** : les branches non couvertes de `LibxcryptBcryptEngine` sont des gardes inatteignables en pratique :
  - `catch Throwable` ;
  - symbole absent ;
  - pointeur résultat différent de `data` ;
  - sortie commençant par `*` alors que `crypt_rn` renvoie `NULL`.

  Si `output` n'était pas terminé par NUL dans les 384 octets, `getString` lèverait une exception au lieu de renvoyer `null`. Ce n'est jamais le cas avec libxcrypt, et `matches` le transformerait en `false`. Aucune action requise.
- **m5 (BackCodeur, style)** :
  - le constructeur `public SymfonyCompatiblePasswordHasher(int, BcryptEngine)` expose une interface package-private ;
  - l'énumération `IdentityProperties.Password.BcryptEngine` porte le même nom que l'interface de l'adapter ;
  - le commit est typé `feat(identity)` alors que l'ordre de travail demandait `perf(identity)`.

  Sans effet fonctionnel.
- **m6 (Manuel, hors B1)** : l'image de base `eclipse-temurin:25-jre` figée a des mises à jour de sécurité Ubuntu en attente (`libexpat1`, `perl-base`, `tzdata`). Rafraîchir le digest lors de la prochaine mise à jour de l'image.
- **m7 (Paul / BackAli, R8)** : la parité est prouvée sur le banc relatif (rapports 1,00 et 1,01). Les critères L1–L5 restent à mesurer de bout en bout par R8 (protocole §6.1), comme prévu au §5.4.
