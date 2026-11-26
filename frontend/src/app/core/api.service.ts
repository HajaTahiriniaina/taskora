import { HttpClient, HttpEvent, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  Activity, AppNotification, Attachment, Board, BoardList, BoardSummary, Card, CardDetail, CardFilters, CardPage, ChecklistItem, EMPTY_FILTERS,
  Comment, CustomField, Label, Member, User,
} from './models';

export interface TrelloImportReport {
  lists: number; cards: number; labels: number; comments: number; commentsInTrello: number; attachments: number;
  assignments: number; skippedArchivedLists: number; skippedArchivedCards: number;
  membersMatched: string[]; membersUnmatched: string[]; nextCardNumber: number;
  /** Files uploaded on Trello, to copy into Taskora */
  files: number; filesBytes: number; filesMalicious: number; cardLinks: number;
}

export interface PushDevice { id: number; kind: string; device: string | null; endpointHash: string; createdAt: string; lastSuccessAt: string | null; }

export interface TrashInfo { deletedAt: string; deletedBy: string | null; purgeAt: string; }
export interface TrashedBoard extends TrashInfo { id: number; title: string; background: string; }
export interface BoardTrash {
  retentionDays: number;
  lists: (BoardList & TrashInfo)[];
  cards: ({ id: number; number: number; title: string; listId: number; listTitle: string; listDeleted: boolean; archived: boolean } & TrashInfo)[];
}

export interface TrelloCredentials { key: string; token: string; }
export interface TrelloFilesState {
  pending: number; done: number; failed: number; total: number; pendingBytes: number;
  failures: { id: number; name: string; error: string; cardId: number }[];
}


/** Thin typed wrapper over the Taskora REST API. */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private http = inject(HttpClient);
  private base = '/api';

  // Auth
  login(email: string, password: string) {
    return this.http.post<{ token: string; user: User }>(`${this.base}/auth/login`, { email, password });
  }
  register(name: string, email: string, password: string) {
    return this.http.post<{ token: string; user: User }>(`${this.base}/auth/register`, { name, email, password });
  }
  me() { return this.http.get<User>(`${this.base}/me`); }
  updateMe(data: Partial<User> & { currentPassword?: string; newPassword?: string }) {
    return this.http.patch<User>(`${this.base}/me`, data);
  }

  // Trello files (import): the key / token travel with each request and are never stored server side
  checkTrello(c: TrelloCredentials) { return this.http.post<{ fullName: string; username: string }>(`${this.base}/import/trello/check`, c); }
  trelloFiles(boardId: number) { return this.http.get<TrelloFilesState>(`${this.base}/boards/${boardId}/trello-files`); }
  transferTrelloFiles(boardId: number, c: TrelloCredentials) {
    return this.http.post<TrelloFilesState & { transferred: { name: string; cardId: number }[]; errors: { name: string; error: string }[]; retryAfter: number | null }>(`${this.base}/boards/${boardId}/trello-files/transfer`, c);
  }
  retryTrelloFiles(boardId: number) { return this.http.post<TrelloFilesState>(`${this.base}/boards/${boardId}/trello-files/retry`, {}); }
  abandonTrelloFiles(boardId: number) { return this.http.delete<TrelloFilesState>(`${this.base}/boards/${boardId}/trello-files`); }

  // Browser notifications (Web Push)
  pushConfig() {
    return this.http.get<{ configured: boolean; publicKey: string; devices: PushDevice[] }>(`${this.base}/push`);
  }
  pushSubscribe(sub: PushSubscriptionJSON, device: string) { return this.http.post<{ id: number }>(`${this.base}/push/subscriptions`, { ...sub, device }); }
  pushUnsubscribe(endpoint: string) { return this.http.delete<void>(`${this.base}/push/subscriptions`, { body: { endpoint } }); }
  pushRemoveDevice(id: number) { return this.http.delete<void>(`${this.base}/push/devices/${id}`); }
  pushTest(endpoint: string) { return this.http.post<{ sent: boolean }>(`${this.base}/push/test`, { endpoint }); }

  // Uploads (images in rich text)
  uploadImage(file: File) { return this.uploadFile(file); }
  /** Any file inserted in rich text (images are displayed, other files become links). */
  uploadFile(file: File) {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<{ url: string; name: string; mime: string; size: number; image: boolean }>(`${this.base}/uploads`, form);
  }

  /** Same as uploadFile, with progress events. */
  uploadFileEvents(file: File) {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<{ url: string; name: string; mime: string; size: number; image: boolean }>(`${this.base}/uploads`, form, { reportProgress: true, observe: 'events' });
  }

  // Attachments
  /** Upload with progress events (HttpEventType.UploadProgress / Response). */
  addAttachment(cardId: number, file: File): Observable<HttpEvent<Attachment>> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<Attachment>(`${this.base}/cards/${cardId}/attachments`, form, { reportProgress: true, observe: 'events' });
  }
  renameAttachment(id: number, name: string) { return this.http.patch<Attachment>(`${this.base}/attachments/${id}`, { name }); }
  /** Raw bytes for the in-page viewers (through the API: same origin, no S3 CORS needed). */
  attachmentContent(id: number) { return this.http.get(`${this.base}/attachments/${id}/content`, { responseType: 'arraybuffer' }); }
  /** PDF rendering of an Office / LibreOffice document (converted server side on first call). */
  attachmentPreview(id: number) { return this.http.get<{ url: string }>(`${this.base}/attachments/${id}/preview`); }
  deleteAttachment(id: number) { return this.http.delete<void>(`${this.base}/attachments/${id}`); }

  // Import
  importTrello(file: File) {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<{ boardId: number; report: TrelloImportReport }>(`${this.base}/import/trello`, form);
  }

  // Boards
  boards() { return this.http.get<BoardSummary[]>(`${this.base}/boards`); }
  board(id: number) { return this.http.get<Board>(`${this.base}/boards/${id}`); }
  createBoard(data: { title: string; description?: string; background: string }) {
    return this.http.post<Board>(`${this.base}/boards`, data);
  }
  updateBoard(id: number, data: Partial<BoardSummary>) { return this.http.patch<BoardSummary>(`${this.base}/boards/${id}`, data); }
  deleteBoard(id: number) { return this.http.delete<void>(`${this.base}/boards/${id}`); }
  toggleFavorite(id: number) { return this.http.post<{ favorite: boolean }>(`${this.base}/boards/${id}/favorite`, {}); }

  // Members
  addMember(boardId: number, email: string, role: string) {
    return this.http.post<{ status: 'added' | 'invited'; member?: Member; message?: string }>(`${this.base}/boards/${boardId}/members`, { email, role });
  }
  updateMember(boardId: number, userId: number, role: string) {
    return this.http.patch<Member>(`${this.base}/boards/${boardId}/members/${userId}`, { role });
  }
  removeMember(boardId: number, userId: number) { return this.http.delete<void>(`${this.base}/boards/${boardId}/members/${userId}`); }
  cancelInvitation(boardId: number, invId: number) { return this.http.delete<void>(`${this.base}/boards/${boardId}/invitations/${invId}`); }

  // Lists
  addList(boardId: number, title: string) { return this.http.post<BoardList>(`${this.base}/boards/${boardId}/lists`, { title }); }
  updateList(id: number, data: Partial<BoardList>) { return this.http.patch<BoardList>(`${this.base}/lists/${id}`, data); }
  archiveList(id: number) { return this.http.post<BoardList>(`${this.base}/lists/${id}/archive`, {}); }
  restoreList(id: number) { return this.http.post<BoardList>(`${this.base}/lists/${id}/restore`, {}); }
  archivedLists(boardId: number) { return this.http.get<BoardList[]>(`${this.base}/boards/${boardId}/archived-lists`); }
  deleteList(id: number) { return this.http.delete<void>(`${this.base}/lists/${id}`); }
  reorderLists(boardId: number, ids: number[]) { return this.http.post(`${this.base}/boards/${boardId}/lists/reorder`, { ids }); }

  // Cards (lazy loading)
  /** First page of several lists in a single request. */
  boardCards(boardId: number, listIds: number[], limit: number, f: CardFilters = EMPTY_FILTERS) {
    return this.http.get<Record<string, CardPage>>(`${this.base}/boards/${boardId}/cards`, {
      params: this.filterParams(f).set('lists', listIds.join(',')).set('limit', limit),
    });
  }
  listCards(listId: number, offset: number, limit: number, f: CardFilters = EMPTY_FILTERS) {
    return this.http.get<CardPage>(`${this.base}/lists/${listId}/cards`, {
      params: this.filterParams(f).set('offset', offset).set('limit', limit),
    });
  }
  private filterParams(f: CardFilters) {
    let p = new HttpParams();
    if (f.q.trim()) p = p.set('q', f.q.trim());
    if (f.mine) p = p.set('mine', 1);
    if (f.noMember) p = p.set('noMember', 1);
    if (f.members.length) p = p.set('members', f.members.join(','));
    if (f.noLabel) p = p.set('noLabel', 1);
    if (f.labels.length) p = p.set('labels', f.labels.join(','));
    if (f.done !== f.open) p = p.set('status', f.done ? 'done' : 'open'); // both or none = no filter
    if (f.due.length) p = p.set('due', f.due.join(','));
    for (const [fieldId, values] of Object.entries(f.cf)) {
      if (values.length) p = p.set(`cf[${fieldId}]`, values.join(','));
    }
    return p;
  }

  // Cards
  addCard(listId: number, title: string) { return this.http.post<Card>(`${this.base}/lists/${listId}/cards`, { title }); }
  card(id: number) { return this.http.get<CardDetail>(`${this.base}/cards/${id}`); }
  updateCard(id: number, data: Record<string, unknown>) { return this.http.patch<CardDetail>(`${this.base}/cards/${id}`, data); }
  archiveCard(id: number) { return this.http.post<CardDetail>(`${this.base}/cards/${id}/archive`, {}); }
  restoreCard(id: number) { return this.http.post<CardDetail>(`${this.base}/cards/${id}/restore`, {}); }
  archivedCards(boardId: number, q = '', offset = 0) {
    return this.http.get<{ items: (Card & { listTitle: string })[]; total: number; hasMore: boolean }>(`${this.base}/boards/${boardId}/archived`, { params: { q, offset } });
  }
  // Trash (30 days)
  boardTrash(boardId: number) { return this.http.get<BoardTrash>(`${this.base}/boards/${boardId}/trash`); }
  restoreTrashCard(id: number) { return this.http.post<CardDetail>(`${this.base}/trash/cards/${id}/restore`, {}); }
  purgeTrashCard(id: number) { return this.http.delete<void>(`${this.base}/trash/cards/${id}`); }
  restoreTrashList(id: number) { return this.http.post<BoardList>(`${this.base}/trash/lists/${id}/restore`, {}); }
  purgeTrashList(id: number) { return this.http.delete<void>(`${this.base}/trash/lists/${id}`); }
  trashBoards() { return this.http.get<TrashedBoard[]>(`${this.base}/trash/boards`); }
  restoreTrashBoard(id: number) { return this.http.post<{ id: number }>(`${this.base}/trash/boards/${id}/restore`, {}); }
  purgeTrashBoard(id: number) { return this.http.delete<void>(`${this.base}/trash/boards/${id}`); }

  deleteCard(id: number) { return this.http.delete<void>(`${this.base}/cards/${id}`); }
  moveCard(id: number, listId: number, position: number) { return this.http.post<{ id: number; listId: number; position: number }>(`${this.base}/cards/${id}/move`, { listId, position }); }
  toggleAssignee(id: number, userId: number) { return this.http.post<CardDetail>(`${this.base}/cards/${id}/assignees`, { userId }); }
  cardActivity(id: number, before?: number) {
    return this.http.get<{ items: Activity[]; hasMore: boolean }>(`${this.base}/cards/${id}/activity`, { params: before ? { before, limit: 30 } : { limit: 30 } });
  }
  addComment(id: number, content: string) { return this.http.post<Comment & { attachments: Attachment[] }>(`${this.base}/cards/${id}/comments`, { content }); }
  deleteComment(id: number) { return this.http.delete<void>(`${this.base}/comments/${id}`); }
  addChecklistItem(cardId: number, content: string) { return this.http.post<ChecklistItem>(`${this.base}/cards/${cardId}/checklist`, { content }); }
  updateChecklistItem(id: number, data: Partial<ChecklistItem>) { return this.http.patch<ChecklistItem>(`${this.base}/checklist/${id}`, data); }
  deleteChecklistItem(id: number) { return this.http.delete<void>(`${this.base}/checklist/${id}`); }
  setCustomValue(cardId: number, fieldId: number, value: unknown) {
    return this.http.put<CardDetail>(`${this.base}/cards/${cardId}/custom-fields/${fieldId}`, { value });
  }

  // Labels
  addLabel(boardId: number, name: string, color: string) { return this.http.post<Label>(`${this.base}/boards/${boardId}/labels`, { name, color }); }
  updateLabel(id: number, data: Partial<Label>) { return this.http.patch<Label>(`${this.base}/labels/${id}`, data); }
  deleteLabel(id: number) { return this.http.delete<void>(`${this.base}/labels/${id}`); }

  // Custom fields
  addCustomField(boardId: number, data: Partial<CustomField>) { return this.http.post<CustomField>(`${this.base}/boards/${boardId}/custom-fields`, data); }
  updateCustomField(id: number, data: Partial<CustomField>) { return this.http.patch<CustomField>(`${this.base}/custom-fields/${id}`, data); }
  deleteCustomField(id: number) { return this.http.delete<void>(`${this.base}/custom-fields/${id}`); }

  // Notifications
  notifications() { return this.http.get<{ unread: number; items: AppNotification[] }>(`${this.base}/notifications`); }
  unreadCount(): Observable<{ unread: number }> { return this.http.get<{ unread: number }>(`${this.base}/notifications/unread-count`); }
  markRead(id: number) { return this.http.post<AppNotification>(`${this.base}/notifications/${id}/read`, {}); }
  markAllRead() { return this.http.post(`${this.base}/notifications/read-all`, {}); }
  deleteNotification(id: number) { return this.http.delete<void>(`${this.base}/notifications/${id}`); }
}
