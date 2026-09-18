import { test as base, type Browser, type Page } from '@playwright/test';

/** A realm user seeded by `containers/idp/realm-hive.json`. */
export interface RealmUser {
  readonly username: string;
  readonly password: string;
  readonly email: string;
  readonly fullName: string;
}

export const ADA: RealmUser = {
  username: 'ada',
  password: 'hive',
  email: 'ada@hive.example',
  fullName: 'Ada Lovelace',
};

export const GRACE: RealmUser = {
  username: 'grace',
  password: 'hive',
  email: 'grace@hive.example',
  fullName: 'Grace Hopper',
};

/** The shape of the token endpoint's success response, as far as tests read it. */
export interface TokenEndpointResponse {
  readonly access_token: string;
  readonly refresh_token?: string;
  readonly id_token?: string;
  readonly expires_in?: number;
  /** Scopes the issuer actually granted, which need not be the ones asked for. */
  readonly scope?: string;
}

/**
 * Records the OAuth traffic the **page** generates.
 *
 * This is the whole point of the suite. Reconstructing an authorization URL in
 * the test and asserting on that proves only that the test can build a URL --
 * exactly the blind spot that let `hive-m50` ship a sign-in that was broken for
 * everyone while a curl-driven PKCE run passed (docs/verification.md 7.2 item
 * 7). Everything asserted here is read off the request Chromium actually sent
 * on behalf of the shipped bundle.
 */
export class OAuthTraffic {
  /** Every navigation to the realm's authorization endpoint, in order. */
  readonly authorizeRequests: URL[] = [];

  /** Every token-endpoint exchange, as a promise for its parsed body. */
  readonly tokenExchanges: Promise<TokenEndpointResponse>[] = [];

  constructor(page: Page) {
    page.on('request', (request) => {
      if (request.url().includes('/protocol/openid-connect/auth')) {
        this.authorizeRequests.push(new URL(request.url()));
      }
    });
    page.on('response', (response) => {
      const request = response.request();
      if (
        request.method() === 'POST' &&
        response.url().includes('/protocol/openid-connect/token')
      ) {
        this.tokenExchanges.push(response.json() as Promise<TokenEndpointResponse>);
      }
    });
  }

  /** Query parameters of the authorization request the bundle issued last. */
  latestAuthorizeParams(): URLSearchParams {
    const latest = this.authorizeRequests.at(-1);
    if (!latest) {
      throw new Error('the page has not issued an authorization request');
    }
    return latest.searchParams;
  }
}

/**
 * Drives the real sign-in: the app's own button, the realm's own form. Tests
 * that are about something *after* authentication use this rather than
 * re-asserting the flow `sign-in.e2e.ts` already owns.
 */
export async function signIn(page: Page, user: RealmUser = ADA): Promise<void> {
  await page.goto('/');
  await page.getByTestId('oauth-sign-in').click();
  await page.locator('#username').fill(user.username);
  await page.locator('#password').fill(user.password);
  await page.locator('#kc-login').click();
  await page.getByTestId('sign-out').waitFor({ state: 'visible' });
}

/**
 * Every `/api/v1` call the backend refused, in the order the responses arrived.
 *
 * `hive-onj` asks for a lifecycle test that "fails if any step's server call is
 * rejected", and a screen assertion alone does not give that: a control whose
 * write 403s can leave the previous render on screen, so the next
 * `toBeVisible()` passes and the test is green over a broken step. Watching the
 * wire turns every refusal into a failure with the method, path and status on
 * it, whatever the UI decided to show.
 *
 * Only the application's own API is watched. The realm's endpoints are not:
 * `sign-in.e2e.ts` owns those, and one of its tests provokes a rejection there
 * on purpose.
 */
export class ApiRejections {
  private readonly entries: string[] = [];

  /** Start recording `page`'s rejected API calls. Returns `this` so it chains. */
  watch(page: Page): this {
    page.on('response', (response) => {
      if (response.status() < 400 || !response.url().includes('/api/v1/')) {
        return;
      }
      this.entries.push(
        `${response.request().method()} ${new URL(response.url()).pathname} -> ${response.status()}`,
      );
    });
    return this;
  }

  /** What has been refused so far, for an `expect(...).toEqual([])`. */
  all(): readonly string[] {
    return [...this.entries];
  }
}

/**
 * A second signed-in browser session, for the parts of a flow that another
 * person has to perform.
 *
 * The task lifecycle needs two: `TaskTransitions` gives `Draft -> Todo` to the
 * project owner and `Todo -> In Progress` to the assignee, and AS-4 forbids the
 * project owner from being the assignee of their own project's task. One person
 * therefore *cannot* drive a task to Completed, and a test that used a single
 * session could only ever cover half of it.
 *
 * `browser.newContext()` does not inherit `use` from the config, so the origin
 * and the stack's self-signed certificate are passed in explicitly. Close the
 * returned page's context when the test is done with it.
 */
export async function openSession(
  browser: Browser,
  user: RealmUser,
  baseURL: string,
  rejections?: ApiRejections,
): Promise<Page> {
  const context = await browser.newContext({ baseURL, ignoreHTTPSErrors: true });
  const page = await context.newPage();
  rejections?.watch(page);
  await signIn(page, user);
  return page;
}

/** `test` with the OAuth recorder already attached to the page. */
export const test = base.extend<{ oauth: OAuthTraffic }>({
  oauth: async ({ page }, use) => {
    await use(new OAuthTraffic(page));
  },
});

export { expect } from '@playwright/test';
