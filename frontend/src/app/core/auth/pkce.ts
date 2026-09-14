/**
 * PKCE primitives (RFC 7636) built directly on the Web Crypto API.
 *
 * WHY HAND-ROLLED: `angular-oauth2-oidc@22.0.2` does publish an Angular 22
 * compatible release, so the library was a live option. It was deliberately not
 * taken. The whole of the flow this app needs - verifier, S256 challenge, state,
 * nonce, one token exchange, one refresh - is the ~200 lines in this directory,
 * with zero dependency surface to keep pinned against Angular 22 / TypeScript
 * 6.0, no second source of truth for "am I logged in", and every branch
 * (including refresh scheduling and nonce mismatch) reachable from a unit test
 * with `HttpTestingController`. The library's internal storage and timer
 * management would have had to be mocked or bypassed to test the same things,
 * and its state lives outside the signal graph this AuthService exposes.
 *
 * `crypto.getRandomValues` and `crypto.subtle` both require a secure context.
 * That is satisfied by HTTPS in production (AU-3 mandates it) and by
 * `http://localhost` in development and under Karma.
 */

/** RFC 7636 allows 43..128 characters; 32 random bytes base64url-encode to 43. */
const VERIFIER_BYTES = 32;

function webCrypto(): Crypto {
  const value = globalThis.crypto;
  if (!value?.subtle) {
    throw new Error(
      'Web Crypto is unavailable. The Hive client requires a secure context (HTTPS or localhost).',
    );
  }
  return value;
}

/** Base64url (RFC 4648 section 5) with padding stripped, as PKCE requires. */
export function base64UrlEncode(bytes: ArrayBuffer | Uint8Array): string {
  const view = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  let binary = '';
  for (const byte of view) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/** Cryptographically random base64url string of `byteLength` entropy bytes. */
export function randomUrlSafeString(byteLength = VERIFIER_BYTES): string {
  const bytes = new Uint8Array(byteLength);
  webCrypto().getRandomValues(bytes);
  return base64UrlEncode(bytes);
}

/** A fresh PKCE `code_verifier`: 43 characters from 256 bits of entropy. */
export function createCodeVerifier(): string {
  return randomUrlSafeString(VERIFIER_BYTES);
}

/** A fresh, unguessable `state` value for CSRF protection of the callback. */
export function createState(): string {
  return randomUrlSafeString(16);
}

/** A fresh `nonce`, echoed in the id_token and checked on the way back. */
export function createNonce(): string {
  return randomUrlSafeString(16);
}

/**
 * The S256 `code_challenge` for a verifier: base64url(SHA-256(ASCII(verifier))).
 * The `plain` method is intentionally not implemented - it offers no protection.
 */
export async function createCodeChallenge(verifier: string): Promise<string> {
  const digest = await webCrypto().subtle.digest('SHA-256', new TextEncoder().encode(verifier));
  return base64UrlEncode(digest);
}

/** The `code_challenge_method` this client always uses. */
export const CODE_CHALLENGE_METHOD = 'S256';

/** A verifier paired with the challenge derived from it. */
export interface PkcePair {
  readonly codeVerifier: string;
  readonly codeChallenge: string;
}

/** Generates a verifier and its S256 challenge in one step. */
export async function createPkcePair(): Promise<PkcePair> {
  const codeVerifier = createCodeVerifier();
  return { codeVerifier, codeChallenge: await createCodeChallenge(codeVerifier) };
}
