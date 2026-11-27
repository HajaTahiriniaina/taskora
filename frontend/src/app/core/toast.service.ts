import { inject, Injectable } from '@angular/core';
import Swal from 'sweetalert2';

import { DialogService } from './dialog.service';

export type ToastKind = 'error' | 'info' | 'success';

interface QueuedToast {
  duration: number;
  kind: ToastKind;
  message: string;
}

/** Static icons (same stroke style as the app's icons); constant strings, never user data. */
const ICONS: Record<ToastKind, string> = {
  success:
    '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5"/></svg>',
  error:
    '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round"><path d="M18 6 6 18M6 6l12 12"/></svg>',
  info: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round"><path d="M12 16v-5M12 8h.01"/></svg>',
};

/**
 * Notifications (SweetAlert2 toasts, bottom right, themed like the confirmation dialogs).
 *
 * SweetAlert2 shows one popup at a time, so toasts are queued: one after the other, never while a
 * confirmation dialog is open (a toast would otherwise close it). Identical consecutive messages
 * are merged, and a long queue speeds up so the user never waits behind old news.
 */
@Injectable({ providedIn: 'root' })
export class ToastService {
  private dialog = inject(DialogService);
  private queue: QueuedToast[] = [];
  private running = false;

  show(message: string, kind: ToastKind = 'info', duration = 3800) {
    const last = this.queue.at(-1);
    if (last && last.message === message) return;
    this.queue.push({ message, kind, duration });
    if (!this.running) this.drain();
  }

  success(message: string) {
    this.show(message, 'success');
  }

  private async drain() {
    this.running = true;
    while (this.queue.length) {
      // never on top of (and never closing) a confirmation dialog
      while (this.dialog.isOpen()) await new Promise((r) => setTimeout(r, 250));
      const t = this.queue.shift()!;
      const busy = this.queue.length > 1; // several waiting: shorter display
      const shownAt = Date.now();
      const result = await Swal.fire({
        toast: true,
        position: 'bottom-end',
        icon: t.kind === 'error' ? 'error' : t.kind === 'success' ? 'success' : 'info',
        iconHtml: ICONS[t.kind],
        text: t.message, // plain text: messages may contain card / board names
        showConfirmButton: false,
        showCloseButton: true,
        closeButtonAriaLabel: 'Fermer la notification',
        timer: busy ? Math.min(t.duration, 1800) : t.duration,
        timerProgressBar: true,
        customClass: {
          container: 'tk-toast-container',
          popup: `tk-toast tk-toast-${t.kind}`,
          htmlContainer: 'tk-toast-text',
          icon: 'tk-toast-icon',
          closeButton: 'tk-toast-close',
          timerProgressBar: 'tk-toast-progress',
        },
        didOpen: (popup) => {
          popup.addEventListener('mouseenter', Swal.stopTimer);
          popup.addEventListener('mouseleave', Swal.resumeTimer);
        },
      });
      // Replaced by a confirmation dialog before the end (not by its timer nor the user): show it again after
      const left = t.duration - (Date.now() - shownAt);
      if (
        result.dismiss !== Swal.DismissReason.timer &&
        result.dismiss !== Swal.DismissReason.close &&
        left > 500
      ) {
        this.queue.unshift({ ...t, duration: Math.max(2500, left) });
        await new Promise((r) => setTimeout(r, 300));
      }
    }
    this.running = false;
  }
}
