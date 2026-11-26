import { ChangeDetectionStrategy, Component, inject, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../core/api.service';
import { TrelloTransferService } from '../core/trello-transfer.service';
import { IconComponent } from './icon.component';

/**
 * Trello API key + token form (copy of the attachments of an imported board).
 * Checked against Trello, then kept in memory only (TrelloTransferService).
 */
@Component({
  selector: 'tk-trello-access',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, IconComponent],
  template: `
    <div class="ta">
      <p class="intro">
        Trello ne donne les fichiers qu’à un compte connecté. Créez un accès
        <b>en lecture seule</b> (2 minutes) :
      </p>
      <ol class="steps">
        <li>
          Ouvrez
          <a href="https://trello.com/power-ups/admin" target="_blank" rel="noopener"
            >trello.com/power-ups/admin</a
          >, créez une intégration (nom : « Taskora », n’importe quel espace de travail), puis
          <b>Générer une nouvelle clé d’API</b>.
        </li>
        <li>
          Collez la clé ici :
          <input
            class="input input-sm mono"
            name="key"
            [(ngModel)]="key"
            placeholder="Clé d’API (32 caractères)"
            autocomplete="off"
            spellcheck="false"
            (ngModelChange)="error.set(null)"
          />
        </li>
        <li>
          <a
            class="btn btn-secondary btn-sm"
            [class.disabled]="!validKey()"
            [attr.href]="validKey() ? authorizeUrl() : null"
            target="_blank"
            rel="noopener"
          >
            <tk-icon name="external" [size]="14" /> Générer un jeton (lecture seule, valable 1 jour)
          </a>
          puis cliquez sur <b>Autoriser</b> et collez le jeton :
          <input
            class="input input-sm mono"
            name="token"
            [(ngModel)]="token"
            placeholder="Jeton (ATTA…)"
            autocomplete="off"
            spellcheck="false"
            (ngModelChange)="error.set(null)"
          />
        </li>
      </ol>
      @if (error(); as e) {
        <p class="err" role="alert">{{ e }}</p>
      }
      @if (account(); as a) {
        <p class="ok">
          <tk-icon name="check" [size]="14" /> Connecté à Trello en tant que <b>{{ a }}</b>
        </p>
      }
      <p class="privacy">
        <tk-icon name="shield" [size]="13" /> La clé et le jeton restent dans cet onglet et ne sont
        jamais enregistrés. Vous pouvez révoquer le jeton à tout moment dans Trello.
      </p>
    </div>
  `,
  styles: `
    .ta {
      display: flex;
      flex-direction: column;
      gap: 8px;
      font-size: 13px;
      color: var(--text-2);
    }
    .intro {
      margin: 0;
    }
    .steps {
      margin: 0;
      padding-left: 20px;
      display: flex;
      flex-direction: column;
      gap: 10px;
      li::marker {
        color: var(--primary);
        font-weight: 700;
      }
      input {
        display: block;
        width: 100%;
        margin-top: 6px;
      }
      a:not(.btn) {
        color: var(--primary);
        font-weight: 600;
      }
    }
    .mono {
      font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
      font-size: 12px;
    }
    .btn.disabled {
      opacity: 0.45;
      pointer-events: none;
    }
    .err {
      margin: 0;
      padding: 8px 10px;
      border-radius: 8px;
      background: rgba(239, 68, 68, 0.1);
      color: var(--danger);
    }
    .ok {
      margin: 0;
      display: flex;
      align-items: center;
      gap: 6px;
      color: var(--success);
    }
    .privacy {
      margin: 0;
      display: flex;
      gap: 6px;
      align-items: flex-start;
      font-size: 12px;
      color: var(--text-3);
      tk-icon {
        margin-top: 2px;
      }
    }
  `,
})
export class TrelloAccessComponent {
  private api = inject(ApiService);
  private transfer = inject(TrelloTransferService);
  verified = output<void>();

  key = this.transfer.credentials()?.key ?? '';
  token = this.transfer.credentials()?.token ?? '';
  error = signal<string | null>(null);
  account = signal<string | null>(this.transfer.credentials()?.account ?? null);
  checking = signal(false);

  validKey() {
    return /^[a-f0-9]{32}$/.test(this.key.trim());
  }
  filled() {
    return this.validKey() && this.token.trim().length >= 32;
  }

  authorizeUrl() {
    return `https://trello.com/1/authorize?expiration=1day&scope=read&response_type=token&name=${encodeURIComponent('Taskora (import)')}&key=${this.key.trim()}`;
  }

  /** Checks the key / token with Trello, then keeps them in memory. Returns false when refused. */
  async verify(): Promise<boolean> {
    const key = this.key.trim();
    const token = this.token.trim();
    if (!this.filled()) {
      this.error.set('Renseignez la clé d’API et le jeton Trello.');
      return false;
    }
    this.checking.set(true);
    try {
      const me = await firstValueFrom(this.api.checkTrello({ key, token }));
      const account = me.fullName || me.username;
      this.account.set(account);
      this.transfer.setCredentials({ key, token, account });
      this.verified.emit();
      return true;
    } catch (e: any) {
      this.error.set(e?.error?.error ?? 'Vérification impossible.');
      return false;
    } finally {
      this.checking.set(false);
    }
  }
}
