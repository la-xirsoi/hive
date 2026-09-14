import { makeJwt } from '../test-support.spec';
import { decodeJwtPayload, jwtExpiryMs, principalFromToken } from './jwt';

describe('JWT reading', () => {
  it('decodes a payload', () => {
    expect(decodeJwtPayload(makeJwt({ sub: 'u1', role: 'lead' }))).toEqual({
      sub: 'u1',
      role: 'lead',
    });
  });

  it('decodes non-ASCII claims correctly', () => {
    // A bare latin-1 `atob` with no UTF-8 decode step mangles this name into
    // mojibake, so this asserts the TextDecoder step is actually there.
    expect(decodeJwtPayload(makeJwt({ sub: 'u1', name: 'Zoë Müller' }))?.['name']).toBe(
      'Zoë Müller',
    );
  });

  it('returns null for anything that is not a readable JWT', () => {
    expect(decodeJwtPayload(null)).toBeNull();
    expect(decodeJwtPayload(undefined)).toBeNull();
    expect(decodeJwtPayload('')).toBeNull();
    expect(decodeJwtPayload('not-a-jwt')).toBeNull();
    expect(decodeJwtPayload('a..c')).toBeNull();
    expect(decodeJwtPayload('a.%%%.c')).toBeNull();
  });

  it('returns null when the payload is not a JSON object', () => {
    const arrayPayload = btoa('[1,2]').replace(/=+$/, '');
    expect(decodeJwtPayload(`h.${arrayPayload}.s`)).toBeNull();
  });

  it('converts the exp claim from seconds to milliseconds', () => {
    expect(jwtExpiryMs({ exp: 1_800_000_000 })).toBe(1_800_000_000_000);
    expect(jwtExpiryMs({})).toBeNull();
    expect(jwtExpiryMs({ exp: 'soon' })).toBeNull();
    expect(jwtExpiryMs(null)).toBeNull();
  });

  it('builds a principal from the standard OIDC claims', () => {
    const principal = principalFromToken(
      makeJwt({ sub: 'auth0|1', email: 'a@hive.test', name: 'Alice', exp: 1_800_000_000 }),
    );

    expect(principal?.subject).toBe('auth0|1');
    expect(principal?.email).toBe('a@hive.test');
    expect(principal?.name).toBe('Alice');
    expect(principal?.expiresAt).toBe(1_800_000_000_000);
    expect(principal?.claims['sub']).toBe('auth0|1');
  });

  it('falls back through preferred_username then given_name for the display name', () => {
    expect(principalFromToken(makeJwt({ sub: 's', preferred_username: 'alice' }))?.name).toBe(
      'alice',
    );
    expect(principalFromToken(makeJwt({ sub: 's', given_name: 'Alice' }))?.name).toBe('Alice');
    expect(principalFromToken(makeJwt({ sub: 's' }))?.name).toBeNull();
  });

  it('rejects a token with no subject', () => {
    expect(principalFromToken(makeJwt({ email: 'a@hive.test' }))).toBeNull();
    expect(principalFromToken(null)).toBeNull();
  });
});
