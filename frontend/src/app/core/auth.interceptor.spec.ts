import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';

import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';
import { ToastService } from './toast.service';

const SESSION_TOAST = 'Votre session a expiré, reconnectez-vous.';

// Jeton factice valide une heure, construit en mémoire (en-tête non JSON : rien qui
// ressemble à un JWT réel dans les rapports).
function fakeToken(): string {
  const encode = (value: object) =>
    btoa(JSON.stringify(value)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  const exp = Math.floor(Date.now() / 1000) + 3600;
  return ['en-tete-factice', encode({ exp }), 'signature'].join('.');
}

function setup() {
  const token = fakeToken();
  localStorage.setItem('taskora.token', token);
  localStorage.setItem(
    'taskora.user',
    JSON.stringify({ avatarColor: '#6366f1', email: 'a@example.com', id: 1, name: 'A' }),
  );
  const toast = { show: vi.fn(), success: vi.fn() };
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([authInterceptor])),
      provideHttpClientTesting(),
      provideRouter([]),
      { provide: ToastService, useValue: toast },
    ],
  });
  const router = TestBed.inject(Router);
  const navigate = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
  return {
    auth: TestBed.inject(AuthService),
    client: TestBed.inject(HttpClient),
    http: TestBed.inject(HttpTestingController),
    navigate,
    toast,
    token,
  };
}

describe('authInterceptor', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
  });

  it('SC-IDENT-025 — 401 hors /auth/ : déconnexion, /login, toast de session', () => {
    const { auth, client, http, navigate, toast } = setup();
    let status = 0;
    client.get('/api/boards').subscribe({ error: (e) => (status = e.status) });
    http
      .expectOne('/api/boards')
      .flush(
        { code: 401, message: 'Invalid JWT Token' },
        { status: 401, statusText: 'Unauthorized' },
      );

    expect(status).toBe(401);
    expect(auth.token()).toBeNull();
    expect(localStorage.getItem('taskora.token')).toBeNull();
    expect(localStorage.getItem('taskora.user')).toBeNull();
    expect(navigate).toHaveBeenCalledWith('/login');
    expect(toast.show).toHaveBeenCalledWith(SESSION_TOAST, 'error');
    expect(toast.show).toHaveBeenCalledTimes(1);
  });

  it('SC-IDENT-025 — 401 sur /api/auth/login : ni déconnexion ni toast', () => {
    const { auth, client, http, navigate, toast, token } = setup();
    let status = 0;
    client
      .post('/api/auth/login', { email: 'a@example.com', password: 'mauvais-mdp' })
      .subscribe({ error: (e) => (status = e.status) });
    http
      .expectOne('/api/auth/login')
      .flush(
        { code: 401, message: 'Invalid credentials.' },
        { status: 401, statusText: 'Unauthorized' },
      );

    expect(status).toBe(401);
    expect(auth.token()).toBe(token);
    expect(localStorage.getItem('taskora.token')).toBe(token);
    expect(navigate).not.toHaveBeenCalled();
    expect(toast.show).not.toHaveBeenCalled();
  });

  it('SC-IDENT-025 — en-tête Authorization ajouté hors /auth/, jamais sur /auth/', () => {
    const { client, http, token } = setup();
    client.get('/api/boards').subscribe();
    client.get('/api/me').subscribe();
    client.post('/api/auth/login', {}).subscribe();
    client.post('/api/auth/register', {}).subscribe();

    for (const url of ['/api/boards', '/api/me']) {
      const req = http.expectOne(url);
      expect(req.request.headers.get('Authorization'), url).toBe(`Bearer ${token}`);
      req.flush({});
    }
    for (const url of ['/api/auth/login', '/api/auth/register']) {
      const req = http.expectOne(url);
      expect(req.request.headers.has('Authorization'), url).toBe(false);
      req.flush({});
    }
    http.verify();
  });
});
