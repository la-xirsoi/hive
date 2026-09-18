import { ADA, expect, test } from './support/fixtures';

/**
 * The sign-in every other screen depends on, driven the way a person drives it:
 * load the app, click the button the bundle renders, type a password into the
 * identity provider's own form, and land on an authenticated screen.
 *
 * `docs/verification.md` 4.5 asked for this before the stack had ever run;
 * 7.2 item 7 is the bug its absence cost.
 */
test.describe('sign-in', () => {
  test('signs a realm user in through Keycloak and renders the dashboard', async ({
    page,
    oauth,
    baseURL,
  }) => {
    await page.goto('/');

    // The guard sends an anonymous visitor to the sign-in screen.
    await expect(page).toHaveURL(/\/login(\?|$)/);

    await page.getByTestId('oauth-sign-in').click();

    // The identity provider's own login form. Reaching it is the assertion the
    // acceptance criterion is about: a scope the client does not hold fails the
    // authorization request outright, and Keycloak renders an error page here
    // instead of a username field -- before any password is typed.
    const username = page.locator('#username');
    await expect(
      username,
      'Keycloak did not render its login form; the authorization request was rejected',
    ).toBeVisible();

    // What the bundle actually sent, read off the wire rather than rebuilt.
    const authorize = oauth.latestAuthorizeParams();
    expect(authorize.get('response_type')).toBe('code');
    expect(authorize.get('client_id')).toBe('hive-web');
    expect(authorize.get('redirect_uri')).toBe(`${baseURL}/auth/callback`);
    expect(authorize.get('code_challenge_method')).toBe('S256');
    expect(authorize.get('code_challenge')).toBeTruthy();
    expect(authorize.get('state')).toBeTruthy();
    expect(authorize.get('nonce')).toBeTruthy();

    const requestedScopes = (authorize.get('scope') ?? '').split(' ').filter(Boolean);
    expect(requestedScopes).toContain('openid');

    await username.fill(ADA.username);
    await page.locator('#password').fill(ADA.password);
    await page.locator('#kc-login').click();

    // Back on the application, authenticated: the shell and a dashboard card
    // that only renders once `GET /users/me` and the dashboard queries have
    // been accepted by the backend with this token.
    await expect(page).toHaveURL(`${baseURL}/`);
    await expect(page.getByTestId('sign-out')).toBeVisible();
    await expect(page.getByTestId('my-tasks')).toBeVisible();
    // The greeting comes from `GET /users/me`, so seeing the user's own name
    // here is the backend accepting this token, not the token being decoded
    // client-side.
    await expect(page.getByText(`Welcome back, ${ADA.fullName}`)).toBeVisible();

    // Every scope the bundle asked for was granted. Keycloak refuses an unheld
    // scope outright, so this is belt and braces -- but it is what turns "the
    // page loaded" into "the request the bundle ships is the one the realm
    // answers".
    const tokens = await oauth.tokenExchanges.at(-1);
    expect(tokens, 'the bundle never exchanged the code for a token').toBeDefined();
    const grantedScopes = (tokens?.scope ?? '').split(' ').filter(Boolean);
    for (const scope of requestedScopes) {
      expect(grantedScopes, `the realm did not grant the requested scope "${scope}"`).toContain(
        scope,
      );
    }
    // A refresh token, so `scheduleRefresh` has something to arm: the reason
    // `offline_access` is deliberately not requested.
    expect(tokens?.refresh_token).toBeTruthy();
  });

  test('signs out back to the sign-in screen', async ({ page, baseURL }) => {
    await page.goto('/');
    await page.getByTestId('oauth-sign-in').click();
    await page.locator('#username').fill(ADA.username);
    await page.locator('#password').fill(ADA.password);
    await page.locator('#kc-login').click();
    await expect(page.getByTestId('sign-out')).toBeVisible();

    await page.getByTestId('sign-out').click();

    await expect(page).toHaveURL(`${baseURL}/login`);
    await expect(page.getByTestId('oauth-sign-in')).toBeVisible();
  });

  /**
   * Proves the check above can fail.
   *
   * It replays the bundle's own authorization request with one extra scope the
   * realm does not grant this client, and asserts the login form never appears.
   * Without this, "Keycloak rendered a username field" would be an assertion no
   * one had ever seen go red -- which is precisely how the curl-driven
   * verification passed through a broken sign-in.
   */
  test('the realm rejects a scope the client does not hold', async ({ page, oauth }) => {
    await page.goto('/');
    await page.getByTestId('oauth-sign-in').click();
    await expect(page.locator('#username')).toBeVisible();

    const authorize = oauth.authorizeRequests.at(-1);
    expect(authorize).toBeDefined();
    const unheld = new URL(authorize!.toString());
    unheld.searchParams.set('scope', `${unheld.searchParams.get('scope')} hive-e2e-unheld-scope`);

    await page.goto(unheld.toString());

    await expect(page.locator('#username')).toHaveCount(0);
    await expect(page.getByText(/invalid.?scope/i).first()).toBeVisible();
  });
});
