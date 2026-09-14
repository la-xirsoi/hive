import { Injectable, inject } from '@angular/core';
import { AuthTransaction, TokenSet } from './auth-models';
import { AUTH_STORAGE } from './browser';

/**
 * Persistence for the token set and the in-flight PKCE transaction.
 *
 * Every read is defensive: storage can hold a value written by an older build,
 * or by a different tab, or be corrupted by hand. A value that does not
 * round-trip into the expected shape is discarded and treated as "not signed
 * in", which is always the safe direction to fail.
 */

const TOKENS_KEY = 'hive.auth.tokens';
const TRANSACTION_KEY = 'hive.auth.tx';

/** A pending authorization request older than this is stale and rejected. */
export const TRANSACTION_TTL_MS = 10 * 60 * 1000;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function str(record: Record<string, unknown>, key: string): string | null {
  const value = record[key];
  return typeof value === 'string' && value.length > 0 ? value : null;
}

function parseTokenSet(raw: string | null): TokenSet | null {
  if (!raw) {
    return null;
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }
  if (!isRecord(parsed)) {
    return null;
  }
  const accessToken = str(parsed, 'accessToken');
  const expiresAt = parsed['expiresAt'];
  if (!accessToken || typeof expiresAt !== 'number' || !Number.isFinite(expiresAt)) {
    return null;
  }
  return {
    accessToken,
    refreshToken: str(parsed, 'refreshToken'),
    idToken: str(parsed, 'idToken'),
    tokenType: str(parsed, 'tokenType') ?? 'Bearer',
    expiresAt,
    scope: str(parsed, 'scope'),
    dev: parsed['dev'] === true,
  };
}

function parseTransaction(raw: string | null): AuthTransaction | null {
  if (!raw) {
    return null;
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }
  if (!isRecord(parsed)) {
    return null;
  }
  const state = str(parsed, 'state');
  const nonce = str(parsed, 'nonce');
  const codeVerifier = str(parsed, 'codeVerifier');
  const createdAt = parsed['createdAt'];
  if (!state || !nonce || !codeVerifier || typeof createdAt !== 'number') {
    return null;
  }
  return { state, nonce, codeVerifier, returnUrl: str(parsed, 'returnUrl') ?? '/', createdAt };
}

@Injectable({ providedIn: 'root' })
export class TokenStore {
  private readonly storage = inject(AUTH_STORAGE);

  /** The persisted token set, or null when absent or unreadable. */
  readTokens(): TokenSet | null {
    return parseTokenSet(this.read(TOKENS_KEY));
  }

  writeTokens(tokens: TokenSet): void {
    this.write(TOKENS_KEY, JSON.stringify(tokens));
  }

  clearTokens(): void {
    this.remove(TOKENS_KEY);
  }

  /** Stores the PKCE transaction for the duration of the provider redirect. */
  writeTransaction(transaction: AuthTransaction): void {
    this.write(TRANSACTION_KEY, JSON.stringify(transaction));
  }

  /**
   * Reads and removes the pending transaction - it is single-use by design, so
   * a replayed callback URL finds nothing and fails `no_transaction`. Returns
   * null for a transaction older than {@link TRANSACTION_TTL_MS}.
   */
  takeTransaction(now: number = Date.now()): AuthTransaction | null {
    const transaction = parseTransaction(this.read(TRANSACTION_KEY));
    this.remove(TRANSACTION_KEY);
    if (!transaction) {
      return null;
    }
    return now - transaction.createdAt > TRANSACTION_TTL_MS ? null : transaction;
  }

  /** Drops everything: tokens and any half-finished authorization request. */
  clear(): void {
    this.remove(TOKENS_KEY);
    this.remove(TRANSACTION_KEY);
  }

  private read(key: string): string | null {
    try {
      return this.storage.getItem(key);
    } catch {
      return null;
    }
  }

  private write(key: string, value: string): void {
    try {
      this.storage.setItem(key, value);
    } catch {
      // Storage full or blocked: the session degrades to this page load only.
    }
  }

  private remove(key: string): void {
    try {
      this.storage.removeItem(key);
    } catch {
      // Nothing useful to do; the value is already unreachable.
    }
  }
}
