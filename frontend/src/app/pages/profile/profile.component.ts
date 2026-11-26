import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { PALETTE } from '../../core/models';
import { PushService } from '../../core/push.service';
import { ToastService } from '../../core/toast.service';
import { AvatarComponent } from '../../shared/avatar.component';
import { IconComponent } from '../../shared/icon.component';

@Component({
  selector: 'tk-profile',
  imports: [FormsModule, AvatarComponent, IconComponent],
  template: `
    <div class="wrap">
      <div class="hero">
        <tk-avatar [user]="{ name: name || '?', avatarColor: color }" [size]="84" />
        <div><h1>{{ name || 'Mon profil' }}</h1><p class="muted">{{ auth.user()?.email }}</p></div>
      </div>

      <form class="card" (ngSubmit)="saveProfile()">
        <h2><tk-icon name="user" [size]="18" /> Informations</h2>
        <div class="field"><label for="n">Nom complet</label><input id="n" class="input" name="name" [(ngModel)]="name" required maxlength="120" /></div>
        <div class="field">
          <label>Couleur de l’avatar</label>
          <div class="colors">
            @for (c of palette; track c) {
              <button type="button" [style.background]="c" [class.sel]="color === c" (click)="color = c" [attr.aria-label]="c">
                @if (color === c) { <tk-icon name="check" [size]="14" [stroke]="3" /> }
              </button>
            }
          </div>
        </div>
        <button class="btn btn-primary" [disabled]="saving()">Enregistrer</button>
      </form>

      <section class="card">
        <h2><tk-icon name="bell" [size]="18" /> Notifications du navigateur</h2>
        <p class="muted intro">Recevez une bannière sur cet appareil quand on vous assigne une carte, vous mentionne, commente vos cartes ou fixe une échéance, même si Taskora est fermé.</p>
        @switch (push.state()) {
          @case ('on') {
            <div class="push-row on"><tk-icon name="check" [size]="16" [stroke]="3" /> <span>Activées sur ce navigateur</span>
              <button class="btn btn-secondary btn-sm" (click)="test()" [disabled]="testing()">{{ testing() ? 'Envoi…' : 'Envoyer un test' }}</button>
              <button class="btn btn-ghost btn-sm" (click)="push.disable()" [disabled]="push.busy()">Désactiver</button>
            </div>
          }
          @case ('off') {
            <div class="push-row"><span>Désactivées sur ce navigateur</span>
              <button class="btn btn-primary btn-sm" (click)="enable()" [disabled]="push.busy()">{{ push.busy() ? 'Activation…' : 'Activer les notifications' }}</button>
            </div>
          }
          @case ('denied') {
            <p class="push-warn">Les notifications sont bloquées pour Taskora dans ce navigateur. Autorisez-les dans les réglages du site (icône à gauche de l’adresse), puis rechargez la page.</p>
          }
          @case ('unconfigured') {
            <p class="push-warn">Le serveur n’est pas configuré pour les notifications (clés VAPID absentes de backend/.env).</p>
          }
          @default {
            <p class="push-warn">Ce navigateur ne gère pas les notifications push.@if (isIos) { Sur iPhone et iPad : ajoutez Taskora à l’écran d’accueil (Partager → Sur l’écran d’accueil), ouvrez-le depuis l’icône, puis activez les notifications ici. }</p>
          }
        }
        @if (push.devices().length) {
          <h3 class="dev-title">Appareils qui reçoivent vos notifications</h3>
          <ul class="devices">
            @for (d of push.devices(); track d.id) {
              <li>
                <tk-icon name="bell" [size]="15" />
                <span class="dev-name">{{ d.device || 'Navigateur' }} @if (d.endpointHash === push.currentHash()) { <em>cet appareil</em> }</span>
                <small>depuis le {{ date(d.createdAt) }}</small>
                <button class="icon-btn sm" (click)="push.removeDevice(d)" title="Ne plus notifier cet appareil" aria-label="Ne plus notifier cet appareil"><tk-icon name="x" [size]="14" /></button>
              </li>
            }
          </ul>
        }
      </section>

      <form class="card" (ngSubmit)="savePassword()">
        <h2><tk-icon name="shield" [size]="18" /> Sécurité</h2>
        <div class="field"><label for="cp">Mot de passe actuel</label><input id="cp" class="input" type="password" name="cp" [(ngModel)]="currentPassword" autocomplete="current-password" /></div>
        <div class="field"><label for="np">Nouveau mot de passe</label><input id="np" class="input" type="password" name="np" [(ngModel)]="newPassword" minlength="8" autocomplete="new-password" placeholder="8 caractères minimum" /></div>
        <button class="btn btn-secondary" [disabled]="!currentPassword || newPassword.length < 8">Changer le mot de passe</button>
      </form>
    </div>
  `,
  styles: [`
    .wrap { max-width: 640px; margin: 0 auto; padding: 36px 16px 64px; }
    .hero { display: flex; align-items: center; gap: 20px; margin-bottom: 28px; h1 { font-size: 26px; } p { margin: 4px 0 0; } }
    .card { background: var(--surface); border: 1px solid var(--border); border-radius: 20px; padding: 24px; margin-bottom: 18px; box-shadow: var(--shadow-sm);
      h2 { display: flex; align-items: center; gap: 8px; font-size: 17px; margin-bottom: 18px; } }
    .intro { margin: -8px 0 14px; font-size: 14px; }
    .push-row { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; span { flex: 1; min-width: 160px; font-weight: 600; }
      &.on { color: var(--success); span { color: var(--text); } } }
    .push-warn { margin: 0; padding: 10px 12px; border-radius: 10px; background: rgba(245,158,11,.12); color: #b45309; font-size: 14px; }
    .dev-title { font-size: 13px; text-transform: uppercase; letter-spacing: .04em; color: var(--text-3); margin: 20px 0 8px; }
    .devices { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px;
      li { display: flex; align-items: center; gap: 10px; padding: 8px 10px; border: 1px solid var(--border); border-radius: 10px; color: var(--text-2); }
      .dev-name { flex: 1; font-weight: 600; color: var(--text); em { font-style: normal; font-size: 11px; font-weight: 700; color: var(--primary); background: var(--primary-soft); padding: 1px 6px; border-radius: 6px; margin-left: 6px; } }
      small { color: var(--text-3); } }
    .colors { display: flex; gap: 8px; flex-wrap: wrap;
      button { width: 36px; height: 36px; border-radius: 50%; border: 3px solid transparent; color: #fff; display: grid; place-items: center; transition: transform .15s;
        &:hover { transform: scale(1.08); } &.sel { border-color: var(--surface); box-shadow: 0 0 0 2px var(--text); } } }
  `],
})
export class ProfileComponent {
  private api = inject(ApiService);
  private toast = inject(ToastService);
  auth = inject(AuthService);
  palette = PALETTE;
  name = this.auth.user()?.name ?? '';
  color = this.auth.user()?.avatarColor ?? PALETTE[6];
  currentPassword = '';
  newPassword = '';
  saving = signal(false);
  push = inject(PushService);
  testing = signal(false);
  isIos = /iPhone|iPad/.test(navigator.userAgent);

  constructor() {
    this.push.start().then(() => this.push.loadDevices());
  }

  async enable() {
    if (await this.push.enable()) this.toast.success('Notifications activées sur ce navigateur');
  }

  async test() {
    this.testing.set(true);
    try {
      await this.push.test();
      this.toast.show('Notification de test envoyée : elle doit apparaître en bannière.', 'info');
    } catch { /* error toast by the interceptor */ } finally {
      this.testing.set(false);
    }
  }

  date(d: string) {
    return new Date(d).toLocaleDateString('fr-FR', { day: 'numeric', month: 'short', year: 'numeric' });
  }

  saveProfile() {
    this.saving.set(true);
    this.api.updateMe({ name: this.name, avatarColor: this.color }).subscribe({
      next: u => { this.auth.setUser(u); this.saving.set(false); this.toast.success('Profil mis à jour'); },
      error: () => this.saving.set(false),
    });
  }

  savePassword() {
    this.api.updateMe({ currentPassword: this.currentPassword, newPassword: this.newPassword }).subscribe(() => {
      this.currentPassword = '';
      this.newPassword = '';
      this.toast.success('Mot de passe modifié');
    });
  }
}
