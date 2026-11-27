import {
  ChangeDetectionStrategy,
  Component,
  computed,
  HostListener,
  inject,
  input,
  linkedSignal,
  OnDestroy,
  OnInit,
  output,
  signal,
} from '@angular/core';

import { DialogService } from '../../core/dialog.service';
import {
  activeFilterCount,
  Board,
  CardFilters,
  DueFilter,
  EMPTY_FILTERS,
  User,
} from '../../core/models';
import { AvatarComponent } from '../../shared/avatar.component';
import { IconComponent } from '../../shared/icon.component';

@Component({
  selector: 'tk-filter-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [IconComponent, AvatarComponent],
  templateUrl: './filter-panel.component.html',
  styleUrl: './filter-panel.component.scss',
})
export class FilterPanelComponent implements OnInit, OnDestroy {
  private dialog = inject(DialogService);
  board = input.required<Board>();
  filters = input.required<CardFilters>();
  me = input<null | User>(null);
  filtersChange = output<CardFilters>();
  /** Local copy, always up to date even if several toggles happen before the parent re-renders. */
  current = linkedSignal(() => this.filters());
  closed = output<void>();

  showMemberPicker = signal(false);
  keyword = signal('');
  private keywordTimer: null | ReturnType<typeof setTimeout> = null;

  dueOptions: { icon: string; label: string; tone: string; value: DueFilter }[] = [
    { value: 'none', label: 'Aucune date', icon: 'calendar', tone: 'neutral' },
    { value: 'overdue', label: 'En retard', icon: 'clock', tone: 'red' },
    { value: 'day', label: 'Dû dans les 24 heures', icon: 'clock', tone: 'yellow' },
    { value: 'week', label: 'Dû la semaine prochaine', icon: 'clock', tone: 'neutral' },
    { value: 'month', label: 'Dû le mois prochain', icon: 'clock', tone: 'neutral' },
  ];

  count = computed(() => activeFilterCount(this.current()));
  others = computed(() => this.board().members.filter((m) => m.id !== this.me()?.id));
  selectedMembers = computed(() =>
    this.board().members.filter((m) => this.current().members.includes(m.id)),
  );
  /** Custom fields that can be filtered by value (select options / checkbox). */
  filterableFields = computed(() =>
    this.board().customFields.filter((f) => f.type === 'select' || f.type === 'checkbox'),
  );

  ngOnInit() {
    this.keyword.set(this.current().q);
  }

  ngOnDestroy() {
    if (this.keywordTimer) clearTimeout(this.keywordTimer);
  }

  private patch(p: Partial<CardFilters>) {
    const next = { ...this.current(), ...p };
    this.current.set(next);
    this.filtersChange.emit(next);
  }

  onKeyword(value: string) {
    this.keyword.set(value);
    if (this.keywordTimer) clearTimeout(this.keywordTimer);
    this.keywordTimer = setTimeout(() => this.patch({ q: value }), 300);
  }

  toggle(key: 'done' | 'mine' | 'noLabel' | 'noMember' | 'open') {
    this.patch({ [key]: !this.current()[key] });
  }

  toggleIn<K extends 'due' | 'labels' | 'members'>(key: K, value: CardFilters[K][number]) {
    const arr = this.current()[key] as unknown[];
    this.patch({
      [key]: arr.includes(value) ? arr.filter((v) => v !== value) : [...arr, value],
    } as Partial<CardFilters>);
  }

  /** Header checkbox of "Choisir les membres": clears the selection, or opens the picker. */
  membersHeader() {
    if (this.current().members.length) this.patch({ members: [] });
    else this.showMemberPicker.set(true);
  }

  hasCf(fieldId: number, value: string) {
    return this.current().cf[fieldId]?.includes(value) ?? false;
  }

  toggleCf(fieldId: number, value: string) {
    const current = this.current().cf[fieldId] ?? [];
    const next = current.includes(value) ? current.filter((v) => v !== value) : [...current, value];
    const cf = { ...this.current().cf };
    if (next.length) cf[fieldId] = next;
    else delete cf[fieldId];
    this.patch({ cf });
  }

  clear() {
    this.keyword.set('');
    this.current.set(EMPTY_FILTERS);
    this.filtersChange.emit(EMPTY_FILTERS);
  }

  @HostListener('document:keydown.escape')
  esc() {
    if (!this.dialog.isOpen()) this.closed.emit();
  }
}
