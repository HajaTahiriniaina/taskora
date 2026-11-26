import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { PushService } from '../../core/push.service';
import { ToastService } from '../../core/toast.service';
import { ProfileComponent } from './profile.component';

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

async function setup() {
  localStorage.setItem(
    'taskora.user',
    JSON.stringify({
      avatarColor: '#6366f1',
      email: 'camille@example.com',
      id: 7,
      name: 'Camille',
    }),
  );
  // PushService simulé : le constructeur du composant appelle push.start() puis loadDevices().
  const push = {
    busy: signal(false),
    currentHash: signal<null | string>(null),
    devices: signal([]),
    loadDevices: vi.fn(),
    start: vi.fn().mockResolvedValue(undefined),
    state: signal('unsupported'),
  };
  const toast = { show: vi.fn(), success: vi.fn() };
  TestBed.configureTestingModule({
    imports: [ProfileComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      { provide: PushService, useValue: push },
      { provide: ToastService, useValue: toast },
    ],
  });
  const fixture = TestBed.createComponent(ProfileComponent);
  await fixture.whenStable();
  const root = fixture.nativeElement as HTMLElement;
  return {
    current: byLabel(root, 'Mot de passe actuel'),
    fixture,
    http: TestBed.inject(HttpTestingController),
    next: byLabel(root, 'Nouveau mot de passe'),
    push,
    root,
    submit: () => buttonByText(root, 'Changer le mot de passe'),
    toast,
  };
}

describe('ProfileComponent — mot de passe', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => {
    // Aucune requête HTTP inattendue ni restée sans réponse (revue m5).
    TestBed.inject(HttpTestingController).verify();
    vi.restoreAllMocks();
    localStorage.clear();
  });

  it('SC-IDENT-018 — bouton « Changer le mot de passe » désactivé sans mot de passe actuel ou avec un nouveau de moins de 8 caractères', async () => {
    const ctx = await setup();
    expect(ctx.push.start).toHaveBeenCalled();
    expect(ctx.submit().disabled, 'champs vides').toBe(true);

    const cases: [string, string, string, boolean][] = [
      ['actuel seul', 'ancienmdp1', '', true],
      ['nouveau de 7 caractères', 'ancienmdp1', 'nouveau', true],
      ['nouveau de 8 sans actuel', '', 'nouveau1', true],
      ['actuel et nouveau de 8', 'ancienmdp1', 'nouveau1', false],
    ];
    for (const [label, current, next, disabled] of cases) {
      await type(ctx.fixture, ctx.current, current);
      await type(ctx.fixture, ctx.next, next);
      expect(ctx.submit().disabled, label).toBe(disabled);
    }
  });

  it('SC-IDENT-017 — succès : PATCH /api/me {currentPassword,newPassword}, champs vidés dans le DOM, toast « Mot de passe modifié »', async () => {
    const ctx = await setup();
    await type(ctx.fixture, ctx.current, 'ancienmdp1');
    await type(ctx.fixture, ctx.next, 'nouveaumdp1');
    ctx.submit().click();
    await ctx.fixture.whenStable();

    const req = ctx.http.expectOne('/api/me');
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ currentPassword: 'ancienmdp1', newPassword: 'nouveaumdp1' });
    req.flush({ avatarColor: '#6366f1', email: 'camille@example.com', id: 7, name: 'Camille' });
    await ctx.fixture.whenStable();

    expect(ctx.toast.success).toHaveBeenCalledWith('Mot de passe modifié');
    // Rendu réel (zoneless) : les champs affichés sont vides et le bouton redevient désactivé.
    expect(ctx.current.value, 'champ « Mot de passe actuel »').toBe('');
    expect(ctx.next.value, 'champ « Nouveau mot de passe »').toBe('');
    expect(ctx.submit().disabled, 'bouton après succès').toBe(true);
  });
});
