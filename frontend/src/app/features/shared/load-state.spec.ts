import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HiveLoadState } from './load-state';

@Component({
  imports: [HiveLoadState],
  template: `
    <hive-load-state
      [loading]="loading()"
      [error]="error()"
      [empty]="empty()"
      [retryable]="retryable()"
      loadingLabel="Loading my teams"
      emptyHeading="You are not in a team yet"
      emptyMessage="Create a team to get going."
      (retried)="retries.set(retries() + 1)"
    >
      <p class="content">Platform</p>
    </hive-load-state>
  `,
})
class LoadStateHost {
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly empty = signal(false);
  readonly retryable = signal(true);
  readonly retries = signal(0);
}

describe('HiveLoadState', () => {
  let fixture: ComponentFixture<LoadStateHost>;
  let host: LoadStateHost;

  const root = () => fixture.nativeElement as HTMLElement;
  const text = () => root().textContent ?? '';

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [LoadStateHost] }).compileComponents();
    fixture = TestBed.createComponent(LoadStateHost);
    host = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('renders the projected content when there is nothing to report', () => {
    expect(root().querySelector('.content')).not.toBeNull();
  });

  it('shows a labelled spinner while loading, and hides the content', async () => {
    host.loading.set(true);
    await fixture.whenStable();

    expect(root().querySelector('hive-spinner')).not.toBeNull();
    expect(text()).toContain('Loading my teams');
    expect(root().querySelector('.content')).toBeNull();
  });

  it('shows the server message with a retry when loading failed', async () => {
    host.error.set('The team could not be loaded.');
    await fixture.whenStable();

    expect(text()).toContain('The team could not be loaded.');
    const retry = root().querySelector<HTMLButtonElement>('[data-testid="retry"] button');
    expect(retry).not.toBeNull();

    retry!.click();
    expect(host.retries()).toBe(1);
  });

  it('omits the retry when the caller says the failure is not retryable', async () => {
    host.error.set('Nope.');
    host.retryable.set(false);
    await fixture.whenStable();

    expect(root().querySelector('[data-testid="retry"]')).toBeNull();
  });

  it('announces the error assertively, not by colour alone', async () => {
    host.error.set('Nope.');
    await fixture.whenStable();

    expect(root().querySelector('[role="alert"]')).not.toBeNull();
  });

  it('shows the empty state when the load succeeded with nothing in it', async () => {
    host.empty.set(true);
    await fixture.whenStable();

    expect(text()).toContain('You are not in a team yet');
    expect(text()).toContain('Create a team to get going.');
    expect(root().querySelector('.content')).toBeNull();
  });

  it('prefers the error over the empty state when both are set', async () => {
    host.error.set('Broken.');
    host.empty.set(true);
    await fixture.whenStable();

    expect(text()).toContain('Broken.');
    expect(text()).not.toContain('You are not in a team yet');
  });
});
