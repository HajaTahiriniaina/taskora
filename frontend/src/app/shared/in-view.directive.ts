import { Directive, ElementRef, OnDestroy, effect, inject, input, output } from '@angular/core';

/**
 * Emits when the host enters its scroll container (IntersectionObserver, no scroll listeners).
 * Changing `tkInViewKey` re-arms the observer: if the host is still visible, it emits again
 * (used to keep loading pages while a sentinel stays on screen).
 */
@Directive({ selector: '[tkInView]' })
export class InViewDirective implements OnDestroy {
  private el = inject<ElementRef<HTMLElement>>(ElementRef);
  private observer: IntersectionObserver | null = null;

  tkInViewRoot = input<HTMLElement | null>(null);
  tkInViewMargin = input('0px');
  tkInViewKey = input<unknown>(null);
  tkInView = output<void>();

  constructor() {
    effect(() => {
      this.tkInViewKey(); // re-arm on key change
      const root = this.tkInViewRoot();
      const margin = this.tkInViewMargin();
      this.observer?.disconnect();
      this.observer = new IntersectionObserver(
        (entries) => {
          if (entries.some((e) => e.isIntersecting)) this.tkInView.emit();
        },
        { root, rootMargin: margin },
      );
      this.observer.observe(this.el.nativeElement);
    });
  }

  ngOnDestroy() {
    this.observer?.disconnect();
  }
}
