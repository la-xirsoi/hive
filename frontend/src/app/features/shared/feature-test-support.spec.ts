import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider } from '@angular/core';
import { ComponentFixture } from '@angular/core/testing';
import { Routes, provideRouter } from '@angular/router';
import { UserSummary } from '../../core/api/models';
import { alice, provideTestConfig } from '../../core/test-support.spec';

/**
 * Shared providers and DOM helpers for the feature specs.
 *
 * Feature screens are tested against `HttpTestingController` and the **real**
 * typed API services rather than mocked service objects. That way a spec fails
 * if a screen calls the wrong endpoint, and the contract's URLs stay asserted
 * from both ends.
 *
 * Like `core/test-support.spec.ts`, this file is a `.spec.ts` so that
 * `tsconfig.app.json` cannot compile it into the application bundle.
 */
export function featureProviders(
  extra: (Provider | EnvironmentProviders)[] = [],
  routes: Routes = [],
): (Provider | EnvironmentProviders)[] {
  return [
    provideHttpClient(),
    provideHttpClientTesting(),
    provideRouter(routes),
    provideTestConfig(),
    ...extra,
  ];
}

export const API = '/api/v1';

/** Flushes the `GET /users/me` that every screen with an identity check fires. */
export function flushMe(http: HttpTestingController, user: UserSummary = alice): void {
  http.expectOne(`${API}/users/me`).flush(user);
}

/** Flushes `GET /users/me` only if a screen actually asked for it. */
export function flushMeIfAsked(http: HttpTestingController, user: UserSummary = alice): void {
  const pending = http.match(`${API}/users/me`);
  for (const request of pending) {
    request.flush(user);
  }
}

/** The one request whose URL matches, ignoring query parameters. */
export function expectPath(http: HttpTestingController, path: string) {
  return http.expectOne((request) => request.url === `${API}${path}`);
}

export function root(fixture: ComponentFixture<unknown>): HTMLElement {
  return fixture.nativeElement as HTMLElement;
}

export function text(fixture: ComponentFixture<unknown>): string {
  return root(fixture).textContent ?? '';
}

export function query<T extends Element = HTMLElement>(
  fixture: ComponentFixture<unknown>,
  selector: string,
): T | null {
  return root(fixture).querySelector<T>(selector);
}

export function queryAll<T extends Element = HTMLElement>(
  fixture: ComponentFixture<unknown>,
  selector: string,
): T[] {
  return Array.from(root(fixture).querySelectorAll<T>(selector));
}

/** The element carrying `data-testid="..."`, or null. */
export function byTestId<T extends Element = HTMLElement>(
  fixture: ComponentFixture<unknown>,
  id: string,
): T | null {
  return query<T>(fixture, `[data-testid="${id}"]`);
}

/** Types `value` into a native control and dispatches the event the app listens for. */
export function type(
  element: HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement,
  value: string,
  event: 'input' | 'change' = 'input',
): void {
  element.value = value;
  element.dispatchEvent(new Event(event));
}

describe('feature test support', () => {
  it('builds the API path helper from the test config base', () => {
    expect(API).toBe('/api/v1');
  });
});
