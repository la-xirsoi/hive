import { request, type FullConfig } from '@playwright/test';

/**
 * Fails fast, with an instruction, when the compose stack is not up.
 *
 * Without this the first test dies on `net::ERR_CONNECTION_REFUSED` inside a
 * browser navigation, which reads like a broken test rather than a missing
 * prerequisite.
 */
export default async function globalSetup(config: FullConfig): Promise<void> {
  const baseURL = config.projects[0]?.use.baseURL ?? 'https://localhost:8444';
  const context = await request.newContext({ ignoreHTTPSErrors: true });
  try {
    // Realm discovery, not the SPA's index: it proves both hops the sign-in
    // needs -- the gateway is answering, and Keycloak behind it has imported
    // the realm.
    const discovery = await context.get(
      `${baseURL}/idp/realms/hive/.well-known/openid-configuration`,
    );
    if (!discovery.ok()) {
      throw new Error(`realm discovery answered ${discovery.status()}`);
    }
  } catch (cause) {
    throw new Error(
      `The Hive stack is not answering at ${baseURL} (${cause instanceof Error ? cause.message : String(cause)}).\n` +
        'These tests drive the shipped bundle against the real identity provider. Start the stack first:\n' +
        '  cd containers && ./scripts/generate-certs.sh && podman compose up -d\n' +
        'Set HIVE_E2E_BASE_URL to point at a stack published somewhere else.',
    );
  } finally {
    await context.dispose();
  }
}
