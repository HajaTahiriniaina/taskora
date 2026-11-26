export interface User {
  id: number;
  name: string;
  email: string;
  avatarColor: string;
}

export type Role = 'admin' | 'member' | 'owner';

export interface Member extends User {
  role: Role;
  joinedAt: string;
}

export interface BoardSummary {
  id: number;
  title: string;
  description: null | string;
  background: string;
  showCardNumbers: boolean;
  favorite: boolean;
  role: Role;
  members: Member[];
  listCount: number;
  cardCount: number;
  createdAt: string;
}

export interface Label {
  id: number;
  name: string;
  color: string;
}

export type CustomFieldType = 'checkbox' | 'date' | 'number' | 'select' | 'text' | 'url';

export interface SelectOption {
  id: string;
  label: string;
  color: string;
}

export interface CustomField {
  id: number;
  name: string;
  type: CustomFieldType;
  options: SelectOption[];
  showOnCard: boolean;
  position: number;
}

export type Priority = 'high' | 'low' | 'medium' | 'none' | 'urgent';

export interface Card {
  id: number;
  listId: number;
  /** Per-board card number (#12), kept when importing from Trello. */
  number: number;
  title: string;
  description: null | string;
  position: number;
  dueDate: null | string;
  completed: boolean;
  /** Set when the card is archived (it then only appears in the board's archives) */
  archivedAt: null | string;
  cover: null | string;
  /** Image attachment used as cover (thumbnail URL), takes precedence over the color */
  coverImage: null | string;
  coverAttachmentId: null | number;
  priority: Priority;
  labelIds: number[];
  assignees: User[];
  commentCount: number;
  attachmentCount: number;
  checklistTotal: number;
  checklistDone: number;
  customValues: Record<string, any>;
  createdAt: string;
}

export interface Comment {
  id: number;
  content: string;
  /** id = 0 and external = true for imported authors without an account */
  author: User & { external?: boolean };
  createdAt: string;
}

export interface Attachment {
  id: number;
  name: string;
  /** "/uploads/<key>": displayed (images, PDF) or downloaded under its name */
  url: string;
  mime: string;
  size: number;
  image: boolean;
  /** Small WebP version for images (null: use url) */
  thumbUrl: null | string;
  /** lower-case extension of the name */
  ext: string;
  /** Set when the file was posted in a comment */
  commentId: null | number;
  uploader: null | User;
  createdAt: string;
}

export interface Activity {
  id: number;
  type: string;
  data: Record<string, unknown>;
  actor: User & { external?: boolean };
  createdAt: string;
}

export interface ChecklistItem {
  id: number;
  content: string;
  done: boolean;
  position: number;
}

export interface CardDetail extends Card {
  listTitle: string;
  /** Its list is archived (the card is not on the board) */
  listArchived: boolean;
  createdBy: null | User;
  comments: Comment[];
  checklist: ChecklistItem[];
  attachments: Attachment[];
}

export interface BoardList {
  id: number;
  title: string;
  position: number;
  color: null | string;
  archivedAt?: null | string;
  /** Total cards in the list (server side, unfiltered). */
  cardCount: number;
  /** Cards loaded so far (lazy, page by page). */
  cards: Card[];
}

export interface CardPage {
  items: Card[];
  total: number;
  offset: number;
  hasMore: boolean;
}

export type DueFilter = 'day' | 'month' | 'none' | 'overdue' | 'week';

/** Trello-like filters: OR inside a section, AND between sections (applied server side). */
export interface CardFilters {
  q: string;
  noMember: boolean;
  mine: boolean;
  members: number[];
  done: boolean;
  open: boolean;
  due: DueFilter[];
  noLabel: boolean;
  labels: number[];
  /** fieldId → selected option ids ("true" for checkbox fields) */
  cf: Record<number, string[]>;
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
  id: number;
  email: string;
  createdAt: string;
}

export interface Board extends BoardSummary {
  /** Trello import: files still to copy into Taskora (pending or failed) */
  trelloFiles: number;
  lists: BoardList[];
  labels: Label[];
  customFields: CustomField[];
  invitations: Invitation[];
}

export interface AppNotification {
  id: number;
  type: string;
  message: string;
  read: boolean;
  actor: null | User;
  boardId: null | number;
  boardTitle: null | string;
  cardId: null | number;
  createdAt: string;
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

export const PRIORITIES: { value: Priority; label: string; color: string }[] = [
  { value: 'none', label: 'Aucune', color: '#94a3b8' },
  { value: 'low', label: 'Basse', color: '#22c55e' },
  { value: 'medium', label: 'Moyenne', color: '#eab308' },
  { value: 'high', label: 'Haute', color: '#f97316' },
  { value: 'urgent', label: 'Urgente', color: '#ef4444' },
];
