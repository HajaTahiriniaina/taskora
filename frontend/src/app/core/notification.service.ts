import { Injectable, inject, signal } from '@angular/core';
import { ApiService } from './api.service';
import { AppNotification } from './models';
import { ToastService } from './toast.service';

/** Polls the API for notifications and exposes them as signals. */
@Injectable({ providedIn: 'root' })
export class NotificationService {
  private api = inject(ApiService);
  private toast = inject(ToastService);

  readonly items = signal<AppNotification[]>([]);
  readonly unread = signal(0);
  readonly loading = signal(false);
  /** Incremented when new notifications arrive: pages may refresh their data. */
  readonly tick = signal(0);

  private timer: ReturnType<typeof setInterval> | null = null;
  private lastUnread = -1;

  start() {
    if (this.timer) return;
    this.load();
    this.timer = setInterval(() => this.poll(), 15000);
  }

  stop() {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
    this.lastUnread = -1;
    this.items.set([]);
    this.unread.set(0);
  }

  load() {
    this.loading.set(true);
    this.api.notifications().subscribe({
      next: r => {
        this.items.set(r.items);
        this.unread.set(r.unread);
        this.lastUnread = r.unread;
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  private poll() {
    this.api.unreadCount().subscribe(r => {
      if (this.lastUnread >= 0 && r.unread > this.lastUnread) {
        const diff = r.unread - this.lastUnread;
        this.toast.show(diff > 1 ? `${diff} nouvelles notifications` : 'Nouvelle notification', 'info');
        this.tick.update(t => t + 1);
        this.load();
      }
      this.lastUnread = r.unread;
      this.unread.set(r.unread);
    });
  }

  markRead(n: AppNotification) {
    if (n.read) return;
    this.items.update(list => list.map(x => (x.id === n.id ? { ...x, read: true } : x)));
    this.unread.update(u => Math.max(0, u - 1));
    this.lastUnread = this.unread();
    this.api.markRead(n.id).subscribe();
  }

  markAllRead() {
    this.items.update(list => list.map(x => ({ ...x, read: true })));
    this.unread.set(0);
    this.lastUnread = 0;
    this.api.markAllRead().subscribe();
  }

  remove(n: AppNotification) {
    this.items.update(list => list.filter(x => x.id !== n.id));
    if (!n.read) { this.unread.update(u => Math.max(0, u - 1)); this.lastUnread = this.unread(); }
    this.api.deleteNotification(n.id).subscribe();
  }
}
