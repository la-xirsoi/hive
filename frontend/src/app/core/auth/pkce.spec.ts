import {
  CODE_CHALLENGE_METHOD,
  base64UrlEncode,
  createCodeChallenge,
  createCodeVerifier,
  createNonce,
  createPkcePair,
  createState,
  randomUrlSafeString,
} from './pkce';

const BASE64URL = /^[A-Za-z0-9_-]+$/;

describe('PKCE primitives', () => {
  it('base64url-encodes without padding or URL-unsafe characters', () => {
    // 0xFB 0xFF encodes to "+/8" in standard base64, which is exactly the pair
    // base64url must rewrite.
    expect(base64UrlEncode(new Uint8Array([0xfb, 0xff, 0xfe]))).toBe('-__-');
    expect(base64UrlEncode(new Uint8Array([1]))).toBe('AQ');
    expect(base64UrlEncode(new Uint8Array([]))).toBe('');
  });

  it('accepts an ArrayBuffer as well as a Uint8Array', () => {
    const bytes = new Uint8Array([1, 2, 3]);

    expect(base64UrlEncode(bytes.buffer)).toBe(base64UrlEncode(bytes));
  });

  it('produces a verifier inside the RFC 7636 43..128 character range', () => {
    const verifier = createCodeVerifier();

    expect(verifier.length).toBe(43);
    expect(verifier.length).toBeGreaterThanOrEqual(43);
    expect(verifier.length).toBeLessThanOrEqual(128);
    expect(verifier).toMatch(BASE64URL);
  });

  it('produces a different verifier every time', () => {
    const verifiers = new Set(Array.from({ length: 25 }, () => createCodeVerifier()));

    expect(verifiers.size).toBe(25);
  });

  it('derives the S256 challenge from the RFC 7636 appendix B test vector', async () => {
    // RFC 7636 appendix B: this verifier must hash to this exact challenge.
    const verifier = 'dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk';

    expect(await createCodeChallenge(verifier)).toBe('E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM');
  });

  it('is deterministic for the same verifier and different for another', async () => {
    const a = await createCodeChallenge('verifier-a');

    expect(await createCodeChallenge('verifier-a')).toBe(a);
    expect(await createCodeChallenge('verifier-b')).not.toBe(a);
    expect(a).toMatch(BASE64URL);
  });

  it('pairs a verifier with its own challenge', async () => {
    const pair = await createPkcePair();

    expect(pair.codeChallenge).toBe(await createCodeChallenge(pair.codeVerifier));
    expect(pair.codeChallenge).not.toBe(pair.codeVerifier);
  });

  it('only ever advertises S256', () => {
    expect(CODE_CHALLENGE_METHOD).toBe('S256');
  });

  it('generates unguessable state and nonce values', () => {
    expect(createState()).toMatch(BASE64URL);
    expect(createNonce()).toMatch(BASE64URL);
    expect(createState()).not.toBe(createState());
    expect(createNonce()).not.toBe(createNonce());
    // 16 bytes of entropy base64url-encode to 22 characters.
    expect(createState().length).toBe(22);
  });

  it('scales the encoded length with the requested entropy', () => {
    expect(randomUrlSafeString(8).length).toBe(11);
    expect(randomUrlSafeString(64).length).toBe(86);
  });
});
