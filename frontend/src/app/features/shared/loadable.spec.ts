import { TestBed } from '@angular/core/testing';
import { Subject, throwError } from 'rxjs';
import { ApiError } from '../../core/api/api-error';
import { Loadable, loadable } from './loadable';

describe('Loadable', () => {
  function make<T>(): Loadable<T> {
    return TestBed.runInInjectionContext(() => loadable<T>());
  }

  it('starts idle with no value and no error', () => {
    const cell = make<string>();
    expect(cell.status()).toBe('idle');
    expect(cell.value()).toBeNull();
    expect(cell.errorMessage()).toBeNull();
  });

  it('moves idle -> loading -> ready', () => {
    const cell = make<string>();
    const source = new Subject<string>();
    cell.load(source);
    expect(cell.isLoading()).toBeTrue();

    source.next('Platform');
    expect(cell.isReady()).toBeTrue();
    expect(cell.value()).toBe('Platform');
  });

  it('normalizes a failure and exposes the server message', () => {
    const cell = make<string>();
    cell.load(
      throwError(
        () => new ApiError({ status: 403, error: 'FORBIDDEN', message: 'Only the lead may.' }),
      ),
    );
    expect(cell.hasError()).toBeTrue();
    expect(cell.errorMessage()).toBe('Only the lead may.');
    expect(cell.error()?.status).toBe(403);
  });

  it('clears the previous value on reload by default', () => {
    const cell = make<string>();
    const first = new Subject<string>();
    cell.load(first);
    first.next('one');

    cell.load(new Subject<string>());
    expect(cell.value()).toBeNull();
  });

  it('keeps the previous value when asked, so a refresh does not blank the screen', () => {
    const cell = make<string>();
    const first = new Subject<string>();
    cell.load(first);
    first.next('one');

    cell.load(new Subject<string>(), { keepValue: true });
    expect(cell.isLoading()).toBeTrue();
    expect(cell.value()).toBe('one');
  });

  it('adopts a value already in hand without a request', () => {
    const cell = make<string>();
    cell.set('from a mutation body');
    expect(cell.isReady()).toBeTrue();
    expect(cell.value()).toBe('from a mutation body');
  });

  it('clears a stale error when a later load succeeds', () => {
    const cell = make<string>();
    cell.load(
      throwError(() => new ApiError({ status: 500, error: 'INTERNAL_ERROR', message: 'x' })),
    );
    const retry = new Subject<string>();
    cell.load(retry);
    retry.next('ok');
    expect(cell.errorMessage()).toBeNull();
    expect(cell.isReady()).toBeTrue();
  });
});
