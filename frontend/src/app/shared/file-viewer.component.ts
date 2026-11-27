import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  HostListener,
  inject,
  input,
  OnDestroy,
  output,
  signal,
  untracked,
} from '@angular/core';
import { DomSanitizer, SafeHtml, SafeResourceUrl } from '@angular/platform-browser';
import DOMPurify from 'dompurify';
import { firstValueFrom } from 'rxjs';

import { ApiService } from '../core/api.service';
import { extColor, fileExt, formatSize } from '../core/attachment-uploads.service';
import { Attachment } from '../core/models';
import { IconComponent } from './icon.component';
import { JsonNodeComponent } from './json-node.component';

export type ViewerKind =
  'audio' | 'eml' | 'image' | 'json' | 'none' | 'office' | 'pdf' | 'sheet' | 'text' | 'video';

/** Formats HTML5 players read natively (the API streams them inline, with seeking). */
const VIDEO = ['mp4', 'm4v', 'mov', 'webm', 'ogv'];
const AUDIO = ['mp3', 'm4a', 'aac', 'wav', 'oga', 'ogg', 'opus', 'flac', 'weba'];
const SHEET = ['xlsx', 'xlsm', 'xlsb', 'xls', 'ods', 'ots', 'fods', 'numbers', 'csv', 'tsv'];
const OFFICE = [
  'doc',
  'docx',
  'docm',
  'dot',
  'dotx',
  'odt',
  'ott',
  'rtf',
  'wpd',
  'pages',
  'ppt',
  'pptx',
  'pptm',
  'pps',
  'ppsx',
  'pot',
  'potx',
  'odp',
  'otp',
  'key',
];
/** Spreadsheets the server can also render as PDF (page layout view). */
const SHEET_AS_PDF = ['xlsx', 'xlsm', 'xlsb', 'xls', 'ods', 'ots', 'numbers'];
const TEXT = [
  'txt',
  'text',
  'log',
  'md',
  'markdown',
  'xml',
  'yml',
  'yaml',
  'ini',
  'conf',
  'cfg',
  'env',
  'properties',
  'toml',
  'sql',
  'js',
  'mjs',
  'ts',
  'css',
  'scss',
  'less',
  'html',
  'htm',
  'svg',
  'py',
  'php',
  'rb',
  'java',
  'kt',
  'c',
  'h',
  'cpp',
  'cs',
  'go',
  'rs',
  'sh',
  'bat',
  'ps1',
  'vue',
  'jsx',
  'tsx',
  'srt',
  'vtt',
  'gitignore',
  'dockerfile',
];

export function viewerKind(a: Attachment): ViewerKind {
  const ext = a.ext || fileExt(a.name).toLowerCase();
  if (a.image) return 'image';
  if (ext === 'pdf') return 'pdf';
  if (VIDEO.includes(ext)) return 'video';
  if (AUDIO.includes(ext)) return 'audio';
  if (SHEET.includes(ext)) return 'sheet';
  if (OFFICE.includes(ext)) return 'office';
  if (ext === 'json' || ext === 'geojson' || ext === 'jsonld') return 'json';
  if (ext === 'eml') return 'eml';
  if (TEXT.includes(ext) || a.mime.startsWith('text/')) return 'text';
  return 'none';
}

/** Above this size, files read in the browser (text, JSON, sheets, e-mails) are not previewed. */
const MAX_CLIENT = 40 * 1024 * 1024;
const MAX_TEXT_CHARS = 400_000;
const MAX_ROWS = 2000;
const MAX_COLS = 80;

interface Sheet {
  name: string;
  rows: string[][];
  totalCols: number;
  totalRows: number;
}
interface MailAddress {
  address?: string;
  name?: string;
}
interface MailFile {
  name: string;
  size: number;
  url: string;
}
interface Mail {
  cc: string;
  date: string;
  files: MailFile[];
  from: string;
  html: null | SafeHtml;
  remoteImages: boolean;
  subject: string;
  text: null | string;
  to: string;
}

/**
 * Full-window viewer for card attachments: images, PDF (browser viewer), Word / PowerPoint /
 * LibreOffice (converted to PDF by the server), Excel / LibreOffice Calc / CSV (sheet tabs + table),
 * text and code, JSON (collapsible tree) and e-mails (.eml, rendered in a sandboxed frame).
 */
@Component({
  selector: 'tk-file-viewer',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconComponent, JsonNodeComponent],
  templateUrl: './file-viewer.component.html',
  styleUrl: './file-viewer.component.scss',
})
export class FileViewerComponent implements OnDestroy {
  private api = inject(ApiService);
  private sanitizer = inject(DomSanitizer);

  attachment = input.required<Attachment>();
  /** All attachments of the card, for previous / next. */
  attachments = input<Attachment[]>([]);
  isCover = input(false);
  navigate = output<Attachment>();
  closed = output<void>();
  coverToggled = output<void>();

  kind = computed(() => viewerKind(this.attachment()));
  index = computed(() => this.attachments().findIndex((a) => a.id === this.attachment().id));
  ext = computed(() => fileExt(this.attachment().name));
  color = computed(() => extColor(this.attachment().name));
  canSheetPdf = computed(() => SHEET_AS_PDF.includes(this.attachment().ext));

  loading = signal(false);
  loadingText = signal('Chargement…');
  error = signal<null | string>(null);
  frameUrl = signal<null | SafeResourceUrl>(null);
  text = signal<null | string>(null);
  truncated = signal(false);
  json = signal<null | { value: unknown }>(null);
  sheets = signal<Sheet[]>([]);
  sheetIndex = signal(0);
  sheetMode = signal<'pdf' | 'table'>('table');
  mail = signal<Mail | null>(null);
  private mailSource: null | { files: Map<string, string>; html: string } = null;
  private objectUrls: string[] = [];
  private token = 0;

  formatSize = formatSize;
  sheet = computed(() => this.sheets()[this.sheetIndex()] ?? null);
  columns = computed(() =>
    Array.from({ length: Math.min(MAX_COLS, this.sheet()?.totalCols ?? 0) }, (_, i) => colName(i)),
  );

  constructor() {
    effect(() => {
      const a = this.attachment();
      untracked(() => this.load(a));
    });
  }

  ngOnDestroy() {
    this.revoke();
  }

  // ---------- navigation ----------
  go(delta: number) {
    const list = this.attachments();
    if (list.length < 2) return;
    this.navigate.emit(list[(this.index() + delta + list.length) % list.length]);
  }

  @HostListener('document:keydown', ['$event'])
  onKey(e: KeyboardEvent) {
    // arrows inside a player seek / change the volume
    if ((e.target as HTMLElement).closest?.('input, textarea, [contenteditable], video, audio'))
      return;
    if (e.key === 'ArrowRight') {
      e.preventDefault();
      this.go(1);
    }
    if (e.key === 'ArrowLeft') {
      e.preventDefault();
      this.go(-1);
    }
  }

  // ---------- loading ----------
  private reset() {
    this.revoke();
    this.error.set(null);
    this.frameUrl.set(null);
    this.text.set(null);
    this.truncated.set(false);
    this.json.set(null);
    this.sheets.set([]);
    this.sheetIndex.set(0);
    this.sheetMode.set('table');
    this.mail.set(null);
    this.mailSource = null;
  }

  private async load(a: Attachment) {
    const token = ++this.token;
    const stale = () => token !== this.token;
    this.reset();
    const kind = viewerKind(a);
    if (kind === 'image' || kind === 'video' || kind === 'audio' || kind === 'none') {
      this.loading.set(false);
      return;
    }
    if (kind === 'pdf') {
      this.frameUrl.set(this.trusted(a.url));
      return;
    }
    if (kind === 'office') {
      await this.loadPdfPreview(a, stale);
      return;
    }
    if (a.size > MAX_CLIENT) {
      this.error.set(
        `Fichier trop volumineux pour l’aperçu (${formatSize(a.size)}). Téléchargez-le pour l’ouvrir.`,
      );
      return;
    }
    this.loading.set(true);
    this.loadingText.set('Chargement…');
    try {
      const buffer = await firstValueFrom(this.api.attachmentContent(a.id));
      if (stale()) return;
      if (kind === 'text') this.showText(buffer);
      else if (kind === 'json') this.showJson(buffer);
      else if (kind === 'sheet') await this.showSheet(buffer, a.ext, stale);
      else if (kind === 'eml') await this.showMail(buffer, stale);
    } catch (e) {
      if (!stale())
        this.error.set(
          e instanceof ViewerError
            ? e.message
            : 'Impossible d’afficher ce fichier. Téléchargez-le pour l’ouvrir.',
        );
    } finally {
      if (!stale()) this.loading.set(false);
    }
  }

  private async loadPdfPreview(a: Attachment, stale: () => boolean) {
    this.loading.set(true);
    this.loadingText.set('Conversion du document en PDF… (seulement à la première ouverture)');
    try {
      const { url } = await firstValueFrom(this.api.attachmentPreview(a.id));
      if (!stale()) this.frameUrl.set(this.trusted(url));
    } catch (e: any) {
      if (!stale())
        this.error.set(
          e?.error?.error ?? 'La conversion du document a échoué. Téléchargez-le pour l’ouvrir.',
        );
    } finally {
      if (!stale()) this.loading.set(false);
    }
  }

  setSheetMode(mode: 'pdf' | 'table') {
    if (mode === this.sheetMode()) return;
    this.sheetMode.set(mode);
    if (mode === 'pdf' && !this.frameUrl()) {
      const token = this.token;
      this.loadPdfPreview(this.attachment(), () => token !== this.token);
    }
  }

  /** Only our own "/uploads/…" URLs are put in a frame. */
  private trusted(url: string): null | SafeResourceUrl {
    return /^\/uploads\/[A-Za-z0-9/._-]+$/.test(url)
      ? this.sanitizer.bypassSecurityTrustResourceUrl(url)
      : null;
  }

  private decode(buffer: ArrayBuffer): string {
    let s = new TextDecoder('utf-8').decode(buffer);
    // Latin-1 / Windows-1252 files (old CSV, logs) when UTF-8 decoding produced replacement characters
    if (s.includes('�')) {
      try {
        s = new TextDecoder('windows-1252').decode(buffer);
      } catch {
        /* keep UTF-8 */
      }
    }
    return s.replace(/^﻿/, '');
  }

  private showText(buffer: ArrayBuffer) {
    const s = this.decode(buffer);
    this.truncated.set(s.length > MAX_TEXT_CHARS);
    this.text.set(s.length > MAX_TEXT_CHARS ? s.slice(0, MAX_TEXT_CHARS) : s);
  }

  private showJson(buffer: ArrayBuffer) {
    const s = this.decode(buffer);
    try {
      this.json.set({ value: JSON.parse(s) });
    } catch {
      this.showText(buffer); // invalid JSON: plain text
      this.error.set(null);
    }
  }

  private async showSheet(buffer: ArrayBuffer, ext: string, stale: () => boolean) {
    const XLSX = await import('xlsx');
    if (stale()) return;
    let wb;
    if (ext === 'csv' || ext === 'tsv') {
      const s = this.decode(buffer);
      const nl = s.indexOf('\n');
      const first = s.slice(0, nl > 0 ? nl : 2000);
      const fs =
        ext === 'tsv' || first.split('\t').length > first.split(/[;,]/).length
          ? '\t'
          : first.split(';').length > first.split(',').length
            ? ';'
            : ',';
      wb = XLSX.read(s, { type: 'string', FS: fs, raw: true });
    } else {
      wb = XLSX.read(new Uint8Array(buffer), { type: 'array', cellDates: true, dense: true });
    }
    const sheets: Sheet[] = wb.SheetNames.map((name) => {
      const ws = wb.Sheets[name];
      const ref = ws['!ref'];
      const range = ref ? XLSX.utils.decode_range(ref) : null;
      const totalRows = range ? range.e.r + 1 : 0;
      const totalCols = range ? range.e.c + 1 : 0;
      const rows = ref
        ? XLSX.utils
            .sheet_to_json<unknown[]>(ws, {
              header: 1,
              raw: false,
              defval: '',
              blankrows: true,
              dateNF: 'dd/mm/yyyy',
              range: {
                s: { r: 0, c: 0 },
                e: { r: Math.min(totalRows, MAX_ROWS) - 1, c: Math.min(totalCols, MAX_COLS) - 1 },
              },
            })
            .map((r) => r.map((v) => String(v ?? '')))
        : [];
      return { name, rows, totalRows, totalCols };
    });
    if (!sheets.length) throw new ViewerError('Ce classeur ne contient aucune feuille.');
    this.sheets.set(sheets);
  }

  private async showMail(buffer: ArrayBuffer, stale: () => boolean) {
    const { default: PostalMime } = await import('postal-mime');
    const email = await PostalMime.parse(buffer);
    if (stale()) return;
    const who = (list?: MailAddress | MailAddress[] | null) =>
      (Array.isArray(list) ? list : list ? [list] : [])
        .map((x) => (x.name ? `${x.name} <${x.address ?? ''}>` : (x.address ?? '')))
        .join(', ');

    const files: MailFile[] = [];
    const inline = new Map<string, string>(); // cid → data: URL (images shown in the body)
    for (const att of email.attachments ?? []) {
      const bytes =
        typeof att.content === 'string'
          ? new TextEncoder().encode(att.content)
          : new Uint8Array(att.content as ArrayBuffer);
      const blob = new Blob([bytes], { type: att.mimeType || 'application/octet-stream' });
      if (
        att.contentId &&
        /^image\/(png|jpe?g|gif|webp)$/.test(att.mimeType) &&
        bytes.length < 5 * 1024 * 1024
      ) {
        inline.set(att.contentId.replace(/^<|>$/g, ''), await toDataUrl(blob));
      }
      if (att.disposition !== 'inline' || !att.contentId) {
        const url = URL.createObjectURL(blob);
        this.objectUrls.push(url);
        files.push({ name: att.filename || 'piece-jointe', size: bytes.length, url });
      }
    }
    this.mailSource = email.html ? { html: email.html, files: inline } : null;
    this.mail.set({
      subject: email.subject || '(sans objet)',
      from: who(email.from as MailAddress),
      to: who(email.to as MailAddress[]),
      cc: who(email.cc as MailAddress[]),
      date: email.date
        ? new Date(email.date).toLocaleString('fr-FR', { dateStyle: 'full', timeStyle: 'short' })
        : '',
      html: this.mailSource ? this.mailHtml(false) : null,
      text: email.html ? null : (email.text ?? ''),
      files,
      remoteImages: false,
    });
  }

  /** The browser cannot decode this file (codec not supported, e.g. HEVC, AVI-like content). */
  mediaError() {
    this.error.set(
      'Votre navigateur ne sait pas lire ce fichier (format ou codec non pris en charge). Téléchargez-le pour l’ouvrir avec un lecteur.',
    );
  }

  /** Remote images are blocked by default (tracking pixels); the user can load them. */
  showRemoteImages() {
    const m = this.mail();
    if (m && this.mailSource)
      this.mail.set({ ...m, remoteImages: true, html: this.mailHtml(true) });
  }

  /**
   * E-mail HTML: sanitized (DOMPurify), cid: images replaced by data: URLs, then shown in an
   * iframe sandboxed WITHOUT scripts nor same-origin, with a CSP that blocks remote content.
   */
  private mailHtml(remote: boolean): SafeHtml {
    const { html, files } = this.mailSource!;
    const clean = DOMPurify.sanitize(html, {
      WHOLE_DOCUMENT: true,
      FORBID_TAGS: ['form', 'input', 'button', 'textarea', 'select'],
      ADD_TAGS: ['style'],
    }).replace(/(src|background)\s*=\s*(["'])cid:([^"']+)\2/gi, (all, attr, q, cid) =>
      files.has(cid) ? `${attr}=${q}${files.get(cid)}${q}` : all,
    );
    const img = remote ? 'data: https: http:' : 'data:';
    const head =
      `<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src ${img}; style-src 'unsafe-inline'; font-src data:">` +
      '<base target="_blank"><style>body{margin:16px;font:14px/1.5 system-ui,sans-serif;color:#0f172a;background:#fff;word-wrap:break-word}img{max-width:100%;height:auto}</style>';
    const doc = /<head[^>]*>/i.test(clean)
      ? clean.replace(/<head[^>]*>/i, (m) => m + head)
      : `<html><head>${head}</head><body>${clean}</body></html>`;
    return this.sanitizer.bypassSecurityTrustHtml(doc);
  }

  hasRemoteImages(): boolean {
    return !!this.mailSource && /<img[^>]+src\s*=\s*["']https?:/i.test(this.mailSource.html);
  }

  private revoke() {
    for (const u of this.objectUrls) URL.revokeObjectURL(u);
    this.objectUrls = [];
  }
}

class ViewerError extends Error {}

function colName(i: number): string {
  let s = '';
  for (i++; i > 0; i = Math.floor((i - 1) / 26)) s = String.fromCharCode(65 + ((i - 1) % 26)) + s;
  return s;
}

function toDataUrl(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(String(r.result));
    r.onerror = () => reject(r.error);
    r.readAsDataURL(blob);
  });
}
