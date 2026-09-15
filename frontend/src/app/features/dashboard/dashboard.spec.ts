import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Page, ProjectSummary, TaskSummary, TeamSummary } from '../../core/api/models';
import {
  alice,
  bob,
  pageOf,
  projectSummary,
  taskSummary,
  teamSummary,
} from '../../core/test-support.spec';
import {
  API,
  byTestId,
  expectPath,
  featureProviders,
  flushMe,
  text,
} from '../shared/feature-test-support.spec';
import { DashboardPage } from './dashboard';

const unassignedTask: TaskSummary = {
  id: 43,
  name: 'Write the runbook',
  status: 'Todo',
  projectId: 20,
  projectName: 'Hive Core',
  assignee: null,
};

/** A team led by someone else, so the acting user is a plain member of it. */
const teamLedByBob: TeamSummary = { ...teamSummary, id: 11, name: 'Design', teamLead: bob };

describe('DashboardPage', () => {
  let fixture: ComponentFixture<DashboardPage>;
  let http: HttpTestingController;

  interface Responses {
    unassigned?: Page<TaskSummary>;
    assigned?: Page<TaskSummary>;
    teams?: readonly TeamSummary[];
    projects?: readonly ProjectSummary[];
  }

  /** Answers all four regions. Anything omitted is left in flight on purpose. */
  async function settle(responses: Responses = {}): Promise<void> {
    flushMe(http);
    if (responses.unassigned) {
      expectPath(http, '/tasks/unassigned').flush(responses.unassigned);
    }
    if (responses.assigned) {
      expectPath(http, '/tasks/assigned-to-me').flush(responses.assigned);
    }
    if (responses.teams) {
      expectPath(http, '/teams/mine').flush(responses.teams);
    }
    if (responses.projects) {
      expectPath(http, '/projects/mine').flush(responses.projects);
    }
    await fixture.whenStable();
  }

  /** The everything-loaded happy path: alice leads Platform. */
  function everything(): Responses {
    return {
      unassigned: pageOf([unassignedTask]),
      assigned: pageOf([taskSummary]),
      teams: [teamSummary],
      projects: [projectSummary],
    };
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [DashboardPage],
      providers: featureProviders(),
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(DashboardPage);
    await fixture.whenStable();
  });

  afterEach(() => {
    http.verify({ ignoreCancelled: true });
  });

  it('greets the acting user once `GET /users/me` answers', async () => {
    await settle(everything());
    expect(text(fixture)).toContain('Welcome back, Alice Ng');
  });

  it('loads each region independently, showing a spinner in each', async () => {
    expect(fixture.nativeElement.querySelectorAll('hive-spinner').length).toBeGreaterThan(0);

    // Answer nothing but the identity: every region is still loading.
    await settle();
    expect(fixture.nativeElement.querySelectorAll('hive-spinner').length).toBe(4);

    http.expectOne((r) => r.url === `${API}/tasks/unassigned`).flush(pageOf([unassignedTask]));
    http.expectOne((r) => r.url === `${API}/tasks/assigned-to-me`).flush(pageOf([taskSummary]));
    http.expectOne((r) => r.url === `${API}/teams/mine`).flush([teamSummary]);
    http.expectOne((r) => r.url === `${API}/projects/mine`).flush([projectSummary]);
    await fixture.whenStable();

    expect(fixture.nativeElement.querySelectorAll('hive-spinner').length).toBe(0);
  });

  it('shows the three Common Needs regions: my tasks, my teams, my projects', async () => {
    await settle(everything());

    expect(byTestId(fixture, 'my-tasks')!.textContent).toContain('Wire the API client');
    expect(byTestId(fixture, 'my-teams')!.textContent).toContain('Platform');
    expect(byTestId(fixture, 'my-projects')!.textContent).toContain('Hive Core');
  });

  it('names the acting user’s standing in each team and project', async () => {
    await settle(everything());

    expect(byTestId(fixture, 'my-teams')!.textContent).toContain('Team lead');
    expect(byTestId(fixture, 'my-projects')!.textContent).toContain('Owner');
  });

  it('gives each region its own empty state', async () => {
    await settle({
      unassigned: pageOf([]),
      assigned: pageOf([]),
      teams: [],
      projects: [],
    });

    expect(byTestId(fixture, 'my-tasks')!.textContent).toContain('No tasks are assigned to you');
    expect(byTestId(fixture, 'my-teams')!.textContent).toContain('You are not in a team yet');
    expect(byTestId(fixture, 'my-projects')!.textContent).toContain('No projects yet');
  });

  it('shows one region’s error without blanking the others', async () => {
    flushMe(http);
    expectPath(http, '/tasks/unassigned').flush(pageOf([]));
    expectPath(http, '/tasks/assigned-to-me').flush(pageOf([taskSummary]));
    expectPath(http, '/teams/mine').flush(
      { status: 500, error: 'INTERNAL_ERROR', message: 'Teams are unavailable right now.' },
      { status: 500, statusText: 'Server Error' },
    );
    expectPath(http, '/projects/mine').flush([projectSummary]);
    await fixture.whenStable();

    expect(byTestId(fixture, 'my-teams')!.textContent).toContain(
      'Teams are unavailable right now.',
    );
    expect(byTestId(fixture, 'my-tasks')!.textContent).toContain('Wire the API client');
    expect(byTestId(fixture, 'my-projects')!.textContent).toContain('Hive Core');
  });

  it('retries just the region that failed', async () => {
    await settle({
      unassigned: pageOf([]),
      assigned: pageOf([]),
      projects: [],
    });
    expectPath(http, '/teams/mine').flush(
      { status: 500, error: 'INTERNAL_ERROR', message: 'Teams are unavailable right now.' },
      { status: 500, statusText: 'Server Error' },
    );
    await fixture.whenStable();

    byTestId<HTMLElement>(fixture, 'my-teams')!
      .querySelector<HTMLButtonElement>('[data-testid="retry"] button')!
      .click();
    await fixture.whenStable();

    expectPath(http, '/teams/mine').flush([teamSummary]);
    await fixture.whenStable();
    expect(byTestId(fixture, 'my-teams')!.textContent).toContain('Platform');
  });

  it('puts the unassigned queue first and calls it a priority (R02)', async () => {
    await settle(everything());

    const queue = byTestId(fixture, 'unassigned-queue');
    expect(queue).not.toBeNull();
    expect(queue!.textContent).toContain('Write the runbook');
    expect(queue!.textContent).toContain('Assigning these is a priority');

    const cards = Array.from(fixture.nativeElement.querySelectorAll('[data-testid]'));
    expect((cards[0] as HTMLElement).dataset['testid']).toBe('unassigned-queue');
  });

  it('keeps the queue visible for a lead even when it is empty, so they know it is clear', async () => {
    await settle({
      unassigned: pageOf([]),
      assigned: pageOf([]),
      teams: [teamSummary],
      projects: [],
    });

    expect(byTestId(fixture, 'unassigned-queue')!.textContent).toContain('The queue is clear');
  });

  it('hides the queue entirely for someone who leads no team and has nothing waiting', async () => {
    await settle({
      unassigned: pageOf([]),
      assigned: pageOf([taskSummary]),
      teams: [teamLedByBob],
      projects: [],
    });

    expect(byTestId(fixture, 'unassigned-queue')).toBeNull();
    expect(byTestId(fixture, 'my-teams')!.textContent).toContain('Member');
  });

  it('shows the queue whenever the server returned work, whatever the client thinks', async () => {
    // The server decides who has a queue (UQ-1). Even with no led team in the
    // `teams/mine` payload, tasks in the queue mean the queue is rendered.
    await settle({
      unassigned: pageOf([unassignedTask]),
      assigned: pageOf([]),
      teams: [teamLedByBob],
      projects: [],
    });

    expect(byTestId(fixture, 'unassigned-queue')!.textContent).toContain('Write the runbook');
  });

  it('links to the full queue when there are more than the first page', async () => {
    await settle({
      unassigned: pageOf([unassignedTask], { totalElements: 12, totalPages: 2 }),
      assigned: pageOf([]),
      teams: [teamSummary],
      projects: [],
    });

    const link = byTestId<HTMLElement>(
      fixture,
      'unassigned-queue',
    )!.querySelector<HTMLAnchorElement>('a[href="/tasks/unassigned"]');
    expect(link?.textContent).toContain('View all 12 unassigned tasks');
  });

  it('still renders when the identity request fails, since each region has its own data', async () => {
    http
      .expectOne(`${API}/users/me`)
      .flush(
        { status: 500, error: 'INTERNAL_ERROR', message: 'nope' },
        { status: 500, statusText: 'Server Error' },
      );
    expectPath(http, '/tasks/unassigned').flush(pageOf([]));
    expectPath(http, '/tasks/assigned-to-me').flush(pageOf([taskSummary]));
    expectPath(http, '/teams/mine').flush([teamSummary]);
    expectPath(http, '/projects/mine').flush([projectSummary]);
    await fixture.whenStable();

    expect(text(fixture)).toContain('Welcome back');
    expect(text(fixture)).not.toContain(`Welcome back, ${alice.name}`);
    expect(byTestId(fixture, 'my-tasks')!.textContent).toContain('Wire the API client');
  });
});
