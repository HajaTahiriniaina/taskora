export interface User {
  avatarColor: string;
  email: string;
  id: number;
  name: string;
}

export type Role = 'admin' | 'member' | 'owner';

export interface Member extends User {
  joinedAt: string;
  role: Role;
}

export interface BoardSummary {
  background: string;
  cardCount: number;
  createdAt: string;
  description: null | string;
  favorite: boolean;
  id: number;
  listCount: number;
  members: Member[];
  role: Role;
  showCardNumbers: boolean;
  title: string;
}

export interface Label {
  color: string;
  id: number;
  name: string;
}

export type CustomFieldType = 'checkbox' | 'date' | 'number' | 'select' | 'text' | 'url';

export interface SelectOption {
  color: string;
  id: string;
  label: string;
}

export interface CustomField {
  id: number;
  name: string;
  options: SelectOption[];
  position: number;
  showOnCard: boolean;
  type: CustomFieldType;
}

export type Priority = 'high' | 'low' | 'medium' | 'none' | 'urgent';

export interface Card {
  /** Set when the card is archived (it then only appears in the board's archives) */
  archivedAt: null | string;
  assignees: User[];
  attachmentCount: number;
  checklistDone: number;
  checklistTotal: number;
  commentCount: number;
  completed: boolean;
  cover: null | string;
  coverAttachmentId: null | number;
  /** Image attachment used as cover (thumbnail URL), takes precedence over the color */
  coverImage: null | string;
  createdAt: string;
  customValues: Record<string, any>;
  description: null | string;
  dueDate: null | string;
  id: number;
  labelIds: number[];
  listId: number;
  /** Per-board card number (#12), kept when importing from Trello. */
  number: number;
  position: number;
  priority: Priority;
  title: string;
}

export interface Comment {
  /** id = 0 and external = true for imported authors without an account */
  author: User & { external?: boolean };
  content: string;
  createdAt: string;
  id: number;
}

export interface Attachment {
  /** Set when the file was posted in a comment */
  commentId: null | number;
  createdAt: string;
  /** lower-case extension of the name */
  ext: string;
  id: number;
  image: boolean;
  mime: string;
  name: string;
  size: number;
  /** Small WebP version for images (null: use url) */
  thumbUrl: null | string;
  uploader: null | User;
  /** "/uploads/<key>": displayed (images, PDF) or downloaded under its name */
  url: string;
}

export interface Activity {
  actor: User & { external?: boolean };
  createdAt: string;
  data: Record<string, unknown>;
  id: number;
  type: string;
}

export interface ChecklistItem {
  content: string;
  done: boolean;
  id: number;
  position: number;
}

export interface CardDetail extends Card {
  attachments: Attachment[];
  checklist: ChecklistItem[];
  comments: Comment[];
  createdBy: null | User;
  /** Its list is archived (the card is not on the board) */
  listArchived: boolean;
  listTitle: string;
}

export interface BoardList {
  archivedAt?: null | string;
  /** Total cards in the list (server side, unfiltered). */
  cardCount: number;
  /** Cards loaded so far (lazy, page by page). */
  cards: Card[];
  color: null | string;
  id: number;
  position: number;
  title: string;
}

export interface CardPage {
  hasMore: boolean;
  items: Card[];
  offset: number;
  total: number;
}

export type DueFilter = 'day' | 'month' | 'none' | 'overdue' | 'week';

/** Trello-like filters: OR inside a section, AND between sections (applied server side). */
export interface CardFilters {
  /** fieldId → selected option ids ("true" for checkbox fields) */
  cf: Record<number, string[]>;
  done: boolean;
  due: DueFilter[];
  labels: number[];
  members: number[];
  mine: boolean;
  noLabel: boolean;
  noMember: boolean;
  open: boolean;
  q: string;
}

export const EMPTY_FILTERS: CardFilters = {
  q: '',
  noMember: false,
  mine: false,
  members: [],
  done: false,
  open: false,
  due: [],
  noLabel: false,
  labels: [],
  cf: {},
};

export function activeFilterCount(f: CardFilters): number {
  return (
    (f.q.trim() ? 1 : 0) +
    (f.noMember ? 1 : 0) +
    (f.mine ? 1 : 0) +
    f.members.length +
    (f.done ? 1 : 0) +
    (f.open ? 1 : 0) +
    f.due.length +
    (f.noLabel ? 1 : 0) +
    f.labels.length +
    Object.values(f.cf).reduce((s, v) => s + v.length, 0)
  );
}

export interface Invitation {
  createdAt: string;
  email: string;
  id: number;
}

export interface Board extends BoardSummary {
  customFields: CustomField[];
  invitations: Invitation[];
  labels: Label[];
  lists: BoardList[];
  /** Trello import: files still to copy into Taskora (pending or failed) */
  trelloFiles: number;
}

export interface AppNotification {
  actor: null | User;
  boardId: null | number;
  boardTitle: null | string;
  cardId: null | number;
  createdAt: string;
  id: number;
  message: string;
  read: boolean;
  type: string;
}

export const BACKGROUNDS = [
  'aurora',
  'sunset',
  'ocean',
  'forest',
  'midnight',
  'peach',
  'lagoon',
  'berry',
  'graphite',
  'sand',
];

export const PALETTE = [
  '#ef4444',
  '#f97316',
  '#eab308',
  '#22c55e',
  '#14b8a6',
  '#0ea5e9',
  '#6366f1',
  '#8b5cf6',
  '#ec4899',
  '#64748b',
];

export const PRIORITIES: { color: string; label: string; value: Priority }[] = [
  { value: 'none', label: 'Aucune', color: '#94a3b8' },
  { value: 'low', label: 'Basse', color: '#22c55e' },
  { value: 'medium', label: 'Moyenne', color: '#eab308' },
  { value: 'high', label: 'Haute', color: '#f97316' },
  { value: 'urgent', label: 'Urgente', color: '#ef4444' },
];
