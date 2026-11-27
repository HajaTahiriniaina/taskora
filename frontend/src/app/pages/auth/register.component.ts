import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';

import { AuthService } from '../../core/auth.service';
import { IconComponent } from '../../shared/icon.component';
import { LogoComponent } from '../../shared/logo.component';

@Component({
  selector: 'tk-register',
  imports: [FormsModule, RouterLink, IconComponent, LogoComponent],
  styleUrl: './auth.scss',
  template: `
    <div class="auth">
      <aside class="side">
        <tk-logo [light]="true" />
        <div>
          <h2>Organisez tout. Avec tout le monde.</h2>
          <p>
            Créez des tableaux, invitez votre équipe et adaptez chaque ticket avec des champs
            personnalisés. Gratuit, sans carte bancaire.
          </p>
        </div>
        <div class="quote">
          <p>
            ✓ Tableaux illimités<br />✓ Notifications d’équipe<br />✓ Champs personnalisés sur
            chaque carte
          </p>
        </div>
      </aside>
      <main class="main">
        <form class="form-wrap" (ngSubmit)="submit()" #f="ngForm">
          <div class="mobile-logo"><tk-logo /></div>
          <h1>Créer un compte</h1>
          <p>Commencez à collaborer en quelques secondes.</p>
          @if (error()) {
            <div class="error" role="alert"><tk-icon name="x" [size]="16" />{{ error() }}</div>
          }
          <div class="field">
            <label for="name">Nom complet</label>
            <input
              id="name"
              class="input"
              name="name"
              [(ngModel)]="name"
              required
              autocomplete="name"
              placeholder="Camille Martin"
            />
          </div>
          <div class="field">
            <label for="email">Adresse e-mail</label>
            <input
              id="email"
              class="input"
              type="email"
              name="email"
              [(ngModel)]="email"
              required
              email
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
                [ngModel]="password()"
                (ngModelChange)="password.set($event)"
                required
                minlength="8"
                autocomplete="new-password"
                placeholder="8 caractères minimum"
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
            <div class="strength">
              @for (i of [0, 1, 2, 3]; track i) {
                <span [style.background]="i < strength() ? strengthColor() : null"></span>
              }
            </div>
          </div>
          <button class="btn btn-gradient btn-lg btn-block" [disabled]="loading() || f.invalid">
            @if (loading()) {
              <span
                class="spinner"
                style="width:18px;height:18px;border-width:2px;border-color:rgba(255,255,255,.3);border-top-color:#fff"
              ></span>
            } @else {
              Créer mon compte
            }
          </button>
          <p class="switch">Déjà inscrit ? <a routerLink="/login">Se connecter</a></p>
        </form>
      </main>
    </div>
  `,
})
export class RegisterComponent {
  private auth = inject(AuthService);
  private router = inject(Router);
  name = '';
  email = '';
  password = signal('');
  loading = signal(false);
  error = signal('');
  show = signal(false);

  strength = computed(() => {
    const p = this.password();
    let s = 0;
    if (p.length >= 8) s++;
    if (/[A-Z]/.test(p) && /[a-z]/.test(p)) s++;
    if (/\d/.test(p)) s++;
    if (/[^A-Za-z0-9]/.test(p) || p.length >= 14) s++;
    return s;
  });
  strengthColor = computed(
    () => ['#ef4444', '#ef4444', '#f59e0b', '#22c55e', '#10b981'][this.strength()],
  );

  submit() {
    this.loading.set(true);
    this.error.set('');
    this.auth.register(this.name, this.email, this.password()).subscribe({
      next: () => this.router.navigateByUrl('/boards'),
      error: (err) => {
        this.loading.set(false);
        this.error.set(err.error?.error ?? 'Inscription impossible pour le moment.');
      },
    });
  }
}
