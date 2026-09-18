import type { Locator, Page } from '@playwright/test';
import { ADA, expect, signIn, test } from './support/fixtures';

/**
 * The header chrome, measured in a real browser (hive-0lu).
 *
 * The bug this covers was not a missing rule but a *resolved* one: the header
 * is painted #1A1A1A while the controls on it read the light-surface colour
 * roles, so `color` computed to #1A1A1A too - ink on ink, 1.00:1, invisible but
 * still present in the DOM and still clickable. No unit test can see that,
 * because it only exists once the cascade has run with the real stylesheet.
 *
 * So these assertions are computed styles turned into WCAG contrast ratios.
 * The floor is 4.5:1 (1.4.3 AA); ./CONTRAST.md records what the shipped pairs
 * actually measure, and the numbers here are the ones that must not regress.
 */

const AA_BODY_TEXT = 4.5;

/** Comfortably longer than --hive-duration-fast (140ms), the colour transition. */
const TRANSITION_SETTLE_MS = 300;

/** WCAG 2.x relative luminance of an `rgb(...)`/`rgba(...)` computed colour. */
function luminance(color: string): number {
  const [r, g, b] = parseRgb(color);
  const channel = (value: number) => {
    const c = value / 255;
    return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
  };
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
}

function parseRgb(color: string): [number, number, number] {
  const parts = color.match(/[\d.]+/g);
  if (!parts || parts.length < 3) {
    throw new Error(`not an rgb colour: ${color}`);
  }
  return [Number(parts[0]), Number(parts[1]), Number(parts[2])];
}

function contrast(foreground: string, background: string): number {
  const [light, dark] = [luminance(foreground), luminance(background)].sort((a, b) => b - a);
  return (light + 0.05) / (dark + 0.05);
}

/**
 * The colour actually behind an element: its own background if it paints one,
 * otherwise the nearest ancestor that does. A ghost button paints nothing, so
 * its text sits on the header's #1A1A1A.
 */
async function effectiveBackground(element: Locator): Promise<string> {
  return element.evaluate((node) => {
    for (let el: Element | null = node; el; el = el.parentElement) {
      const background = getComputedStyle(el).backgroundColor;
      const alpha = background.match(/[\d.]+/g);
      if (background !== 'transparent' && !(alpha?.length === 4 && Number(alpha[3]) === 0)) {
        return background;
      }
    }
    return getComputedStyle(document.documentElement).backgroundColor;
  });
}

/**
 * Colour and background transition over 140ms, and a computed style read
 * mid-transition returns the interpolated value rather than the state being
 * measured, so every reading waits the transition out first.
 */
async function ratioOf(element: Locator): Promise<number> {
  await element.page().waitForTimeout(TRANSITION_SETTLE_MS);
  const color = await element.evaluate((node) => getComputedStyle(node).color);
  return contrast(color, await effectiveBackground(element));
}

test.describe('header chrome contrast', () => {
  test.beforeEach(async ({ page }) => {
    await signIn(page);
  });

  test('the sign-out button is legible in every interaction state', async ({ page }) => {
    const button = page.getByTestId('sign-out').locator('button');
    await expect(button).toBeVisible();

    // Resting: white on the near-black header. This is the assertion that was
    // failing at 1.00:1 before the header became an inverse surface context.
    expect(await ratioOf(button), 'sign out, resting').toBeGreaterThanOrEqual(AA_BODY_TEXT);

    await button.hover();
    expect(await ratioOf(button), 'sign out, hover').toBeGreaterThanOrEqual(AA_BODY_TEXT);

    // :active only holds while the button is genuinely pressed, so the mouse
    // stays down across the measurement.
    const box = await button.boundingBox();
    expect(box).not.toBeNull();
    await page.mouse.move(box!.x + box!.width / 2, box!.y + box!.height / 2);
    await page.mouse.down();
    try {
      expect(await ratioOf(button), 'sign out, pressed').toBeGreaterThanOrEqual(AA_BODY_TEXT);
    } finally {
      // Released away from the button: pressing it would sign the session out.
      await page.mouse.move(box!.x + box!.width / 2, box!.y + box!.height + 200);
      await page.mouse.up();
    }

    // :focus-visible follows the last input modality, so the focus has to
    // arrive from the keyboard for the ring to be drawn at all.
    await page.keyboard.press('Tab');
    await button.focus();
    expect(await ratioOf(button), 'sign out, focused').toBeGreaterThanOrEqual(AA_BODY_TEXT);
    // The focus indicator itself must be drawn, not merely not-removed.
    const ring = await button.evaluate((node) => getComputedStyle(node).boxShadow);
    expect(ring, 'focus ring').not.toBe('none');
  });

  test('the user chip reads as a name and an email, not a smudge', async ({ page }) => {
    const chip = page.locator('hive-user-chip');
    const name = chip.locator('.hive-chip__name');
    const email = chip.locator('.hive-chip__secondary');

    await expect(name).toHaveText(ADA.fullName);
    await expect(email).toHaveText(ADA.email);

    // #FFFFFF on #1A1A1A.
    expect(await ratioOf(name), 'chip name').toBeGreaterThanOrEqual(15);
    // #BDBDBD on #1A1A1A - the muted line, still well clear of AA.
    expect(await ratioOf(email), 'chip email').toBeGreaterThanOrEqual(AA_BODY_TEXT);
  });

  test('inverting the header does not invert the page under it', async ({ page }) => {
    // The context is scoped to the header: text on the light page must keep
    // its ink colour, or this fix would have traded one invisible button for a
    // whole invisible page.
    const onPage = page.locator('main h1').first();
    await expect(onPage).toBeVisible();
    expect(await ratioOf(onPage), 'the page heading on the light body').toBeGreaterThanOrEqual(
      AA_BODY_TEXT,
    );

    const header = page.locator('header[role="banner"]');
    await expect(header).toHaveClass(/hive-surface-inverse/);
    expect(
      await page.locator('main').evaluate((main) => main.closest('.hive-surface-inverse') !== null),
      'the main region inherited the inverse context',
    ).toBe(false);
  });
});

/**
 * Proves the measurement can fail.
 *
 * Ink text is forced back onto the header - exactly the state hive-0lu
 * described - and the same helper must report a ratio below the AA floor.
 * Without this, "the ratio is >= 4.5" would be an assertion no one had watched
 * go red, which is how the invisible button shipped in the first place.
 */
test('the contrast measurement catches ink-on-ink', async ({ page }: { page: Page }) => {
  await signIn(page);
  const button = page.getByTestId('sign-out').locator('button');
  const header = page.locator('header[role="banner"]');
  expect(await header.evaluate((node) => getComputedStyle(node).backgroundColor)).toBe(
    'rgb(26, 26, 26)',
  );
  await button.evaluate((node) => ((node as HTMLElement).style.color = '#1a1a1a'));

  expect(await ratioOf(button)).toBeLessThan(AA_BODY_TEXT);
});
