import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';

import { AuthService } from './auth.service';
import { User } from './models';

// Jeton factice construit en mémoire : seule la charge utile {exp} compte pour le front.
// En-tête volontairement non JSON : rien qui ressemble à un JWT réel dans les rapports.
function fakeToken(expSeconds: number): string {
  const encode = (value: object) =>
    btoa(JSON.stringify(value)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return ['en-tete-factice', encode({ exp: expSeconds }), 'signature'].join('.');
}

const nowSeconds = () => Math.floor(Date.now() / 1000);
const USER: User = { avatarColor: '#6366f1', email: 'camille@example.com', id: 7, name: 'Camille' };

function setup(): { auth: AuthService; http: HttpTestingController } {
  TestBed.configureTestingModule({
    providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
  });
  // logout() navigue vers /login : la navigation est neutralisée (aucune route déclarée ici).
  vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
  return { auth: TestBed.inject(AuthService), http: TestBed.inject(HttpTestingController) };
}

describe('AuthService', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
  });

  it('SC-IDENT-026 — isLoggedIn : faux si jeton absent, expiré ou illisible ; vrai si exp futur', () => {
    const cases: [string, null | string, boolean][] = [
      ['absent', null, false],
      ['expiré', fakeToken(nowSeconds() - 60), false],
      ['illisible', 'pas-un-jeton', false],
      ['charge utile non JSON', 'a.b.c', false],
      ['exp futur', fakeToken(nowSeconds() + 3600), true],
    ];
    for (const [label, token, expected] of cases) {
      TestBed.resetTestingModule();
      localStorage.clear();
      if (token !== null) localStorage.setItem('taskora.token', token);
      const { auth } = setup();
      expect(auth.isLoggedIn(), label).toBe(expected);
    }
  });

  it('SC-IDENT-026 — localStorage indisponible : aucune exception, utilisateur non connecté', () => {
    const denied = () => {
      throw new DOMException('denied', 'SecurityError');
    };
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(denied);
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(denied);
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(denied);

    let ctx!: ReturnType<typeof setup>;
    expect(() => (ctx = setup())).not.toThrow();
    expect(ctx.auth.isLoggedIn()).toBe(false);
    expect(ctx.auth.user()).toBeNull();

    let failed = false;
    ctx.auth.login(USER.email, 'motdepasse').subscribe({ error: () => (failed = true) });
    ctx.http
      .expectOne('/api/auth/login')
      .flush({ token: fakeToken(nowSeconds() + 3600), user: USER });
    expect(failed).toBe(false);
    expect(() => ctx.auth.logout()).not.toThrow();
    expect(ctx.auth.isLoggedIn()).toBe(false);
    ctx.http.verify();
  });

  it('SC-IDENT-007 — login stocke taskora.token et taskora.user', () => {
    const { auth, http } = setup();
    const token = fakeToken(nowSeconds() + 3600);
    auth.login('Camille@Example.com', 'motdepasse').subscribe();

    const req = http.expectOne('/api/auth/login');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ email: 'Camille@Example.com', password: 'motdepasse' });
    req.flush({ token, user: USER });

    expect(localStorage.getItem('taskora.token')).toBe(token);
    expect(JSON.parse(localStorage.getItem('taskora.user') ?? 'null')).toEqual(USER);
    expect(auth.token()).toBe(token);
    expect(auth.user()).toEqual(USER);
    expect(auth.isLoggedIn()).toBe(true);
    http.verify();
  });

  it('SC-IDENT-001 — register stocke jeton et profil', () => {
    const { auth, http } = setup();
    const token = fakeToken(nowSeconds() + 3600);
    auth.register('Camille', 'camille@example.com', 'motdepasse').subscribe();

    const req = http.expectOne('/api/auth/register');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      email: 'camille@example.com',
      name: 'Camille',
      password: 'motdepasse',
    });
    req.flush({ token, user: USER }, { status: 201, statusText: 'Created' });

    expect(localStorage.getItem('taskora.token')).toBe(token);
    expect(JSON.parse(localStorage.getItem('taskora.user') ?? 'null')).toEqual(USER);
    expect(auth.user()).toEqual(USER);
    expect(auth.isLoggedIn()).toBe(true);
    http.verify();
  });
});
