import { Activity, PRIORITIES } from '../../core/models';

/** A sentence piece: plain text, bold value, or colored label chip. Rendered with interpolation only (no innerHTML). */
export interface Seg {
  t: string;
  b?: boolean;
  color?: string;
}

const fmtDate = (iso: string) =>
  new Date(iso).toLocaleString('fr-FR', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });

/** French sentence (after the actor's name) describing an activity. */
export function describeActivity(a: Activity): Seg[] {
  const d = a.data as Record<string, null | string | undefined>;
  const v = (x: unknown) => String(x ?? '');
  const self = (name: unknown) => v(name) === a.actor.name;
  switch (a.type) {
    case 'created':
      return [{ t: 'a ajouté cette carte à ' }, { t: v(d['list']), b: true }];
    case 'renamed':
      return [{ t: 'a renommé cette carte en ' }, { t: `« ${v(d['to'])} »`, b: true }];
    case 'description':
      return [{ t: 'a modifié la description' }];
    case 'moved':
      return [
        { t: 'a déplacé cette carte de ' },
        { t: v(d['from']), b: true },
        { t: ' vers ' },
        { t: v(d['to']), b: true },
      ];
    case 'completed':
      return [{ t: 'a marqué cette carte comme terminée' }];
    case 'reopened':
      return [{ t: 'a marqué cette carte comme non terminée' }];
    case 'due_set':
      return [
        { t: 'a fixé la date limite au ' },
        { t: d['due'] ? fmtDate(v(d['due'])) : '', b: true },
      ];
    case 'due_removed':
      return [{ t: 'a retiré la date limite' }];
    case 'priority':
      return [
        { t: 'a défini la priorité sur ' },
        { t: PRIORITIES.find((p) => p.value === d['to'])?.label ?? v(d['to']), b: true },
      ];
    case 'cover_set':
      return [{ t: 'a changé la couverture' }];
    case 'cover_removed':
      return [{ t: 'a retiré la couverture' }];
    case 'label_added':
      return [{ t: 'a ajouté l’étiquette ' }, { t: v(d['name']) || ' ', color: v(d['color']) }];
    case 'label_removed':
      return [{ t: 'a retiré l’étiquette ' }, { t: v(d['name']) || ' ', color: v(d['color']) }];
    case 'member_added':
      return self(d['name'])
        ? [{ t: 'a rejoint cette carte' }]
        : [{ t: 'a ajouté ' }, { t: v(d['name']), b: true }, { t: ' à cette carte' }];
    case 'member_removed':
      return self(d['name'])
        ? [{ t: 'a quitté cette carte' }]
        : [{ t: 'a retiré ' }, { t: v(d['name']), b: true }, { t: ' de cette carte' }];
    case 'checklist_added':
      return [{ t: 'a ajouté ' }, { t: v(d['item']), b: true }, { t: ' à la checklist' }];
    case 'checklist_done':
      return [{ t: 'a terminé ' }, { t: v(d['item']), b: true }];
    case 'checklist_undone':
      return [{ t: 'a marqué ' }, { t: v(d['item']), b: true }, { t: ' comme non terminé' }];
    case 'checklist_removed':
      return [{ t: 'a supprimé ' }, { t: v(d['item']), b: true }, { t: ' de la checklist' }];
    case 'field_set':
      return [
        { t: 'a défini ' },
        { t: v(d['field']), b: true },
        { t: ' sur ' },
        { t: v(d['value']), b: true },
      ];
    case 'field_cleared':
      return [{ t: 'a vidé le champ ' }, { t: v(d['field']), b: true }];
    case 'attachment':
      return [{ t: 'a joint ' }, { t: v(d['name']), b: true }, { t: ' à cette carte' }];
    case 'archived':
      return [{ t: 'a archivé cette carte' }];
    case 'unarchived':
      return [{ t: 'a restauré cette carte dans ' }, { t: v(d['list']), b: true }];
    case 'restored':
      return [{ t: 'a restauré cette carte depuis la corbeille' }];
    case 'attachment_removed':
      return [{ t: 'a retiré la pièce jointe ' }, { t: v(d['name']), b: true }];
    default:
      return [{ t: 'a modifié cette carte' }];
  }
}
