import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TeamSummary } from '../../core/api/models';
import { bob, teamDetail, teamSummary } from '../../core/test-support.spec';
import {
  API,
  byTestId,
  featureProviders,
  flushMe,
  query,
  queryAll,
  text,
  type,
} from '../shared/feature-test-support.spec';
import { TeamsPage } from './teams';

describe('TeamsPage', () => {
  let fixture: ComponentFixture<TeamsPage>;
  let http: HttpTestingController;

  const rows = () => queryAll(fixture, '.teams__item');

  async function load(teams: readonly TeamSummary[] = [teamSummary]): Promise<void> {
    flushMe(http);
    http.expectOne(`${API}/teams/mine`).flush(teams);
    await fixture.whenStable();
  }

  async function openForm(): Promise<void> {
    byTestId<HTMLElement>(fixture, 'new-team')!.querySelector('button')!.click();
    await fixture.whenStable();
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [TeamsPage],
      providers: featureProviders(),
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(TeamsPage);
    await fixture.whenStable();
  });

  afterEach(() => http.verify({ ignoreCancelled: true }));

  it('shows a spinner while the list loads', () => {
    expect(query(fixture, 'hive-spinner')).not.toBeNull();
    flushMe(http);
    http.expectOne(`${API}/teams/mine`).flush([]);
  });

  it('lists the teams the caller leads or belongs to', async () => {
    await load();

    expect(rows().length).toBe(1);
    expect(text(fixture)).toContain('Platform');
    expect(text(fixture)).toContain('led by Alice Ng');
    expect(query<HTMLAnchorElement>(fixture, 'a[href="/teams/10"]')).not.toBeNull();
  });

  it('names the caller’s standing in each team from the record the server sent', async () => {
    await load([teamSummary, { ...teamSummary, id: 11, name: 'Design', teamLead: bob }]);

    expect(rows()[0].textContent).toContain('Team lead');
    expect(rows()[1].textContent).toContain('Member');
  });

  it('offers the empty state when the caller is in no team', async () => {
    await load([]);
    expect(text(fixture)).toContain('You are not in a team yet');
  });

  it('shows the server message when the list fails, with a retry', async () => {
    flushMe(http);
    http
      .expectOne(`${API}/teams/mine`)
      .flush(
        { status: 500, error: 'INTERNAL_ERROR', message: 'Teams are unavailable.' },
        { status: 500, statusText: 'Server Error' },
      );
    await fixture.whenStable();

    expect(text(fixture)).toContain('Teams are unavailable.');
    byTestId<HTMLElement>(fixture, 'retry')!.querySelector('button')!.click();
    await fixture.whenStable();
    http.expectOne(`${API}/teams/mine`).flush([teamSummary]);
    await fixture.whenStable();
    expect(text(fixture)).toContain('Platform');
  });

  it('offers the create form to everyone, because TM-1 grants it to any user', async () => {
    await load([]);

    expect(byTestId(fixture, 'new-team')).not.toBeNull();
    await openForm();
    expect(byTestId(fixture, 'team-name')).not.toBeNull();
  });

  it('creates a team, reloads the list and says the caller now leads it', async () => {
    await load([]);
    await openForm();

    type(byTestId<HTMLInputElement>(fixture, 'team-name')!, 'Platform');
    await fixture.whenStable();
    query(fixture, 'form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    const request = http.expectOne((r) => r.url === `${API}/teams` && r.method === 'POST');
    expect(request.request.body).toEqual({ name: 'Platform' });
    request.flush(teamDetail);
    await fixture.whenStable();

    http.expectOne(`${API}/teams/mine`).flush([teamSummary]);
    await fixture.whenStable();

    expect(text(fixture)).toContain('Platform was created. You are its team lead.');
    expect(rows().length).toBe(1);
  });

  it('shows a failed create against the field, keeping the form open', async () => {
    await load([]);
    await openForm();

    type(byTestId<HTMLInputElement>(fixture, 'team-name')!, 'x');
    await fixture.whenStable();
    query(fixture, 'form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    http
      .expectOne((r) => r.method === 'POST')
      .flush(
        { status: 400, error: 'BAD_REQUEST', message: 'Name must be 1 to 200 characters.' },
        { status: 400, statusText: 'Bad Request' },
      );
    await fixture.whenStable();

    expect(text(fixture)).toContain('Name must be 1 to 200 characters.');
    expect(byTestId(fixture, 'team-name')).not.toBeNull();
  });

  it('does not submit an empty name', async () => {
    await load([]);
    await openForm();

    query(fixture, 'form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
    expect(() => http.verify()).not.toThrow();
  });
});
