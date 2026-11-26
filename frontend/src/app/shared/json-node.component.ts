import { ChangeDetectionStrategy, Component, computed, input, linkedSignal } from '@angular/core';

const MAX_CHILDREN = 300;

/** One node of the JSON viewer: collapsible objects / arrays, colored scalars (text only, no innerHTML). */
@Component({
  selector: 'tk-json-node',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [JsonNodeComponent],
  template: `
    <div class="row">
      @if (isContainer()) {
        <button class="tog" (click)="open.set(!open())" [attr.aria-expanded]="open()" [attr.aria-label]="open() ? 'Replier' : 'Déplier'">{{ open() ? '▾' : '▸' }}</button>
      } @else { <span class="tog-space"></span> }
      @if (name() !== null) { <span class="k">{{ name() }}</span><span class="p">: </span> }
      @if (isContainer()) {
        <span class="p">{{ isArray() ? '[' : '{' }}</span>
        @if (!open()) {
          <button class="sum" (click)="open.set(true)">{{ count() }} {{ isArray() ? (count() > 1 ? 'éléments' : 'élément') : (count() > 1 ? 'clés' : 'clé') }}</button><span class="p">{{ isArray() ? ']' : '}' }}</span>
        }
      } @else {
        <span [class]="'v ' + type()">{{ display() }}</span>
      }
    </div>
    @if (isContainer() && open()) {
      <div class="kids">
        @for (e of entries(); track e[0]) { <tk-json-node [name]="isArray() ? null : e[0]" [value]="e[1]" [depth]="depth() + 1" /> }
        @if (count() > entries().length) { <div class="more">… {{ count() - entries().length }} de plus</div> }
      </div>
      <div class="row"><span class="tog-space"></span><span class="p">{{ isArray() ? ']' : '}' }}</span></div>
    }
  `,
  styles: `
    :host { display: block; font: 13px/1.6 ui-monospace, SFMono-Regular, Menlo, monospace; }
    .row { display: flex; align-items: baseline; white-space: pre-wrap; word-break: break-word; }
    .tog, .tog-space { width: 16px; flex-shrink: 0; }
    .tog { border: 0; background: none; padding: 0; color: var(--text-3); cursor: pointer; font: inherit; }
    .kids { padding-left: 18px; border-left: 1px dashed var(--border); margin-left: 7px; }
    .k { color: #7c3aed; } .p { color: var(--text-3); }
    .v.string { color: #059669; } .v.number { color: #2563eb; } .v.boolean { color: #d97706; } .v.null { color: var(--text-3); font-style: italic; }
    .sum { border: 0; background: var(--surface-3); color: var(--text-2); border-radius: 6px; padding: 0 6px; margin: 0 4px; font: 12px var(--font, sans-serif); cursor: pointer; }
    .more { color: var(--text-3); font-style: italic; padding-left: 16px; }
  `,
})
export class JsonNodeComponent {
  name = input<string | null>(null);
  value = input<unknown>(null);
  depth = input(0);

  isArray = computed(() => Array.isArray(this.value()));
  isContainer = computed(() => this.value() !== null && typeof this.value() === 'object');
  count = computed(() => (this.isContainer() ? Object.keys(this.value() as object).length : 0));
  /** First two levels open, and any small object. */
  open = linkedSignal(() => this.depth() < 2 || this.count() <= 5);
  entries = computed(() => (this.isContainer() ? Object.entries(this.value() as object).slice(0, MAX_CHILDREN) : []));
  type = computed(() => (this.value() === null ? 'null' : typeof this.value()));
  display = computed(() => {
    const v = this.value();
    return typeof v === 'string' ? JSON.stringify(v) : String(v);
  });
}
