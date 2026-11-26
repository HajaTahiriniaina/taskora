# Équipe d'agents Taskora : rôles et circuit de travail

## Organigramme

```
                          Paul (chef, test planner)
              ┌───────────────────┼────────────────────┐
         BackAli (backend)   FrontPierre (frontend)   Manuel (DevOps)
     ┌────────┼────────┐    ┌────────┼─────────┐
 BackTesteur BackCodeur BackReviewer  FrontTesteur FrontCodeur FrontReviewer
```

Les définitions sont dans `.claude/agents/` (portée : ce projet uniquement).

## Limite à connaître : un sous-agent ne lance pas de sous-agent

Dans Claude Code, seul l'agent qui pilote la session (le « fil principal ») peut lancer d'autres agents. La hiérarchie fonctionne donc selon deux modes.

| Mode | Commande | Qui délègue |
|---|---|---|
| Équipe complète | `claude --agent paul` | Paul lance les chefs de pôle **et** leurs sous-agents. Quand un chef de pôle tourne sous Paul, il ne peut rien lancer : il rend un **ordre de travail**, que Paul exécute dans l'ordre (Testeur → Codeur → Reviewer) avant de lui renvoyer les résultats pour recette. |
| Pôle seul | `claude --agent backali`, `claude --agent frontpierre` ou `claude --agent manuel` | Le chef de pôle lance lui-même ses sous-agents. |

## Échanges entre agents : uniquement par fichiers

Les agents ne se parlent pas directement. Ils s'échangent des fichiers versionnés.

| Fichier | Auteur | Lecteurs |
|---|---|---|
| `docs/specs/<fonctionnalité>.md` | Paul | tous |
| `docs/specs/pages.md` (pages à réaliser côté front) | Paul | FrontPierre, FrontTesteur, FrontCodeur |
| `docs/architecture/backend-hexagonal.md` | BackAli | pôle backend, Manuel |
| `docs/contracts/openapi/<service>.yaml` | BackAli | FrontPierre, FrontTesteur, FrontCodeur |
| `docs/contracts/requests/<NNN>-<sujet>.md` (demande d'endpoint) | FrontPierre ouvre, BackAli répond | les deux |
| `docs/ci/requests/<backend\|frontend>.md` (jobs CI/CD demandés) | BackAli, FrontPierre | Manuel |
| `docs/reviews/<AAAA-MM-JJ>-<périmètre>.md` | BackReviewer, FrontReviewer | chef de pôle, Paul |

## Format d'un scénario (Paul)

Chaque scénario a un identifiant stable, repris dans le nom ou le `@DisplayName` des tests :

```
### SC-CARD-012 — Archiver une carte (critique)
Étant donné un membre du tableau
Quand il archive la carte
Alors la carte disparaît de la liste et apparaît dans « Archives »
Limites : carte déjà archivée → 409 ; non-membre → 404 (pas de fuite d'existence)
Validation : unitaire (domaine), API, sécurité, e2e page Tableau
```

## Circuit d'une fonctionnalité

1. **Paul** : spécification et scénarios, priorités (critique / important / secondaire), stratégie de validation, pages front.
2. **Chef de pôle** : découpage, contrat d'API, règles d'architecture, ordre de travail.
3. **Testeur** : tests exécutables, **rouges** au départ ; commit `test(<scope>): …`.
4. **Codeur** : code jusqu'au vert, **sans toucher aux tests** ; commit `feat(<scope>): …`.
5. **Reviewer** : contrôles outillés, rapport dans `docs/reviews/`. Tout point bloquant renvoie au Codeur.
6. **Chef de pôle** : recette.
7. **Paul** : validation contre les scénarios. Tout scénario non couvert renvoie au Testeur.

## Règle d'intégrité des tests

- Les Codeurs n'écrivent pas dans les fichiers de test. Un hook (`.claude/hooks/protect-tests.sh`) bloque leurs écritures dans :
  - `services/**/src/test/**` ;
  - `frontend/**/*.spec.ts` ;
  - `frontend/e2e/**` ;
  - `backend/tests/**` (tests PHPUnit du backend Symfony).
- Le Reviewer vérifie que `git diff <commit test>..HEAD` ne touche à aucun fichier de test. Il refuse aussi tout affaiblissement : `@Disabled`, `skip`, assertion supprimée, seuil baissé, exclusion de couverture.
- Si un test est faux, le Codeur l'écrit dans son rapport ; seul le Testeur le corrige, après accord du chef de pôle.

## Git

- Une branche par fonctionnalité : `feat/<id>-<sujet>`.
- Les agents commitent sur cette branche ; **aucun push sans accord de l'utilisateur**.
- Aucun secret dans un commit : `.env`, `backend/.env` et les clés restent ignorés.
