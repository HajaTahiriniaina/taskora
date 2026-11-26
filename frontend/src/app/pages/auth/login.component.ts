import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';

import { AuthService } from '../../core/auth.service';
import { IconComponent } from '../../shared/icon.component';
import { LogoComponent } from '../../shared/logo.component';

@Component({
  selector: 'tk-login',
  imports: [FormsModule, RouterLink, IconComponent, LogoComponent],
  styleUrl: './auth.scss',
  template: `
    <div class="auth">
      <aside class="side">
        <tk-logo [light]="true" />
        <div>
          <h2>Bon retour parmi nous 👋</h2>
          <p>
            Vos tableaux, vos cartes et votre équipe vous attendent exactement là où vous les avez
            laissés.
          </p>
        </div>
        <div class="quote">
          <p>
            « Depuis que l’équipe utilise Taskora, nos sprints sont plus clairs et personne ne rate
            une échéance. »
          </p>
          <small>— Une équipe produit heureuse</small>
        </div>
      </aside>
      <main class="main">
        <form class="form-wrap" (ngSubmit)="submit()" #f="ngForm">
          <div class="mobile-logo"><tk-logo /></div>
          <h1>Connexion</h1>
          <p>Ravi de vous revoir sur Taskora.</p>
          @if (error()) {
            <div class="error"><tk-icon name="x" [size]="16" />{{ error() }}</div>
          }
          <div class="field">
            <label for="email">Adresse e-mail</label>
            <input
              id="email"
              class="input"
              type="email"
              name="email"
              [(ngModel)]="email"
              required
              autocomplete="email"
              placeholder="vous@entreprise.com"
            />
          </div>
          <div class="field">
            <label for="password">Mot de passe</label>
            <div class="pw">
              <input
                id="password"
                class="input"
                [type]="show() ? 'text' : 'password'"
                name="password"
                [(ngModel)]="password"
                required
                autocomplete="current-password"
                placeholder="••••••••"
              />
              <button
                type="button"
                class="icon-btn"
                (click)="show.set(!show())"
                [attr.aria-label]="show() ? 'Masquer' : 'Afficher'"
              >
                <tk-icon name="eye" [size]="16" />
              </button>
            </div>
          </div>
          <button class="btn btn-primary btn-lg btn-block" [disabled]="loading() || f.invalid">
            @if (loading()) {
              <span
                class="spinner"
                style="width:18px;height:18px;border-width:2px;border-color:rgba(255,255,255,.3);border-top-color:#fff"
              ></span>
            } @else {
              Se connecter
            }
          </button>
          <p class="switch">Pas encore de compte ? <a routerLink="/register">Créer un compte</a></p>
        </form>
      </main>
    </div>
  `,
})
export class LoginComponent {
  private auth = inject(AuthService);
  private router = inject(Router);
  email = '';
  password = '';
  loading = signal(false);
  error = signal('');
  show = signal(false);

  submit() {
    this.loading.set(true);
    this.error.set('');
    this.auth.login(this.email, this.password).subscribe({
      next: () => this.router.navigateByUrl('/boards'),
      error: (err) => {
        this.loading.set(false);
        this.error.set(
          err.status === 401
            ? 'E-mail ou mot de passe incorrect.'
            : 'Impossible de se connecter pour le moment.',
        );
      },
    });
  }
}
