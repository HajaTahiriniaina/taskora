import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { authInterceptor } from '../../core/auth.interceptor';
import { AuthService } from '../../core/auth.service';
import { User } from '../../core/models';
import { PushService } from '../../core/push.service';
import { ToastService } from '../../core/toast.service';
import { ProfileComponent } from './profile.component';

const CAMILLE: User = {
  avatarColor: '#6366f1',
  email: 'camille@example.com',
  id: 7,
  name: 'Camille',
};

/** Format strict de identity-service (décision E18) : exactement #RRGGBB. */
const STRICT_COLOR = /^#[0-9a-fA-F]{6}$/;

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

interface SetupOptions {
  /** Intercepteur réel : erreurs HTTP affichées en toast (pages.md §7, « erreur 400 en toast »). */
  interceptor?: boolean;
  user?: User;
}

async function setup(options: SetupOptions = {}) {
  localStorage.setItem('taskora.user', JSON.stringify(options.user ?? CAMILLE));
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
      options.interceptor
        ? provideHttpClient(withInterceptors([authInterceptor]))
        : provideHttpClient(),
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
    auth: TestBed.inject(AuthService),
    colors: () => Array.from(root.querySelectorAll<HTMLButtonElement>('button[aria-label^="#"]')),
    current: byLabel(root, 'Mot de passe actuel'),
    fixture,
    heading: () => root.querySelector('h1')?.textContent?.trim(),
    http: TestBed.inject(HttpTestingController),
    name: byLabel(root, 'Nom complet'),
    next: byLabel(root, 'Nouveau mot de passe'),
    push,
    root,
    save: () => buttonByText(root, 'Enregistrer'),
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

type Ctx = Awaited<ReturnType<typeof setup>>;

function colorButton(ctx: Ctx, color: string): HTMLButtonElement {
  const button = ctx.colors().find((b) => b.getAttribute('aria-label') === color);
  if (!button) throw new Error(`couleur introuvable : ${color}`);
  return button;
}

/** Couleurs marquées comme sélectionnées (coche affichée dans la pastille). */
function selectedColors(ctx: Ctx): (null | string)[] {
  return ctx
    .colors()
    .filter((b) => b.querySelector('tk-icon') !== null)
    .map((b) => b.getAttribute('aria-label'));
}

async function saveProfile(ctx: Ctx) {
  ctx.save().click();
  await ctx.fixture.whenStable();
  const req = ctx.http.expectOne('/api/me');
  expect(req.request.method).toBe('PATCH');
  return req;
}

describe('ProfileComponent — informations (nom, couleur)', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => {
    // Un module par test : verify() en afterEach (décision m5bis).
    TestBed.inject(HttpTestingController).verify();
    vi.restoreAllMocks();
    localStorage.clear();
  });

  it('SC-IDENT-014 — affichage du profil stocké : nom dans « Nom complet » et le titre, e-mail, couleur courante sélectionnée', async () => {
    const ctx = await setup();
    expect(ctx.name.value).toBe('Camille');
    expect(ctx.heading()).toBe('Camille');
    expect(ctx.root.textContent).toContain('camille@example.com');
    expect(selectedColors(ctx)).toEqual(['#6366f1']);
  });

  it("SC-IDENT-014 — profil rafraîchi par GET /api/me après l'ouverture de la page : le formulaire affiche le nom et la couleur reçus", async () => {
    // La coquille appelle refreshProfile() dans ngOnInit ; la réponse arrive après la création de
    // la page Profil. Le formulaire ne doit pas garder le profil périmé de localStorage : sinon
    // « Enregistrer » réécrirait l'ancien nom.
    const ctx = await setup();
    ctx.auth.refreshProfile();
    ctx.http
      .expectOne({ method: 'GET', url: '/api/me' })
      .flush({ ...CAMILLE, avatarColor: '#0ea5e9', name: 'Camille Martin' });
    await ctx.fixture.whenStable();

    expect(ctx.root.textContent, 'e-mail').toContain('camille@example.com');
    expect(ctx.name.value, 'champ « Nom complet »').toBe('Camille Martin');
    expect(ctx.heading(), 'titre').toBe('Camille Martin');
    expect(selectedColors(ctx), 'couleur sélectionnée').toEqual(['#0ea5e9']);
  });

  it('SC-IDENT-015 — renommage : PATCH /api/me {name, avatarColor}, enregistrement en cours, profil stocké, toast « Profil mis à jour »', async () => {
    const ctx = await setup();
    await type(ctx.fixture, ctx.name, '  Camille M. ');
    expect(ctx.save().disabled, 'avant envoi').toBe(false);

    const req = await saveProfile(ctx);
    expect(ctx.save().disabled, 'enregistrement en cours').toBe(true);
    expect(Object.keys(req.request.body).sort()).toEqual(['avatarColor', 'name']);
    // Le trim est fait par l'API (identity.md §1) ; le front peut l'anticiper.
    expect(req.request.body.name.trim()).toBe('Camille M.');
    expect(req.request.body.avatarColor, 'couleur inchangée').toBe('#6366f1');
    req.flush({ ...CAMILLE, name: 'Camille M.' });
    await ctx.fixture.whenStable();

    expect(ctx.auth.user()).toEqual({ ...CAMILLE, name: 'Camille M.' });
    expect(JSON.parse(localStorage.getItem('taskora.user') ?? 'null')).toEqual({
      ...CAMILLE,
      name: 'Camille M.',
    });
    expect(ctx.toast.success).toHaveBeenCalledWith('Profil mis à jour');
    expect(ctx.save().disabled, 'après succès').toBe(false);
  });

  it("SC-IDENT-015 — après 200, le champ « Nom complet » et le titre affichent le nom renvoyé par l'API (trim)", async () => {
    const ctx = await setup();
    await type(ctx.fixture, ctx.name, '  Camille M. ');
    (await saveProfile(ctx)).flush({ ...CAMILLE, name: 'Camille M.' });
    await ctx.fixture.whenStable();

    expect(ctx.name.value, 'champ « Nom complet »').toBe('Camille M.');
    expect(ctx.heading(), 'titre').toBe('Camille M.');
  });

  it.each(['', '   '])(
    'SC-IDENT-015 — nom vide après trim (%j) : 400 « Le champ "name" est obligatoire. » en toast, profil inchangé',
    async (empty) => {
      const ctx = await setup({ interceptor: true });
      await type(ctx.fixture, ctx.name, empty);

      const req = await saveProfile(ctx);
      expect(req.request.body.name.trim()).toBe('');
      req.flush(
        { error: 'Le champ "name" est obligatoire.' },
        { status: 400, statusText: 'Bad Request' },
      );
      await ctx.fixture.whenStable();

      expect(ctx.toast.show).toHaveBeenCalledWith('Le champ "name" est obligatoire.', 'error');
      expect(ctx.toast.success).not.toHaveBeenCalled();
      expect(ctx.auth.user(), 'profil en mémoire').toEqual(CAMILLE);
      expect(JSON.parse(localStorage.getItem('taskora.user') ?? 'null')).toEqual(CAMILLE);
      expect(ctx.auth.token(), 'pas de déconnexion').toBeNull();
      expect(ctx.save().disabled, 'bouton réactivé').toBe(false);
    },
  );

  it('SC-IDENT-015 — jamais de null envoyé : name et avatarColor sont des chaînes (null = « inchangé » côté API)', async () => {
    const ctx = await setup();
    const req = await saveProfile(ctx);
    expect(typeof req.request.body.name).toBe('string');
    expect(typeof req.request.body.avatarColor).toBe('string');
    expect(req.request.body).toEqual({ avatarColor: '#6366f1', name: 'Camille' });
    req.flush(CAMILLE);
    await ctx.fixture.whenStable();
    expect(ctx.auth.user()).toEqual(CAMILLE);
  });

  it('SC-IDENT-015 — borne 120 : champ limité par maxlength=120 ; un nom de 120 caractères est envoyé et stocké entier', async () => {
    const ctx = await setup();
    expect(ctx.name.getAttribute('maxlength')).toBe('120');
    const name120 = 'N'.repeat(120);
    await type(ctx.fixture, ctx.name, name120);

    const req = await saveProfile(ctx);
    expect(req.request.body.name).toBe(name120);
    req.flush({ ...CAMILLE, name: name120 });
    await ctx.fixture.whenStable();
    expect(ctx.auth.user()?.name).toBe(name120);
  });

  it("SC-IDENT-015 — 150 caractères : le nom tronqué à 120 par l'API est celui qui est stocké", async () => {
    const ctx = await setup();
    const name150 = 'L'.repeat(150);
    await type(ctx.fixture, ctx.name, name150);

    (await saveProfile(ctx)).flush({ ...CAMILLE, name: name150.slice(0, 120) });
    await ctx.fixture.whenStable();

    expect(ctx.auth.user()?.name).toHaveLength(120);
    expect(JSON.parse(localStorage.getItem('taskora.user') ?? 'null').name).toBe(
      name150.slice(0, 120),
    );
  });

  it('SC-IDENT-016 — palette : pastilles distinctes, toutes au format strict #RRGGBB (E18), une seule sélectionnée', async () => {
    const ctx = await setup();
    const labels = ctx.colors().map((b) => b.getAttribute('aria-label') ?? '');
    expect(labels.length).toBeGreaterThan(1);
    expect(new Set(labels).size, 'pas de doublon').toBe(labels.length);
    for (const label of labels) expect(label, label).toMatch(STRICT_COLOR);
    expect(selectedColors(ctx)).toEqual(['#6366f1']);
  });

  it('SC-IDENT-016 — couleur choisie dans la palette : envoyée et stockée telle quelle, nom inchangé', async () => {
    const ctx = await setup();
    colorButton(ctx, '#0ea5e9').click();
    await ctx.fixture.whenStable();
    expect(selectedColors(ctx), 'sélection déplacée').toEqual(['#0ea5e9']);

    const req = await saveProfile(ctx);
    expect(req.request.body).toEqual({ avatarColor: '#0ea5e9', name: 'Camille' });
    req.flush({ ...CAMILLE, avatarColor: '#0ea5e9' });
    await ctx.fixture.whenStable();

    expect(ctx.auth.user()?.avatarColor).toBe('#0ea5e9');
    expect(JSON.parse(localStorage.getItem('taskora.user') ?? 'null').avatarColor).toBe('#0ea5e9');
    expect(ctx.toast.success).toHaveBeenCalledWith('Profil mis à jour');
  });

  it("SC-IDENT-016 — le sélecteur n'envoie que des couleurs valides : chaque pastille envoie sa propre valeur #RRGGBB", async () => {
    const ctx = await setup();
    for (const button of ctx.colors()) {
      const color = button.getAttribute('aria-label') ?? '';
      button.click();
      await ctx.fixture.whenStable();
      const req = await saveProfile(ctx);
      expect(req.request.body.avatarColor, color).toBe(color);
      expect(req.request.body.avatarColor, color).toMatch(STRICT_COLOR);
      req.flush({ ...CAMILLE, avatarColor: color });
      await ctx.fixture.whenStable();
    }
  });

  it("SC-IDENT-016 — couleur actuelle hors de la palette du sélecteur (palette d'inscription) : conservée telle quelle au renommage", async () => {
    // #f59e0b est tirée à l'inscription (identity.md §1) mais absente de la palette du profil.
    const ctx = await setup({ user: { ...CAMILLE, avatarColor: '#f59e0b' } });
    await type(ctx.fixture, ctx.name, 'Camille M.');

    const req = await saveProfile(ctx);
    expect(req.request.body).toEqual({ avatarColor: '#f59e0b', name: 'Camille M.' });
    req.flush({ ...CAMILLE, avatarColor: '#f59e0b', name: 'Camille M.' });
    await ctx.fixture.whenStable();
    expect(ctx.auth.user()?.avatarColor).toBe('#f59e0b');
  });

  it("SC-IDENT-016 — couleur refusée par l'API (E18, 400 « Couleur d'avatar invalide. ») : toast d'erreur, profil inchangé", async () => {
    // Couleur stockée invalide (localStorage altéré) : identity-service répond 400, sans rien écrire.
    const stale: User = { ...CAMILLE, avatarColor: 'red' };
    const ctx = await setup({ interceptor: true, user: stale });
    await type(ctx.fixture, ctx.name, 'Camille M.');

    const req = await saveProfile(ctx);
    req.flush({ error: "Couleur d'avatar invalide." }, { status: 400, statusText: 'Bad Request' });
    await ctx.fixture.whenStable();

    expect(ctx.toast.show).toHaveBeenCalledWith("Couleur d'avatar invalide.", 'error');
    expect(ctx.toast.success).not.toHaveBeenCalled();
    expect(ctx.auth.user(), 'ni le nom ni la couleur').toEqual(stale);
    expect(JSON.parse(localStorage.getItem('taskora.user') ?? 'null')).toEqual(stale);
    expect(ctx.save().disabled, 'bouton réactivé').toBe(false);
  });
});
