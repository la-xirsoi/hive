import { HttpClient, HttpParams } from '@angular/common/http';
import { inject } from '@angular/core';
import { APP_CONFIG } from '../config/app-config';
import { MAX_PAGE_SIZE, PageRequest } from './models';

/**
 * Shared plumbing for the resource services: URL construction against
 * `apiBaseUrl` and the contract's `?page=&size=` envelope parameters.
 *
 * Subclasses stay flat lists of endpoint methods. DI happens through `inject()`
 * in field initializers, so subclasses need no constructor at all.
 */
export abstract class ApiBase {
  protected readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl.replace(/\/+$/, '');

  /** Joins a contract path (`/users/me`) onto the configured API base URL. */
  protected url(path: string): string {
    return `${this.apiBaseUrl}${path.startsWith('/') ? path : `/${path}`}`;
  }

  /**
   * Builds query parameters for a paged endpoint.
   *
   * `page` and `size` are only sent when the caller supplied them, so the
   * server's documented defaults (`page=0&size=50`) stay authoritative. `size`
   * is clamped to the contract's 1..200 range rather than being sent as a value
   * the server would reject with a 400.
   */
  protected pageParams(
    request?: PageRequest,
    extra?: Readonly<Record<string, string | number | undefined>>,
  ): HttpParams {
    let params = new HttpParams();
    for (const [key, value] of Object.entries(extra ?? {})) {
      if (value !== undefined && value !== '') {
        params = params.set(key, String(value));
      }
    }
    if (request?.page !== undefined) {
      params = params.set('page', String(Math.max(0, Math.trunc(request.page))));
    }
    if (request?.size !== undefined) {
      const size = Math.min(MAX_PAGE_SIZE, Math.max(1, Math.trunc(request.size)));
      params = params.set('size', String(size));
    }
    return params;
  }
}
