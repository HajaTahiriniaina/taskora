# Validation Paul — identity-service, phase 1

- Date : 26/11/2026
- Auteur : Paul (chef d'équipe, test planner), étape 7 du circuit (`docs/team/workflow.md`)
- Périmètre : fusion `86b3b07` dans `main` (PR #1) ; validation faite sur `chore/identity-phase1-acceptance` (recette BackAli `66799eb`)
- Références :
  - spécifications `docs/specs/identity.md` (SC-IDENT-001…036) et `docs/specs/phase-1-identity.md` ;
  - matrice `docs/specs/traceability.md` ;
  - revues BackReviewer `2026-11-26-identity-service.md`, `-r2.md`, `-r3.md` (ACCEPTÉ) ;
  - recette BackAli `2026-11-26-identity-service-recette.md` (ACCEPTÉ, N1–N8 non bloquants) ;
  - bascule et retour arrière `docs/architecture/identity-migration.md` §5 et §6.
- Aucun secret, hash, jeton, valeur de `.env` ni donnée personnelle dans ce rapport.
- Ni la suite `compat` ni aucune stack Docker n'ont été lancées pour cette validation. Les preuves d'exécution sont la CI de la PR #1 et le run local de BackAli (596 unitaires, 250 IT, verts).

## 1. Verdict : **VALIDÉ** (backend d'identity-service, phase 1)

Les quatre conditions de validation (`.claude/agents/paul.md`) sont réunies pour le périmètre backend.

| Condition | Constat |
|---|---|
| Chaque scénario critique est couvert par au moins un test qui passe | Les 14 critiques (001, 002, 003, 005, 007, 008, 010, 011, 012, 014, 017, 018, 023, 024) sont couverts côté back, tests verts en local (BackAli) et en CI (PR #1, dont `identity-compat` 32/32). |
| Les rapports des Reviewers ne contiennent aucun point bloquant | r3 ACCEPTÉ ; recette BackAli sans bloquant. |
| Aucun test modifié ni affaibli après le commit des tests | Référence d'intégrité `71cebca` (recette N1, renforcement relu par BackAli). `git diff 71cebca..HEAD` sur `services/identity-service/src/test`, `backend/tests`, les deux POM et `.github/workflows` est **vide**. Aucun `@Disabled`, `assume*` ni `markTestSkipped` dans `services/identity-service/src/test` et `backend/tests`. |
| Matrice de traçabilité à jour | Mise à jour dans ce commit (§3). |

**Portée du verdict.** Il valide le backend. Il **ne vaut pas** sortie de phase 1 ni autorisation de bascule. Le critère de sortie de `phase-1-identity.md` §1 exige aussi le retour arrière testé (§5 ci-dessous). Les volets front (e2e, composants) restent à écrire (§2.2).

## 2. Couverture des scénarios

### 2.1 Méthode
1. Liste des scénarios de `identity.md` et `phase-1-identity.md` : SC-IDENT-001…036, sans trou ni doublon. Les 36 figurent dans la matrice.
2. Existence des références : les 142 références de tests des lignes IDENT (`fichier#méthode` Java, `fichier::méthode` PHPUnit) ont été vérifiées une par une, plus les méthodes listées entre parenthèses pour SC-IDENT-035 (8) et SC-IDENT-036 (3). **Aucun fichier ni aucune méthode manquant.**
3. Contrôle des limites : chaque ligne « Limites » de la spec a été rapprochée des `@DisplayName` / `#[TestDox]` des tests cités. Les contrôles ciblés sont détaillés en §2.3.
4. Suite `identity-compat` : `@Tag("compat")` est porté par `MirrorCompatibilityTest`, `CrossTokenCompatibilityTest` et `CrossPasswordCompatibilityTest`, que sélectionne le profil Maven `compat` (`test.groups=compat`) lancé par `.github/workflows/identity-compat.yml`. Les 32 cas sont : 27 cas miroir (001–003, 007, 008, 014–018, 020), 2 jetons croisés (010) et 3 mots de passe croisés (017, 023). `MirrorScopeTest` n'est pas tagué : il tourne dans le build par défaut et garde le périmètre miroir hors E15–E21.

### 2.2 Résultat par scénario
| Scénarios | Priorité | Back | Front / CI |
|---|---|---|---|
| 001, 002, 003, 005, 007, 008, 010, 011, 012, 014, 017, 018, 023, 024 | critique | couverts, verts | e2e et composants de 001, 002, 003, 005, 007, 008, 014, 017, 018 : **à écrire** (FrontTesteur) |
| 004, 009, 013, 015, 019, 020, 028–036 | important | couverts, verts | e2e 015 : à écrire |
| 006, 016, 021, 022, 031, 034 | secondaire | couverts, verts | e2e 016 et 031 : à écrire ; 022 healthcheck CI (Manuel) : à consigner |
| 025, 026 | important | — (front seul) | **non couverts** : à écrire (FrontTesteur) |
| 027 | secondaire | hors phase 1 (décision U3, gateway) | — |

**Scénarios back non couverts : aucun.** Aucun renvoi à BackTesteur n'est bloquant.

Scénarios non couverts, hors back :
- **SC-IDENT-025** (expiration de session) et **SC-IDENT-026** (gardes de navigation), tous deux importants, sont front seul. Ils vont à **FrontTesteur** via FrontPierre en phase 2. Ils ne relèvent pas de BackTesteur.
- **SC-IDENT-027** : hors phase 1 par décision, aucun renvoi.

### 2.3 Limites et cas critiques : contrôles ciblés
| Scénario | Limite de la spec | Test constaté |
|---|---|---|
| 001 / 024 | hash à préfixe reconnu ; jamais de mot de passe ni de hash dans la réponse | `RegisterApiIT` (hash `$2y$` stocké ; `noSecretInResponse`) |
| 002 | deux inscriptions simultanées → un seul compte, 409, jamais 500 | `ConcurrentRegistrationIT#parallelRegistrations`, `UserPersistenceIT#uniqueIndexCollation` |
| 003 | 8 caractères acceptés ; `ééééééé` refusé (points de code) ; pré-hachage au-delà de 72 octets | `PlainPasswordTest`, `RegisterApiIT#eightAccepted`, `SymfonyCompatiblePasswordHasherTest` (pas de troncature naïve), `CrossPasswordCompatibilityTest#longPassword` |
| 005 | tout ou rien / rejeu idempotent ; tableau en corbeille ; Symfony injoignable | `RegistrationInvitationClaimIT` (timeout, 5xx rejouable, rattrapage à +60 s), PHPUnit `InternalInvitationControllerTest` (rejeu idempotent, adhésion existante, corbeille, secret absent ou faux) |
| 008 | même corps ; écart de temps médian < 50 % ; mot de passe absent des journaux | `LoginApiIT#sameBodyForUnknownAndWrong`, `LoginTimingIT#comparableTimes`, `SecretsDoNotLeakIT` |
| 010 | RS256, `exp − iat = 604800`, `username`, `ROLE_USER` ; jetons croisés dans les deux sens | `NimbusTokenIssuerTest`, `CrossTokenCompatibilityTest` (2 sens, CI) |
| 012 | expiré, signature altérée, `alg: none`, HS256 signé avec la clé publique, autre clé | `NimbusTokenVerifierTest#rejects` (paramétré sur les 5 cas), `#zeroLeeway` |
| 023 | `$2y$` 13/4, `$2a$`, `$2b$`, `$argon2id$` ; hash corrompu → 401, jamais 500 | `SymfonyCompatiblePasswordHasherTest` (golden PHP, `corruptNeverThrows`), `LegacyImportIT`, `CrossPasswordCompatibilityTest` |
| 028–035 | divergences voulues E15–E21 et A18 exclues du miroir | `MirrorScopeTest#mirrorExcludesDivergences` et `#divergencesAreOnlyDecidedOnes` |

### 2.4 Écarts non bloquants (renvoyés à BackTesteur, priorité basse)
| Réf. | Constat | Demande |
|---|---|---|
| P1 | `identity.md` §5, « Concurrence » : l'inscription concurrente avec un ajout de membre sur le même e-mail est couverte **par conception** (claim idempotent, adhésion existante sans doublon, rattrapage à +60 s), sans test qui exécute réellement les deux opérations en parallèle. | BackTesteur : test d'intégration d'une inscription via identity en parallèle d'un `POST` d'ajout de membre Symfony sur le même e-mail. Résultat attendu : une seule adhésion, aucune invitation résiduelle après le rattrapage. À faire avant la bascule si possible, sinon en phase 2 (board-service reprend les invitations). |
| P2 | `identity.md` §6, performance : `POST /api/auth/login` p95 < 500 ms et `GET /api/me` p95 < 50 ms ne sont mesurés par aucun test. `phase-1-identity.md` §6 en fait une mesure de recette. | À mesurer pendant la répétition du retour arrière (§5, étape R8), résultat consigné dans le compte rendu. |
| P3 | Non-régression des autres domaines (SC-BOARD-002, 009, 011, SC-NOTIF-001, SC-PUSH-008, `phase-1-identity.md` §1) : seul `CrossTokenCompatibilityTest#identityTokenOnSymfony` (`GET /api/boards`) l'exerce aujourd'hui. Les tests de ces scénarios sont « à écrire ». | Rejeu manuel pendant la répétition (§5, étape R6), puis tests automatiques en phase 2 (board-service). |

## 3. Mise à jour de la traçabilité (recette N6)

Dix lignes passent de « vert (tests `compat` non exécutés : stack identity requise) » à « vert (`compat` exécuté en CI : PR #1, `identity-compat` 32/32) » : SC-IDENT-001, 007, 008, 010, 014, 015, 016, 017, 018, 023.
- Pour chacune, le test `compat` cité (`MirrorCompatibilityTest#sameAsSymfony`, `CrossTokenCompatibilityTest`, `CrossPasswordCompatibilityTest`, dont `#passwordChangeVisibleToSymfony`) porte `@Tag("compat")` et contient au moins un cas du scénario. Il fait donc partie des 32 cas d'`identity-compat`.
- Aucune autre ligne n'est modifiée, IDENT ou non.
- Le statut reste `vert`, à la demande du coordinateur. Le passage des lignes back à `validé` est acquis sur le fond par ce rapport. Il sera porté dans la matrice avec la consignation de la répétition du retour arrière, afin que `validé` coïncide avec le critère de sortie de la phase.

N6 est soldé. BackTesteur n'a plus d'action sur ce point.

## 4. Préparation de la bascule

| Réf. | Statut | Action restante |
|---|---|---|
| C1 | ✅ remplie | Manuel : reporter le résumé chiffré OWASP dans `docs/ci/requests/backend.md` (N7). |
| C2 | ✅ remplie | — |
| C3 | ⏳ utilisateur | Régénérer la paire de clés JWT avec une **nouvelle** passphrase partout où l'ancienne a servi ; annoncer l'invalidation des jetons. Ensuite, relancer `identity-compat` : `CrossTokenCompatibilityTest` doit rester vert avec la nouvelle paire, lue par Symfony **et** identity. |
| C4 | ⏳ utilisateur | Créer la variable `SONAR_HOST_URL` et le secret `SONAR_TOKEN` sur GitHub ; relancer `identity-service` ; quality gate vert avec les seules exclusions `e1`–`e3` (BackReviewer). |
| Répétition du retour arrière (§6.3) | ⏳ à faire | Plan ci-dessous (§5). |
| Autres | — | N2 (digests des images du backend, Manuel) : recommandé avant la bascule, non bloquant. |

**Ordre recommandé** : C3 d'abord, car elle change les clés que la répétition doit utiliser. Puis C4 et N7, en parallèle. Puis la répétition. La bascule n'est annoncée qu'avec C1–C4 consignées et la répétition verte.

## 5. Plan de répétition du retour arrière (identity-migration.md §6.3)

**Responsables** : BackAli (exécution) et Manuel (stack, nginx). Paul consigne le résultat dans `docs/reviews/<date>-identity-service-rollback-rehearsal.md`.

**Préalables**
- C3 faite (nouvelle paire de clés).
- Stack de recette **distincte** de la stack de travail : projet compose dédié (`-p taskora-rehearsal`) avec ses propres volumes. Jamais de `docker compose down -v` sur la stack `taskora`.
- Copie de la base : `mariadb-dump` de la base de travail restauré dans la stack de recette. Le fichier reste hors dépôt et est supprimé après la répétition. Le compte rendu ne contient aucune donnée personnelle (comptages et statuts seulement).
- Deux comptes de recette créés pour l'exercice, et un tableau de recette avec une invitation en attente pour le compte R1.

**Étapes** (✅ = résultat attendu, à cocher dans le compte rendu)
| # | Étape | Attendu |
|---|---|---|
| R0 | Comptages avant : nombre de lignes `taskora.user`, répartition des préfixes de hash (sans afficher de hash) ; `verify` à blanc | rapport chiffré |
| R1 | Bascule selon §5, étapes 3 à 5 : gel nginx, `--identity.legacy-import=run`, `verify`, routage `/api/auth/` et `/api/me` vers identity | `verify` vert ; gel levé ; durée totale < 5 min (chronométrée) |
| R2 | Contrôles à chaud §5, étape 6 : login d'un compte repris ; `GET /api/me` avec un jeton émis par Symfony avant la bascule ; `GET /api/boards` (Symfony) avec un jeton identity | 200 partout |
| R3 | Via identity : inscription du compte R1 (invité sur le tableau de recette), puis changement de mot de passe de R1 | 201 ; adhésion visible côté Symfony ; outbox `DELIVERED` ; 200 au changement |
| R4 | Retour arrière §6.2 : amont nginx → `api:80`, rechargement sans reconstruction ; vider l'outbox (`PENDING` = 0) | bascule < 1 min ; aucune perte de message |
| R5 | Via Symfony : login de R1 avec le **nouveau** mot de passe (200), puis avec l'ancien (401) ; jeton identity émis en R3 accepté par Symfony | 200 / 401 / 200 |
| R6 | Via Symfony : inscription du compte R2 ; rejeu manuel de SC-BOARD-002, 009, 011, SC-NOTIF-001, SC-PUSH-008 avec un jeton identity (non-régression P3) | conformes aux specs |
| R7 | Re-bascule : §5 depuis l'étape 3 (la copie idempotente rapatrie R2) ; `verify` | `verify` vert |
| R8 | Via identity : login de R1 et de R2 ; mesures p95 sur 200 logins et 1 000 `GET /api/me` (P2) | 200 ; p95 login < 500 ms, `GET /api/me` < 50 ms |
| R9 | Comptages après, comparés à R0 + 2 ; journaux identity et nginx parcourus (aucun mot de passe, hash ni jeton, SC-IDENT-024) ; taux de 5xx | écart = 2 comptes ; 0 fuite ; 0 × 5xx |
| R10 | Nettoyage : arrêt du projet `taskora-rehearsal` et suppression de **ses** volumes uniquement ; suppression du dump | — |

**Critère de réussite** : R1–R9 tous conformes. Tout écart renvoie au pôle concerné (BackAli ou Manuel) avant une nouvelle répétition. Une fois la répétition verte, Paul passe les lignes back IDENT à `validé` dans la matrice et la phase 1 est déclarée sortie.

## 6. Phase 2 : recommandation

### Options
| Option | Prérequis manquants | Valeur |
|---|---|---|
| **A. Front d'identité** (FrontPierre) | `docs/ci/requests/frontend.md` absent ; pas de cible `test` dans `angular.json` ni de `*.spec.ts` (le job `frontend.yml` saute `ng test`) ; pas de `frontend/e2e/` ni de Playwright ; proxy de dev `/api/auth` et `/api/me` vers identity (`phase-1-identity.md` §4.3) | Couvre 025 et 026 (seuls importants non couverts) et les e2e des critiques. C'est le filet de non-régression **à la bascule** d'identity, exécutable contre les deux backends. |
| **B. board-service** (BackAli) | spec `boards-members.md` prête (19 scénarios, 10 critiques) ; contrat OpenAPI à écrire ; reprise des invitations aujourd'hui servies par l'endpoint transitoire Symfony | Suite logique de l'ordre d'architecture (`backend-hexagonal.md` : identity → board → …). Plus gros chantier. |

### Ordre recommandé
1. **Tout de suite, en parallèle de C3, C4 et de la répétition — option A réduite (socle de tests front)** :
   - FrontPierre ouvre `docs/ci/requests/frontend.md`, qui demande à Manuel : cible `test` (builder de tests unitaires Angular), job `frontend-e2e-identity` (Playwright sur la stack compose, URL de backend en variable) et proxy de dev ;
   - FrontTesteur écrit `frontend/e2e/identity.spec.ts` (001, 002, 003, 007, 008, 017, 018, 025, 026) et les tests de composants (intercepteur, gardes, `AuthService.isExpired`), rouges puis verts **sans code applicatif nouveau** : le comportement existe déjà ;
   - FrontReviewer relit, Paul valide 025 et 026.
2. **Bascule d'identity**, avec ces e2e joués contre Symfony puis contre identity : ils deviennent une condition supplémentaire de non-régression.
3. **Puis option B, board-service** :
   - Paul complète `boards-members.md` : reprise des invitations, fin de l'endpoint transitoire, scénarios de non-régression P3 ;
   - BackAli publie le contrat et l'ordre de travail ;
   - ensuite le circuit habituel Testeur → Codeur → Reviewer. P1 est repris dans ce périmètre s'il n'a pas été fait avant.

Raison de cet ordre : le front ne bloque pas la bascule mais la sécurise à faible coût (aucun code de production), et il ferme les deux seuls scénarios importants non couverts. Lancer board-service avant la bascule d'identity ferait coexister deux migrations sans filet e2e.

## 7. Suites
| Qui | Action |
|---|---|
| Utilisateur | C3, puis C4 ; accord pour le push de cette branche |
| Manuel | N2, N7 ; run `identity-sonar` après C4 ; stack de répétition `taskora-rehearsal` |
| BackAli et Manuel | Répétition R0–R10 (§5) |
| BackTesteur | P1 (non bloquant) |
| FrontPierre et FrontTesteur | `docs/ci/requests/frontend.md`, puis e2e et composants d'identité (§6, point 1) |
| Paul | Consigner la répétition ; matrice IDENT back → `validé` ; spec board-service complétée |
