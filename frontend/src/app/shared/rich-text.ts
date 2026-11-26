import DOMPurify from 'dompurify';
import { marked } from 'marked';

/** True when the stored text was produced by the rich editor (HTML) rather than legacy text / Markdown. */
export function isHtml(value: string): boolean {
  return /^\s*<(p|h[1-6]|ul|ol|blockquote|pre|div|hr|img|span|strong|em|a|br)[\s>/]/i.test(value);
}

/** Legacy plain text and Markdown (e.g. Trello imports) → HTML. */
export function toHtml(value: string | null | undefined): string {
  const v = (value ?? '').trim();
  if (!v) return '';
  return isHtml(v) ? v : (marked.parse(v, { async: false, gfm: true, breaks: true }) as string);
}

let hooked = false;
function ensureHooks() {
  if (hooked) return;
  hooked = true;
  DOMPurify.addHook('afterSanitizeAttributes', node => {
    if (node.tagName === 'A') {
      node.setAttribute('target', '_blank');
      node.setAttribute('rel', 'noopener noreferrer');
    }
    // The only inputs in rich text are task-list checkboxes: force the type (DOMPurify drops it) and make them read-only
    if (node.tagName === 'INPUT') {
      node.setAttribute('type', 'checkbox');
      node.setAttribute('disabled', '');
      if (node.closest('li[data-checked="true"]')) node.setAttribute('checked', '');
    }
  });
}

/** Anything rendered with innerHTML goes through here (stored data is untrusted). */
export function sanitizeHtml(html: string): string {
  ensureHooks();
  return DOMPurify.sanitize(html, {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ['style', 'form', 'iframe', 'object', 'embed', 'button', 'textarea', 'select', 'svg', 'math'],
    FORBID_ATTR: ['srcset', 'formaction'],
    ADD_ATTR: ['type', 'checked'],
    ALLOWED_URI_REGEXP: /^(?:https?:|mailto:|\/(?!\/))/i,
  });
}

export function renderRich(value: string | null | undefined): string {
  return sanitizeHtml(toHtml(value));
}
