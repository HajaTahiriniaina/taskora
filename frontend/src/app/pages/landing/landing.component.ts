import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

import { IconComponent } from '../../shared/icon.component';
import { LogoComponent } from '../../shared/logo.component';

@Component({
  selector: 'tk-landing',
  imports: [RouterLink, IconComponent, LogoComponent],
  templateUrl: './landing.component.html',
  styleUrl: './landing.component.scss',
})
export class LandingComponent {
  year = new Date().getFullYear();
  mockCols = [
    {
      title: 'À faire',
      cards: [
        ['#6366f1', '#22c55e'],
        ['#ec4899', '#8b5cf6'],
        ['#f59e0b', '#ef4444'],
      ],
    },
    {
      title: 'En cours',
      cards: [
        ['#14b8a6', '#6366f1'],
        ['#ef4444', '#f59e0b'],
      ],
    },
    {
      title: 'Terminé',
      cards: [
        ['#22c55e', '#0ea5e9'],
        ['#8b5cf6', '#ec4899'],
      ],
    },
  ];
  features = [
    {
      icon: 'layout',
      title: 'Kanban fluide',
      text: 'Glissez-déposez cartes et colonnes avec des animations naturelles. Tout est sauvegardé instantanément.',
    },
    {
      icon: 'bell',
      title: 'Notifications utiles',
      text: 'Assignations, commentaires, mentions @, échéances : votre équipe est prévenue au bon moment.',
    },
    {
      icon: 'sliders',
      title: 'Champs personnalisés',
      text: 'Texte, nombre, date, case à cocher, liste ou lien : adaptez chaque ticket à votre process.',
    },
    {
      icon: 'users',
      title: 'Collaboration',
      text: 'Invitez vos collègues par e-mail, gérez les rôles et suivez qui fait quoi.',
    },
    {
      icon: 'checkSquare',
      title: 'Checklists & priorités',
      text: 'Décomposez le travail, suivez la progression et faites ressortir l’urgent.',
    },
    {
      icon: 'filter',
      title: 'Filtres instantanés',
      text: 'Retrouvez vos cartes par mot-clé, membre ou étiquette en une fraction de seconde.',
    },
  ];
}
