import { TestBed } from '@angular/core/testing';
import { AuthTransaction, TokenSet } from './auth-models';
import { AUTH_STORAGE, MemoryStorage } from './browser';
import { TRANSACTION_TTL_MS, TokenStore } from './token-store';

const tokens: TokenSet = {
  accessToken: 'at',
  refreshToken: 'rt',
  idToken: 'it',
  tokenType: 'Bearer',
  expiresAt: 1_800_000_000_000,
  scope: 'openid',
};

const transaction: AuthTransaction = {
  state: 'st',
  nonce: 'no',
  codeVerifier: 'cv',
  returnUrl: '/tasks',
  createdAt: 1_000_000,
};

describe('TokenStore', () => {
  let storage: MemoryStorage;
  let store: TokenStore;

  beforeEach(() => {
    storage = new MemoryStorage();
    TestBed.configureTestingModule({
      providers: [{ provide: AUTH_STORAGE, useValue: storage }],
    });
    store = TestBed.inject(TokenStore);
  });

  it('round-trips a token set', () => {
    store.writeTokens(tokens);

    expect(store.readTokens()).toEqual({ ...tokens, dev: false });
  });

  it('round-trips the dev flag', () => {
    store.writeTokens({ ...tokens, dev: true });

    expect(store.readTokens()?.dev).toBeTrue();
  });

  it('returns null when nothing is stored', () => {
    expect(store.readTokens()).toBeNull();
  });

  it('discards an unparseable or incomplete token set rather than half-trusting it', () => {
    storage.setItem('hive.auth.tokens', 'not json');
    expect(store.readTokens()).toBeNull();

    storage.setItem('hive.auth.tokens', '"a string"');
    expect(store.readTokens()).toBeNull();

    storage.setItem('hive.auth.tokens', JSON.stringify({ accessToken: 'at' }));
    expect(store.readTokens()).toBeNull();

    storage.setItem('hive.auth.tokens', JSON.stringify({ expiresAt: 1 }));
    expect(store.readTokens()).toBeNull();
  });

  it('defaults a missing token type to Bearer', () => {
    storage.setItem('hive.auth.tokens', JSON.stringify({ accessToken: 'at', expiresAt: 1 }));

    expect(store.readTokens()?.tokenType).toBe('Bearer');
    expect(store.readTokens()?.refreshToken).toBeNull();
  });

  it('clears the token set', () => {
    store.writeTokens(tokens);
    store.clearTokens();

    expect(store.readTokens()).toBeNull();
  });

  it('makes the transaction single-use', () => {
    store.writeTransaction(transaction);

    expect(store.takeTransaction(transaction.createdAt)).toEqual(transaction);
    // A replayed callback URL finds nothing the second time.
    expect(store.takeTransaction(transaction.createdAt)).toBeNull();
  });

  it('rejects a transaction older than the TTL', () => {
    store.writeTransaction(transaction);

    expect(store.takeTransaction(transaction.createdAt + TRANSACTION_TTL_MS + 1)).toBeNull();
  });

  it('accepts a transaction right at the TTL boundary', () => {
    store.writeTransaction(transaction);

    expect(store.takeTransaction(transaction.createdAt + TRANSACTION_TTL_MS)).not.toBeNull();
  });

  it('discards a malformed transaction', () => {
    storage.setItem('hive.auth.tx', JSON.stringify({ state: 'st' }));

    expect(store.takeTransaction()).toBeNull();
  });

  it('defaults a missing returnUrl to the root', () => {
    storage.setItem(
      'hive.auth.tx',
      JSON.stringify({ state: 's', nonce: 'n', codeVerifier: 'c', createdAt: Date.now() }),
    );

    expect(store.takeTransaction()?.returnUrl).toBe('/');
  });

  it('clear() removes tokens and any half-finished transaction', () => {
    store.writeTokens(tokens);
    store.writeTransaction({ ...transaction, createdAt: Date.now() });
    store.clear();

    expect(store.readTokens()).toBeNull();
    expect(store.takeTransaction()).toBeNull();
  });

  it('degrades quietly when the storage backend throws', () => {
    const hostile: Storage = {
      length: 0,
      clear: () => {
        throw new Error('blocked');
      },
      getItem: () => {
        throw new Error('blocked');
      },
      key: () => null,
      removeItem: () => {
        throw new Error('blocked');
      },
      setItem: () => {
        throw new Error('blocked');
      },
    };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [{ provide: AUTH_STORAGE, useValue: hostile }] });
    const hostileStore = TestBed.inject(TokenStore);

    expect(() => hostileStore.writeTokens(tokens)).not.toThrow();
    expect(hostileStore.readTokens()).toBeNull();
    expect(() => hostileStore.clear()).not.toThrow();
  });
});

describe('MemoryStorage', () => {
  it('implements the Storage surface the auth layer relies on', () => {
    const storage = new MemoryStorage();
    storage.setItem('a', '1');
    storage.setItem('b', '2');

    expect(storage.length).toBe(2);
    expect(storage.getItem('a')).toBe('1');
    expect(storage.key(1)).toBe('b');
    expect(storage.key(9)).toBeNull();
    expect(storage.getItem('missing')).toBeNull();

    storage.removeItem('a');
    expect(storage.getItem('a')).toBeNull();

    storage.clear();
    expect(storage.length).toBe(0);
  });
});
