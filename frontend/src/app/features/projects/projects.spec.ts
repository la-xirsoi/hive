import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ProjectSummary, TeamSummary } from '../../core/api/models';
import { bob, projectSummary, teamSummary } from '../../core/test-support.spec';
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
import { ProjectsPage } from './projects';

describe('ProjectsPage', () => {
  let fixture: ComponentFixture<ProjectsPage>;
  let http: HttpTestingController;

  const rows = () => queryAll(fixture, '.projects__item');

  async function load(
    projects: readonly ProjectSummary[] = [projectSummary],
    teams: readonly TeamSummary[] = [teamSummary],
  ): Promise<void> {
    flushMe(http);
    http.expectOne(`${API}/projects/mine`).flush(projects);
    http.expectOne(`${API}/teams/mine`).flush(teams);
    await fixture.whenStable();
  }

  async function openForm(): Promise<void> {
    byTestId<HTMLElement>(fixture, 'new-project')!.querySelector('button')!.click();
    await fixture.whenStable();
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ProjectsPage],
      providers: featureProviders(),
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ProjectsPage);
    await fixture.whenStable();
  });

  afterEach(() => http.verify({ ignoreCancelled: true }));

  it('shows a spinner while the list loads', () => {
    expect(query(fixture, 'hive-spinner')).not.toBeNull();
    flushMe(http);
    http.expectOne(`${API}/projects/mine`).flush([]);
    http.expectOne(`${API}/teams/mine`).flush([]);
  });

  it('lists the projects the caller owns or can see through a team', async () => {
    await load();

    expect(rows().length).toBe(1);
    expect(text(fixture)).toContain('Hive Core');
    expect(text(fixture)).toContain('Platform');
    expect(query<HTMLAnchorElement>(fixture, 'a[href="/projects/20"]')).not.toBeNull();
  });

  it('names the caller’s standing from the record the server sent', async () => {
    await load([projectSummary, { ...projectSummary, id: 21, projectOwner: bob }]);

    expect(rows()[0].textContent).toContain('Owner');
    expect(rows()[1].textContent).toContain('Team project');
  });

  it('offers the empty state when there are no projects', async () => {
    await load([], []);
    expect(text(fixture)).toContain('No projects yet');
  });

  it('shows the server message when the list fails', async () => {
    flushMe(http);
    http
      .expectOne(`${API}/projects/mine`)
      .flush(
        { status: 500, error: 'INTERNAL_ERROR', message: 'Projects are unavailable.' },
        { status: 500, statusText: 'Server Error' },
      );
    http.expectOne(`${API}/teams/mine`).flush([]);
    await fixture.whenStable();

    expect(text(fixture)).toContain('Projects are unavailable.');
  });

  it('limits the team picker to the caller’s own teams, which is what PR-2 accepts', async () => {
    await load([], [teamSummary, { ...teamSummary, id: 11, name: 'Design' }]);
    await openForm();

    const options = Array.from(
      byTestId<HTMLSelectElement>(fixture, 'project-team')!.querySelectorAll('option'),
    ).map((option) => option.textContent?.trim());
    expect(options).toEqual(['Choose a team', 'Platform', 'Design']);
  });

  it('creates a project on the chosen team and reloads', async () => {
    await load([]);
    await openForm();

    type(byTestId<HTMLInputElement>(fixture, 'project-name')!, 'Hive Core');
    type(byTestId<HTMLSelectElement>(fixture, 'project-team')!, String(teamSummary.id), 'change');
    await fixture.whenStable();
    query(fixture, '#project-create-form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    const request = http.expectOne((r) => r.url === `${API}/projects` && r.method === 'POST');
    expect(request.request.body).toEqual({ name: 'Hive Core', teamId: teamSummary.id });
    request.flush(projectSummary);
    await fixture.whenStable();

    http.expectOne(`${API}/projects/mine`).flush([projectSummary]);
    await fixture.whenStable();

    expect(text(fixture)).toContain('Hive Core was created. You are its project owner.');
  });

  it('sends the caller to create a team first when they are in none', async () => {
    await load([], []);
    await openForm();

    expect(byTestId(fixture, 'no-teams')!.textContent).toContain('A project belongs to a team');
    expect(byTestId(fixture, 'project-name')).toBeNull();
    expect(query<HTMLAnchorElement>(fixture, 'a[href="/teams"]')).not.toBeNull();
  });

  it('shows a failed create against the field', async () => {
    await load([]);
    await openForm();

    type(byTestId<HTMLInputElement>(fixture, 'project-name')!, 'Hive Core');
    type(byTestId<HTMLSelectElement>(fixture, 'project-team')!, String(teamSummary.id), 'change');
    await fixture.whenStable();
    query(fixture, '#project-create-form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    http
      .expectOne((r) => r.method === 'POST')
      .flush(
        { status: 403, error: 'FORBIDDEN', message: 'You are not a member of that team.' },
        { status: 403, statusText: 'Forbidden' },
      );
    await fixture.whenStable();

    expect(text(fixture)).toContain('You are not a member of that team.');
  });
});
