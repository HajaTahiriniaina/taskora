import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnDestroy,
  afterNextRender,
  computed,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { Editor } from '@tiptap/core';
import { Highlight } from '@tiptap/extension-highlight';
import { Image } from '@tiptap/extension-image';
import { Mention } from '@tiptap/extension-mention';
import { TaskItem, TaskList } from '@tiptap/extension-list';
import { TextAlign } from '@tiptap/extension-text-align';
import { Color, TextStyle } from '@tiptap/extension-text-style';
import { Placeholder } from '@tiptap/extensions';
import { StarterKit } from '@tiptap/starter-kit';
import { HttpEventType } from '@angular/common/http';
import { ApiService } from '../core/api.service';
import { MAX_ATTACHMENT } from '../core/attachment-uploads.service';
import { User } from '../core/models';
import { AvatarComponent } from './avatar.component';
import { ToastService } from '../core/toast.service';
import { IconComponent } from './icon.component';
import { toHtml } from './rich-text';

const TEXT_COLORS = [
  '#0f172a',
  '#64748b',
  '#dc2626',
  '#ea580c',
  '#ca8a04',
  '#16a34a',
  '#0d9488',
  '#2563eb',
  '#7c3aed',
  '#db2777',
];
const HIGHLIGHTS = [
  '#fef08a',
  '#bbf7d0',
  '#bae6fd',
  '#ddd6fe',
  '#fbcfe8',
  '#fed7aa',
  '#fecaca',
  '#e2e8f0',
];

/**
 * WYSIWYG editor (Tiptap / ProseMirror): headings, bold, italic, underline, strike, text color, highlight,
 * bullet / numbered / check lists, alignment, links, images (button, paste, drag & drop), quotes, code, divider.
 * Output is HTML (sanitized server side and again before display).
 */
@Component({
  selector: 'tk-rich-editor',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconComponent, AvatarComponent],
  templateUrl: './rich-editor.component.html',
  styleUrl: './rich-editor.component.scss',
})
export class RichEditorComponent implements OnDestroy {
  private api = inject(ApiService);
  private toast = inject(ToastService);

  value = input<string | null>('');
  placeholder = input('Écrivez ici…');
  /** Smaller toolbar (comments). */
  compact = input(false);
  autofocus = input(false);
  /** People offered after "@" (board members). */
  mentionables = input<User[]>([]);
  submitted = output<void>();
  cancelled = output<void>();

  private host = viewChild.required<ElementRef<HTMLElement>>('host');
  private frame = viewChild.required<ElementRef<HTMLElement>>('frame');
  /** "@" suggestion list: members matching what is typed after "@", position under the caret. */
  mention = signal<{ items: User[]; index: number; top: number; left: number } | null>(null);
  private mentionCommand: ((attrs: { id: string; label: string }) => void) | null = null;
  private fileInput = viewChild.required<ElementRef<HTMLInputElement>>('file');
  private anyFileInput = viewChild.required<ElementRef<HTMLInputElement>>('anyFile');
  editor: Editor | null = null;

  /** Bumped on every editor transaction so the toolbar re-reads its active states (OnPush). */
  private tick = signal(0);
  isEmpty = signal(true);
  /** Files being sent, with their progress (shown under the text). */
  fileUploads = signal<{ uid: number; name: string; progress: number; saving: boolean }[]>([]);
  uploading = computed(() => this.fileUploads().length);
  private uploadSeq = 0;
  pop = signal<'color' | 'highlight' | 'link' | 'heading' | null>(null);
  linkUrl = '';

  textColors = TEXT_COLORS;
  highlights = HIGHLIGHTS;

  headingLabel = computed(() => {
    this.tick();
    const e = this.editor;
    if (!e) return 'Texte';
    for (const level of [1, 2, 3] as const)
      if (e.isActive('heading', { level })) return `Titre ${level}`;
    return 'Texte';
  });

  constructor() {
    afterNextRender(() => {
      this.editor = new Editor({
        element: this.host().nativeElement,
        extensions: [
          StarterKit.configure({
            heading: { levels: [1, 2, 3] },
            link: {
              openOnClick: false,
              autolink: true,
              defaultProtocol: 'https',
              HTMLAttributes: { target: '_blank', rel: 'noopener noreferrer' },
            },
          }),
          TextStyle,
          Color,
          Highlight.configure({ multicolor: true }),
          TextAlign.configure({ types: ['heading', 'paragraph'] }),
          TaskList,
          TaskItem.configure({
            nested: true,
            a11y: {
              checkboxLabel: (node, checked) =>
                `Tâche ${checked ? 'terminée' : 'à faire'} : ${node.textContent || 'vide'}`,
            },
          }),
          Image.configure({ allowBase64: false }),
          Mention.configure({
            renderText: ({ node }) => `@${node.attrs['label'] ?? node.attrs['id']}`,
            renderHTML: ({ node }) => [
              'span',
              {
                'data-type': 'mention',
                'data-id': node.attrs['id'],
                'data-label': node.attrs['label'],
              },
              `@${node.attrs['label'] ?? node.attrs['id']}`,
            ],
            suggestion: {
              char: '@',
              items: ({ query }) => this.matchMembers(query),
              render: () => ({
                onStart: (props) => this.showMentions(props),
                onUpdate: (props) => this.showMentions(props),
                onKeyDown: ({ event }) => this.mentionKey(event),
                onExit: () => {
                  this.mention.set(null);
                  this.mentionCommand = null;
                },
              }),
            },
          }),
          Placeholder.configure({ placeholder: () => this.placeholder() }),
        ],
        content: toHtml(this.value()),
        autofocus: this.autofocus() ? 'end' : false,
        editorProps: {
          attributes: { class: 'rich rich-editable', role: 'textbox', 'aria-multiline': 'true' },
          handleKeyDown: (_view, event) => {
            if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) {
              this.submitted.emit();
              return true;
            }
            if (
              this.mention() &&
              ['Escape', 'Enter', 'Tab', 'ArrowUp', 'ArrowDown'].includes(event.key) &&
              !event.metaKey &&
              !event.ctrlKey
            )
              return false; // handled by the "@" list
            if (event.key === 'Escape') {
              this.cancelled.emit();
              return true;
            }
            return false;
          },
          handlePaste: (_view, event) => this.handleFiles(event.clipboardData?.files),
          handleDrop: (view, event) => {
            const pos = view.posAtCoords({ left: event.clientX, top: event.clientY })?.pos;
            return this.handleFiles((event as DragEvent).dataTransfer?.files, pos);
          },
        },
        onTransaction: () => this.tick.update((t) => t + 1),
        onUpdate: ({ editor }) => this.isEmpty.set(editor.isEmpty),
      });
      this.isEmpty.set(this.editor.isEmpty);
    });
  }

  ngOnDestroy() {
    this.editor?.destroy();
  }

  // ---------- public API for parents ----------
  getHTML(): string {
    return !this.editor || this.editor.isEmpty ? '' : this.editor.getHTML();
  }

  clear() {
    this.editor?.commands.clearContent(true);
  }

  focus() {
    this.editor?.commands.focus('end');
  }

  insertText(text: string) {
    this.editor?.chain().focus().insertContent(text).run();
  }

  /** Inserts a mention chip ("@Name") followed by a space. */
  insertMention(user: User) {
    this.editor
      ?.chain()
      .focus()
      .insertContent([
        { type: 'mention', attrs: { id: String(user.id), label: user.name } },
        { type: 'text', text: ' ' },
      ])
      .run();
  }

  // ---------- "@" mentions ----------
  private matchMembers(query: string): User[] {
    const q = norm(query);
    return this.mentionables()
      .filter(
        (u) =>
          !q ||
          norm(u.name)
            .split(/\s+/)
            .some((w) => w.startsWith(q)) ||
          norm(u.name).startsWith(q) ||
          norm(u.email).startsWith(q),
      )
      .slice(0, 8);
  }

  private showMentions(props: {
    items: User[];
    command: (a: { id: string; label: string }) => void;
    clientRect?: (() => DOMRect | null) | null;
  }) {
    this.mentionCommand = props.command;
    const rect = props.clientRect?.();
    if (!props.items.length || !rect) {
      this.mention.set(null);
      return;
    }
    const box = this.frame().nativeElement.getBoundingClientRect();
    const prev = this.mention();
    this.mention.set({
      items: props.items,
      index: prev ? Math.min(prev.index, props.items.length - 1) : 0,
      top: rect.bottom - box.top + 6,
      left: Math.max(0, Math.min(rect.left - box.left, box.width - 260)),
    });
  }

  private mentionKey(event: KeyboardEvent): boolean {
    const m = this.mention();
    if (!m) return false;
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      const n = m.items.length;
      this.mention.set({ ...m, index: (m.index + (event.key === 'ArrowDown' ? 1 : n - 1)) % n });
      return true;
    }
    if (event.key === 'Enter' || event.key === 'Tab') {
      this.pickMention(m.items[m.index]);
      return true;
    }
    if (event.key === 'Escape') {
      this.mention.set(null);
      return true;
    }
    return false;
  }

  pickMention(user: User) {
    this.mentionCommand?.({ id: String(user.id), label: user.name });
    this.mention.set(null);
  }

  hoverMention(index: number) {
    const m = this.mention();
    if (m && m.index !== index) this.mention.set({ ...m, index });
  }

  // ---------- toolbar ----------
  active(name: string, attrs?: Record<string, unknown>): boolean {
    this.tick();
    return !!this.editor?.isActive(name, attrs);
  }

  activeAlign(align: string): boolean {
    this.tick();
    return !!this.editor?.isActive({ textAlign: align });
  }

  can(action: 'undo' | 'redo'): boolean {
    this.tick();
    return !!this.editor?.can()[action]();
  }

  currentColor(): string | null {
    this.tick();
    return (this.editor?.getAttributes('textStyle')['color'] as string) ?? null;
  }

  run(fn: (c: ReturnType<Editor['chain']>) => ReturnType<Editor['chain']>) {
    if (!this.editor) return;
    fn(this.editor.chain().focus()).run();
    this.pop.set(null);
  }

  setHeading(level: 0 | 1 | 2 | 3) {
    this.run((c) => (level ? c.setHeading({ level }) : c.setParagraph()));
  }

  setColor(color: string | null) {
    this.run((c) => (color ? c.setColor(color) : c.unsetColor()));
  }

  setHighlight(color: string | null) {
    this.run((c) => (color ? c.setHighlight({ color }) : c.unsetHighlight()));
  }

  togglePop(p: 'color' | 'highlight' | 'link' | 'heading', e: Event) {
    e.preventDefault();
    e.stopPropagation();
    if (p === 'link') this.linkUrl = (this.editor?.getAttributes('link')['href'] as string) ?? '';
    this.pop.set(this.pop() === p ? null : p);
  }

  applyLink() {
    const url = this.linkUrl.trim();
    if (!url) {
      this.run((c) => c.extendMarkRange('link').unsetLink());
      return;
    }
    const href = /^(https?:|mailto:)/i.test(url) ? url : `https://${url}`;
    const { empty } = this.editor!.state.selection;
    this.run((c) =>
      empty
        ? c.insertContent({ type: 'text', text: url, marks: [{ type: 'link', attrs: { href } }] })
        : c.extendMarkRange('link').setLink({ href }),
    );
  }

  pickImage() {
    this.fileInput().nativeElement.click();
  }

  pickAny() {
    this.anyFileInput().nativeElement.click();
  }

  onFile(input: HTMLInputElement) {
    this.handleFiles(input.files);
    input.value = '';
  }

  /**
   * Uploads files (button, paste or drop) then inserts them: images are shown inline,
   * other files become a "📎 name" download link. In a comment, they are also listed
   * with the card attachments. Returns true when handled.
   */
  private handleFiles(files: FileList | null | undefined, pos?: number): boolean {
    const list = Array.from(files ?? []);
    if (!list.length) return false;
    for (const file of list) {
      if (file.size > MAX_ATTACHMENT) {
        this.toast.show(`« ${file.name} » dépasse 100 Mo.`, 'error');
        continue;
      }
      const uid = ++this.uploadSeq;
      this.fileUploads.update((u) => [...u, { uid, name: file.name, progress: 0, saving: false }]);
      const done = () => this.fileUploads.update((u) => u.filter((x) => x.uid !== uid));
      this.api.uploadFileEvents(file).subscribe({
        next: (e) => {
          if (e.type === HttpEventType.UploadProgress) {
            const progress = Math.min(
              100,
              Math.floor((e.loaded / ((e.total ?? file.size) || 1)) * 100),
            );
            this.fileUploads.update((u) =>
              u.map((x) => (x.uid === uid ? { ...x, progress, saving: progress >= 100 } : x)),
            );
          } else if (e.type === HttpEventType.Response && e.body) {
            done();
            const { url, image } = e.body;
            const node = image
              ? { type: 'image', attrs: { src: url, alt: file.name } }
              : {
                  type: 'paragraph',
                  content: [
                    {
                      type: 'text',
                      text: `📎 ${file.name}`,
                      marks: [{ type: 'link', attrs: { href: url } }],
                    },
                  ],
                };
            const chain = this.editor!.chain().focus();
            (pos !== undefined
              ? chain.insertContentAt(pos, node)
              : chain.insertContent(node)
            ).run();
          }
        },
        error: () => done(), // error toast already shown by the interceptor
      });
    }
    return true;
  }
}

/** Lower case without accents, for "@élo" → "Éloïse". */
function norm(s: string): string {
  return s
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase();
}
