# Ordre de travail — Phase 1 : B1, latence bcrypt d'identity-service

```
Fonctionnalité : B1 — POST /api/auth/login (et register) plus lents via identity-service que via Symfony
Origine : docs/reviews/2026-11-26-identity-rollback-rehearsal.md (branche chore/identity-rollback-rehearsal), §5 et §7 B1
Spec : docs/specs/identity.md (§ performance, SC-IDENT-008, SC-IDENT-023)
Architecture : docs/architecture/backend-hexagonal.md (ADR A2 hachage), docs/architecture/identity-migration.md
Service : identity-service (adapter out.security uniquement)
Branche : perf/identity-bcrypt-latency (depuis main 934dd2c ; aucun push sans accord de l'utilisateur)
1. BackTesteur  — §5.1
2. BackCodeur   — §5.2
3. BackReviewer — §5.3
4. R8 (répétition, mesures) — §5.4
```
Auteur : BackAli, 26/11/2026. Le seuil d'acceptation (absolu < 500 ms ou relatif à Symfony) est décidé par Paul en parallèle ; ce document traite la cause et la correction.

## 1. Constat (répétition du 26/11)

| Amont | Endpoint | p50 (ms) | p95 (ms) |
|---|---|---|---|
| identity | `POST /api/auth/login` (200 séquentiels, via nginx) | 605,5 / 609,5 | **641,6 / 643,5** |
| Symfony | `POST /api/auth/login` | 459,7 | 498,3 |
| identity / Symfony | `POST /api/auth/register` (p95) | — | 659 / 493 |
| identity / Symfony | `GET /api/me` (p95) | — | 5,7 / 5,1 |

Écart login : **+146 ms en p50, +143 ms en p95** (+29 à +32 %). `GET /api/me` est équivalent : l'écart n'est ni réseau, ni nginx, ni JWT.

## 2. Diagnostic

### 2.1 Lecture du code (ce que fait une connexion)

`AuthenticateService.authenticate` → une requête `findByLoginEmail` → **une seule** vérification bcrypt (`hasher.matches`) ou, si l'e-mail est inconnu, **une seule** vérification factice (`verifyAgainstDummy`) → émission RS256 (Nimbus). Vérifié :
- pas de double vérification : le chemin factice n'est emprunté que si l'e-mail est inconnu ; pas de `upgradeEncoding`, pas de re-hachage au login, pas de `DelegatingPasswordEncoder`, pas de `DaoAuthenticationProvider` (`UserDetailsServiceAutoConfiguration` exclue) ;
- pas de `sleep` ni d'attente d'égalisation : l'égalisation SC-IDENT-008 repose sur un calcul de même coût, le hash factice est calculé une fois au démarrage ;
- pas d'outbox ni d'appel HTTP au login (l'outbox et l'appel synchrone `InvitationClaimPort` ne concernent que l'inscription, A10).

Implémentation : `SymfonyCompatiblePasswordHasher` appelle `org.springframework.security.crypto.bcrypt.BCrypt` (spring-security-crypto 7.2.0, **Java pur**) : `checkpw(byte[], String)` au login, `hashpw` à l'inscription. PHP utilise `crypt_blowfish` d'Openwall, **en C**, intégré à PHP.

### 2.2 Micro-bancs (Apple M1, Docker Desktop 8 CPU, conteneurs jetables `--rm`)

Même mot de passe, même hash `$2y$13$` produit par PHP. Java : image d'exécution d'identity `eclipse-temurin:25-jre@sha256:4ed132f6…` (Java 25.0.5.1), `--memory=768m`, `JAVA_TOOL_OPTIONS` de l'image (`-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError`), 5 appels de chauffe exclus, n = 30. PHP : image `taskora-api` (PHP 8.4.28), 3 appels de chauffe, n = 30.

| Implémentation (vérification coût 13) | 1ᵉʳ appel | min | p50 | p95 | Rapport p50 / PHP |
|---|---|---|---|---|---|
| PHP `password_verify` (référence Symfony) | — | 436–439 | **440–443** | 524–527 | 1,00 |
| **Spring `BCrypt.checkpw` (actuel)** | 611 | 587 | **596** | 700 | **1,35** |
| Spring `BCrypt.hashpw` `$2y$` 13 (inscription) | 589 | 587 | 592 | 671 | 1,34 (PHP `password_hash` : 442) |
| Bouncy Castle 1.87 `OpenBSDBCrypt.checkPassword` | 807 | 552 | 571 | 723 | 1,29 |
| `at.favre.lib:bcrypt` 0.10.2 | 661 | 588 | 594 | 629 | 1,34 |
| **libxcrypt 4.5.1 (`libcrypt.so.1` de l'image JRE) via FFM `crypt_rn`** | 514 | 437 | **443** | 500 | **1,00** |

Réglages JVM / conteneur (Spring `checkpw`, n = 20) — aucun gain :

| Variante | GC choisi | p50 | p95 |
|---|---|---|---|
| `--memory=768m` (configuration actuelle) | Serial | 596 | 714 |
| `--memory=2g` | G1 | 612 | 667 |
| `--memory=768m` + `-XX:+UseG1GC` | G1 | 619 | 767 |
| `-XX:ActiveProcessorCount=1` | Serial | 606 | 663 |
| `--cpus=1` (quota CPU) | Serial | **659** | **782** |
| `-XX:-TieredCompilation` | Serial | 675 | 747 |

Le compilateur JIT est chaud dès le 2ᵉ appel (1ᵉʳ appel 611 ms, puis plancher 587 ms) : la répétition excluait déjà la chauffe. Le minimum reste à ~587 ms quels que soient GC, mémoire ou nombre de CPU : c'est le coût du code Blowfish compilé par C2, pas la configuration. Aucune limite CPU n'est posée sur `identity` dans `docker-compose.yml` (seulement `mem_limit: 768m`) ; en poser une dégraderait la latence.

Compatibilité vérifiée pour libxcrypt : hash `$2y$13$` produit par libxcrypt → `password_verify` PHP = `true` ; `$2a$04$` identique à `crypt()` PHP ; `$2b$` accepté ; hash tronqué → `NULL` (donc `false`, sans exception).

### 2.3 Répartition de l'écart

| Contributeur | Login p50 identity | Login p50 Symfony | Écart |
|---|---|---|---|
| Vérification bcrypt coût 13 | ~596 | ~440 | **≈ +152 ms** |
| Reste (requête SQL, JWT, framework, nginx, client) | ~10 | ~20 | ≈ −10 ms |
| **Total mesuré en répétition** | 605,5 | 459,7 | **+146 ms** |

Inscription (p95 : +166 ms) : ≈ +150 ms de `hashpw` Java, et ≈ 15 ms non expliqués par le hachage, attribués **par hypothèse** (non mesuré ici, aucune stack démarrée) à l'appel synchrone de consommation d'invitations vers Symfony (A10). À confirmer en R8 ; hors périmètre de cette correction.

**Cause unique de B1 : bcrypt en Java pur est ~35 % plus lent que le C de PHP à coût égal.** Toutes les implémentations Java pures disponibles sont dans le même ordre de grandeur (571–596 ms).

## 3. Options (contraintes : hashes `$2y$` coût 13 compatibles Symfony pendant la coexistence ; coût jamais baissé ; garantie SC-IDENT-008 / `LoginTimingIT` inchangée ; dépendances figées et sans alerte OWASP)

| Rang | Option | Gain attendu (p50 login) | Évaluation |
|---|---|---|---|
| **1** | **bcrypt natif : `crypt_rn` de libxcrypt (`libcrypt.so.1`, déjà présent dans `eclipse-temurin:25-jre`, paquet Ubuntu `libcrypt1`) appelé par l'API FFM `java.lang.foreign` de Java 25** | **≈ −150 ms** (parité PHP : même algorithme Openwall en C) | Aucune dépendance Maven ajoutée ; bibliothèque système suivie par le digest de l'image et l'analyse d'image (Trivy). Exige `--enable-native-access=ALL-UNNAMED`. Code natif → revue mémoire stricte (§5.2). **Retenue.** |
| 2 | Bouncy Castle `OpenBSDBCrypt` (déjà en dépendance, 1.87) | ≈ −25 ms, p95 pire (723) | Insuffisant. |
| 3 | `at.favre.lib:bcrypt` 0.10.2 | ≈ 0 | Aucun gain, dépendance de plus. Rejetée. |
| 4 | Réglage JVM / compose (GC, mémoire, CPU, JIT) | 0 à négatif | Mesuré sans effet : **pas de correction de pure configuration**, rien à demander à Manuel pour la compose. |
| 5 | Bibliothèque JNI/JNA tierce embarquant un binaire natif | ≈ −150 ms | Même gain que l'option 1 avec un binaire tiers et une surface OWASP supplémentaires. Rejetée. |
| — | Baisser le coût, re-hacher, mettre en cache, paralléliser | — | Interdit (coût 13, compatibilité) ou sans objet (bcrypt est séquentiel). |

Projection après l'option 1 : login p50 ≈ 443 + 10 ≈ **455 ms**, p95 ≈ **500 ms**, soit la parité avec Symfony (498 ms). Un seuil **absolu** p95 < 500 ms sur M1 / Docker Desktop resterait à la limite, comme pour Symfony lui-même : c'est la décision de Paul (seuil relatif ou matériel de référence).

## 4. Choix

Option 1. Le port `PasswordHasherPort`, le format des hashes, le pré-hachage `base64(sha512)` > 72 octets ou NUL, la vérification argon2 (Spring/BC, inchangée), le coût 13 et le hash factice restent identiques ; seul le **moteur** bcrypt change, et il change **pour les deux chemins** (vrai hash et hash factice), ce qui préserve SC-IDENT-008. Le moteur Java (Spring `BCrypt`) reste disponible par configuration explicite pour le développement hors Linux (macOS n'a pas `libcrypt.so.1`).

## 5. Étapes

### 5.1 BackTesteur (tests rouges d'abord ; commit `test(identity): …`)

1. **Garde de performance relative** `BcryptLatencyIT` (Failsafe, groupe par défaut, `@DisplayName` « B1 — … ») :
   - lance par Testcontainers l'image PHP du backend **figée par digest** (`dunglas/frankenphp:1-php8.4-alpine@sha256:f81693fa548684eb12964eeab78d86325b75c5cd91289c09ffc4dd4404a641e5`, déjà présente localement) et y mesure, **dans le conteneur** (`hrtime`), la médiane de 15 `password_verify` coût 13 après 3 appels de chauffe ; récupère aussi le hash `$2y$13$` produit par PHP ;
   - mesure ensuite, **séquentiellement** (jamais en même temps que PHP), la médiane de 15 `SymfonyCompatiblePasswordHasher(13).matches` sur ce hash après 5 appels de chauffe, et vérifie `true` ;
   - affirme `médiane Java ≤ 1,15 × médiane PHP` (rapport mesuré aujourd'hui : 1,35 → **rouge** ; attendu après correction : ≈ 1,00). Message d'échec avec les deux médianes ;
   - même contrôle pour `hash` (coût 13) contre `password_hash`.
   Comparaison relative sur la même machine : indépendante du matériel du CI.
2. **Compatibilité du moteur natif** (unitaires, `SymfonyCompatiblePasswordHasherTest` ou classe dédiée, sans modifier les cas existants) : sortie identique entre moteur natif et moteur Java pour un même sel (`$2y$`, `$2a$`, `$2b$`, coûts 4 et 13, 72 et 73 octets, NUL, 100 octets, UTF-8 multi-octets) ; vecteurs PHP → Java et Java → PHP existants rejoués avec le moteur natif ; hash tronqué, préfixe inconnu, sel invalide → `false` sans exception ; 16 vérifications concurrentes correctes (sûreté multi-thread) ; moteur natif demandé mais bibliothèque introuvable → échec explicite au démarrage du contexte (message sans donnée sensible) ; le bean du contexte Spring utilise le moteur natif par défaut.
3. **`LoginTimingIT` inchangé** (30 essais, écart des médianes < 50 %) : doit rester vert.
4. `services/pom.xml` (propriété BackTesteur) : ajouter `--enable-native-access=ALL-UNNAMED` à l'`argLine` de Surefire et Failsafe, sans rien changer d'autre (inclusions, exclusions, tags, seuils).
5. Créer le squelette (signatures, corps `throw new UnsupportedOperationException("BackCodeur")`) : interface package-private `BcryptEngine` (`String crypt(byte[] input, String setting)`, retour `null` si échec), `LibxcryptBcryptEngine`, `JavaBcryptEngine`, constructeur `SymfonyCompatiblePasswordHasher(int cost, BcryptEngine engine)` ; le constructeur existant `(int cost)` garde sa signature et utilisera le moteur natif.
Les tests s'exécutent sous Linux (conteneur `eclipse-temurin:25-jdk` et CI), où `libcrypt.so.1` existe.

### 5.2 BackCodeur (jusqu'au vert, sans toucher aux tests ; commit `perf(identity): …`)

1. `LibxcryptBcryptEngine` (adapter `out.security`) : `SymbolLookup.libraryLookup("libcrypt.so.1", Arena.global())` et `downcallHandle` de `crypt_rn(const char *phrase, const char *setting, void *data, int size)` résolus **une fois** au démarrage ; à chaque appel, `Arena.ofConfined()` (try-with-resources), tampon de travail de 32 768 octets (`sizeof(struct crypt_data)` de libxcrypt 4.x), phrase copiée depuis `bcryptInput(...)` (≤ 72 octets, jamais de NUL : affirmer les deux) suivie d'un NUL ; lecture du résultat bornée (`reinterpret(128)`), `NULL` ou résultat commençant par `*` → échec ; tampon et phrase effacés (`fill(0)`) avant fermeture.
2. `SymfonyCompatiblePasswordHasher` : `hash` = sel par `BCrypt.gensalt("$2y", cost, random)` (Spring, génération de sel seulement) puis moteur ; `matches` (bcrypt) et `verifyAgainstDummy` = moteur, comparaison du résultat au hash stocké par `MessageDigest.isEqual` (temps constant) ; argon2 inchangé ; toujours `false`, jamais d'exception, sur hash corrompu.
3. Propriété `identity.password.bcrypt-engine` (`native` par défaut, `java` réservé au développement hors Linux, documenté dans `IdentityProperties`) ; `native` indisponible → **refus de démarrer**, comme pour le secret interne.
4. `services/identity-service/Dockerfile` : ajouter `--enable-native-access=ALL-UNNAMED` à `JAVA_TOOL_OPTIONS`. Aucun changement de compose : rien pour Manuel à ce stade.
5. Aucune dépendance Maven ajoutée ; coût, format et pré-hachage inchangés ; aucune journalisation de mot de passe ou de hash.

### 5.3 BackReviewer (rapport `docs/reviews/<date>-identity-perf-b1.md`)

- Intégrité : `git diff <commit test>..HEAD` ne touche aucun fichier de test ; `LoginTimingIT`, ses 30 essais et son seuil 0,5 inchangés ; seuil 1,15 de `BcryptLatencyIT` inchangé.
- Outillage : `./mvnw -B -ntp -pl identity-service -am verify` et `-Pquality` (Checkstyle, PMD, SpotBugs, Sonar, JaCoCo), ArchUnit, OWASP dependency-check (aucune dépendance nouvelle), analyse de l'image (Trivy) avec l'état de `libcrypt1`.
- Non-affaiblissement : hashes produits `$2y$13$`, coût non baissé, vecteurs croisés PHP ↔ Java verts, chemin factice sur le même moteur, comparaison à temps constant, démarrage refusé sans bibliothèque.
- Revue mémoire du code FFM : bornes, terminaison NUL, durée de vie des `Arena`, absence d'état partagé, effacement des tampons.

### 5.4 Recette BackAli, puis R8

- BackAli : mettre à jour l'ADR A2 (moteur bcrypt natif via FFM, propriété `identity.password.bcrypt-engine`, `--enable-native-access`).
- Répétition sur une stack `taskora-rehearsal` neuve, au minimum R7–R9 avec les mesures R8 : 200 logins et 200 inscriptions séquentiels par amont, chauffe exclue, identity **et** Symfony sur le même hôte ; idéalement aussi sur le matériel cible. Critère : celui que fixe Paul. Vérifier au passage le résiduel de l'inscription (≈ 15 ms, hypothèse A10).

## 6. Mode opératoire du diagnostic (reproductible)

Conteneurs `docker run --rm` uniquement (aucune stack compose démarrée, projet `taskora` non touché, aucune image créée) : banc Java compilé dans `eclipse-temurin:25-jdk@sha256:ce2ef9d2…` et exécuté dans `eclipse-temurin:25-jre@sha256:4ed132f6…` avec les options de l'image ; `at.favre.lib:bcrypt` récupéré par `./mvnw dependency:get` dans un dépôt Maven jetable ; PHP mesuré par `php -r` dans l'image `taskora-api`. Hash et mot de passe de banc jetables, aucun compte réel lu.
