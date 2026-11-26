import { Routes } from '@angular/router';
import { authGuard, guestGuard } from './core/auth.guard';

export const routes: Routes = [
  { path: '', pathMatch: 'full', canActivate: [guestGuard], loadComponent: () => import('./pages/landing/landing.component').then(m => m.LandingComponent) },
  { path: 'login', canActivate: [guestGuard], loadComponent: () => import('./pages/auth/login.component').then(m => m.LoginComponent) },
  { path: 'register', canActivate: [guestGuard], loadComponent: () => import('./pages/auth/register.component').then(m => m.RegisterComponent) },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./pages/shell/shell.component').then(m => m.ShellComponent),
    children: [
      { path: 'boards', loadComponent: () => import('./pages/dashboard/dashboard.component').then(m => m.DashboardComponent) },
      { path: 'boards/:id', loadComponent: () => import('./pages/board/board.component').then(m => m.BoardComponent) },
      { path: 'profile', loadComponent: () => import('./pages/profile/profile.component').then(m => m.ProfileComponent) },
    ],
  },
  { path: '**', redirectTo: '' },
];
