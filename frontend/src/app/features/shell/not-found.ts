import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { HiveEmptyState } from '../../shared/ui';

/**
 * Catch-all inside the authenticated area.
 *
 * Wording matters here: the API answers 404 both for records that do not exist
 * and for records the caller may not see (authorization.md section 4 - returning
 * 403 would confirm the record exists). This page says the same thing in the
 * same neutral way rather than promising the address is wrong.
 */
@Component({
  selector: 'app-not-found',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, HiveEmptyState],
  template: `
    <hive-empty-state
      heading="We could not find that"
      message="The page may not exist, or it may not be yours to see."
      glyph="?"
    >
      <a hive-empty-state-actions routerLink="/">Back to the dashboard</a>
    </hive-empty-state>
  `,
  styles: `
    :host {
      display: block;
      padding-block: var(--hive-space-10);
    }
  `,
})
export class NotFoundPage {}
