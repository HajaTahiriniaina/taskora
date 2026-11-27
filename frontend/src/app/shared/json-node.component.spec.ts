import { TestBed } from '@angular/core/testing';

import { JsonNodeComponent } from './json-node.component';

// Visionneuse JSON (SC-ATTACH-010, recette m1) : la ligne est en `white-space: pre-wrap`, donc
// toute espace autour du texte des boutons est rendue. Le texte est comparé sans `trim`.

async function render(value: unknown, depth: number) {
  TestBed.configureTestingModule({ imports: [JsonNodeComponent] });
  const fixture = TestBed.createComponent(JsonNodeComponent);
  fixture.componentRef.setInput('value', value);
  fixture.componentRef.setInput('depth', depth);
  await fixture.whenStable();
  return { fixture, root: fixture.nativeElement as HTMLElement };
}

const SIX_KEYS = { a: 1, b: 2, c: 3, d: 4, e: 5, f: 6 };
const SIX_ITEMS = [1, 2, 3, 4, 5, 6];

describe('JsonNodeComponent', () => {
  it('SC-ATTACH-010 — visionneuse JSON : bouton de repli et résumé sans espace parasite', async () => {
    // Profondeur 0 : nœud ouvert, enfants scalaires (un seul bouton .tog).
    const opened = await render(SIX_KEYS, 0);
    const tog = opened.root.querySelector<HTMLButtonElement>('.tog');
    expect(tog, 'bouton de repli').not.toBeNull();
    expect(tog?.textContent, '.tog ouvert').toBe('▾');
    tog?.click();
    await opened.fixture.whenStable();
    expect(opened.root.querySelector('.tog')?.textContent, '.tog replié').toBe('▸');

    // Profondeur 2 et plus de 5 enfants : nœud fermé, résumé affiché.
    TestBed.resetTestingModule();
    const object = await render(SIX_KEYS, 2);
    expect(object.root.querySelector('.sum')?.textContent, 'objet de 6 clés').toBe('6 clés');

    TestBed.resetTestingModule();
    const array = await render(SIX_ITEMS, 2);
    expect(array.root.querySelector('.sum')?.textContent, 'tableau de 6 éléments').toBe(
      '6 éléments',
    );
  });
});
