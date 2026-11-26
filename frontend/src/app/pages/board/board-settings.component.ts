import {
  Component,
  computed,
  effect,
  HostListener,
  inject,
  input,
  OnInit,
  output,
  signal,
  untracked,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';

import { ApiService, BoardTrash } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { DialogService } from '../../core/dialog.service';
import {
  BACKGROUNDS,
  Board,
  BoardList,
  Card,
  CardDetail,
  CustomField,
  CustomFieldType,
  Label,
  Member,
  PALETTE,
  SelectOption,
} from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { AvatarComponent } from '../../shared/avatar.component';
import { IconComponent } from '../../shared/icon.component';
import { TimeAgoPipe } from '../../shared/time-ago.pipe';

type Tab = 'archived' | 'background' | 'fields' | 'labels' | 'members' | 'trash';

@Component({
  selector: 'tk-board-settings',
  imports: [FormsModule, IconComponent, AvatarComponent, TimeAgoPipe],
  templateUrl: './board-settings.component.html',
  styleUrl: './board-settings.component.scss',
})
export class BoardSettingsComponent implements OnInit {
  private api = inject(ApiService);
  private toast = inject(ToastService);
  private dialog = inject(DialogService);
  private router = inject(Router);
  auth = inject(AuthService);

  board = input.required<Board>();
  tab = input.required<Tab>();
  tabChange = output<Tab>();
  boardChange = output<Board>();
  closed = output<void>();
  openCard = output<number>();
  restored = output<CardDetail>();
  listRestored = output<BoardList>();
  archivedLists = signal<BoardList[]>([]);

  // archived cards
  archived = signal<(Card & { listTitle: string })[]>([]);
  archivedTotal = signal(0);
  archivedHasMore = signal(false);
  archivedLoading = signal(false);
  archivedQuery = '';
  private archivedTimer: null | ReturnType<typeof setTimeout> = null;
  /** Keeps the active tab visible when the tab bar scrolls (narrow drawer). */
  private tabVisible = effect(() => {
    this.tab();
    setTimeout(() =>
      document
        .querySelector('.d-tabs .active')
        ?.scrollIntoView({ block: 'nearest', inline: 'nearest' }),
    );
  });
  // trash
  trash = signal<BoardTrash | null>(null);
  private trashLoaded = effect(() => {
    if (this.tab() === 'trash') untracked(() => this.loadTrash());
  });

  loadTrash() {
    this.api.boardTrash(this.board().id).subscribe((t) => this.trash.set(t));
  }

  restoreTrashCard(c: BoardTrash['cards'][number]) {
    this.api.restoreTrashCard(c.id).subscribe((detail) => {
      this.restored.emit(detail);
      this.loadTrash();
      this.toast.success(
        detail.archivedAt
          ? `« ${c.title} » restaurée dans les archives`
          : `« ${c.title} » restaurée dans « ${detail.listTitle} »`,
      );
    });
  }

  restoreTrashList(l: BoardTrash['lists'][number]) {
    this.api.restoreTrashList(l.id).subscribe((list) => {
      if (!list.archivedAt) this.listRestored.emit(list);
      this.loadTrash();
      this.toast.success(
        list.archivedAt
          ? `Liste « ${l.title} » restaurée dans les archives`
          : `Liste « ${l.title} » restaurée à la fin du tableau`,
      );
    });
  }

  async purgeTrashCard(c: BoardTrash['cards'][number]) {
    if (
      !(await this.dialog.confirm({
        title: 'Supprimer définitivement ?',
        text: `« ${c.title} », ses commentaires et ses pièces jointes seront effacés. Cette action est irréversible.`,
        confirmText: 'Supprimer définitivement',
      }))
    )
      return;
    this.api.purgeTrashCard(c.id).subscribe(() => this.loadTrash());
  }

  async purgeTrashList(l: BoardTrash['lists'][number]) {
    if (
      !(await this.dialog.confirm({
        title: 'Supprimer définitivement ?',
        text: `La liste « ${l.title} » et ses ${l.cardCount} carte(s) seront effacées, pièces jointes comprises. Cette action est irréversible.`,
        confirmText: 'Supprimer définitivement',
      }))
    )
      return;
    this.api.purgeTrashList(l.id).subscribe(() => this.loadTrash());
  }

  daysLeft(purgeAt: string): number {
    return Math.max(0, Math.ceil((new Date(purgeAt).getTime() - Date.now()) / 86400000));
  }

  private archivedLoaded = effect(() => {
    if (this.tab() === 'archived') untracked(() => this.loadArchived());
  });

  loadArchived(more = false) {
    this.archivedLoading.set(true);
    if (!more && !this.archivedQuery.trim())
      this.api.archivedLists(this.board().id).subscribe((l) => this.archivedLists.set(l));
    this.api
      .archivedCards(this.board().id, this.archivedQuery.trim(), more ? this.archived().length : 0)
      .subscribe({
        next: (r) => {
          this.archived.set(more ? [...this.archived(), ...r.items] : r.items);
          this.archivedTotal.set(r.total);
          this.archivedHasMore.set(r.hasMore);
          this.archivedLoading.set(false);
        },
        error: () => this.archivedLoading.set(false),
      });
  }

  searchArchived(q: string) {
    this.archivedQuery = q;
    if (this.archivedTimer) clearTimeout(this.archivedTimer);
    this.archivedTimer = setTimeout(() => this.loadArchived(), 250);
  }

  restoreCard(c: Card & { listTitle: string }) {
    this.api.restoreCard(c.id).subscribe((detail) => {
      this.archived.update((list) => list.filter((x) => x.id !== c.id));
      this.archivedTotal.update((n) => n - 1);
      this.restored.emit(detail);
      this.toast.success(`« ${c.title} » restaurée dans « ${detail.listTitle} »`);
    });
  }

  restoreList(l: BoardList) {
    this.api.restoreList(l.id).subscribe((list) => {
      this.archivedLists.update((x) => x.filter((y) => y.id !== l.id));
      this.listRestored.emit(list);
      this.loadArchived(); // its archived cards can be restored again
      this.toast.success(`Liste « ${l.title} » restaurée à la fin du tableau`);
    });
  }

  async deleteArchivedList(l: BoardList) {
    if (
      !(await this.dialog.confirm({
        title: 'Mettre la liste à la corbeille ?',
        text: `« ${l.title} » et ses ${l.cardCount} carte(s) pourront être restaurées pendant 30 jours.`,
        confirmText: 'Mettre à la corbeille',
      }))
    )
      return;
    this.api.deleteList(l.id).subscribe(() => {
      this.archivedLists.update((x) => x.filter((y) => y.id !== l.id));
      this.loadArchived();
    });
  }

  async deleteArchived(c: Card & { listTitle: string }) {
    if (
      !(await this.dialog.confirm({
        title: 'Mettre la carte à la corbeille ?',
        text: `« ${c.title} » pourra être restaurée pendant 30 jours.`,
        confirmText: 'Mettre à la corbeille',
      }))
    )
      return;
    this.api.deleteCard(c.id).subscribe(() => {
      this.archived.update((list) => list.filter((x) => x.id !== c.id));
      this.archivedTotal.update((n) => n - 1);
    });
  }

  archivedDate(d: null | string) {
    return d
      ? new Date(d).toLocaleDateString('fr-FR', { day: 'numeric', month: 'short', year: 'numeric' })
      : '';
  }

  tabs: { id: Tab; label: string; icon: string }[] = [
    { id: 'members', label: 'Membres', icon: 'users' },
    { id: 'fields', label: 'Champs', icon: 'sliders' },
    { id: 'labels', label: 'Étiquettes', icon: 'tag' },
    { id: 'background', label: 'Général', icon: 'palette' },
    { id: 'archived', label: 'Archives', icon: 'archive' },
    { id: 'trash', label: 'Corbeille', icon: 'trash' },
  ];
  fieldTypes: { value: CustomFieldType; label: string; icon: string }[] = [
    { value: 'text', label: 'Texte', icon: 'type' },
    { value: 'number', label: 'Nombre', icon: 'hash' },
    { value: 'date', label: 'Date', icon: 'calendar' },
    { value: 'checkbox', label: 'Case à cocher', icon: 'toggle' },
    { value: 'select', label: 'Liste déroulante', icon: 'list' },
    { value: 'url', label: 'Lien', icon: 'link' },
  ];
  backgrounds = BACKGROUNDS;
  palette = PALETTE;

  canManage = computed(() => ['owner', 'admin'].includes(this.board().role));
  isOwner = computed(() => this.board().role === 'owner');

  // members
  invite = { email: '', role: 'member' };
  inviting = signal(false);

  // labels
  newLabel = { name: '', color: PALETTE[5] };
  editingLabel = signal<null | number>(null);

  // custom fields
  newField: { name: string; type: CustomFieldType; options: SelectOption[]; showOnCard: boolean } =
    this.blankField();
  newOption = { label: '', color: PALETTE[5] };
  editingField = signal<null | number>(null);
  fieldOption = { label: '', color: PALETTE[6] };

  // general
  meta = { title: '', description: '' };

  ngOnInit() {
    this.meta = { title: this.board().title, description: this.board().description ?? '' };
  }

  private emit(patch: Partial<Board>) {
    this.boardChange.emit({ ...this.board(), ...patch });
  }

  @HostListener('document:keydown.escape')
  esc() {
    if (!this.dialog.isOpen()) this.closed.emit();
  }

  roleLabel(r: string) {
    return r === 'owner' ? 'Propriétaire' : r === 'admin' ? 'Administrateur' : 'Membre';
  }

  // ---------- members ----------
  sendInvite() {
    const email = this.invite.email.trim();
    if (!email) return;
    this.inviting.set(true);
    this.api.addMember(this.board().id, email, this.invite.role).subscribe({
      next: (r) => {
        this.inviting.set(false);
        this.invite.email = '';
        if (r.status === 'added' && r.member) {
          this.emit({ members: [...this.board().members, r.member] });
          this.toast.success(`${r.member.name} a été ajouté au tableau`);
        } else {
          this.emit({
            invitations: [
              ...this.board().invitations,
              { id: Date.now(), email, createdAt: new Date().toISOString() },
            ],
          });
          this.toast.show(r.message ?? 'Invitation enregistrée', 'info', 5000);
          this.reloadInvitations();
        }
      },
      error: () => this.inviting.set(false),
    });
  }

  private reloadInvitations() {
    this.api.board(this.board().id).subscribe((b) => this.emit({ invitations: b.invitations }));
  }

  changeRole(m: Member, role: string) {
    this.api
      .updateMember(this.board().id, m.id, role)
      .subscribe((updated) =>
        this.emit({ members: this.board().members.map((x) => (x.id === m.id ? updated : x)) }),
      );
  }

  async removeMember(m: Member) {
    const self = m.id === this.auth.user()?.id;
    if (
      !(await this.dialog.confirm(
        self
          ? {
              title: 'Quitter ce tableau ?',
              text: 'Vous n’y aurez plus accès, sauf si un administrateur vous invite à nouveau.',
              confirmText: 'Quitter',
            }
          : {
              title: `Retirer ${m.name} ?`,
              text: 'Cette personne n’aura plus accès au tableau et sera retirée de ses cartes.',
              confirmText: 'Retirer',
            },
      ))
    )
      return;
    this.api.removeMember(this.board().id, m.id).subscribe(() => {
      if (self) {
        this.toast.success('Vous avez quitté le tableau');
        this.router.navigateByUrl('/boards');
        return;
      }
      this.emit({ members: this.board().members.filter((x) => x.id !== m.id) });
      this.toast.success(`${m.name} a été retiré`);
    });
  }

  cancelInvitation(id: number) {
    this.api
      .cancelInvitation(this.board().id, id)
      .subscribe(() =>
        this.emit({ invitations: this.board().invitations.filter((i) => i.id !== id) }),
      );
  }

  // ---------- labels ----------
  addLabel() {
    const name = this.newLabel.name.trim();
    if (!name) return;
    this.api.addLabel(this.board().id, name, this.newLabel.color).subscribe((l) => {
      this.emit({ labels: [...this.board().labels, l] });
      this.newLabel.name = '';
    });
  }

  saveLabel(l: Label, name: string, color: string) {
    this.editingLabel.set(null);
    this.api
      .updateLabel(l.id, { name, color })
      .subscribe((u) =>
        this.emit({ labels: this.board().labels.map((x) => (x.id === l.id ? u : x)) }),
      );
  }

  async deleteLabel(l: Label) {
    if (
      !(await this.dialog.confirm({
        title: 'Supprimer l’étiquette ?',
        text: `« ${l.name || 'Sans nom'} » sera retirée de toutes les cartes du tableau.`,
        confirmText: 'Supprimer',
      }))
    )
      return;
    this.api.deleteLabel(l.id).subscribe(() =>
      this.emit({
        labels: this.board().labels.filter((x) => x.id !== l.id),
        lists: this.board().lists.map((li) => ({
          ...li,
          cards: li.cards.map((c) => ({ ...c, labelIds: c.labelIds.filter((id) => id !== l.id) })),
        })),
      }),
    );
  }

  // ---------- custom fields ----------
  blankField() {
    return {
      name: '',
      type: 'text' as CustomFieldType,
      options: [] as SelectOption[],
      showOnCard: true,
    };
  }
  typeOf(t: string) {
    return this.fieldTypes.find((x) => x.value === t)!;
  }

  addOption(target: SelectOption[], opt: { label: string; color: string }) {
    const label = opt.label.trim();
    if (!label) return;
    target.push({ id: Math.random().toString(36).slice(2, 10), label, color: opt.color });
    opt.label = '';
    opt.color = PALETTE[(PALETTE.indexOf(opt.color) + 1) % PALETTE.length];
  }

  createField() {
    const f = this.newField;
    if (!f.name.trim()) return;
    if (f.type === 'select' && !f.options.length) {
      this.toast.show('Ajoutez au moins une option à la liste.', 'error');
      return;
    }
    this.api.addCustomField(this.board().id, f).subscribe((field) => {
      this.emit({ customFields: [...this.board().customFields, field] });
      this.newField = this.blankField();
      this.toast.success(`Champ « ${field.name} » ajouté à toutes les cartes`);
    });
  }

  updateField(f: CustomField, patch: Partial<CustomField>) {
    this.api
      .updateCustomField(f.id, patch)
      .subscribe((u) =>
        this.emit({ customFields: this.board().customFields.map((x) => (x.id === f.id ? u : x)) }),
      );
  }

  removeOption(f: CustomField, id: string) {
    this.updateField(f, { options: f.options.filter((o) => o.id !== id) });
  }

  addOptionToField(f: CustomField) {
    const opts = [...f.options];
    this.addOption(opts, this.fieldOption);
    if (opts.length !== f.options.length) this.updateField(f, { options: opts });
  }

  async deleteField(f: CustomField) {
    if (
      !(await this.dialog.confirm({
        title: 'Supprimer le champ ?',
        text: `« ${f.name} » et ses valeurs sur toutes les cartes seront supprimés.`,
        confirmText: 'Supprimer',
      }))
    )
      return;
    this.api
      .deleteCustomField(f.id)
      .subscribe(() =>
        this.emit({ customFields: this.board().customFields.filter((x) => x.id !== f.id) }),
      );
  }

  // ---------- general ----------
  setBackground(bg: string) {
    this.emit({ background: bg });
    this.api.updateBoard(this.board().id, { background: bg }).subscribe();
  }

  setShowNumbers(showCardNumbers: boolean) {
    this.emit({ showCardNumbers });
    this.api.updateBoard(this.board().id, { showCardNumbers }).subscribe();
  }

  saveMeta() {
    const title = this.meta.title.trim();
    if (!title) return;
    this.api
      .updateBoard(this.board().id, { title, description: this.meta.description })
      .subscribe(() => {
        this.emit({ title, description: this.meta.description || null });
        this.toast.success('Tableau mis à jour');
      });
  }

  async deleteBoard() {
    if (
      !(await this.dialog.confirm({
        title: 'Mettre le tableau à la corbeille ?',
        text: `« ${this.board().title} » pourra être restauré pendant 30 jours depuis vos tableaux (bouton Corbeille).`,
        confirmText: 'Mettre à la corbeille',
      }))
    )
      return;
    this.api.deleteBoard(this.board().id).subscribe(() => {
      this.toast.success('Tableau mis à la corbeille');
      this.router.navigateByUrl('/boards');
    });
  }

  leave() {
    const me = this.board().members.find((m) => m.id === this.auth.user()?.id);
    if (me) this.removeMember(me);
  }
}
