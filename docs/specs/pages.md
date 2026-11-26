# Pages et vues du frontend

Destinataires : FrontPierre, FrontTesteur, FrontCodeur. Source : `frontend/src/app/app.routes.ts` et `pages/**` (Angular 22, composants autonomes, signals, sans zone.js).
La migration du backend **ne doit modifier aucune page** tant que les contrats HTTP sont conservés ; les seules évolutions front attendues sont listées dans [phase-1-identity.md](phase-1-identity.md) § Impact front.

## Conventions
- **Rôles** : `anonyme` (non connecté), `connecté`, puis par tableau `owner` / `admin` / `member`. « Gestionnaire » = owner ou admin.
- **États** à couvrir pour chaque vue : chargement, vide, erreur (toast de l'intercepteur avec le message `error` de l'API), contenu ; plus les états propres listés.
- Erreurs HTTP : un 401 hors `/auth/` déconnecte (SC-IDENT-025) ; les autres erreurs affichent un toast avec `error.error`, sauf `unread-count` (silencieux).
- Tests e2e (Playwright, `frontend/e2e/`) : un fichier par page ; titre de chaque test préfixé par l'identifiant du scénario.

## 1. Accueil public — `/`
| Élément | Valeur |
|---|---|
| Rôle | anonyme (`guestGuard` : connecté → `/boards`) |
| Composants | `LandingComponent`, `LogoComponent`, `IconComponent` |
| Contenu | présentation, maquette de kanban, 6 fonctionnalités, liens Connexion / Inscription |
| États | statique |
| Scénarios | SC-IDENT-026 |

## 2. Connexion — `/login`
| Élément | Valeur |
|---|---|
| Rôle | anonyme (`guestGuard`) |
| Composants | `LoginComponent`, `LogoComponent`, `IconComponent` |
| Formulaire | e-mail (requis, `type=email`), mot de passe (requis, bouton Afficher/Masquer) ; bouton désactivé si formulaire invalide ou envoi en cours |
| États | chargement (spinner dans le bouton) ; erreur 401 → « E-mail ou mot de passe incorrect. » ; autre erreur → « Impossible de se connecter pour le moment. » (message dans un élément `role="alert"`, identity.md §6) ; succès → `/boards` |
| Scénarios | SC-IDENT-007, 008, 025, 026 |

## 3. Inscription — `/register`
| Élément | Valeur |
|---|---|
| Rôle | anonyme (`guestGuard`) |
| Composants | `RegisterComponent`, `LogoComponent`, `IconComponent` |
| Formulaire | nom (requis), e-mail (requis, format), mot de passe (requis, `minlength=8`, jauge de robustesse 4 niveaux) |
| États | chargement ; erreur → message `error` de l'API (ex. 409) ou « Inscription impossible pour le moment. » (message dans un élément `role="alert"`, identity.md §6) ; succès → `/boards` |
| Scénarios | SC-IDENT-001, 002, 003, 005 |

## 4. Coquille de l'application (routes protégées)
| Élément | Valeur |
|---|---|
| Rôle | connecté (`authGuard` : sinon `/login`) |
| Composants | `ShellComponent` (en-tête, logo, panneau 🔔, menu utilisateur, bandeau d'activation push), `AvatarComponent`, `ToastService`, `DialogService` (confirmations) |
| Panneau 🔔 | badge non lues, liste (40 plus récentes), filtre « toutes / non lues », marquer lu au clic puis navigation vers la carte, « tout lire », supprimer ; actualisation toutes les 15 s |
| Bandeau push | proposé tant que les notifications ne sont pas activées ; masquable pour la session |
| Menu utilisateur | Profil, thème clair/sombre, Déconnexion (désabonnement push puis suppression du jeton) |
| États | panneau vide (« Aucune notification ») ; erreur silencieuse sur le compteur |
| Scénarios | SC-NOTIF-001, 004, 005, 006, SC-PUSH-001, 008, 009, SC-IDENT-025 |

## 5. Tableau de bord — `/boards`
| Élément | Valeur |
|---|---|
| Rôle | connecté |
| Composants | `DashboardComponent`, `TrelloAccessComponent`, `AvatarComponent`, `IconComponent` |
| Contenu | salutation, statistiques (tableaux, cartes, collaborateurs), recherche locale, section Favoris, tous les tableaux (fond, membres, compteurs, étoile) |
| Dialogues | **Créer un tableau** (titre, description, 10 fonds) ; **Importer depuis Trello** (fichier JSON ≤ 20 Mo, case « Copier les pièces jointes dans Taskora », clé et jeton en lecture seule avec mode d'emploi, vérification du compte) ; **Corbeille** des tableaux possédés (jours restants, restaurer, supprimer définitivement avec confirmation) |
| États | chargement ; vide (« Créez votre premier tableau ») ; recherche sans résultat ; import en cours ; corbeille vide |
| Scénarios | SC-BOARD-001, 002, 008, 019, SC-TRASH-007, 010, SC-IMPORT-001, 003, 007 |

## 6. Tableau — `/boards/:id` (et `?card=<id>`)
| Élément | Valeur |
|---|---|
| Rôle | membre du tableau (403/404 → toast et retour à l'accueil : HYPOTHÈSE, comportement à confirmer) ; actions de gestion réservées aux gestionnaires ; suppression du tableau réservée au propriétaire |
| Composants | `BoardComponent` (colonnes virtualisées, chargement paresseux `InViewDirective`, glisser-déposer CDK des colonnes et des cartes, sélecteur de colonnes, « Déplacer en position… », défilement horizontal au pavé tactile / Maj + molette, dépôt de fichiers sur une carte), `CardItemComponent` (`OnPush`, numéro, étiquettes, badges échéance / checklist / commentaires / 📎, assignés, couverture, champs `showOnCard`), `FilterPanelComponent`, `CardModalComponent`, `BoardSettingsComponent`, barre de progression du transfert Trello (`TrelloTransferService`) |
| Menus | liste : renommer, couleur, déplacer en position, archiver, supprimer ; carte « ⋯ » : archiver, supprimer, déplacer |
| États | chargement de la coquille ; colonne en chargement ; colonne vide ; tableau sans liste ; filtres actifs (totaux serveur) ; transfert Trello en cours / en pause / en échec / limitation (attente `retryAfter`) |
| Scénarios | SC-BOARD-003, 004, 005, SC-LIST-001 à 010, SC-CARD-006, 007, 023, 024, 025, 026, 027, 028, 029, SC-ARCHIVE-001, 005, SC-TRASH-001, 002, SC-IMPORT-009, 010, 011, 014 |

### 6.1 Panneau Filtre (dans la page Tableau)
Mot-clé ; membres (aucun / moi / choisis) ; statut ; date limite (aucune, en retard, 24 h, semaine, mois) ; étiquettes (aucune / choisies) ; champs liste et case à cocher. Filtres envoyés au serveur. Scénarios : SC-CARD-023, 024, 025.

### 6.2 Fiche carte (dialogue `CardModalComponent`, `?card=<id>`)
| Élément | Valeur |
|---|---|
| Disposition | deux colonnes : détails à gauche, « Commentaires et activité » à droite (défilements indépendants, empilées sur mobile) |
| Détails | numéro (clic = copie du lien), titre, liste, membres, étiquettes, échéance, terminé, priorité, couverture (couleur, image jointe, import d'image), description riche (`RichEditorComponent`, ⌘/Ctrl + Entrée), champs personnalisés, checklist (progression), pièces jointes (aperçu, badge de type, renommer, télécharger, couverture, supprimer, glisser-déposer, progression) |
| Fil | commentaires et historique mêlés ; « Masquer les détails » (mémorisé) ; « Répondre » (mention) ; mentions « @ » ; pagination de l'activité |
| États | chargement ; carte archivée (bandeau, restaurer) ; carte introuvable / supprimée ; envoi en cours ; erreurs d'envoi de fichier |
| Scénarios | SC-CARD-004, 005, 009 à 022, 028, SC-COMMENT-001 à 012, SC-ACTIVITY-001 à 006, SC-ATTACH-001, 002, 011 à 014, SC-ARCHIVE-007 |

### 6.3 Visionneuse de fichiers (`FileViewerComponent`, depuis la fiche)
Images, PDF, documents bureautiques (aperçu PDF), tableurs et CSV (onglets, tableau, « Mise en page »), texte et code, JSON (`JsonNodeComponent`, arbre repliable), e-mails EML (en-têtes, pièces jointes, HTML nettoyé dans un cadre isolé, images distantes bloquées par défaut), audio / vidéo. ← → pour naviguer, Échap pour fermer, bouton « Définir comme couverture » pour une image. États : chargement, conversion en cours, conversion impossible (502 → invitation à télécharger), type non pris en charge (422 → téléchargement). Scénarios : SC-ATTACH-008, 009, 010.

### 6.4 Paramètres du tableau (dialogue `BoardSettingsComponent`)
| Onglet | Contenu | Rôle | Scénarios |
|---|---|---|---|
| Membres | membres et rôles, inviter (e-mail + rôle), invitations en attente (annuler), retirer, quitter | gestion : gestionnaire ; quitter : membre non propriétaire | SC-BOARD-009 à 018 |
| Étiquettes | créer, renommer, recolorer, supprimer | membre | SC-CARD-011 |
| Champs | créer (6 types, options colorées), renommer, options, « afficher sur la carte », supprimer | membre | SC-CARD-019, 022 |
| Général (`background`) | titre, description, fond (10), afficher les numéros, supprimer le tableau (propriétaire) | membre ; suppression : propriétaire | SC-BOARD-005, 007, SC-CARD-029 |
| Archives | listes archivées (restaurer, supprimer → corbeille) ; cartes archivées (recherche titre / description / #n, ouvrir, restaurer, supprimer → corbeille) | membre | SC-ARCHIVE-003, 005, 006 |
| Corbeille | listes et cartes supprimées (jours restants, restaurer ; supprimer définitivement réservé aux gestionnaires) | membre ; purge : gestionnaire | SC-TRASH-003 à 006, 009, 010 |

## 7. Profil — `/profile`
| Élément | Valeur |
|---|---|
| Rôle | connecté |
| Composants | `ProfileComponent`, `AvatarComponent`, `IconComponent`, `PushService` |
| Sections | **Informations** (nom ≤ 120, couleur d'avatar parmi la palette) ; **Notifications du navigateur** (états `on` / `off` / `denied` / `unconfigured` / `unsupported`, aide iPhone/iPad, bouton de test, liste des appareils avec « cet appareil », retirer) ; **Sécurité** (mot de passe actuel, nouveau ≥ 8 ; bouton désactivé sinon) |
| États | enregistrement en cours ; toasts « Profil mis à jour », « Mot de passe modifié », « Notifications activées sur ce navigateur » ; erreur 400 en toast |
| Scénarios | SC-IDENT-014 à 018, SC-PUSH-001, 003, 007, 008 |

## 8. Autres points d'entrée
| Élément | Rôle | Scénarios |
|---|---|---|
| `**` → `/` | tous | SC-IDENT-026 |
| Service worker `/push-sw.js` (bannières, clic → navigation) | — | SC-PUSH-005, 009 |
| Manifeste `/manifest.webmanifest` (écran d'accueil iOS) | — | SC-PUSH-001 |

## 9. Exigences transverses (front)
- Accessibilité : WCAG 2.1 AA ; dialogues avec focus piégé et Échap ; boutons d'icône avec `aria-label` ; contrastes en thème clair et sombre ; alternatives clavier au glisser-déposer.
- Responsive : fiche carte empilée sous 900 px (HYPOTHÈSE: point de rupture à confirmer), tableau défilable au doigt.
- Performance : tableau de 9 000 cartes / 300 colonnes fluide (DOM ≈ 4 000 nœuds, SC-LIST-009).
- Sécurité : tout HTML riche passe par DOMPurify (`rich-text.ts`) ; aucune donnée sensible hors `localStorage` (`taskora.token`, `taskora.user`) ; identifiants Trello uniquement en mémoire de l'onglet.
