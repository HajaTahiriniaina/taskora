import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';

import { LoginComponent } from './login.component';

function byLabel(root: HTMLElement, text: string): HTMLInputElement {
  const label = Array.from(root.querySelectorAll('label')).find(
    (l) => l.textContent?.trim() === text,
  );
  if (!label) throw new Error(`libellé introuvable : ${text}`);
  return root.querySelector<HTMLInputElement>(`#${label.htmlFor}`)!;
}

function buttonByText(root: HTMLElement, text: string): HTMLButtonElement {
  const button = Array.from(root.querySelectorAll('button')).find(
    (b) => b.textContent?.trim() === text,
  );
  if (!button) throw new Error(`bouton introuvable : ${text}`);
  return button;
}

async function type(fixture: ComponentFixture<unknown>, input: HTMLInputElement, value: string) {
  input.value = value;
  input.dispatchEvent(new Event('input'));
  await fixture.whenStable();
}

async function setupAndSubmit() {
  TestBed.configureTestingModule({
    imports: [LoginComponent],
    providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
  });
  const fixture = TestBed.createComponent(LoginComponent);
  await fixture.whenStable();
  const root = fixture.nativeElement as HTMLElement;
  const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
  await type(fixture, byLabel(root, 'Adresse e-mail'), 'Camille@Example.com');
  await type(fixture, byLabel(root, 'Mot de passe'), 'motdepasse');
  const http = TestBed.inject(HttpTestingController);
  const submit = async () => {
    buttonByText(root, 'Se connecter').click();
    await fixture.whenStable();
    return http.expectOne('/api/auth/login');
  };
  return { fixture, navigate, root, submit };
}

const errorText = (root: HTMLElement) => root.querySelector('.error')?.textContent?.trim() ?? null;

describe('LoginComponent', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
  });

  it('SC-IDENT-008 — 401 : « E-mail ou mot de passe incorrect. » ; autre erreur : « Impossible de se connecter pour le moment. »', async () => {
    const { fixture, navigate, root, submit } = await setupAndSubmit();

    (await submit()).flush(
      { code: 401, message: 'Invalid credentials.' },
      { status: 401, statusText: 'Unauthorized' },
    );
    await fixture.whenStable();
    expect(errorText(root)).toBe('E-mail ou mot de passe incorrect.');

    (await submit()).flush(
      { error: 'Erreur interne du serveur.' },
      { status: 500, statusText: 'Internal Server Error' },
    );
    await fixture.whenStable();
    expect(errorText(root)).toBe('Impossible de se connecter pour le moment.');

    (await submit()).error(new ProgressEvent('error'), { status: 0, statusText: 'Unknown Error' });
    await fixture.whenStable();
    expect(errorText(root)).toBe('Impossible de se connecter pour le moment.');

    expect(navigate).not.toHaveBeenCalled();
    expect(localStorage.getItem('taskora.token')).toBeNull();
  });

  it('SC-IDENT-007 — succès : navigation vers /boards', async () => {
    const { fixture, navigate, root, submit } = await setupAndSubmit();
    const req = await submit();
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ email: 'Camille@Example.com', password: 'motdepasse' });
    req.flush({
      token: 'h.p.s',
      user: { avatarColor: '#6366f1', email: 'camille@example.com', id: 1, name: 'Camille' },
    });
    await fixture.whenStable();

    expect(navigate).toHaveBeenCalledWith('/boards');
    expect(errorText(root)).toBeNull();
  });
});
