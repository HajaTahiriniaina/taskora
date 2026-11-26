import { DialogService } from '../../core/dialog.service';
import { PushService } from '../../core/push.service';
import { Component, HostListener, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { AppNotification } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { AvatarComponent } from '../../shared/avatar.component';
import { IconComponent } from '../../shared/icon.component';
import { LogoComponent } from '../../shared/logo.component';
import { TimeAgoPipe } from '../../shared/time-ago.pipe';

const NOTIF_ICONS: Record<string, string> = {
  board_invite: 'userPlus',
  member_joined: 'users',
  member_removed: 'users',
  card_assigned: 'user',
  card_comment: 'message',
  card_moved: 'arrowRight',
  card_due: 'clock',
};

@Component({
  selector: 'tk-shell',
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    IconComponent,
    AvatarComponent,
    LogoComponent,
    TimeAgoPipe,
  ],
  templateUrl: './shell.component.html',
  styleUrl: './shell.component.scss',
})
export class ShellComponent implements OnInit, OnDestroy {
  private dialog = inject(DialogService);
  push = inject(PushService);
  pushPromptHidden = signal(
    (() => {
      try {
        return sessionStorage.getItem('taskora.pushPrompt') === 'hidden';
      } catch {
        return false;
      }
    })(),
  );
  auth = inject(AuthService);
  notifs = inject(NotificationService);
  private router = inject(Router);

  openPanel = signal<'notifs' | 'user' | null>(null);
  notifFilter = signal<'all' | 'unread'>('all');
  theme = signal<'light' | 'dark' | null>(this.readTheme());

  ngOnInit() {
    this.notifs.start();
    this.push.start();
    this.auth.refreshProfile();
    this.applyTheme();
  }

  ngOnDestroy() {
    this.notifs.stop();
  }

  toggle(panel: 'notifs' | 'user', e: Event) {
    e.stopPropagation();
    const next = this.openPanel() === panel ? null : panel;
    this.openPanel.set(next);
    if (next === 'notifs') this.notifs.load();
  }

  @HostListener('document:click')
  close() {
    this.openPanel.set(null);
  }

  @HostListener('document:keydown.escape')
  esc() {
    if (!this.dialog.isOpen()) this.openPanel.set(null);
  }

  iconFor(n: AppNotification) {
    return NOTIF_ICONS[n.type] ?? 'bell';
  }

  visibleNotifs() {
    return this.notifFilter() === 'unread'
      ? this.notifs.items().filter((n) => !n.read)
      : this.notifs.items();
  }

  open(n: AppNotification) {
    this.notifs.markRead(n);
    this.openPanel.set(null);
    if (n.boardId) {
      this.router.navigate(['/boards', n.boardId], {
        queryParams: n.cardId ? { card: n.cardId } : {},
      });
    }
  }

  toggleTheme() {
    const dark =
      document.documentElement.dataset['theme'] === 'dark' ||
      (!document.documentElement.dataset['theme'] &&
        matchMedia('(prefers-color-scheme: dark)').matches);
    this.theme.set(dark ? 'light' : 'dark');
    try {
      localStorage.setItem('taskora.theme', this.theme()!);
    } catch {
      /* ignore */
    }
    this.applyTheme();
  }

  isDark() {
    return (
      this.theme() === 'dark' ||
      (!this.theme() && matchMedia('(prefers-color-scheme: dark)').matches)
    );
  }

  private applyTheme() {
    const t = this.theme();
    if (t) document.documentElement.dataset['theme'] = t;
  }

  private readTheme(): 'light' | 'dark' | null {
    try {
      return (localStorage.getItem('taskora.theme') as 'light' | 'dark') ?? null;
    } catch {
      return null;
    }
  }

  /** Logout: this browser stops receiving notifications for this account. */
  async logout() {
    this.openPanel.set(null);
    await this.push.forget();
    this.auth.logout();
  }

  async enablePush() {
    await this.push.enable();
  }

  hidePushPrompt() {
    this.pushPromptHidden.set(true);
    try {
      sessionStorage.setItem('taskora.pushPrompt', 'hidden');
    } catch {
      /* ignore */
    }
  }
}
