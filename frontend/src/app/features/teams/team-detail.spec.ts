import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TeamDetail, UserSummary } from '../../core/api/models';
import { alice, bob, pageOf, projectSummary, teamDetail } from '../../core/test-support.spec';
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
import { CONFLICT_HEADING } from '../shared/mutation';
import { TeamDetailPage } from './team-detail';

const carol: UserSummary = { id: 3, name: 'Carol Diaz', email: 'carol@hive.test' };

/** The same team seen by a plain member: bob leads it, alice is acting. */
const ledByBob: TeamDetail = { ...teamDetail, teamLead: bob };

describe('TeamDetailPage', () => {
  let fixture: ComponentFixture<TeamDetailPage>;
  let http: HttpTestingController;

  const members = () => queryAll(fixture, '.team__member');
  const removeButtons = () => queryAll(fixture, '[data-remove-user]');

  async function load(detail: TeamDetail = teamDetail, me: UserSummary = alice): Promise<void> {
    flushMe(http, me);
    http.expectOne(`${API}/teams/10`).flush(detail);
    http.expectOne(`${API}/teams/10/projects`).flush([projectSummary]);
    await fixture.whenStable();
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [TeamDetailPage],
      providers: featureProviders(),
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(TeamDetailPage);
    fixture.componentRef.setInput('id', '10');
    await fixture.whenStable();
  });

  afterEach(() => http.verify({ ignoreCancelled: true }));

  it('shows a spinner while the team loads', () => {
    expect(query(fixture, 'hive-spinner')).not.toBeNull();
    flushMe(http);
    http.expectOne(`${API}/teams/10`).flush(teamDetail);
    http.expectOne(`${API}/teams/10/projects`).flush([]);
  });

  it('shows the team, its members and its projects', async () => {
    await load();

    expect(text(fixture)).toContain('Platform');
    expect(members().length).toBe(2);
    expect(text(fixture)).toContain('Alice Ng');
    expect(text(fixture)).toContain('Bob Ito');
    expect(text(fixture)).toContain('Hive Core');
  });

  it('marks the lead in words, not by styling alone', async () => {
    await load();
    expect(byTestId(fixture, 'lead-badge')!.textContent).toContain('Team lead');
  });

  it('shows the server message when the team cannot be read', async () => {
    flushMe(http);
    http
      .expectOne(`${API}/teams/10`)
      .flush(
        { status: 404, error: 'NOT_FOUND', message: 'Team 10 was not found.' },
        { status: 404, statusText: 'Not Found' },
      );
    http.expectOne(`${API}/teams/10/projects`).flush([]);
    await fixture.whenStable();

    expect(text(fixture)).toContain('Team 10 was not found.');
    expect(byTestId(fixture, 'lead-controls')).toBeNull();
  });

  it('shows the project list its own empty state', async () => {
    flushMe(http);
    http.expectOne(`${API}/teams/10`).flush(teamDetail);
    http.expectOne(`${API}/teams/10/projects`).flush([]);
    await fixture.whenStable();

    expect(text(fixture)).toContain('No projects yet');
  });

  // -------------------------------------------------------------------------
  // Lead-only controls
  // -------------------------------------------------------------------------

  it('OMITS every management control from a member who does not lead the team', async () => {
    await load(ledByBob);

    expect(byTestId(fixture, 'lead-controls')).toBeNull();
    expect(byTestId(fixture, 'rename-input')).toBeNull();
    expect(byTestId(fixture, 'lead-select')).toBeNull();
    expect(query(fixture, 'hive-user-search')).toBeNull();
    expect(removeButtons().length).toBe(0);
    expect(text(fixture)).toContain('Led by Bob Ito');
  });

  it('shows the management controls to the lead', async () => {
    await load();

    expect(byTestId(fixture, 'lead-controls')).not.toBeNull();
    expect(byTestId<HTMLInputElement>(fixture, 'rename-input')!.value).toBe('Platform');
    expect(query(fixture, 'hive-user-search')).not.toBeNull();
  });

  it('never offers to remove the lead, whose membership INV-1 pins in place', async () => {
    await load();

    expect(removeButtons().length).toBe(1);
    expect(removeButtons()[0].getAttribute('data-remove-user')).toBe(String(bob.id));
  });

  it('renames the team and adopts the returned record', async () => {
    await load();

    type(byTestId<HTMLInputElement>(fixture, 'rename-input')!, 'Platform Engineering');
    await fixture.whenStable();
    query(fixture, '.team__form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    const request = http.expectOne((r) => r.url === `${API}/teams/10` && r.method === 'PATCH');
    expect(request.request.body).toEqual({ name: 'Platform Engineering' });
    request.flush({ ...teamDetail, name: 'Platform Engineering' });
    await fixture.whenStable();

    expect(text(fixture)).toContain('Platform Engineering');
  });

  it('adds the person picked from the directory', async () => {
    await load();

    query(fixture, 'hive-user-search input')!.dispatchEvent(new Event('input'));
    const search = query(fixture, 'hive-user-search form')!;
    search.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
    http.expectOne((r) => r.url === `${API}/users`).flush(pageOf([carol]));
    await fixture.whenStable();

    query<HTMLButtonElement>(fixture, '.user-search__result button')!.click();
    await fixture.whenStable();

    const request = http.expectOne(
      (r) => r.url === `${API}/teams/10/members` && r.method === 'POST',
    );
    expect(request.request.body).toEqual({ userId: carol.id });
    request.flush({ ...teamDetail, members: [alice, bob, carol] });
    await fixture.whenStable();

    expect(members().length).toBe(3);
  });

  it('removes a member and adopts the returned record', async () => {
    await load();

    query<HTMLButtonElement>(fixture, `[data-remove-user="${bob.id}"] button`)!.click();
    await fixture.whenStable();

    const request = http.expectOne(
      (r) => r.url === `${API}/teams/10/members/${bob.id}` && r.method === 'DELETE',
    );
    request.flush({ ...teamDetail, members: [alice] });
    await fixture.whenStable();

    expect(members().length).toBe(1);
  });

  it('labels each remove control with the person it removes', async () => {
    await load();
    expect(
      query(fixture, `[data-remove-user="${bob.id}"] button`)!.getAttribute('aria-label'),
    ).toBe('Remove Bob Ito from the team');
  });

  it('transfers the lead to a member and offers only the other members', async () => {
    await load();

    const select = byTestId<HTMLSelectElement>(fixture, 'lead-select')!;
    const options = Array.from(select.querySelectorAll('option')).map((o) => o.textContent?.trim());
    expect(options).toEqual(['Choose a member', 'Bob Ito']);

    type(select, String(bob.id), 'change');
    await fixture.whenStable();
    queryAll(fixture, '.team__form')[1].dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    const request = http.expectOne((r) => r.url === `${API}/teams/10/lead` && r.method === 'PUT');
    expect(request.request.body).toEqual({ userId: bob.id });
    request.flush(ledByBob);
    await fixture.whenStable();

    // Having transferred it away, the acting user loses the lead controls.
    expect(byTestId(fixture, 'lead-controls')).toBeNull();
  });

  it('explains a 409 and re-reads the team', async () => {
    await load();

    query<HTMLButtonElement>(fixture, `[data-remove-user="${bob.id}"] button`)!.click();
    await fixture.whenStable();

    http
      .expectOne((r) => r.method === 'DELETE')
      .flush(
        { status: 409, error: 'CONFLICT', message: 'Bob Ito now leads this team.' },
        { status: 409, statusText: 'Conflict' },
      );
    await fixture.whenStable();

    expect(text(fixture)).toContain(CONFLICT_HEADING);
    expect(text(fixture)).toContain('Bob Ito now leads this team.');

    http.expectOne(`${API}/teams/10`).flush(ledByBob);
    await fixture.whenStable();
    expect(byTestId(fixture, 'lead-controls')).toBeNull();
  });

  it('submits neither an empty rename nor a transfer with nobody chosen', async () => {
    await load();

    type(byTestId<HTMLInputElement>(fixture, 'rename-input')!, '   ');
    await fixture.whenStable();
    query(fixture, '.team__form')!.dispatchEvent(new Event('submit'));
    queryAll(fixture, '.team__form')[1].dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    expect(() => http.verify()).not.toThrow();
  });

  it('shows an ordinary failure without re-reading', async () => {
    await load();

    query<HTMLButtonElement>(fixture, `[data-remove-user="${bob.id}"] button`)!.click();
    await fixture.whenStable();
    http
      .expectOne((r) => r.method === 'DELETE')
      .flush(
        { status: 403, error: 'FORBIDDEN', message: 'Only the team lead may do that.' },
        { status: 403, statusText: 'Forbidden' },
      );
    await fixture.whenStable();

    expect(text(fixture)).toContain('Only the team lead may do that.');
    expect(text(fixture)).not.toContain(CONFLICT_HEADING);
    http.verify();
  });
});
