import { inject, Injectable, signal } from '@angular/core';
import { firstValueFrom, Subject } from 'rxjs';

import { ApiService, TrelloCredentials, TrelloFilesState } from './api.service';
import { ToastService } from './toast.service';

export interface TrelloTransferState extends TrelloFilesState {
  boardId: number;
  error: null | string;
  /** Last files copied (most recent first) */
  recent: string[];
  running: boolean;
  waitingSeconds: number;
}

/**
 * Copies the files of an imported Trello board, slice by slice (each API call works ~40 s).
 * The Trello key / token live ONLY in this service's memory: never in localStorage, never sent
 * anywhere but to our API with each slice. Reloading the page forgets them.
 * Root service: the copy goes on while the user browses other boards.
 */
@Injectable({ providedIn: 'root' })
export class TrelloTransferService {
  private api = inject(ApiService);
  private toast = inject(ToastService);

  /** Key / token given in this tab (memory only). */
  readonly credentials = signal<null | (TrelloCredentials & { account: string })>(null);
  readonly state = signal<null | TrelloTransferState>(null);
  /** Emitted when files were copied on a board (the board view refreshes its cards). */
  readonly progressed = new Subject<number>();
  private stopRequested = false;

  setCredentials(c: null | (TrelloCredentials & { account: string })) {
    this.credentials.set(c);
  }

  async start(boardId: number) {
    const creds = this.credentials();
    const s = this.state();
    if (!creds || (s?.running && s.boardId === boardId)) return;
    if (s?.running) {
      this.toast.show(
        'Une copie de pièces jointes Trello est déjà en cours sur un autre tableau.',
        'info',
      );
      return;
    }
    this.stopRequested = false;
    this.state.set({
      ...(await this.status(boardId)),
      boardId,
      running: true,
      recent: [],
      waitingSeconds: 0,
      error: null,
    });

    while (!this.stopRequested) {
      const current = this.state()!;
      if (current.pending === 0) break;
      try {
        const r = await firstValueFrom(this.api.transferTrelloFiles(boardId, creds));
        const recent = [...r.transferred.map((t) => t.name).reverse(), ...current.recent].slice(
          0,
          5,
        );
        this.state.set({
          ...current,
          ...r,
          boardId,
          running: true,
          recent,
          waitingSeconds: 0,
          error: null,
        });
        if (r.transferred.length) this.progressed.next(boardId);
        if (r.retryAfter) await this.wait(r.retryAfter); // Trello rate limit
      } catch (e: any) {
        const error =
          e?.error?.error ?? 'La copie a été interrompue (réseau ?). Vous pouvez la reprendre.';
        if (e?.status === 422) this.credentials.set(null); // key / token refused: ask again
        this.state.set({ ...current, running: false, error });
        return;
      }
    }
    const end = this.state()!;
    this.state.set({ ...end, running: false });
    if (!this.stopRequested) {
      this.toast.show(
        end.failed
          ? `Pièces jointes Trello copiées : ${end.done} sur ${end.total}. ${end.failed} n’ont pas pu l’être.`
          : `Toutes les pièces jointes Trello (${end.done}) sont copiées dans Taskora.`,
        end.failed ? 'info' : 'success',
        7000,
      );
      this.progressed.next(boardId);
    }
  }

  stop() {
    this.stopRequested = true;
  }

  async refresh(boardId: number) {
    const s = this.state();
    if (s?.running) return;
    this.state.set({
      ...(await this.status(boardId)),
      boardId,
      running: false,
      recent: s?.boardId === boardId ? s.recent : [],
      waitingSeconds: 0,
      error: s?.boardId === boardId ? s.error : null,
    });
  }

  async retryFailed(boardId: number) {
    await firstValueFrom(this.api.retryTrelloFiles(boardId));
    await this.refresh(boardId);
    if (this.credentials()) this.start(boardId);
  }

  async abandon(boardId: number) {
    this.stop();
    const s = await firstValueFrom(this.api.abandonTrelloFiles(boardId));
    this.state.set({ ...s, boardId, running: false, recent: [], waitingSeconds: 0, error: null });
    this.progressed.next(boardId);
  }

  private status(boardId: number) {
    return firstValueFrom(this.api.trelloFiles(boardId));
  }

  private async wait(seconds: number) {
    for (let s = seconds; s > 0 && !this.stopRequested; s--) {
      this.state.update((x) => x && { ...x, waitingSeconds: s });
      await new Promise((r) => setTimeout(r, 1000));
    }
    this.state.update((x) => x && { ...x, waitingSeconds: 0 });
  }
}
