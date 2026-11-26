# Décision Paul — critère de latence de la connexion et de l'inscription (B1)

- Date : 26/11/2026
- Auteur : Paul (chef d'équipe, test planner)
- Destinataires : BackAli, Manuel, utilisateur
- Décision : **PERF-IDENT-1**, texte normatif dans `docs/specs/identity.md` §6.1
- Origine : constat B1 de `docs/reviews/2026-11-26-identity-rollback-rehearsal.md` (R8)
- Ce document ne contient ni secret, ni hash, ni jeton, ni donnée personnelle.

## 1. Problème
Pendant la répétition, les deux amonts ont été mesurés sur la même stack et la même machine (Apple M1, Docker Desktop, à travers nginx, 200 connexions séquentielles) :
- connexion via identity-service : p95 de 641,6 et 643,5 ms ;
- connexion via Symfony : p95 de 498,3 ms ;
- inscription : 658,7 ms contre 492,5 ms ;
- `GET /api/me` : 5,7 ms contre 5,1 ms.

Le seuil absolu « login p95 < 500 ms » est surtout un budget bcrypt (coût 13, environ 0,4 à 0,5 s par opération). Symfony ne le respecte que de justesse sur ce poste.

## 2. Décision
1. **Critère relatif pendant la coexistence**, pour la connexion et l'inscription. Il est mesuré dans la même session, sur la même stack, en blocs alternés.
   - L1 : p95 identity ≤ 1,15 × p95 Symfony.
   - L2 : p50 identity ≤ 1,15 × p50 Symfony.
   - L3 : p95 ≤ 1 000 ms en valeur absolue.
   - L5 : 0 × 5xx.
   - Objectif de conception (L4) : ratio ≤ 1,10.
2. **Après la coexistence** : même calcul contre le p95 Symfony **figé** de la dernière mesure conforme, sur le même matériel. Si le matériel change, la référence est remesurée.
3. **`GET /api/me`** : le critère absolu p95 < 50 ms est inchangé. Aucun critère relatif : l'écart est inférieur à 1 ms.
4. **Coût bcrypt 13 intouchable.**
   - Il est imposé par la coexistence (A2 de `phase-1-identity.md` ; retour arrière avec des hashes lisibles par PHP, `identity-migration.md` §6.2).
   - Le baisser affaiblirait les hashes sans accélérer la connexion des comptes existants, puisqu'un hash se vérifie au coût qu'il contient.
   - Sont aussi interdits : mettre en cache une vérification, raccourcir le chemin « e-mail inconnu » (SC-IDENT-008), abandonner la comparaison à temps constant.
5. **Protocole de mesure** :
   - par bloc, 50 requêtes de chauffe, puis 200 mesures ;
   - deux blocs par amont, soit 400 mesures ;
   - requêtes séquentielles pour le critère ; une mesure à 4 requêtes simultanées est consignée à titre informatif ;
   - un écart supérieur à 5 % entre les deux blocs d'un même amont invalide la mesure ;
   - mêmes limites de ressources que le `docker-compose.yml` versionné.
6. **Matériel** : le poste de recette suffit pour R8, puisque le critère est relatif. Si la production tourne sur un autre matériel, le protocole y est rejoué avant d'annoncer la bascule.

Justification de la marge de 15 % :
- le bruit de mesure entre deux échantillons d'un même amont est inférieur à 1 % ;
- 15 % représentent environ 75 ms sur une action faite environ une fois par semaine (jeton de 7 jours) ;
- un surcoût de 30 % révèle un défaut d'implémentation ou de ressources, pas une limite de bcrypt.

## 3. B1 au regard du critère
| Route | Ratio p50 | Ratio p95 | Verdict |
|---|---|---|---|
| connexion | 1,32–1,33 | 1,29 | L1 et L2 non conformes |
| inscription | 1,33 | 1,34 | L1 et L2 non conformes |

**B1 reste bloquant.**

Cible pour BackAli, contre la référence Symfony actuelle (la nouvelle mesure fait foi) :
- connexion : p95 ≤ ~573 ms et p50 ≤ ~529 ms ;
- inscription : p95 ≤ ~566 ms et p50 ≤ ~531 ms.

Cela représente au moins 70 ms de gain par connexion et 90 ms par inscription, à coût égal. L'objectif L4 est d'environ 548 et 542 ms.

Pistes laissées à l'analyse de BackAli, sans les prescrire :
- implémentation bcrypt de `SymfonyCompatiblePasswordHasher` ;
- hachage ou vérification faits deux fois dans une même requête ;
- ressources CPU et JIT du conteneur.

Si la cible est hors d'atteinte sans enfreindre le point 4, l'acceptation de l'écart comme risque est soumise à l'utilisateur. Paul n'assouplit pas le seuil seul.

## 4. Reprise de R8
Les étapes M0–M6 sont détaillées dans `docs/architecture/identity-migration.md` §6.3, « Reprise de R8 ».
- **Pas de répétition complète.** R1–R7 et R9 sont acquis, sauf si la correction touche au routage, au gel, à la reprise, à la projection ou à l'outbox.
- **Préalables** : revue BackReviewer sans bloquant ; tests intacts ; `SymfonyCompatiblePasswordHasherTest` et `LoginTimingIT` verts ; CI `identity-compat` verte.
- **Déroulé**, sur une stack `taskora-rehearsal` neuve :
  1. préparation : reprise, `verify`, routage ;
  2. contrôles 200 / 401 / 401 ;
  3. mesures selon le protocole ;
  4. e2e `@identity` 14/14 ;
  5. journaux : 0 fuite, 0 × 5xx ;
  6. nettoyage.
- **Qui** :
  - BackAli exécute et rédige `docs/reviews/<date>-identity-r8-rerun.md` ;
  - Manuel fournit la stack et les commutations nginx, puis nettoie ;
  - Paul juge L1–L5.
- **Si la reprise est conforme** : la répétition §6.3 est déclarée réussie, les lignes back IDENT passent à `validé` et la phase 1 est déclarée sortie.

## 5. Fichiers mis à jour
- `docs/specs/identity.md` §6 et §6.1 : décision PERF-IDENT-1, protocole, application à B1.
- `docs/specs/phase-1-identity.md` §6 : ligne de risque « Login plus lent » alignée sur PERF-IDENT-1.
- `docs/architecture/identity-migration.md` §6.3 : statut B1 et reprise de R8.
