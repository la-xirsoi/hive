import { HttpTestingController } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { UserSummary } from '../../core/api/models';
import { alice, bob, pageOf } from '../../core/test-support.spec';
import { API, featureProviders, type } from './feature-test-support.spec';
import { HiveUserSearch } from './user-search';

@Component({
  imports: [HiveUserSearch],
  template: `
    <hive-user-search
      selectLabel="Add to team"
      [excludeIds]="excludeIds()"
      (picked)="picked.set($event)"
    />
  `,
})
class UserSearchHost {
  readonly excludeIds = signal<readonly number[]>([]);
  readonly picked = signal<UserSummary | null>(null);
}

describe('HiveUserSearch', () => {
  let fixture: ComponentFixture<UserSearchHost>;
  let host: UserSearchHost;
  let http: HttpTestingController;

  const root = () => fixture.nativeElement as HTMLElement;
  const text = () => root().textContent ?? '';
  const results = () => Array.from(root().querySelectorAll('.user-search__result'));

  async function search(query: string): Promise<void> {
    type(root().querySelector<HTMLInputElement>('input[name="query"]')!, query);
    root().querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [UserSearchHost],
      providers: featureProviders(),
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(UserSearchHost);
    host = fixture.componentInstance;
    await fixture.whenStable();
  });

  afterEach(() => http.verify());

  it('asks for nothing until the search is submitted', () => {
    expect(results().length).toBe(0);
    http.verify();
  });

  it('searches the directory on submit and lists the matches', async () => {
    await search('al');

    const request = http.expectOne((r) => r.url === `${API}/users`);
    expect(request.request.params.get('query')).toBe('al');
    request.flush(pageOf([alice, bob]));
    await fixture.whenStable();

    expect(results().length).toBe(2);
    expect(text()).toContain('alice@hive.test');
  });

  it('hides people the caller says are already accounted for', async () => {
    host.excludeIds.set([alice.id]);
    await search('a');
    http.expectOne((r) => r.url === `${API}/users`).flush(pageOf([alice, bob]));
    await fixture.whenStable();

    expect(results().length).toBe(1);
    expect(text()).toContain('Bob Ito');
    expect(text()).not.toContain('Alice Ng');
  });

  it('emits the person the user picked', async () => {
    await search('b');
    http.expectOne((r) => r.url === `${API}/users`).flush(pageOf([bob]));
    await fixture.whenStable();

    root().querySelector<HTMLButtonElement>('.user-search__result button')!.click();
    expect(host.picked()).toEqual(bob);
  });

  it('labels each select button with the person it selects', async () => {
    await search('b');
    http.expectOne((r) => r.url === `${API}/users`).flush(pageOf([bob]));
    await fixture.whenStable();

    const button = root().querySelector<HTMLButtonElement>('.user-search__result button')!;
    expect(button.getAttribute('aria-label')).toBe('Add to team Bob Ito');
  });

  it('says so politely when nothing matched', async () => {
    await search('zzz');
    http.expectOne((r) => r.url === `${API}/users`).flush(pageOf([]));
    await fixture.whenStable();

    expect(root().querySelector('[role="status"]')?.textContent).toContain(
      'No matching people were found.',
    );
  });

  it('shows the server message when the search fails', async () => {
    await search('al');
    http
      .expectOne((r) => r.url === `${API}/users`)
      .flush(
        { status: 500, error: 'INTERNAL_ERROR', message: 'The directory is unavailable.' },
        { status: 500, statusText: 'Server Error' },
      );
    await fixture.whenStable();

    expect(text()).toContain('The directory is unavailable.');
  });
});
