import { InjectionToken } from '@angular/core';

/**
 * Injectable seams over the two pieces of browser global state the auth layer
 * touches. Both exist so tests can substitute fakes without stubbing globals,
 * and so a non-browser (SSR / prerender) environment degrades to a no-op rather
 * than throwing at injector construction.
 */

/** The subset of `window.location` the auth layer uses. */
export interface BrowserLocation {
  /** Navigates the top-level document, e.g. to the authorization endpoint. */
  assign(url: string): void;
  readonly href: string;
  /** The query string including the leading `?`. */
  readonly search: string;
  readonly origin: string;
}

const NOOP_LOCATION: BrowserLocation = {
  assign: () => undefined,
  href: '',
  search: '',
  origin: '',
};

export const BROWSER_LOCATION = new InjectionToken<BrowserLocation>('hive.browser-location', {
  providedIn: 'root',
  factory: () => (typeof window === 'undefined' ? NOOP_LOCATION : window.location),
});

/**
 * In-memory `Storage` used when the real one is unavailable (SSR, or a browser
 * with site data blocked). Keeps the session working for the current page load
 * instead of throwing on every read.
 */
export class MemoryStorage implements Storage {
  private readonly map = new Map<string, string>();

  get length(): number {
    return this.map.size;
  }
  clear(): void {
    this.map.clear();
  }
  getItem(key: string): string | null {
    return this.map.get(key) ?? null;
  }
  key(index: number): string | null {
    return Array.from(this.map.keys())[index] ?? null;
  }
  removeItem(key: string): void {
    this.map.delete(key);
  }
  setItem(key: string, value: string): void {
    this.map.set(key, value);
  }
}

/**
 * Where tokens and the pending PKCE transaction live.
 *
 * `sessionStorage`, deliberately, not `localStorage`: tokens are scoped to the
 * tab and are gone when it closes, which narrows the window in which a stolen
 * token is useful and keeps two tabs from fighting over one refresh. It still
 * survives the full-page redirect to the identity provider and back, which is
 * the one property the PKCE transaction actually requires.
 */
export const AUTH_STORAGE = new InjectionToken<Storage>('hive.auth-storage', {
  providedIn: 'root',
  factory: () => {
    try {
      if (typeof sessionStorage !== 'undefined') {
        // Touch it: a browser with site data blocked throws on access, not on load.
        const probe = '__hive_probe__';
        sessionStorage.setItem(probe, '1');
        sessionStorage.removeItem(probe);
        return sessionStorage;
      }
    } catch {
      // fall through to memory
    }
    return new MemoryStorage();
  },
});
