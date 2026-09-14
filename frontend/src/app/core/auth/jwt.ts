/**
 * Minimal JWT reading.
 *
 * The client decodes tokens only to read `exp` and the identity claims it needs
 * to render a name. It never verifies the signature: verification is the API
 * server's job (AU-1), and a client-side check would be security theatre. Treat
 * everything returned here as display-only.
 */

/** Claims the client cares about, plus the raw claim bag for anything else. */
export interface JwtClaims {
  readonly [claim: string]: unknown;
}

function base64UrlDecode(segment: string): string {
  const padded = segment.replace(/-/g, '+').replace(/_/g, '/');
  const binary = atob(padded.padEnd(Math.ceil(padded.length / 4) * 4, '='));
  const bytes = Uint8Array.from(binary, (char) => char.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}

/** Decodes the payload of a JWT, or returns null if it is not a readable JWT. */
export function decodeJwtPayload(token: string | null | undefined): JwtClaims | null {
  if (!token) {
    return null;
  }
  const segments = token.split('.');
  if (segments.length < 2 || !segments[1]) {
    return null;
  }
  try {
    const parsed: unknown = JSON.parse(base64UrlDecode(segments[1]));
    return typeof parsed === 'object' && parsed !== null && !Array.isArray(parsed)
      ? (parsed as JwtClaims)
      : null;
  } catch {
    return null;
  }
}

function claimString(claims: JwtClaims | null, name: string): string | null {
  const value = claims?.[name];
  return typeof value === 'string' && value.length > 0 ? value : null;
}

/**
 * `exp` as epoch milliseconds, or null when the token carries no expiry.
 * JWT `exp` is in seconds (RFC 7519); everything else in this app uses ms.
 */
export function jwtExpiryMs(claims: JwtClaims | null): number | null {
  const exp = claims?.['exp'];
  return typeof exp === 'number' && Number.isFinite(exp) ? exp * 1000 : null;
}

/** The authenticated user as the client understands them. Display-only. */
export interface Principal {
  /** The `sub` claim - the identity provider's stable user id. */
  readonly subject: string;
  readonly email: string | null;
  readonly name: string | null;
  /** Token expiry as epoch milliseconds, when the token declares one. */
  readonly expiresAt: number | null;
  /** All claims, for anything this interface does not surface. */
  readonly claims: JwtClaims;
}

/**
 * Builds a {@link Principal} from an access token.
 *
 * Name falls back through the OIDC standard claims (`name`, then
 * `preferred_username`, then `given_name`) so a provider that omits `name`
 * still renders something useful. Returns null for a token with no `sub`, since
 * an identity with no subject is not one the app can act on.
 */
export function principalFromToken(token: string | null | undefined): Principal | null {
  const claims = decodeJwtPayload(token);
  if (!claims) {
    return null;
  }
  const subject = claimString(claims, 'sub');
  if (!subject) {
    return null;
  }
  return {
    subject,
    email: claimString(claims, 'email'),
    name:
      claimString(claims, 'name') ??
      claimString(claims, 'preferred_username') ??
      claimString(claims, 'given_name'),
    expiresAt: jwtExpiryMs(claims),
    claims,
  };
}
