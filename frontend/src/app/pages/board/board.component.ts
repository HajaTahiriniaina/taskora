import {
  CdkDrag,
  CdkDragDrop,
  CdkDragHandle,
  CdkDropList,
  moveItemInArray,
  transferArrayItem,
} from '@angular/cdk/drag-drop';
import {
  Component,
  computed,
  effect,
  ElementRef,
  HostListener,
  inject,
  input,
  OnDestroy,
  OnInit,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { forkJoin, map, of } from 'rxjs';

import { ApiService } from '../../core/api.service';
import {
  AttachmentUploadsService,
  draggedFiles,
  formatSize,
} from '../../core/attachment-uploads.service';
import { AuthService } from '../../core/auth.service';
import { DialogService } from '../../core/dialog.service';
import {
  activeFilterCount,
  Board,
  BoardList,
  Card,
  CardDetail,
  CardFilters,
  CardPage,
  EMPTY_FILTERS,
  PALETTE,
} from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { ToastService } from '../../core/toast.service';
import { TrelloTransferService } from '../../core/trello-transfer.service';
import { AvatarComponent } from '../../shared/avatar.component';
import { IconComponent } from '../../shared/icon.component';
import { InViewDirective } from '../../shared/in-view.directive';
import { TrelloAccessComponent } from '../../shared/trello-access.component';
import { BoardSettingsComponent } from './board-settings.component';
import { CardItemComponent } from './card-item.component';
import { CardModalComponent } from './card-modal.component';
import { FilterPanelComponent } from './filter-panel.component';

/** Cards fetched per request (first page of a column and each "infinite scroll" page). */
const PAGE = 30;
/** Max cards re-fetched per column on a silent refresh (= API max limit). */
const REFRESH_MAX = 200;
/** Columns rendered on each side of the viewport (prefetch). */
const BUFFER = 3;
/** Columns kept in memory beyond the rendered window; farther ones are evicted. */
const KEEP = 15;
/** Max lists per batch request (API limit). */
const BATCH = 20;
/** Gap between columns (must match .list margin-right). */
const GAP = 14;

interface Range {
  start: number;
  end: number;
}

interface ListState {
  loaded: boolean;
  loading: boolean;
  hasMore: boolean;
  /** Total matching the current filters. */
  total: number;
}

@Component({
  selector: 'tk-board',
  imports: [
    FormsModule,
    RouterLink,
    CdkDropList,
    CdkDrag,
    CdkDragHandle,
    IconComponent,
    AvatarComponent,
    CardModalComponent,
    BoardSettingsComponent,
    CardItemComponent,
    InViewDirective,
    FilterPanelComponent,
    TrelloAccessComponent,
  ],
  templateUrl: './board.component.html',
  styleUrl: './board.component.scss',
})
export class BoardComponent implements OnInit, OnDestroy {
  private api = inject(ApiService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private toast = inject(ToastService);
  private dialog = inject(DialogService);
  private notifs = inject(NotificationService);
  uploads = inject(AttachmentUploadsService);
  trello = inject(TrelloTransferService);
  /** Trello import: files still to copy on this board (banner for owners / admins). */
  trelloBanner = computed(() => {
    const b = this.board();
    const t = this.trello.state();
    if (!b || !t || t.boardId !== b.id || !(b.role === 'owner' || b.role === 'admin')) return null;
    return t.running || t.pending || t.failed ? t : null;
  });
  trelloPct = computed(() => {
    const t = this.trelloBanner();
    return t && t.total ? Math.floor(((t.done + t.failed) / t.total) * 100) : 0;
  });
  showTrelloAccess = signal(false);
  trelloAccess = viewChild(TrelloAccessComponent);
  formatSize = formatSize;
  auth = inject(AuthService);

  id = input.required<string>();

  board = signal<Board | null>(null);
  /** Lazy-loading state per list id. */
  states = signal<Record<number, ListState>>({});
  /** Bumped to re-arm the viewport observers (board reload, filter change). */
  epoch = signal(0);

  openCardId = signal<null | number>(null);
  settingsTab = signal<
    'archived' | 'background' | 'fields' | 'labels' | 'members' | 'trash' | null
  >(null);

  // filters (applied server side, see FilterPanelComponent)
  filters = signal<CardFilters>(EMPTY_FILTERS);
  showFilters = signal(false);

  // inline editors
  editingTitle = signal(false);
  addingCardTo = signal<null | number>(null);
  newCardTitle = '';
  addingList = signal(false);
  newListTitle = '';
  editingListId = signal<null | number>(null);
  listMenu = signal<null | number>(null);

  palette = PALETTE;
  skeletons = [0, 1, 2];

  /**
   * Horizontal virtualization: only columns in [start, end) exist in the DOM,
   * spacers keep the full scroll width. Fixed-width columns make it O(1).
   */
  range = signal<Range>(
    { start: 0, end: 8 },
    { equal: (a, b) => a.start === b.start && a.end === b.end },
  );
  colWidth = signal(306);
  /** Columns actually visible (1-based, for the header indicator). */
  visible = signal<Range>(
    { start: 1, end: 1 },
    { equal: (a, b) => a.start === b.start && a.end === b.end },
  );
  showJump = signal(false);
  jumpQuery = signal('');
  movingListId = signal<null | number>(null);
  private canvasRef = viewChild<ElementRef<HTMLElement>>('canvas');
  private resizeObs: null | ResizeObserver = null;
  private scrollRaf = 0;

  private dragging = false;
  private refreshTimer: null | ReturnType<typeof setInterval> = null;
  private queue = new Set<number>();
  private queueTimer: null | ReturnType<typeof setTimeout> = null;

  lists = computed(() => this.board()?.lists ?? []);
  listCount = computed(() => this.lists().length);
  windowLists = computed(() => this.lists().slice(this.range().start, this.range().end));
  leftSpace = computed(() => this.range().start * this.colWidth());
  rightSpace = computed(() => Math.max(0, this.listCount() - this.range().end) * this.colWidth());
  /** Only rendered columns are connected for drag & drop (CDK measures every connected list). */
  listIds = computed(() => this.windowLists().map((l) => 'list-' + l.id));
  jumpResults = computed(() => {
    const q = this.jumpQuery().toLowerCase().trim();
    return this.lists()
      .map((l, i) => ({ l, i }))
      .filter((x) => !q || x.l.title.toLowerCase().includes(q))
      .slice(0, 100);
  });
  labelMap = computed(() => new Map((this.board()?.labels ?? []).map((l) => [l.id, l])));
  fieldsOnCard = computed(() => (this.board()?.customFields ?? []).filter((f) => f.showOnCard));
  filterCount = computed(() => activeFilterCount(this.filters()));
  filtering = computed(() => this.filterCount() > 0);
  totalCards = computed(() => (this.board()?.lists ?? []).reduce((s, l) => s + l.cardCount, 0));

  constructor() {
    effect(() => {
      const id = Number(this.id());
      untracked(() => this.load(id));
    });
    // Filters changed → drop loaded cards; observers reload the visible columns.
    let first = true;
    effect(() => {
      this.filters();
      if (first) {
        first = false;
        return;
      }
      untracked(() => this.resetCards());
    });
    effect(() => {
      if (this.notifs.tick() > 0) untracked(() => this.refresh());
    });
    // Canvas available → wire scroll/resize listeners outside the template (no change detection per scroll event)
    effect((onCleanup) => {
      const el = this.canvasRef()?.nativeElement;
      if (!el) return;
      const onScroll = () => {
        if (this.scrollRaf) return;
        this.scrollRaf = requestAnimationFrame(() => {
          this.scrollRaf = 0;
          this.updateRange();
        });
      };
      el.addEventListener('scroll', onScroll, { passive: true });
      // Touchpad / Shift+wheel horizontal scrolling anywhere on the board (header, filters, columns, cards)
      const root = el.closest<HTMLElement>('.board') ?? el;
      const onWheel = (e: WheelEvent) => {
        const horizontal =
          Math.abs(e.deltaX) > Math.abs(e.deltaY) ? e.deltaX : e.shiftKey ? e.deltaY : 0;
        if (!horizontal || e.ctrlKey) return;
        const t = e.target as HTMLElement;
        if (t.closest('.popover, .filter-panel, input, textarea, select')) return;
        if (e.target !== el) e.preventDefault();
        if (e.target !== el) el.scrollLeft += horizontal * (e.deltaMode === 1 ? 32 : 1);
      };
      root.addEventListener('wheel', onWheel, { passive: false });
      this.resizeObs = new ResizeObserver(() => {
        this.measure();
        this.updateRange();
      });
      this.resizeObs.observe(el);
      untracked(() => {
        this.measure();
        this.updateRange();
      });
      onCleanup(() => {
        el.removeEventListener('scroll', onScroll);
        root.removeEventListener('wheel', onWheel);
        this.resizeObs?.disconnect();
      });
    });
    // Window moved / lists changed / filters reset → load rendered columns, evict far ones
    effect(() => {
      const r = this.range();
      this.epoch();
      this.listCount();
      untracked(() => {
        this.updateRange();
        this.windowLists().forEach((l) => this.onListVisible(l));
        this.evictFar(r);
      });
    });
  }

  // ======================= Virtualization =======================

  private measure() {
    const first = this.canvasRef()?.nativeElement.querySelector<HTMLElement>('section.list');
    if (first?.offsetWidth) this.colWidth.set(first.offsetWidth + GAP);
  }

  private updateRange() {
    const el = this.canvasRef()?.nativeElement;
    if (!el || this.dragging) return; // frozen while dragging: CDK must keep the same items
    const col = this.colWidth();
    const left = Math.max(0, el.scrollLeft - 20);
    const n = this.listCount();
    const start = Math.min(n, Math.max(0, Math.floor(left / col) - BUFFER));
    const end = Math.min(n, Math.ceil((left + el.clientWidth) / col) + BUFFER);
    this.range.set({ start, end: Math.max(start, end) });
    this.visible.set({
      start: Math.min(n, Math.floor((el.scrollLeft + col / 2) / col) + 1),
      end: Math.min(n, Math.floor((left + el.clientWidth - 40) / col) + 1),
    });
  }

  /** Free memory and refresh traffic: drop the cards of columns far from the window. */
  private evictFar(r: Range) {
    const lists = this.lists();
    const far = new Set(
      lists
        .filter(
          (l, i) =>
            (i < r.start - KEEP || i >= r.end + KEEP) && (l.cards.length || this.state(l.id)),
        )
        .map((l) => l.id),
    );
    if (!far.size) return;
    this.states.update((all) => {
      const next = { ...all };
      far.forEach((id) => delete next[id]);
      return next;
    });
    this.board.update(
      (b) => b && { ...b, lists: b.lists.map((l) => (far.has(l.id) ? { ...l, cards: [] } : l)) },
    );
  }

  windowIndex(list: BoardList) {
    return this.lists().findIndex((l) => l.id === list.id);
  }

  private isRendered(listId: number) {
    return this.windowLists().some((l) => l.id === listId);
  }

  jumpTo(index: number) {
    const el = this.canvasRef()?.nativeElement;
    if (!el) return;
    this.showJump.set(false);
    this.jumpQuery.set('');
    const target = index * this.colWidth();
    // Smooth only for short distances: a long smooth scroll would walk through (and load) every column
    el.scrollTo({
      left: target,
      behavior: Math.abs(target - el.scrollLeft) < el.clientWidth * 2 ? 'smooth' : 'auto',
    });
  }

  moveListTo(list: BoardList, position: number) {
    this.movingListId.set(null);
    this.listMenu.set(null);
    const b = this.board()!;
    const from = b.lists.findIndex((l) => l.id === list.id);
    const to = Math.min(b.lists.length - 1, Math.max(0, position - 1));
    if (from < 0 || from === to) return;
    const lists = [...b.lists];
    moveItemInArray(lists, from, to);
    this.board.set({ ...b, lists });
    this.api
      .reorderLists(
        b.id,
        lists.map((l) => l.id),
      )
      .subscribe(() => this.toast.success(`Colonne déplacée en position ${to + 1}`));
    this.jumpTo(to);
  }

  ngOnInit() {
    // A file dropped on a card: count it on the preview (the open modal syncs its own card)
    // Files copied from Trello: refresh the visible cards (attachment counters, covers)
    this.trelloSub = this.trello.progressed.subscribe((boardId) => {
      if (boardId === this.board()?.id) this.refresh();
    });
    this.uploadSub = this.uploads.added.subscribe(({ cardId, attachment }) => {
      this.toast.success(`« ${attachment.name} » joint à la carte`);
      if (cardId !== this.openCardId())
        this.patchCard(cardId, (c) => ({ ...c, attachmentCount: c.attachmentCount + 1 }));
    });
    this.route.queryParamMap.subscribe((q) => {
      const c = q.get('card');
      this.openCardId.set(c ? Number(c) : null);
    });
    this.refreshTimer = setInterval(() => this.refresh(), 30000);
  }

  private uploadSub?: { unsubscribe(): void };
  private trelloSub?: { unsubscribe(): void };

  ngOnDestroy() {
    this.uploadSub?.unsubscribe();
    this.trelloSub?.unsubscribe();
    if (this.scrollRaf) cancelAnimationFrame(this.scrollRaf);
    if (this.refreshTimer) clearInterval(this.refreshTimer);
    if (this.queueTimer) clearTimeout(this.queueTimer);
  }

  // ======================= Loading =======================

  /** Loads the board "shell" (lists + counters, no cards). Cards come lazily. */
  load(id: number) {
    this.board.set(null);
    this.api.board(id).subscribe({
      next: (b) => {
        this.states.set({});
        this.board.set({ ...b, lists: b.lists.map((l) => ({ ...l, cards: [] })) });
        this.epoch.update((e) => e + 1);
        if (b.trelloFiles || this.trello.state()?.boardId === b.id) this.trello.refresh(b.id);
      },
      error: () => this.router.navigateByUrl('/boards'),
    });
  }

  state(listId: number): ListState | undefined {
    return this.states()[listId];
  }

  /** A column entered (or is near) the viewport: queue its first page. */
  onListVisible(list: BoardList) {
    const s = this.state(list.id);
    if (s?.loaded || s?.loading) return;
    this.setState(list.id, { loaded: false, loading: true, hasMore: false, total: list.cardCount });
    this.queue.add(list.id);
    // Wait for the scroll to settle a little: columns only flown over are not fetched
    if (this.queueTimer) clearTimeout(this.queueTimer);
    this.queueTimer = setTimeout(() => this.flushQueue(), 90);
  }

  private flushQueue() {
    this.queueTimer = null;
    const b = this.board();
    const queued = [...this.queue];
    this.queue.clear();
    if (!b || !queued.length) return;
    // Columns that already left the window go back to "not loaded"
    const ids = queued.filter((id) => this.isRendered(id));
    queued.filter((id) => !ids.includes(id)).forEach((id) => this.setState(id, undefined));
    const epoch = this.epoch();
    for (let i = 0; i < ids.length; i += BATCH) {
      const chunk = ids.slice(i, i + BATCH);
      this.api.boardCards(b.id, chunk, PAGE, this.filters()).subscribe({
        next: (pages) => {
          if (epoch !== this.epoch()) return; // filters changed meanwhile
          for (const id of chunk) this.applyPage(id, pages[id], false);
        },
        error: () => chunk.forEach((id) => this.setState(id, undefined)),
      });
    }
  }

  /** Infinite scroll inside a column. */
  loadMore(list: BoardList) {
    const s = this.state(list.id);
    if (!s?.loaded || s.loading || !s.hasMore) return;
    this.setState(list.id, { ...s, loading: true });
    const epoch = this.epoch();
    this.api.listCards(list.id, list.cards.length, PAGE, this.filters()).subscribe({
      next: (page) => {
        if (epoch === this.epoch()) this.applyPage(list.id, page, true);
      },
      error: () => this.setState(list.id, { ...s, loading: false }),
    });
  }

  private applyPage(listId: number, page: CardPage | undefined, append: boolean) {
    if (!page) {
      this.setState(listId, undefined);
      return;
    }
    this.patchList(listId, (l) => {
      const known = new Set(append ? l.cards.map((c) => c.id) : []);
      const cards = append
        ? [...l.cards, ...page.items.filter((c) => !known.has(c.id))]
        : page.items;
      return { ...l, cards };
    });
    this.setState(listId, {
      loaded: true,
      loading: false,
      hasMore: page.hasMore,
      total: page.total,
    });
  }

  private resetCards() {
    this.queue.clear();
    this.states.set({});
    this.board.update((b) => b && { ...b, lists: b.lists.map((l) => ({ ...l, cards: [] })) });
    this.epoch.update((e) => e + 1);
  }

  /**
   * Silent refresh (polling / notifications): reload the shell, then re-fetch only the
   * columns already loaded, keeping the number of cards the user has scrolled through.
   */
  refresh() {
    const b = this.board();
    if (!b || this.dragging || this.addingCardTo() || this.editingListId() || document.hidden)
      return;
    const loaded = b.lists
      .filter((l) => this.state(l.id)?.loaded && l.cards.length <= REFRESH_MAX)
      .slice(0, 60);
    // Each column keeps its own depth: group columns by page-rounded size (one request per group)
    const groups = new Map<number, number[]>();
    for (const l of loaded) {
      const limit = Math.min(REFRESH_MAX, Math.max(PAGE, Math.ceil(l.cards.length / PAGE) * PAGE));
      groups.set(limit, [...(groups.get(limit) ?? []), l.id]);
    }
    const requests = [...groups].flatMap(([limit, ids]) => {
      const chunks: number[][] = [];
      for (let i = 0; i < ids.length; i += BATCH) chunks.push(ids.slice(i, i + BATCH));
      return chunks.map((chunk) => this.api.boardCards(b.id, chunk, limit, this.filters()));
    });
    const epoch = this.epoch();
    forkJoin({
      shell: this.api.board(b.id),
      pages: requests.length
        ? forkJoin(requests).pipe(
            map((all) => Object.assign({}, ...all) as Record<string, CardPage>),
          )
        : of({} as Record<string, CardPage>),
    }).subscribe(({ shell, pages }) => {
      if (epoch !== this.epoch() || this.dragging) return;
      const current = new Map(this.board()!.lists.map((l) => [l.id, l]));
      this.board.set({
        ...shell,
        lists: shell.lists.map((l) => ({
          ...l,
          cards: pages[l.id]?.items ?? current.get(l.id)?.cards ?? [],
        })),
      });
      for (const l of loaded) {
        const p = pages[l.id];
        if (p)
          this.setState(l.id, { loaded: true, loading: false, hasMore: p.hasMore, total: p.total });
      }
    });
  }

  // ======================= Helpers (immutable updates for OnPush cards) =======================

  private setState(listId: number, s: ListState | undefined) {
    this.states.update((all) => {
      const next = { ...all };
      if (s) next[listId] = s;
      else delete next[listId];
      return next;
    });
  }

  private patchList(listId: number, fn: (l: BoardList) => BoardList) {
    this.board.update(
      (b) => b && { ...b, lists: b.lists.map((l) => (l.id === listId ? fn(l) : l)) },
    );
  }

  private patchCard(cardId: number, fn: (c: Card) => Card) {
    this.board.update(
      (b) =>
        b && {
          ...b,
          lists: b.lists.map((l) =>
            l.cards.some((c) => c.id === cardId)
              ? { ...l, cards: l.cards.map((c) => (c.id === cardId ? fn(c) : c)) }
              : l,
          ),
        },
    );
  }

  private bumpTotal(listId: number, delta: number) {
    const s = this.state(listId);
    if (s) this.setState(listId, { ...s, total: Math.max(0, s.total + delta) });
  }

  // ======================= Filters =======================

  clearFilters() {
    this.filters.set(EMPTY_FILTERS);
  }

  // ======================= Board =======================

  saveTitle(title: string) {
    this.editingTitle.set(false);
    const b = this.board()!;
    title = title.trim();
    if (!title || title === b.title) return;
    this.board.set({ ...b, title });
    this.api.updateBoard(b.id, { title }).subscribe();
  }

  toggleFavorite() {
    const b = this.board()!;
    this.board.set({ ...b, favorite: !b.favorite });
    this.api.toggleFavorite(b.id).subscribe();
  }

  onBoardChanged(b: Board) {
    this.board.set(b);
  }

  // ======================= Lists =======================

  addList() {
    const title = this.newListTitle.trim();
    if (!title) return;
    const b = this.board()!;
    this.newListTitle = '';
    this.api.addList(b.id, title).subscribe((l) => {
      this.board.update((x) => x && { ...x, lists: [...x.lists, { ...l, cards: [] }] });
      this.setState(l.id, { loaded: true, loading: false, hasMore: false, total: 0 });
      setTimeout(() => this.jumpTo(this.listCount()));
    });
  }

  renameList(list: BoardList, title: string) {
    this.editingListId.set(null);
    title = title.trim();
    if (!title || title === list.title) return;
    this.patchList(list.id, (l) => ({ ...l, title }));
    this.api.updateList(list.id, { title }).subscribe();
  }

  setListColor(list: BoardList, color: null | string) {
    this.listMenu.set(null);
    this.patchList(list.id, (l) => ({ ...l, color }));
    this.api.updateList(list.id, { color }).subscribe();
  }

  /** Archives a list: it leaves the board with its cards (Paramètres → Archives to restore it). */
  archiveList(list: BoardList) {
    this.listMenu.set(null);
    this.board.update((b) => b && { ...b, lists: b.lists.filter((l) => l.id !== list.id) });
    this.api.archiveList(list.id).subscribe({
      next: () =>
        this.toast.success(
          `Liste « ${list.title} » archivée : retrouvez-la dans Paramètres → Archives`,
        ),
      error: () => this.refresh(),
    });
  }

  /** A list restored from the archives comes back at the right end of the board (its cards load lazily). */
  onListRestored(list: BoardList) {
    this.board.update(
      (b) =>
        b && { ...b, lists: [...b.lists.filter((l) => l.id !== list.id), { ...list, cards: [] }] },
    );
    this.epoch.update((e) => e + 1);
  }

  async deleteList(list: BoardList) {
    this.listMenu.set(null);
    if (
      !(await this.dialog.confirm({
        title: 'Mettre la liste à la corbeille ?',
        text: `« ${list.title} » et ses ${list.cardCount} carte(s) pourront être restaurées pendant 30 jours (Paramètres → Corbeille).`,
        confirmText: 'Mettre à la corbeille',
      }))
    )
      return;
    this.board.update((b) => b && { ...b, lists: b.lists.filter((l) => l.id !== list.id) });
    this.api
      .deleteList(list.id)
      .subscribe(() =>
        this.toast.success('Liste mise à la corbeille : Paramètres → Corbeille pour la restaurer'),
      );
  }

  dropList(e: CdkDragDrop<BoardList[]>) {
    this.dragEnded();
    const b = this.board()!;
    if (e.previousIndex === e.currentIndex) return;
    // CDK indexes are relative to the rendered window
    const offset = this.range().start;
    const lists = [...b.lists];
    moveItemInArray(lists, e.previousIndex + offset, e.currentIndex + offset);
    this.board.set({ ...b, lists });
    this.api
      .reorderLists(
        b.id,
        lists.map((l) => l.id),
      )
      .subscribe();
  }

  // ======================= Cards =======================

  startAddCard(list: BoardList) {
    this.addingCardTo.set(list.id);
    this.newCardTitle = '';
    setTimeout(() => {
      const el = document.getElementById('new-card-' + list.id) as HTMLTextAreaElement | null;
      el?.focus();
      el?.scrollIntoView({ block: 'nearest' });
    });
  }

  addCard(list: BoardList) {
    const title = this.newCardTitle.trim();
    if (!title) {
      this.addingCardTo.set(null);
      return;
    }
    this.newCardTitle = '';
    this.api.addCard(list.id, title).subscribe((card) => {
      this.patchList(list.id, (l) => ({
        ...l,
        cardCount: l.cardCount + 1,
        cards: [...l.cards, card],
      }));
      this.bumpTotal(list.id, 1);
    });
  }

  onAddCardKey(e: KeyboardEvent, list: BoardList) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      this.addCard(list);
    }
    if (e.key === 'Escape') this.addingCardTo.set(null);
  }

  dragStarted() {
    this.dragging = true;
  }
  dragEnded() {
    setTimeout(() => {
      this.dragging = false;
      this.updateRange();
    });
  }

  /**
   * Loaded cards are always the first N of the column (ordered by position),
   * so the drop index in the loaded array IS the server position.
   */
  dropCard(e: CdkDragDrop<BoardList, BoardList, Card>) {
    const from = e.previousContainer.data;
    const to = e.container.data;
    if (from === to && e.previousIndex === e.currentIndex) return;
    const card = e.item.data;

    if (from === to) {
      const cards = [...to.cards];
      moveItemInArray(cards, e.previousIndex, e.currentIndex);
      this.patchList(to.id, (l) => ({ ...l, cards }));
    } else {
      const src = [...from.cards];
      const dst = [...to.cards];
      transferArrayItem(src, dst, e.previousIndex, e.currentIndex);
      dst[e.currentIndex] = { ...card, listId: to.id };
      this.board.update(
        (b) =>
          b && {
            ...b,
            lists: b.lists.map((l) =>
              l.id === from.id
                ? { ...l, cards: src, cardCount: l.cardCount - 1 }
                : l.id === to.id
                  ? { ...l, cards: dst, cardCount: l.cardCount + 1 }
                  : l,
            ),
          },
      );
      this.bumpTotal(from.id, -1);
      this.bumpTotal(to.id, 1);
    }
    this.api.moveCard(card.id, to.id, e.currentIndex).subscribe({ error: () => this.refresh() });
  }

  toggleComplete(card: Card) {
    const completed = !card.completed;
    this.patchCard(card.id, (c) => ({ ...c, completed }));
    this.api.updateCard(card.id, { completed }).subscribe();
  }

  openCard(card: Card) {
    this.router.navigate([], { queryParams: { card: card.id }, queryParamsHandling: 'merge' });
  }

  closeCard() {
    this.router.navigate([], { queryParams: { card: null }, queryParamsHandling: 'merge' });
  }

  /** Sync the card preview after an edit in the modal. */
  onCardChanged(detail: CardDetail) {
    const { comments, checklist, listTitle, createdBy, attachments, ...summary } = detail;
    this.patchCard(detail.id, (c) => ({
      ...c,
      ...summary,
      commentCount: comments.length,
      attachmentCount: attachments.length,
      checklistTotal: checklist.length,
      checklistDone: checklist.filter((i) => i.done).length,
    }));
  }

  /** Opens a card by id (archives: the card is not on the board). */
  openCardId$(id: number) {
    this.router.navigate([], { queryParams: { card: id }, queryParamsHandling: 'merge' });
  }

  /** Archived: the card leaves its list, the dialog stays open. Restored: it comes back at the bottom of its list. */
  onCardArchived(detail: CardDetail) {
    if (detail.archivedAt) {
      const list = this.board()?.lists.find((l) => l.cards.some((c) => c.id === detail.id));
      if (list) {
        this.patchList(list.id, (l) => ({
          ...l,
          cardCount: l.cardCount - 1,
          cards: l.cards.filter((c) => c.id !== detail.id),
        }));
        this.bumpTotal(list.id, -1);
      }
    } else {
      this.patchList(detail.listId, (l) => ({ ...l, cardCount: l.cardCount + 1 }));
      this.bumpTotal(detail.listId, 1);
      this.refresh();
    }
  }

  onCardDeleted(id: number) {
    const list = this.board()?.lists.find((l) => l.cards.some((c) => c.id === id));
    if (list) {
      this.patchList(list.id, (l) => ({
        ...l,
        cardCount: l.cardCount - 1,
        cards: l.cards.filter((c) => c.id !== id),
      }));
      this.bumpTotal(list.id, -1);
    }
    this.closeCard();
  }

  // ---------- Trello import: copy of the attachments ----------
  resumeTrello() {
    if (this.trello.credentials()) this.trello.start(this.board()!.id);
    else this.showTrelloAccess.set(true);
  }

  async startTrelloWithAccess() {
    if (await this.trelloAccess()!.verify()) {
      this.showTrelloAccess.set(false);
      this.trello.start(this.board()!.id);
    }
  }

  async abandonTrello() {
    const t = this.trelloBanner();
    if (
      !t ||
      !(await this.dialog.confirm({
        title: 'Laisser les fichiers sur Trello ?',
        text: `${t.pending + t.failed} fichier(s) ne seront pas copiés. Un lien vers Trello sera ajouté à la description de chaque carte concernée.`,
        confirmText: 'Laisser sur Trello',
        tone: 'primary',
      }))
    )
      return;
    this.trello.abandon(this.board()!.id);
  }

  trelloFailures(): string {
    return (this.trelloBanner()?.failures ?? []).map((f) => `${f.name} : ${f.error}`).join('\n');
  }

  /** Files dropped on a card preview → attachments of that card. */
  dropFiles(card: Card, files: File[]) {
    this.uploads.upload(card.id, files);
  }

  /** A file dropped next to a card must not make the browser leave the app to open it. */
  @HostListener('document:dragover', ['$event'])
  @HostListener('document:drop', ['$event'])
  blockFileDrop(e: DragEvent) {
    if (draggedFiles(e) && !e.defaultPrevented) {
      e.preventDefault();
      if (e.dataTransfer && e.type === 'dragover') e.dataTransfer.dropEffect = 'none';
    }
  }

  @HostListener('document:click')
  closeMenus() {
    this.listMenu.set(null);
    this.showJump.set(false);
    this.showFilters.set(false);
  }

  @HostListener('document:keydown', ['$event'])
  onKey(e: KeyboardEvent) {
    const target = e.target as HTMLElement;
    if (['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName) || this.openCardId()) return;
    if (e.key === 'f' || e.key === '/') {
      e.preventDefault();
      this.showFilters.set(true);
      setTimeout(() => document.getElementById('board-search')?.focus());
    }
  }
}
