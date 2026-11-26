import { Component, input } from '@angular/core';

@Component({
  selector: 'tk-logo',
  template: `
    <span class="logo" [class.light]="light()">
      <svg width="30" height="30" viewBox="0 0 32 32" aria-hidden="true">
        <defs>
          <linearGradient id="tkg" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0" stop-color="#8b5cf6" />
            <stop offset=".55" stop-color="#6366f1" />
            <stop offset="1" stop-color="#06b6d4" />
          </linearGradient>
        </defs>
        <rect width="32" height="32" rx="9" fill="url(#tkg)" />
        <rect x="7" y="8" width="5.5" height="16" rx="2.2" fill="#fff" />
        <rect x="14.5" y="8" width="5.5" height="10" rx="2.2" fill="#fff" opacity=".85" />
        <rect x="22" y="8" width="3.5" height="6" rx="1.6" fill="#fff" opacity=".65" />
      </svg>
      <span class="word">Taskora</span>
    </span>
  `,
  styles: [
    `
      .logo {
        display: inline-flex;
        align-items: center;
        gap: 10px;
        font-family: var(--font-display);
        font-weight: 800;
        font-size: 20px;
        letter-spacing: -0.02em;
        color: var(--text);
      }
      .logo.light {
        color: #fff;
      }
    `,
  ],
})
export class LogoComponent {
  light = input(false);
}
