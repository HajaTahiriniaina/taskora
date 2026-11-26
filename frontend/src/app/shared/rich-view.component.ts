import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { DomSanitizer } from '@angular/platform-browser';

import { renderRich } from './rich-text';

/** Read-only rendering of rich text (sanitized with DOMPurify, then trusted). */
@Component({
  selector: 'tk-rich-view',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<div class="rich" [innerHTML]="html()"></div>`,
  styles: [':host { display: block; min-width: 0; }'],
})
export class RichViewComponent {
  private sanitizer = inject(DomSanitizer);
  content = input<null | string>('');
  html = computed(() => this.sanitizer.bypassSecurityTrustHtml(renderRich(this.content())));
}
