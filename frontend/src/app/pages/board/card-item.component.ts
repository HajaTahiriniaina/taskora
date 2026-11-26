import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';

import { draggedFiles } from '../../core/attachment-uploads.service';
import { Card, CustomField, Label, PRIORITIES } from '../../core/models';
import { AvatarComponent } from '../../shared/avatar.component';
import { IconComponent } from '../../shared/icon.component';
import { dueState, formatDue, formatFieldValue } from './card-format';

/**
 * Kanban card preview. OnPush + signal inputs: a card only re-renders when its own
 * object (or the label/field definitions) change — essential with thousands of cards.
 */
@Component({
  selector: 'tk-card-item',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconComponent, AvatarComponent],
  host: {
    tabindex: '0',
    '[class.done]': 'card().completed',
    '(keydown.enter)': 'opened.emit()',
    '(click)': 'opened.emit()',
    // Files dragged from the computer onto the card become attachments
    '[class.file-over]': 'fileOver()',
    '(dragenter)': 'onDragEnter($event)',
    '(dragover)': 'onDragOver($event)',
    '(dragleave)': 'onDragLeave($event)',
    '(drop)': 'onDrop($event)',
  },
  template: `
    @let c = card();
    @if (c.coverImage) {
      <div class="cover-img">
        <img [src]="c.coverImage" alt="" loading="lazy" decoding="async" draggable="false" />
      </div>
    } @else if (c.cover) {
      <div class="cover" [style.background]="c.cover"></div>
    }
    <div class="card-body">
      @if (labels().length) {
        <div class="labels">
          @for (l of labels(); track l.id) {
            <span class="label" [style.--c]="l.color">{{ l.name }}</span>
          }
        </div>
      }
      <div class="title-row">
        <button
          class="check"
          [class.on]="c.completed"
          (click)="$event.stopPropagation(); toggled.emit()"
          [attr.aria-label]="c.completed ? 'Marquer comme non terminée' : 'Marquer comme terminée'"
        >
          <tk-icon name="check" [size]="11" [stroke]="3.5" />
        </button>
        <h3>
          @if (showNumber()) {
            <span class="num">#{{ c.number }}</span>
          }
          {{ c.title }}
        </h3>
      </div>
      @if (fieldChips().length) {
        <div class="cf">
          @for (v of fieldChips(); track v.id) {
            <span
              class="cf-chip"
              [style.--c]="v.color ?? null"
              [class.colored]="!!v.color"
              [title]="v.name"
              ><b>{{ v.name }}</b> {{ v.text }}</span
            >
          }
        </div>
      }
      @if (hasMeta()) {
        <div class="meta">
          @if (priority(); as p) {
            <span class="m" [style.color]="p.color" [title]="'Priorité ' + p.label"
              ><tk-icon name="flag" [size]="13" [filled]="true"
            /></span>
          }
          @if (c.dueDate) {
            <span class="m due" [class]="'m due ' + due()"
              ><tk-icon name="clock" [size]="13" /> {{ dueText() }}</span
            >
          }
          @if (c.description) {
            <span class="m" title="Description"><tk-icon name="text" [size]="13" /></span>
          }
          @if (c.attachmentCount) {
            <span class="m" title="Pièces jointes"
              ><tk-icon name="paperclip" [size]="13" /> {{ c.attachmentCount }}</span
            >
          }
          @if (c.commentCount) {
            <span class="m"><tk-icon name="message" [size]="13" /> {{ c.commentCount }}</span>
          }
          @if (c.checklistTotal) {
            <span class="m" [class.all]="c.checklistDone === c.checklistTotal"
              ><tk-icon name="checkSquare" [size]="13" /> {{ c.checklistDone }}/{{
                c.checklistTotal
              }}</span
            >
          }
          <div class="assignees avatar-stack">
            @for (a of c.assignees.slice(0, 3); track a.id) {
              <tk-avatar [user]="a" [size]="24" />
            }
          </div>
        </div>
      }
    </div>
    @if (uploading() !== null) {
      <div class="upload-label">
        <tk-icon name="upload" [size]="12" />
        {{ uploading()! >= 100 ? 'Enregistrement…' : 'Envoi ' + uploading() + ' %' }}
      </div>
      <div
        class="upload-bar"
        role="progressbar"
        [attr.aria-valuenow]="uploading()"
        aria-valuemin="0"
        aria-valuemax="100"
        aria-label="Envoi des pièces jointes"
      >
        <span [style.width.%]="uploading()"></span>
      </div>
    }
    @if (fileOver()) {
      <div class="drop-hint"><tk-icon name="paperclip" [size]="16" /> Déposer pour joindre</div>
    }
  `,
  styleUrl: './card-item.component.scss',
})
export class CardItemComponent {
  card = input.required<Card>();
  labelMap = input.required<Map<number, Label>>();
  fields = input.required<CustomField[]>();
  showNumber = input(true);
  opened = output<void>();
  toggled = output<void>();
  /** Progress (0-100) of attachments being uploaded to this card, null when idle. */
  uploading = input<null | number>(null);
  filesDropped = output<File[]>();

  fileOver = signal(false);
  private depth = 0;

  onDragEnter(e: DragEvent) {
    if (!draggedFiles(e)) return;
    e.preventDefault();
    this.depth++;
    this.fileOver.set(true);
  }
  onDragOver(e: DragEvent) {
    if (!draggedFiles(e)) return;
    e.preventDefault();
    e.dataTransfer!.dropEffect = 'copy';
  }
  onDragLeave(e: DragEvent) {
    if (!draggedFiles(e)) return;
    if (--this.depth <= 0) {
      this.depth = 0;
      this.fileOver.set(false);
    }
  }
  onDrop(e: DragEvent) {
    if (!draggedFiles(e)) return;
    e.preventDefault();
    e.stopPropagation();
    this.depth = 0;
    this.fileOver.set(false);
    const files = Array.from(e.dataTransfer!.files);
    if (files.length) this.filesDropped.emit(files);
  }

  labels = computed(() =>
    this.card()
      .labelIds.map((id) => this.labelMap().get(id))
      .filter((l): l is Label => !!l),
  );
  fieldChips = computed(() =>
    this.fields().flatMap((f) => {
      const v = formatFieldValue(f, this.card().customValues[f.id]);
      return v ? [{ id: f.id, name: f.name, ...v }] : [];
    }),
  );
  priority = computed(() =>
    this.card().priority === 'none'
      ? null
      : (PRIORITIES.find((p) => p.value === this.card().priority) ?? null),
  );
  due = computed(() => dueState(this.card()));
  dueText = computed(() => (this.card().dueDate ? formatDue(this.card().dueDate!) : ''));
  hasMeta = computed(() => {
    const c = this.card();
    return !!(
      this.priority() ||
      c.dueDate ||
      c.description ||
      c.commentCount ||
      c.attachmentCount ||
      c.checklistTotal ||
      c.assignees.length
    );
  });
}
