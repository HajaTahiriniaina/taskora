import { Card, CustomField } from '../../core/models';

/** Human readable value of a custom field, or null when empty. */
export function formatFieldValue(field: CustomField, value: unknown): { text: string; color?: string } | null {
  if (value === null || value === undefined || value === '') return null;
  switch (field.type) {
    case 'checkbox':
      return value ? { text: '✓ ' + field.name } : null;
    case 'select': {
      const opt = field.options.find(o => o.id === value);
      return opt ? { text: opt.label, color: opt.color } : null;
    }
    case 'date':
      return { text: new Date(value as string).toLocaleDateString('fr-FR', { day: 'numeric', month: 'short' }) };
    case 'url':
      try { return { text: new URL(value as string).hostname.replace(/^www\./, '') }; } catch { return { text: String(value) }; }
    case 'number':
      return { text: Number(value).toLocaleString('fr-FR') };
    default:
      return { text: String(value) };
  }
}

export type DueState = 'done' | 'overdue' | 'soon' | 'later';

export function dueState(card: Pick<Card, 'dueDate' | 'completed'>): DueState | null {
  if (!card.dueDate) return null;
  if (card.completed) return 'done';
  const diff = new Date(card.dueDate).getTime() - Date.now();
  if (diff < 0) return 'overdue';
  if (diff < 1000 * 60 * 60 * 48) return 'soon';
  return 'later';
}

export function formatDue(date: string): string {
  return new Date(date).toLocaleDateString('fr-FR', { day: 'numeric', month: 'short' });
}
