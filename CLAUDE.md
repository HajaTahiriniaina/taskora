# Taskora

SaaS de gestion de tâches de type kanban.
- **Frontend** : Angular 22 (`frontend/`).
- **Backend actuel** : Symfony 8 (`backend/`), en cours de migration vers des microservices Spring Boot hexagonaux (`services/`).

## Équipe d'agents (`.claude/agents/`)
- **Paul** : chef d'équipe et test planner. Il orchestre BackAli, FrontPierre et Manuel.
- **BackAli** : chef du pôle backend ; il pilote BackTesteur, BackCodeur et BackReviewer.
- **FrontPierre** : chef du pôle frontend ; il pilote FrontTesteur, FrontCodeur et FrontReviewer.
- **Manuel** : DevOps (Docker, GitHub Actions).

Lancement :
- équipe complète : `claude --agent paul` ;
- un seul pôle : `claude --agent backali`, `claude --agent frontpierre` ou `claude --agent manuel`.

Circuit, échanges par fichiers et règle d'intégrité des tests : `docs/team/workflow.md`.
Architecture cible du backend : `docs/architecture/backend-hexagonal.md`.

## Règles communes
- Ne jamais lire, afficher ni commiter `.env` ou `backend/.env` (clés AWS, VAPID, JWT). Les modèles sont `.env.example` et `backend/.env.dist`.
- Pas de push, de merge sur `main` ni d'action externe sans accord de l'utilisateur.
- Les tests sont écrits avant le code et ne sont jamais modifiés ni affaiblis par les Codeurs.
- Toute la stack tourne avec Docker : `docker compose up -d --build`. L'application est servie sur http://localhost:8080.
