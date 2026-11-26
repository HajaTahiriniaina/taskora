# Revue BackReviewer — identity-service, 3e passage (R1/R2)

- Date : 26/11/2026
- Branche : `feat/identity-service`
- Revues précédentes :
  - `docs/reviews/2026-11-26-identity-service.md` (`2a50f3f`) ;
  - `docs/reviews/2026-11-26-identity-service-r2.md` (`a4dac63`, REFUSÉ sur R1 et R2).
- Commits revus :
  - `4dc1c31` (Manuel) : `-DskipITs` dans les jobs qualité et sécurité ;
  - `797825d` (BackAli) : Sonar `e3`, A12, A16, conditions C1–C4 ;
  - `6e617bb` (BackTesteur) : tests R1. **Nouvelle référence d'intégrité.**
- Aucun secret, hash, jeton ni donnée personnelle dans ce rapport.

## 1. Verdict : **ACCEPTÉ**

R1 et R2 sont levés :
- `JpaUnitOfWork` est couvert à 15/15 lignes et 12/12 branches ;
- le quality gate Sonar est vert, avec 87,6 % de couverture sur tout le code modifié depuis la revue 1 et 0 nouveau problème ;
- tous les autres outils sont verts ;
- l'intégrité est respectée.

Comme décidé, B5/B7 et les conditions **C1–C4** (identity-migration.md) restent à lever avant la bascule. Ce verdict n'en dispense pas.

## 2. Résultats des outils

Toutes les commandes Maven ont été lancées depuis `services/` dans `eclipse-temurin:25-jdk`, avec `./mvnw -B -ntp -pl identity-service -am`, avec un `clean` entre le build de tests et le build qualité.

| # | Outil | Commande | Statut | Mesures |
|---|---|---|---|---|
| 1 | Tests + JaCoCo | `clean verify` | ✅ | Surefire 596/596 (573 + 23 nouveaux), Failsafe 250/250 (248 + 2), « All coverage checks have been met », 0 `SQLTimeoutException`. |
| 1b | Couverture JaCoCo (fusionnée) | — | ✅ | domaine : lignes 100 %, branches 100 % ; application : 99,0 % / 95,0 % ; module : lignes 93,2 % (contre 89,4 % au 2e passage), branches 83,2 %. `JpaUnitOfWork` : 15/15 lignes, 12/12 branches. `LegacyImportRunner` : 38/38 lignes, 12/13 branches. Les chiffres de BackTesteur sont confirmés. |
| 2 | Checkstyle + Nullness + Spotless | `clean -Pquality verify -DskipTests -DskipITs` | ✅ | 0 violation Checkstyle (2 modules). Nullness : 94 sources compilées par le compilateur forké avec le checker, 0 erreur. Surefire et Failsafe sautés : `-DskipITs` est bien pris en compte, ce qui confirme le correctif de `4dc1c31`. |
| 3 | Spotless (gjf 2.0.0) | `clean spotless:check` | ✅ | 0 écart. |
| 4 | OpenRewrite | `clean rewrite:dryRun` | ✅ | « would make no changes ». |
| 5 | Sonar, analyse A (référence : revue 1) | `sonar:sonar` sur les résultats de l'étape 1 | ✅ **gate PASSED** | `new_coverage` **87,6 %** (98 lignes à couvrir, 8 non couvertes) ≥ 80 ; `new_violations` **0** ; duplication du nouveau code 0 %. Globalement : couverture 90,9 %, 0 bug, 0 vulnérabilité, 0 hotspot, 0 code smell, notes A/A, 3538 lignes. |
| 5b | Sonar, analyse B (référence : 2e passage) | idem | ✅ **gate PASSED** | `new_violations` 0, duplication 0 % ; `new_lines_to_cover` = 0, donc la condition de couverture ne s'applique pas. **0 problème ouvert** sur le projet : S5998 est absorbé par `e3`, S4502 et S2077 par `e1` et `e2`. |
| 6 | OWASP | — | ⏳ condition **C1** | À prouver en CI après push (accord de l'utilisateur). |
| 7 | PHPUnit et composer audit | — | non relancés | Aucun changement sous `backend/` depuis le 2e passage (13/13 verts, audit vide). |

Jeton admin local révoqué. La référence « nouveau code » du projet a été remise à sa valeur héritée, puis SonarQube a été arrêté (`stop`, pas de `down -v`).

### 2.1 Référence « nouveau code » utilisée, et pourquoi
- Le coordinateur demandait comme référence l'analyse du 2e passage (`3183387c…`, 09:35, avec couverture). C'est **l'analyse B**. Mais aucun code de production n'a changé depuis `5a61a74` (§3), si bien que le « nouveau code » de cette référence ne contient **aucune** ligne à couvrir. Son gate est vert, mais il ne dit rien de la couverture de R1.
- Pour juger R1 honnêtement, j'ai donc d'abord lancé **l'analyse A** avec la référence `SPECIFIC_ANALYSIS` réglée sur l'analyse de la revue 1 (`0830e59c…`, 08:17). Son nouveau code est exactement celui que BackCodeur a modifié pendant la boucle 1 : les 98 lignes du 2e passage, dont `JpaUnitOfWork` et `LegacyImportRunner`.
- Cette référence était défavorable au 2e passage uniquement parce que l'analyse de la revue 1 avait été faite sans couverture. Ce n'est plus vrai : l'analyse A est faite avec la couverture complète (tests unitaires et d'intégration).
- Résultat : 87,6 %, au-dessus du seuil de 80 %.
- Les deux gates sont verts. C'est **le gate A** qui lève R1.

## 3. Intégrité ✅

- **`6e617bb`** n'ajoute que des tests :
  - `JpaUnitOfWorkTest` (10 tests, nouveau) ;
  - `LegacyImportRunnerTest` (13 tests, nouveau) ;
  - `LegacyImportIT` : diff **uniquement en ajouts** (0 ligne retirée) : 2 tests (`targetEmailConflictAborts`, `runnerExitCodes`), 2 imports et 1 champ `@Autowired` ;
  - `traceability.md`.
  Aucune assertion existante n'est modifiée.
  - `JpaUnitOfWorkTest` couvre :
    - 1213 rejoué ;
    - `40001` rejoué ;
    - 1213 enfoui dans la chaîne des causes ;
    - 2 interblocages puis succès (3 essais) ;
    - 3e interblocage propagé (exactement 3 essais, 3 annulations, 0 validation) ;
    - attente de verrou 1205 non rejouée ;
    - `PessimisticLockingFailureException` sans cause SQL non rejouée ;
    - 1062 non rejouée ;
    - autre exception non rejouée ;
    - cas nominal.
- **`797825d`** ne change dans `services/pom.xml` que des propriétés Sonar (`e3` et le commentaire A16), plus des documents d'architecture. Aucun seuil, ni configuration Surefire, Failsafe ou JaCoCo, n'est touché.
- **`4dc1c31`** : `-DskipITs` ajouté aux jobs `identity-quality` et `identity-security`, et une ligne dans le README CI. Rien d'autre.
- **Code de production** : `git diff 5a61a74..HEAD -- services/identity-service/src/main backend/src` est **vide**.
- Aucun `@Disabled`, `assume*` ni skip ajouté.

## 4. Revue manuelle exigée par A16 (fichiers `e2` et `e3`)

- **`domain/model/PhpEmailValidator.java`** (`e3`, S5998) ✅ : `isValid` calcule les octets UTF-8 et renvoie `false` au-delà de 320 octets (`:69-72`) **avant** l'unique `EMAIL.matcher(...)` (`:73`). L'expression régulière est compilée une seule fois (`:44`) et n'est utilisée nulle part ailleurs. La pile reste donc bornée, et l'exclusion est justifiée.
- **`adapter/out/legacy/JdbcLegacyUserSource.java`** (`e2`, S2077) ✅ :
  - opérandes concaténés dans le SQL : la constante `TARGET` ; les constantes `SOURCE_ROW` et `TARGET_ROW` ; `source` (= `LegacySchema.userTable()`, schéma validé `[a-z_]+` au démarrage) ; et, dans `metrics(table, email, hash, row)`, uniquement les littéraux des appels internes des lignes 96 et 106 ;
  - le seul entier concaténé (`conflicts`) va dans le message d'exception, pas dans le SQL ;
  - aucune entrée utilisateur, aucune valeur de ligne : les valeurs passent en paramètres ou restent dans le SQL côté serveur.

## 5. Décisions sur les trois remarques de BackTesteur

| Remarque | Décision | Motif |
|---|---|---|
| L'exception du contrôle préalable remonte par l'échec du démarrage, sans code de sortie dédié | **Non bloquant** | `LegacyImportRunner.run` laisse l'`IllegalStateException` remonter : Spring Boot interrompt alors la commande avec un code de sortie non nul, donc la procédure de bascule (« code ≠ 0 si incohérent ») est respectée. Le message ne contient ni e-mail ni valeur (vérifié par `targetEmailConflictAborts`), et aucune écriture n'a lieu. Amélioration possible (BackCodeur, plus tard) : intercepter le conflit et renvoyer un code dédié (par exemple 3) sans pile, pour un journal d'exploitation plus lisible. |
| Le rejeu ne couvre que `PessimisticLockingFailureException` | **Non bloquant** | Les deux chemins de traduction d'un interblocage aboutissent à une sous-classe de `PessimisticLockingFailureException` : JPA/Hibernate (`LockAcquisitionException` → `CannotAcquireLockException`) et JDBC (`SQLErrorCodeSQLExceptionTranslator` : 1213 → `CannotAcquireLockException`, et `DeadlockLoserDataAccessException`, dépréciée). Le filtre sur 1213/`40001` est maintenant prouvé par les tests. Un interblocage hors de cette famille donnerait au pire un 500 générique sans donnée, ce qui est sans risque de sécurité. |
| Un 3e interblocage donne 500 | **Non bloquant** | C'est le comportement voulu : après 3 essais, l'erreur remonte. `ApiErrorHandler` renvoie `{"error":"Erreur interne du serveur."}` sans trace, et la transaction est annulée sans écriture partielle (prouvé : 3 annulations, 0 validation). SC-IDENT-002 (8 inscriptions parallèles : 0 × 500) reste vert. Amélioration possible : répondre 503 avec `Retry-After`, mais il faudrait ajouter cette réponse au contrat. À arbitrer par BackAli après la bascule si la métrique d'interblocages le justifie. |

## 6. Points restants

- **Aucun point bloquant.**
- Conditions avant la bascule (déjà décidées, identity-migration.md) :
  - **C1** : rapport OWASP en CI (CVSS < 7) ;
  - **C2** : runs `identity-service` et `identity-compat` verts sur GitHub (tests miroir et croisés) ;
  - **C3** : changement de la passphrase JWT présente dans l'historique (utilisateur) ;
  - **C4** : quality gate Sonar vert sur le serveur de CI.
  C1 et C2 demandent un push, qui exige l'accord de l'utilisateur.
- Mineurs, sans action requise pour cette boucle :
  - `LegacyImportRunner` a encore 1 branche sur 13 non couverte ;
  - code de sortie dédié pour le conflit de reprise (§5, ligne 1) ;
  - 503 pour un interblocage persistant (§5, ligne 3).
