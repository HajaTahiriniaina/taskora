import { HttpEventType } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { Subject } from 'rxjs';

import { ApiService } from './api.service';
import { Attachment } from './models';
import { ToastService } from './toast.service';

export const MAX_ATTACHMENT = 100 * 1024 * 1024;

export interface PendingUpload {
  cardId: number;
  loaded: number;
  name: string;
  /** 0-100: bytes sent to the server */
  progress: number;
  /** All bytes sent: the server is storing the file (Amazon S3) */
  saving: boolean;
  size: number;
  uid: number;
}

/**
 * Card attachment uploads, shared by the board (file dropped on a card) and the card modal
 * (button, drop). Keeps the in-flight uploads with their progress so both views can show them.
 */
@Injectable({ providedIn: 'root' })
export class AttachmentUploadsService {
  private api = inject(ApiService);
  private toast = inject(ToastService);
  private seq = 0;

  readonly pending = signal<PendingUpload[]>([]);
  /** Emitted for each attachment once stored. */
  readonly added = new Subject<{ attachment: Attachment; cardId: number }>();

  /** cardId → overall progress (0-100) of its in-flight uploads (100 = being stored) */
  readonly progressByCard = computed(() => {
    const out = new Map<number, { done: number; total: number }>();
    for (const p of this.pending()) {
      const o = out.get(p.cardId) ?? { done: 0, total: 0 };
      o.done += Math.min(p.loaded, p.size);
      o.total += p.size;
      out.set(p.cardId, o);
    }
    return new Map(
      [...out].map(([id, o]) => [
        id,
        o.total ? Math.min(100, Math.floor((o.done / o.total) * 100)) : 0,
      ]),
    );
  });

  upload(cardId: number, files: Iterable<File>) {
    for (const file of files) {
      if (file.size > MAX_ATTACHMENT) {
        this.toast.show(`« ${file.name} » dépasse 100 Mo.`, 'error');
        continue;
      }
      if (!file.size && !file.type) {
        this.toast.show(`« ${file.name} » est un dossier ou un fichier vide.`, 'error');
        continue;
      }
      const uid = ++this.seq;
      this.pending.update((p) => [
        ...p,
        {
          uid,
          cardId,
          name: file.name,
          size: file.size || 1,
          progress: 0,
          loaded: 0,
          saving: false,
        },
      ]);
      const done = () => this.pending.update((p) => p.filter((x) => x.uid !== uid));
      this.api.addAttachment(cardId, file).subscribe({
        next: (e) => {
          if (e.type === HttpEventType.UploadProgress) {
            const total = e.total ?? file.size;
            const progress = total ? Math.min(100, Math.floor((e.loaded / total) * 100)) : 0;
            this.pending.update((p) =>
              p.map((x) =>
                x.uid === uid ? { ...x, progress, loaded: e.loaded, saving: progress >= 100 } : x,
              ),
            );
          } else if (e.type === HttpEventType.Response && e.body) {
            done();
            this.added.next({ cardId, attachment: e.body });
          }
        },
        error: () => done(), // error toast shown by the interceptor
      });
    }
  }
}

/** Files of a drag event (only when the user drags files from the OS, not text or cards). */
export function draggedFiles(e: DragEvent): boolean {
  return !!e.dataTransfer && Array.from(e.dataTransfer.types).includes('Files');
}

export function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} o`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} Ko`;
  return `${(bytes / 1024 / 1024).toFixed(bytes < 10 * 1024 * 1024 ? 1 : 0).replace('.', ',')} Mo`;
}

/** Short type label shown on the file thumbnail (PDF, DOCX, ZIP…). */
export function fileExt(name: string): string {
  const m = /\.([a-z0-9]{1,5})$/i.exec(name);
  return m ? m[1].toUpperCase() : 'FICHIER';
}

/** Badge color by family of file. */
export function extColor(name: string): string {
  const ext = fileExt(name).toLowerCase();
  if (ext === 'pdf') return '#dc2626';
  if (['doc', 'docx', 'odt', 'rtf', 'txt', 'md', 'pages'].includes(ext)) return '#2563eb';
  if (['xls', 'xlsx', 'ods', 'csv', 'numbers'].includes(ext)) return '#16a34a';
  if (['ppt', 'pptx', 'odp', 'key'].includes(ext)) return '#ea580c';
  if (['zip', 'rar', '7z', 'gz', 'tar'].includes(ext)) return '#ca8a04';
  if (['mp4', 'mov', 'avi', 'mkv', 'webm', 'mp3', 'wav'].includes(ext)) return '#7c3aed';
  if (['svg', 'psd', 'ai', 'fig', 'sketch', 'heic', 'tif', 'tiff', 'bmp'].includes(ext))
    return '#db2777';
  return '#64748b';
}
