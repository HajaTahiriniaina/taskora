import { Injectable } from '@angular/core';
import Swal from 'sweetalert2';

export interface ConfirmOptions {
  cancelText?: string;
  confirmText?: string;
  icon?: 'info' | 'question' | 'warning';
  /** Plain text (never interpreted as HTML: titles of cards / boards come from users). */
  text?: string;
  title: string;
  /** danger: red button, focus on Cancel (irreversible / destructive actions). */
  tone?: 'danger' | 'primary';
}

/**
 * Confirmation dialogs (SweetAlert2) styled like the rest of Taskora: theme tokens, buttons,
 * dark mode. Replaces the browser's native confirm().
 */
@Injectable({ providedIn: 'root' })
export class DialogService {
  private openCount = 0;

  /** True while a dialog is shown (and during the key event that closes it): Escape handlers behind it stay quiet. */
  isOpen(): boolean {
    return this.openCount > 0;
  }

  async confirm(o: ConfirmOptions): Promise<boolean> {
    this.openCount++;
    try {
      return await this.fire(o);
    } finally {
      setTimeout(() => this.openCount--);
    }
  }

  private async fire(o: ConfirmOptions): Promise<boolean> {
    const danger = (o.tone ?? 'danger') === 'danger';
    const result = await Swal.fire({
      title: o.title,
      text: o.text,
      icon: o.icon ?? (danger ? 'warning' : 'question'),
      showCancelButton: true,
      confirmButtonText: o.confirmText ?? 'Confirmer',
      cancelButtonText: o.cancelText ?? 'Annuler',
      reverseButtons: true,
      focusCancel: danger,
      buttonsStyling: false,
      returnFocus: true,
      customClass: {
        container: 'tk-swal-container',
        popup: 'tk-swal',
        icon: 'tk-swal-icon',
        title: 'tk-swal-title',
        htmlContainer: 'tk-swal-text',
        actions: 'tk-swal-actions',
        confirmButton: danger ? 'btn tk-swal-danger' : 'btn btn-primary',
        cancelButton: 'btn btn-secondary',
      },
    });
    return result.isConfirmed;
  }
}
