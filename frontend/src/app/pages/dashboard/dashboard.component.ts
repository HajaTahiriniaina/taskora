import {
  Component,
  HostListener,
  OnInit,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { ApiService, TrashedBoard } from '../../core/api.service';
import { DialogService } from '../../core/dialog.service';
import { AuthService } from '../../core/auth.service';
import { BACKGROUNDS, BoardSummary } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { TrelloTransferService } from '../../core/trello-transfer.service';
import { TrelloAccessComponent } from '../../shared/trello-access.component';
import { AvatarComponent } from '../../shared/avatar.component';
import { IconComponent } from '../../shared/icon.component';

@Component({
  selector: 'tk-dashboard',
  imports: [
    NgTemplateOutlet,
    FormsModule,
    RouterLink,
    IconComponent,
    AvatarComponent,
    TrelloAccessComponent,
  ],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss',
})
export class DashboardComponent implements OnInit {
  private api = inject(ApiService);
  private router = inject(Router);
  private toast = inject(ToastService);
  auth = inject(AuthService);

  backgrounds = BACKGROUNDS;
  boards = signal<BoardSummary[]>([]);
  loading = signal(true);
  query = signal('');
  showCreate = signal(false);
  creating = signal(false);
  importing = signal(false);
  /** Trello import dialog */
  showImport = signal(false);
  importFile = signal<File | null>(null);
  copyFiles = signal(true);
  private trello = inject(TrelloTransferService);
  private dialog = inject(DialogService);
  access = viewChild(TrelloAccessComponent);
  form = { title: '', description: '', background: 'aurora' };

  filtered = computed(() => {
    const q = this.query().toLowerCase().trim();
    return this.boards().filter(
      (b) =>
        !q || b.title.toLowerCase().includes(q) || (b.description ?? '').toLowerCase().includes(q),
    );
  });
  favorites = computed(() => this.filtered().filter((b) => b.favorite));
  totalCards = computed(() => this.boards().reduce((s, b) => s + b.cardCount, 0));
  collaborators = computed(
    () => new Set(this.boards().flatMap((b) => b.members.map((m) => m.id))).size,
  );

  greeting = (() => {
    const h = new Date().getHours();
    return h < 6 ? 'Bonne nuit' : h < 18 ? 'Bonjour' : 'Bonsoir';
  })();

  // ---------- trash (boards deleted by their owner, 30 days) ----------
  showTrash = signal(false);
  trashedBoards = signal<TrashedBoard[] | null>(null);

  openTrash() {
    this.trashedBoards.set(null);
    this.showTrash.set(true);
    this.api.trashBoards().subscribe((b) => this.trashedBoards.set(b));
  }

  restoreBoard(b: TrashedBoard) {
    this.api.restoreTrashBoard(b.id).subscribe(() => {
      this.trashedBoards.update((l) => (l ?? []).filter((x) => x.id !== b.id));
      this.api.boards().subscribe((list) => this.boards.set(list));
      this.toast.success(`« ${b.title} » restauré`);
    });
  }

  async purgeBoard(b: TrashedBoard) {
    if (
      !(await this.dialog.confirm({
        title: 'Supprimer définitivement ?',
        text: `« ${b.title} », ses listes, cartes et pièces jointes seront effacés. Cette action est irréversible.`,
        confirmText: 'Supprimer définitivement',
      }))
    )
      return;
    this.api
      .purgeTrashBoard(b.id)
      .subscribe(() => this.trashedBoards.update((l) => (l ?? []).filter((x) => x.id !== b.id)));
  }

  daysLeft(purgeAt: string): number {
    return Math.max(0, Math.ceil((new Date(purgeAt).getTime() - Date.now()) / 86400000));
  }

  shortDate(d: string) {
    return new Date(d).toLocaleDateString('fr-FR', {
      day: 'numeric',
      month: 'short',
      year: 'numeric',
    });
  }

  ngOnInit() {
    this.api.boards().subscribe({
      next: (b) => {
        this.boards.set(b);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  firstName() {
    return this.auth.user()?.name.split(' ')[0] ?? '';
  }

  openCreate() {
    this.form = {
      title: '',
      description: '',
      background: BACKGROUNDS[Math.floor(Math.random() * BACKGROUNDS.length)],
    };
    this.showCreate.set(true);
  }

  @HostListener('document:keydown.escape')
  closeCreate() {
    if (this.dialog.isOpen()) return;
    this.showCreate.set(false);
    this.showTrash.set(false);
    if (!this.importing()) this.showImport.set(false);
  }

  create() {
    if (!this.form.title.trim()) return;
    this.creating.set(true);
    this.api.createBoard(this.form).subscribe({
      next: (b) => {
        this.toast.success('Tableau créé 🎉');
        this.router.navigate(['/boards', b.id]);
      },
      error: () => this.creating.set(false),
    });
  }

  openImport() {
    this.importFile.set(null);
    this.showImport.set(true);
  }

  pickImportFile(input: HTMLInputElement) {
    this.importFile.set(input.files?.[0] ?? null);
    input.value = '';
  }

  /**
   * Trello JSON export → new board (card numbers, labels, comments, checklists kept), then,
   * when asked, copy of the files attached on Trello into Taskora (key / token in memory only).
   */
  async importTrello() {
    const file = this.importFile();
    if (!file || this.importing()) return;
    const copy = this.copyFiles();
    if (copy && !(await this.access()!.verify())) return;
    this.importing.set(true);
    this.api.importTrello(file).subscribe({
      next: ({ boardId, report: r }) => {
        this.importing.set(false);
        this.showImport.set(false);
        this.toast.show(
          `Import terminé : ${r.lists} listes, ${r.cards} cartes (numéros conservés), ${r.comments} commentaires.`,
          'success',
          6000,
        );
        if (r.membersUnmatched.length) {
          this.toast.show(
            `${r.membersUnmatched.length} membre(s) Trello sans compte Taskora : invitez-les depuis « Partager ».`,
            'info',
            8000,
          );
        }
        this.router.navigate(['/boards', boardId]);
        if (copy && r.files) this.trello.start(boardId);
      },
      error: () => this.importing.set(false),
    });
  }

  toggleFavorite(b: BoardSummary, e: Event) {
    e.preventDefault();
    e.stopPropagation();
    this.boards.update((list) =>
      list.map((x) => (x.id === b.id ? { ...x, favorite: !x.favorite } : x)),
    );
    this.api.toggleFavorite(b.id).subscribe();
  }
}
