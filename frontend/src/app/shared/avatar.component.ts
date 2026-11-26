import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { User } from '../core/models';

@Component({
  selector: 'tk-avatar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span
    class="av"
    [style.width.px]="size()"
    [style.height.px]="size()"
    [style.font-size.px]="size() * 0.4"
    [style.background]="bg()"
    [attr.title]="user()?.name"
    >{{ initials() }}</span
  >`,
  styles: [
    `
      .av {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        border-radius: 50%;
        color: #fff;
        font-weight: 700;
        letter-spacing: 0.02em;
        user-select: none;
        box-shadow: 0 0 0 2px var(--avatar-ring, #fff);
        flex-shrink: 0;
      }
    `,
  ],
})
export class AvatarComponent {
  user = input<null | Pick<User, 'avatarColor' | 'name'>>(null);
  size = input(32);
  initials = computed(() => {
    const parts = (this.user()?.name ?? '?').trim().split(/\s+/);
    return (
      (parts[0]?.[0] ?? '') + (parts.length > 1 ? parts[parts.length - 1][0] : '')
    ).toUpperCase();
  });
  bg = computed(() => {
    const c = this.user()?.avatarColor ?? '#6366f1';
    return `linear-gradient(135deg, ${c}, color-mix(in srgb, ${c} 60%, #000))`;
  });
}
