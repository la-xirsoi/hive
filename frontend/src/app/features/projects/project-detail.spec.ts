import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ProjectSummary, UserSummary } from '../../core/api/models';
import {
  alice,
  bob,
  pageOf,
  projectSummary,
  taskDetail,
  taskSummary,
} from '../../core/test-support.spec';
import {
  API,
  byTestId,
  featureProviders,
  flushMe,
  query,
  text,
  type,
} from '../shared/feature-test-support.spec';
import { CONFLICT_HEADING } from '../shared/mutation';
import { ProjectDetailPage } from './project-detail';

/** The same project seen by someone who does not own it. */
const ownedByBob: ProjectSummary = { ...projectSummary, projectOwner: bob };

describe('ProjectDetailPage', () => {
  let fixture: ComponentFixture<ProjectDetailPage>;
  let http: HttpTestingController;

  async function load(
    project: ProjectSummary = projectSummary,
    me: UserSummary = alice,
  ): Promise<void> {
    flushMe(http, me);
    http.expectOne(`${API}/projects/20`).flush(project);
    http.expectOne((r) => r.url === `${API}/projects/20/tasks`).flush(pageOf([taskSummary]));
    await fixture.whenStable();
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ProjectDetailPage],
      providers: featureProviders(),
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ProjectDetailPage);
    fixture.componentRef.setInput('id', '20');
    await fixture.whenStable();
  });

  afterEach(() => http.verify({ ignoreCancelled: true }));

  it('shows a spinner while the project loads', () => {
    expect(query(fixture, 'hive-spinner')).not.toBeNull();
    flushMe(http);
    http.expectOne(`${API}/projects/20`).flush(projectSummary);
    http.expectOne((r) => r.url === `${API}/projects/20/tasks`).flush(pageOf([]));
  });

  it('shows the project, its team and its owner', async () => {
    await load();

    expect(text(fixture)).toContain('Hive Core');
    expect(text(fixture)).toContain('Worked by Platform');
    expect(text(fixture)).toContain('Alice Ng');
    expect(query<HTMLAnchorElement>(fixture, 'a[href="/teams/10"]')).not.toBeNull();
  });

  it('lists the tasks the caller can see', async () => {
    await load();
    expect(text(fixture)).toContain('Wire the API client');
  });

  it('filters the task list by status, asking the server to narrow it', async () => {
    await load();

    type(byTestId<HTMLSelectElement>(fixture, 'status-filter')!, 'Draft', 'change');
    await fixture.whenStable();

    const request = http.expectOne((r) => r.url === `${API}/projects/20/tasks`);
    expect(request.request.params.getAll('status')).toEqual(['Draft']);
    request.flush(pageOf([]));
    await fixture.whenStable();

    expect(text(fixture)).toContain('No Draft tasks are visible to you');
  });

  it('gives the task list its own empty state', async () => {
    flushMe(http);
    http.expectOne(`${API}/projects/20`).flush(projectSummary);
    http.expectOne((r) => r.url === `${API}/projects/20/tasks`).flush(pageOf([]));
    await fixture.whenStable();

    expect(text(fixture)).toContain('No tasks in this project are visible to you yet.');
  });

  it('shows the server message when the project cannot be read', async () => {
    flushMe(http);
    http
      .expectOne(`${API}/projects/20`)
      .flush(
        { status: 404, error: 'NOT_FOUND', message: 'Project 20 was not found.' },
        { status: 404, statusText: 'Not Found' },
      );
    http.expectOne((r) => r.url === `${API}/projects/20/tasks`).flush(pageOf([]));
    await fixture.whenStable();

    expect(text(fixture)).toContain('Project 20 was not found.');
    expect(byTestId(fixture, 'owner-controls')).toBeNull();
  });

  // -------------------------------------------------------------------------
  // Owner-only controls
  // -------------------------------------------------------------------------

  it('OMITS task creation, rename and transfer from a non-owner', async () => {
    await load(ownedByBob);

    expect(byTestId(fixture, 'owner-controls')).toBeNull();
    expect(byTestId(fixture, 'create-task')).toBeNull();
    expect(byTestId(fixture, 'rename-input')).toBeNull();
    expect(query(fixture, 'hive-user-search')).toBeNull();
    // The tasks are still listed: visibility and authority are different things.
    expect(text(fixture)).toContain('Wire the API client');
  });

  it('shows the owner controls to the owner', async () => {
    await load();

    expect(byTestId(fixture, 'owner-controls')).not.toBeNull();
    expect(byTestId<HTMLInputElement>(fixture, 'rename-input')!.value).toBe('Hive Core');
  });

  it('creates a task as a Draft and reloads the list', async () => {
    await load();

    type(byTestId<HTMLInputElement>(fixture, 'task-name')!, 'Write the runbook');
    type(byTestId<HTMLTextAreaElement>(fixture, 'task-description')!, 'Operations steps.');
    await fixture.whenStable();
    query(fixture, '.project__form--stacked')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    const request = http.expectOne((r) => r.url === `${API}/tasks` && r.method === 'POST');
    expect(request.request.body).toEqual({
      projectId: 20,
      name: 'Write the runbook',
      description: 'Operations steps.',
    });
    request.flush({ ...taskDetail, name: 'Write the runbook' });
    await fixture.whenStable();

    http.expectOne((r) => r.url === `${API}/projects/20/tasks`).flush(pageOf([taskSummary]));
    await fixture.whenStable();

    expect(text(fixture)).toContain('Write the runbook was created as a Draft.');
  });

  it('creates nothing without both a name and a description', async () => {
    await load();

    type(byTestId<HTMLInputElement>(fixture, 'task-name')!, 'Write the runbook');
    await fixture.whenStable();
    query(fixture, '.project__form--stacked')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    expect(() => http.verify()).not.toThrow();
  });

  it('does not submit an empty rename', async () => {
    await load();

    type(byTestId<HTMLInputElement>(fixture, 'rename-input')!, '  ');
    await fixture.whenStable();
    query(fixture, '.project__form:not(.project__form--stacked)')!.dispatchEvent(
      new Event('submit'),
    );
    await fixture.whenStable();

    expect(() => http.verify()).not.toThrow();
  });

  it('renames the project', async () => {
    await load();

    type(byTestId<HTMLInputElement>(fixture, 'rename-input')!, 'Hive Core API');
    await fixture.whenStable();
    query(fixture, '.project__form:not(.project__form--stacked)')!.dispatchEvent(
      new Event('submit'),
    );
    await fixture.whenStable();

    const request = http.expectOne((r) => r.url === `${API}/projects/20` && r.method === 'PATCH');
    expect(request.request.body).toEqual({ name: 'Hive Core API' });
    request.flush({ ...projectSummary, name: 'Hive Core API' });
    await fixture.whenStable();

    expect(text(fixture)).toContain('Hive Core API');
  });

  it('transfers ownership to anyone in the directory (PR-7), then drops the controls', async () => {
    await load();

    query(fixture, 'hive-user-search form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
    http.expectOne((r) => r.url === `${API}/users`).flush(pageOf([bob]));
    await fixture.whenStable();

    query<HTMLButtonElement>(fixture, '.user-search__result button')!.click();
    await fixture.whenStable();

    const request = http.expectOne(
      (r) => r.url === `${API}/projects/20/owner` && r.method === 'PUT',
    );
    expect(request.request.body).toEqual({ userId: bob.id });
    request.flush(ownedByBob);
    await fixture.whenStable();

    expect(byTestId(fixture, 'owner-controls')).toBeNull();
  });

  it('explains the PR-8 conflict and re-reads the project and its tasks', async () => {
    await load();

    query(fixture, 'hive-user-search form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
    http.expectOne((r) => r.url === `${API}/users`).flush(pageOf([bob]));
    await fixture.whenStable();
    query<HTMLButtonElement>(fixture, '.user-search__result button')!.click();
    await fixture.whenStable();

    http
      .expectOne((r) => r.url === `${API}/projects/20/owner`)
      .flush(
        {
          status: 409,
          error: 'CONFLICT',
          message: 'Bob Ito is the assignee of task 42; reassign it first.',
        },
        { status: 409, statusText: 'Conflict' },
      );
    await fixture.whenStable();

    expect(text(fixture)).toContain(CONFLICT_HEADING);
    expect(text(fixture)).toContain('Bob Ito is the assignee of task 42; reassign it first.');

    http.expectOne(`${API}/projects/20`).flush(projectSummary);
    http.expectOne((r) => r.url === `${API}/projects/20/tasks`).flush(pageOf([taskSummary]));
    await fixture.whenStable();

    expect(byTestId(fixture, 'owner-controls')).not.toBeNull();
  });
});
