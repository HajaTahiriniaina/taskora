import {
  Component,
  HostListener,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/api.service';
import { DialogService } from '../../core/dialog.service';
import { AuthService } from '../../core/auth.service';
import {
  AttachmentUploadsService,
  draggedFiles,
  extColor,
  fileExt,
  formatSize,
} from '../../core/attachment-uploads.service';
import {
  Activity,
  Attachment,
  Board,
  CardDetail,
  Member,
  Comment,
  CustomField,
  PALETTE,
  PRIORITIES,
  Priority,
} from '../../core/models';
import { Seg, describeActivity } from './activity-text';
import { ToastService } from '../../core/toast.service';
import { AvatarComponent } from '../../shared/avatar.component';
import { IconComponent } from '../../shared/icon.component';
import { TimeAgoPipe } from '../../shared/time-ago.pipe';
import { RichEditorComponent } from '../../shared/rich-editor.component';
import { RichViewComponent } from '../../shared/rich-view.component';
import { FileViewerComponent } from '../../shared/file-viewer.component';
import { dueState } from './card-format';

type FeedItem =
  | { kind: 'comment'; id: string; at: string; comment: Comment }
  | { kind: 'activity'; id: string; at: string; activity: Activity; segs: Seg[] };

type PopKind = 'members' | 'labels' | 'cover' | 'priority' | 'more' | 'add';

function readPref(key: string, fallback: boolean): boolean {
  try {
    const v = localStorage.getItem(key);
    return v === null ? fallback : v === '1';
  } catch {
    return fallback;
  }
}

const FIELD_ICONS: Record<string, string> = {
  text: 'type',
  number: 'hash',
  date: 'calendar',
  checkbox: 'toggle',
  select: 'list',
  url: 'link',
};

@Component({
  selector: 'tk-card-modal',
  imports: [
    FormsModule,
    IconComponent,
    AvatarComponent,
    TimeAgoPipe,
    RichEditorComponent,
    RichViewComponent,
    FileViewerComponent,
  ],
  templateUrl: './card-modal.component.html',
  styleUrl: './card-modal.component.scss',
})
export class CardModalComponent {
  private api = inject(ApiService);
  private toast = inject(ToastService);
  private dialog = inject(DialogService);
  uploads = inject(AttachmentUploadsService);
  auth = inject(AuthService);

  cardId = input.required<number>();
  board = input.required<Board>();
  changed = output<CardDetail>();
  deleted = output<number>();
  /** The card was archived (leaves the board, the dialog stays open) or restored. */
  archivedChange = output<CardDetail>();
  closed = output<void>();
  moved = output<void>();
  manageFields = output<void>();

  card = signal<CardDetail | null>(null);
  pop = signal<PopKind | null>(null);
  /** Comment composer: collapsed placeholder until clicked (Trello-like). */
  composing = signal(false);
  composerSeed = signal('');
  /** Remembered per browser: show/hide the comment list in the activity panel. */
  /** "Afficher / Masquer les détails": activity entries in the feed (comments always shown). Remembered per browser. */
  showDetails = signal(readPref('taskora.card.details', true));
  activities = signal<Activity[]>([]);
  activityHasMore = signal(false);
  activityLoading = signal(false);
  private activityTimer: ReturnType<typeof setTimeout> | null = null;

  /** Comments + activity merged, newest first (Trello-like feed). */
  feed = computed<FeedItem[]>(() => {
    const comments: FeedItem[] = (this.card()?.comments ?? []).map((c) => ({
      kind: 'comment',
      id: 'c' + c.id,
      at: c.createdAt,
      comment: c,
    }));
    const acts: FeedItem[] = this.showDetails()
      ? this.activities().map((a) => ({
          kind: 'activity',
          id: 'a' + a.id,
          at: a.createdAt,
          activity: a,
          segs: describeActivity(a),
        }))
      : [];
    return [...comments, ...acts].sort(
      (x, y) => y.at.localeCompare(x.at) || y.id.localeCompare(x.id),
    );
  });
  editingDesc = signal(false);

  // ---------- attachments ----------
  /** Uploads in flight for this card (progress bars in the list). */
  pendingUploads = computed(() => this.uploads.pending().filter((p) => p.cardId === this.cardId()));
  /** A file is dragged over the modal (outside an editor): show the drop overlay. */
  fileOver = signal(false);
  renaming = signal<number | null>(null);
  showAllAttachments = signal(false);
  visibleAttachments = computed(() => {
    const all = this.card()?.attachments ?? [];
    return this.showAllAttachments() ? all : all.slice(0, 6);
  });
  /** Members offered after "@" in comments and the description (everyone but me). */
  mentionables = computed(() => this.board().members.filter((m) => m.id !== this.auth.user()?.id));
  imageAttachments = computed(() => (this.card()?.attachments ?? []).filter((a) => a.image));
  /** Attachment shown in the in-page viewer. */
  viewing = signal<Attachment | null>(null);
  /** Names of images uploaded from the cover popover: they become the cover once stored. */
  private coverUploads = new Set<string>();
  formatSize = formatSize;
  fileExt = fileExt;
  extColor = extColor;
  private dragDepth = 0;
  newItem = '';
  newLabel = { name: '', color: PALETTE[6] };
  hideDone = signal(false);

  palette = PALETTE;
  priorities = PRIORITIES;
  fieldIcons = FIELD_ICONS;

  progress = computed(() => {
    const c = this.card();
    if (!c || !c.checklist.length) return 0;
    return Math.round((c.checklist.filter((i) => i.done).length / c.checklist.length) * 100);
  });
  visibleChecklist = computed(() =>
    (this.card()?.checklist ?? []).filter((i) => !this.hideDone() || !i.done),
  );
  priority = computed(() => this.priorities.find((p) => p.value === this.card()?.priority));
  due = computed(() => (this.card() ? dueState(this.card()!) : null));
  dueInput = computed(() => {
    const d = this.card()?.dueDate;
    if (!d) return '';
    const date = new Date(d);
    return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
  });

  constructor() {
    this.uploads.added.pipe(takeUntilDestroyed()).subscribe(({ cardId, attachment }) => {
      const c = this.card();
      if (c && c.id === cardId && !c.attachments.some((a) => a.id === attachment.id)) {
        this.apply({ ...c, attachments: [attachment, ...c.attachments] });
        if (attachment.image && this.coverUploads.delete(attachment.name))
          this.setCoverImage(attachment.id);
      }
    });
    effect(() => {
      const id = this.cardId();
      untracked(() => {
        this.card.set(null);
        this.activities.set([]);
        this.api.card(id).subscribe({
          next: (c) => {
            this.card.set(c);
            this.loadActivity();
          },
          error: () => this.closed.emit(),
        });
      });
    });
  }

  // ---------- activity ----------
  loadActivity() {
    const id = this.card()?.id;
    if (!id) return;
    this.api.cardActivity(id).subscribe((r) => {
      this.activities.set(r.items);
      this.activityHasMore.set(r.hasMore);
    });
  }

  loadMoreActivity() {
    const last = this.activities().at(-1);
    if (!last || this.activityLoading()) return;
    this.activityLoading.set(true);
    this.api.cardActivity(this.card()!.id, last.id).subscribe({
      next: (r) => {
        this.activities.update((a) => [...a, ...r.items]);
        this.activityHasMore.set(r.hasMore);
        this.activityLoading.set(false);
      },
      error: () => this.activityLoading.set(false),
    });
  }

  /** After an edit, fetch the new history entries (debounced: several edits → one request). */
  private refreshActivity() {
    if (this.activityTimer) clearTimeout(this.activityTimer);
    this.activityTimer = setTimeout(() => this.loadActivity(), 400);
  }

  private apply(c: CardDetail) {
    this.refreshActivity();
    this.card.set(c);
    this.changed.emit(c);
  }

  private patch(data: Record<string, unknown>) {
    this.api.updateCard(this.card()!.id, data).subscribe((c) => this.apply(c));
  }

  // ---------- basics ----------
  saveTitle(title: string) {
    title = title.trim();
    if (title && title !== this.card()!.title) this.patch({ title });
  }

  moveTo(listId: number) {
    const c = this.card()!;
    if (listId === c.listId) return;
    const target = this.board().lists.find((l) => l.id === listId)!;
    this.api.moveCard(c.id, listId, target.cardCount).subscribe(() => {
      this.card.set({ ...c, listId, listTitle: target.title });
      this.toast.success(`Carte déplacée vers « ${target.title} »`);
      this.moved.emit();
      this.refreshActivity();
    });
  }

  copyLink() {
    const c = this.card()!;
    const url = `${location.origin}/boards/${this.board().id}?card=${c.id}`;
    navigator.clipboard?.writeText(url).then(
      () => this.toast.success(`Lien de la carte #${c.number} copié`),
      () => this.toast.show(url, 'info', 8000),
    );
  }

  toggleCompleted() {
    this.patch({ completed: !this.card()!.completed });
  }
  setCover(color: string | null) {
    this.pop.set(null);
    this.patch(
      color || !this.card()?.coverAttachmentId ? { cover: color } : { coverAttachmentId: null },
    );
  }
  setCoverImage(attachmentId: number | null) {
    this.pop.set(null);
    this.patch({ coverAttachmentId: attachmentId });
  }

  onCoverPicked(input: HTMLInputElement) {
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    this.coverUploads.add(file.name);
    this.uploads.upload(this.card()!.id, [file]);
  }

  /** Opens the in-page viewer (Ctrl/⌘-click keeps the browser's "open in new tab"). */
  view(a: Attachment, e: MouseEvent) {
    if (e.metaKey || e.ctrlKey || e.shiftKey || e.button === 1) return;
    e.preventDefault();
    this.viewing.set(a);
  }
  setPriority(p: Priority) {
    this.pop.set(null);
    this.patch({ priority: p });
  }
  setDue(value: string) {
    this.patch({ dueDate: value ? new Date(value).toISOString() : null });
  }

  /** Opens the editor, unless the click was on a link or a checklist box inside the description. */
  startDesc(e?: MouseEvent) {
    if ((e?.target as HTMLElement | null)?.closest('a, input')) return;
    this.editingDesc.set(true);
  }

  saveDesc(editor: RichEditorComponent) {
    const html = editor.getHTML();
    this.editingDesc.set(false);
    if (html !== (this.card()!.description ?? '')) this.patch({ description: html || null });
  }

  // ---------- members / labels ----------
  isAssigned(userId: number) {
    return this.card()!.assignees.some((a) => a.id === userId);
  }
  toggleAssignee(userId: number) {
    this.api.toggleAssignee(this.card()!.id, userId).subscribe((c) => this.apply(c));
  }

  hasLabel(id: number) {
    return this.card()!.labelIds.includes(id);
  }
  toggleLabel(id: number) {
    const ids = this.hasLabel(id)
      ? this.card()!.labelIds.filter((x) => x !== id)
      : [...this.card()!.labelIds, id];
    this.patch({ labelIds: ids });
  }
  labelById(id: number) {
    return this.board().labels.find((l) => l.id === id);
  }

  createLabel() {
    const name = this.newLabel.name.trim();
    if (!name) return;
    this.api.addLabel(this.board().id, name, this.newLabel.color).subscribe((l) => {
      this.board().labels.push(l);
      this.newLabel.name = '';
      this.toggleLabel(l.id);
    });
  }

  // ---------- custom fields ----------
  fieldValue(f: CustomField) {
    return this.card()!.customValues[f.id] ?? null;
  }
  setField(f: CustomField, value: unknown) {
    if ((value ?? '') === (this.fieldValue(f) ?? '')) return;
    this.api.setCustomValue(this.card()!.id, f.id, value).subscribe((c) => this.apply(c));
  }
  optionOf(f: CustomField) {
    return f.options.find((o) => o.id === this.fieldValue(f));
  }

  // ---------- checklist ----------
  addItem() {
    const content = this.newItem.trim();
    if (!content) return;
    this.newItem = '';
    this.api.addChecklistItem(this.card()!.id, content).subscribe((item) => {
      const c = this.card()!;
      this.apply({ ...c, checklist: [...c.checklist, item] });
    });
  }
  toggleItem(id: number) {
    const c = this.card()!;
    const item = c.checklist.find((i) => i.id === id)!;
    this.apply({
      ...c,
      checklist: c.checklist.map((i) => (i.id === id ? { ...i, done: !i.done } : i)),
    });
    this.api.updateChecklistItem(id, { done: !item.done }).subscribe();
  }
  deleteItem(id: number) {
    const c = this.card()!;
    this.apply({ ...c, checklist: c.checklist.filter((i) => i.id !== id) });
    this.api.deleteChecklistItem(id).subscribe();
  }

  // ---------- comments ----------
  openComposer(seed = '') {
    this.composerSeed.set(seed);
    // Re-create the editor so the seed (e.g. "@Name ") becomes its initial content
    this.composing.set(false);
    setTimeout(() => this.composing.set(true));
  }

  /** "Répondre": opens the composer with a mention of the comment author. */
  reply(author: { id: number; name: string }) {
    const name = author.name.replace(/[<>&"]/g, '');
    // Imported authors (Trello) have no account: plain text, otherwise a real mention
    this.openComposer(
      author.id
        ? `<p><span data-type="mention" data-id="${author.id}" data-label="${name}">@${name}</span>&nbsp;</p>`
        : `<p>@${name}&nbsp;</p>`,
    );
  }

  cancelComposer() {
    this.composing.set(false);
    this.composerSeed.set('');
  }

  toggleDetails() {
    const v = !this.showDetails();
    this.showDetails.set(v);
    try {
      localStorage.setItem('taskora.card.details', v ? '1' : '0');
    } catch {
      /* ignore */
    }
  }

  sendComment(editor: RichEditorComponent) {
    const content = editor.getHTML();
    if (!content) return;
    this.cancelComposer();
    this.api.addComment(this.card()!.id, content).subscribe(({ attachments, ...cm }) => {
      const c = this.card()!;
      // Files posted in the comment also appear in the card attachments
      this.apply({
        ...c,
        comments: [cm, ...c.comments],
        attachments: [...attachments, ...c.attachments],
      });
    });
  }
  mention(editor: RichEditorComponent, member: Member) {
    editor.insertMention(member);
  }
  deleteComment(id: number) {
    const c = this.card()!;
    this.apply({
      ...c,
      comments: c.comments.filter((x) => x.id !== id),
      attachments: c.attachments.filter((a) => a.commentId !== id),
    });
    this.api.deleteComment(id).subscribe();
  }

  // ---------- attachments ----------
  pickFiles(input: HTMLInputElement, e?: Event) {
    e?.stopPropagation();
    this.pop.set(null);
    input.click();
  }

  onFilesPicked(input: HTMLInputElement) {
    if (input.files?.length) this.uploads.upload(this.card()!.id, Array.from(input.files));
    input.value = '';
  }

  canEditAttachment(a: Attachment) {
    const me = this.auth.user()?.id;
    const role = this.board().role;
    return a.uploader?.id === me || role === 'owner' || role === 'admin';
  }

  renameAttachment(a: Attachment, name: string) {
    this.renaming.set(null);
    name = name.trim();
    if (!name || name === a.name) return;
    this.api.renameAttachment(a.id, name).subscribe((updated) => {
      const c = this.card()!;
      this.apply({ ...c, attachments: c.attachments.map((x) => (x.id === a.id ? updated : x)) });
    });
  }

  async deleteAttachment(a: Attachment) {
    const ok = await this.dialog.confirm(
      a.commentId
        ? {
            title: 'Retirer la pièce jointe ?',
            text: `« ${a.name} » ne sera plus listée dans les pièces jointes, mais reste visible dans son commentaire.`,
            confirmText: 'Retirer',
          }
        : {
            title: 'Supprimer la pièce jointe ?',
            text: `« ${a.name} » sera supprimée définitivement.`,
            confirmText: 'Supprimer',
          },
    );
    if (!ok) return;
    const c = this.card()!;
    this.apply({ ...c, attachments: c.attachments.filter((x) => x.id !== a.id) });
    this.api
      .deleteAttachment(a.id)
      .subscribe({ error: () => this.api.card(c.id).subscribe((fresh) => this.apply(fresh)) });
  }

  /** Scrolls the activity feed to the comment that holds this file. */
  showComment(commentId: number) {
    if (!this.card()?.comments.some((cm) => cm.id === commentId)) return;
    const el = document.getElementById('comment-' + commentId);
    el?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    el?.classList.add('flash');
    setTimeout(() => el?.classList.remove('flash'), 1600);
  }

  // Drag & drop of files from the computer anywhere on the card (editors keep their own drop: inline insertion)
  onDragEnter(e: DragEvent) {
    if (!draggedFiles(e)) return;
    this.dragDepth++;
    this.fileOver.set(!this.inEditor(e));
  }

  onDragOver(e: DragEvent) {
    if (!draggedFiles(e)) return;
    e.preventDefault();
    e.dataTransfer!.dropEffect = 'copy';
    const over = !this.inEditor(e);
    if (over !== this.fileOver()) this.fileOver.set(over);
  }

  onDragLeave(e: DragEvent) {
    if (!draggedFiles(e)) return;
    if (--this.dragDepth <= 0) {
      this.dragDepth = 0;
      this.fileOver.set(false);
    }
  }

  onDrop(e: DragEvent) {
    if (!draggedFiles(e)) return;
    this.dragDepth = 0;
    this.fileOver.set(false);
    if (e.defaultPrevented) return; // inserted by an editor
    e.preventDefault();
    const files = Array.from(e.dataTransfer!.files);
    if (files.length && this.card()) this.uploads.upload(this.card()!.id, files);
  }

  private inEditor(e: DragEvent) {
    return !!(e.target as HTMLElement | null)?.closest?.('.ProseMirror');
  }

  archive() {
    this.pop.set(null);
    this.api.archiveCard(this.card()!.id).subscribe((c) => {
      this.apply(c);
      this.archivedChange.emit(c);
      this.toast.success('Carte archivée : retrouvez-la dans Paramètres → Archives');
    });
  }

  restore() {
    this.pop.set(null);
    this.api.restoreCard(this.card()!.id).subscribe((c) => {
      this.apply(c);
      this.archivedChange.emit(c);
      this.toast.success(`Carte restaurée dans « ${c.listTitle} »`);
    });
  }

  async deleteCard() {
    this.pop.set(null);
    if (
      !(await this.dialog.confirm({
        title: 'Mettre la carte à la corbeille ?',
        text: `« ${this.card()!.title} » pourra être restaurée pendant 30 jours (Paramètres → Corbeille).`,
        confirmText: 'Mettre à la corbeille',
      }))
    )
      return;
    const id = this.card()!.id;
    this.api.deleteCard(id).subscribe(() => {
      this.toast.success('Carte mise à la corbeille');
      this.deleted.emit(id);
    });
  }

  @HostListener('document:keydown.escape', ['$event'])
  esc(e?: Event) {
    if (e?.defaultPrevented || this.dialog.isOpen()) return; // already handled (editor, "@" list, confirmation dialog…)
    if (this.viewing()) {
      this.viewing.set(null);
      return;
    } // Escape closes the viewer first
    // Escape inside an editor only leaves the editor
    const active = document.activeElement as HTMLElement | null;
    if (active?.closest('.ProseMirror')) {
      if (active.closest('.composer')) this.cancelComposer();
      else if (this.editingDesc()) this.editingDesc.set(false);
      else active.blur();
      return;
    }
    if (this.pop()) this.pop.set(null);
    else if (this.editingDesc()) this.editingDesc.set(false);
    else this.closed.emit();
  }

  /** "+ Ajouter" menu: jumps to the matching control of the details column. */
  addAction(kind: 'members' | 'labels' | 'due' | 'checklist' | 'fields' | 'attachment', e: Event) {
    e.stopPropagation();
    this.pop.set(null);
    if (kind === 'members' || kind === 'labels') {
      setTimeout(() => this.pop.set(kind));
      return;
    }
    if (kind === 'fields') {
      this.manageFields.emit();
      return;
    }
    if (kind === 'attachment') {
      (document.getElementById('card-attach-input') as HTMLInputElement | null)?.click();
      return;
    }
    const el = document.getElementById(
      kind === 'due' ? 'card-due' : 'card-checklist-add',
    ) as HTMLInputElement | null;
    el?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    setTimeout(() => {
      el?.focus();
      if (kind === 'due') el?.showPicker?.();
    }, 250);
  }

  fullDate(d: string) {
    return new Date(d).toLocaleString('fr-FR', { dateStyle: 'long', timeStyle: 'short' });
  }

  dueLabel(): string | null {
    return (
      ({ overdue: 'En retard', soon: 'Bientôt', done: 'Terminée' } as Record<string, string>)[
        this.due() ?? ''
      ] ?? null
    );
  }

  togglePop(p: PopKind, e: Event) {
    e.stopPropagation();
    this.pop.set(this.pop() === p ? null : p);
  }
}
