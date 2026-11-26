import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';

import { RegisterComponent } from './register.component';

// Sélection par libellé et par texte visible, comme l'utilisateur (frontend.md §4.3).
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
  TestBed.configureTestingModule({
    imports: [RegisterComponent],
    providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
  });
  const fixture = TestBed.createComponent(RegisterComponent);
  await fixture.whenStable();
  const root = fixture.nativeElement as HTMLElement;
  const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
  return { fixture, http: TestBed.inject(HttpTestingController), navigate, root };
}

async function fillForm(
  ctx: Awaited<ReturnType<typeof setup>>,
  values: { email: string; name: string; password: string },
) {
  await type(ctx.fixture, byLabel(ctx.root, 'Nom complet'), values.name);
  await type(ctx.fixture, byLabel(ctx.root, 'Adresse e-mail'), values.email);
  await type(ctx.fixture, byLabel(ctx.root, 'Mot de passe'), values.password);
}

const submitButton = (root: HTMLElement) => buttonByText(root, 'Créer mon compte');
const errorText = (root: HTMLElement) => root.querySelector('.error')?.textContent?.trim() ?? null;

const VALID = { email: 'camille@example.com', name: 'Camille', password: 'motdepas' };

describe('RegisterComponent', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => {
    // Aucune requête HTTP inattendue ni restée sans réponse (revue m5).
    TestBed.inject(HttpTestingController).verify();
    vi.restoreAllMocks();
    localStorage.clear();
  });

  it('SC-IDENT-003 — bouton désactivé : champ vide, e-mail invalide, mot de passe de 7 caractères (dont « ééééééé ») ; activé à 8', async () => {
    const ctx = await setup();
    expect(submitButton(ctx.root).disabled, 'formulaire vide').toBe(true);

    const disabledCases: [string, typeof VALID][] = [
      ['nom vide', { ...VALID, name: '' }],
      ['e-mail vide', { ...VALID, email: '' }],
      ['e-mail invalide', { ...VALID, email: 'pas-un-email' }],
      ['mot de passe vide', { ...VALID, password: '' }],
      ['mot de passe de 7 caractères', { ...VALID, password: 'motdepa' }],
      ['« ééééééé » (7 caractères)', { ...VALID, password: 'ééééééé' }],
    ];
    for (const [label, values] of disabledCases) {
      await fillForm(ctx, values);
      expect(submitButton(ctx.root).disabled, label).toBe(true);
    }

    await fillForm(ctx, VALID);
    expect(submitButton(ctx.root).disabled, '8 caractères').toBe(false);
    await fillForm(ctx, { ...VALID, password: 'éééééééé' });
    expect(submitButton(ctx.root).disabled, '« éééééééé » (8 caractères)').toBe(false);
  });

  it('SC-IDENT-001 — succès : navigation vers /boards', async () => {
    const ctx = await setup();
    await fillForm(ctx, VALID);
    submitButton(ctx.root).click();
    await ctx.fixture.whenStable();

    const req = ctx.http.expectOne('/api/auth/register');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(VALID);
    req.flush(
      {
        token: 'h.p.s',
        user: { avatarColor: '#6366f1', email: VALID.email, id: 1, name: VALID.name },
      },
      { status: 201, statusText: 'Created' },
    );
    await ctx.fixture.whenStable();

    expect(ctx.navigate).toHaveBeenCalledWith('/boards');
    expect(errorText(ctx.root)).toBeNull();
  });

  it("SC-IDENT-002 — 409 : message de l'API affiché ; repli « Inscription impossible pour le moment. »", async () => {
    const ctx = await setup();
    await fillForm(ctx, VALID);

    submitButton(ctx.root).click();
    await ctx.fixture.whenStable();
    ctx.http
      .expectOne('/api/auth/register')
      .flush(
        { error: 'Un compte existe déjà avec cette adresse e-mail.' },
        { status: 409, statusText: 'Conflict' },
      );
    await ctx.fixture.whenStable();
    expect(errorText(ctx.root)).toBe('Un compte existe déjà avec cette adresse e-mail.');
    expect(ctx.navigate).not.toHaveBeenCalled();
    expect(submitButton(ctx.root).disabled, 'bouton réactivé après l’erreur').toBe(false);

    submitButton(ctx.root).click();
    await ctx.fixture.whenStable();
    ctx.http
      .expectOne('/api/auth/register')
      .flush(null, { status: 500, statusText: 'Internal Server Error' });
    await ctx.fixture.whenStable();
    expect(errorText(ctx.root)).toBe('Inscription impossible pour le moment.');
    expect(ctx.navigate).not.toHaveBeenCalled();
  });
});
