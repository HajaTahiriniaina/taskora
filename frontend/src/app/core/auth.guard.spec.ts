import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import {
  ActivatedRouteSnapshot,
  CanActivateFn,
  provideRouter,
  Router,
  RouterStateSnapshot,
  UrlTree,
} from '@angular/router';

import { authGuard, guestGuard } from './auth.guard';
import { AuthService } from './auth.service';

function run(guard: CanActivateFn, loggedIn: boolean): boolean | UrlTree {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      { provide: AuthService, useValue: { isLoggedIn: signal(loggedIn) } },
    ],
  });
  return TestBed.runInInjectionContext(
    () =>
      guard({} as ActivatedRouteSnapshot, { url: '/x' } as RouterStateSnapshot) as
        boolean | UrlTree,
  );
}

function serialized(result: boolean | UrlTree): boolean | string {
  return result instanceof UrlTree ? TestBed.inject(Router).serializeUrl(result) : result;
}

describe('auth.guard', () => {
  it('SC-IDENT-026 — authGuard : UrlTree /login sans session valide, true sinon', () => {
    const anonymous = run(authGuard, false);
    expect(anonymous).toBeInstanceOf(UrlTree);
    expect(serialized(anonymous)).toBe('/login');

    expect(run(authGuard, true)).toBe(true);
  });

  it('SC-IDENT-026 — guestGuard : UrlTree /boards avec session valide, true sinon', () => {
    const connected = run(guestGuard, true);
    expect(connected).toBeInstanceOf(UrlTree);
    expect(serialized(connected)).toBe('/boards');

    expect(run(guestGuard, false)).toBe(true);
  });
});
