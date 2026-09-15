import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TaskDetail, TaskPermissions } from '../../core/api/models';
import { bob, commentFixture, pageOf, taskDetail, teamDetail } from '../../core/test-support.spec';
import {
  API,
  byTestId,
  featureProviders,
  query,
  queryAll,
  text,
  type,
} from '../shared/feature-test-support.spec';
import { CONFLICT_HEADING } from '../shared/mutation';
import { TaskDetailPage } from './task-detail';

/** No rights at all: the shape the server returns for a passive observer. */
const noRights: TaskPermissions = {
  canEdit: false,
  canAssign: false,
  canComment: false,
  allowedTransitions: [],
};

describe('TaskDetailPage', () => {
  let fixture: ComponentFixture<TaskDetailPage>;
  let http: HttpTestingController;

  const transitionButtons = () =>
    queryAll(fixture, '[data-transition]').map((element) =>
      element.getAttribute('data-transition'),
    );

  /** Answers `GET /tasks/42`, plus whatever the permissions then cause. */
  async function load(detail: TaskDetail = taskDetail): Promise<void> {
    http.expectOne(`${API}/tasks/42`).flush(detail);
    await fixture.whenStable();
    if (detail.permissions.canAssign) {
      http.expectOne(`${API}/teams/${detail.project.team.id}`).flush(teamDetail);
    }
    const comments = http.match((r) => r.url === `${API}/tasks/42/comments`);
    for (const request of comments) {
      request.flush(pageOf([commentFixture]));
    }
    await fixture.whenStable();
  }

  function withPermissions(overrides: Partial<TaskPermissions>): TaskDetail {
    return { ...taskDetail, permissions: { ...taskDetail.permissions, ...overrides } };
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [TaskDetailPage],
      providers: featureProviders(),
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(TaskDetailPage);
    fixture.componentRef.setInput('id', '42');
    await fixture.whenStable();
  });

  afterEach(() => http.verify({ ignoreCancelled: true }));

  it('shows a spinner while the task loads', () => {
    expect(query(fixture, 'hive-spinner')).not.toBeNull();
    http.expectOne(`${API}/tasks/42`).flush(taskDetail);
  });

  it('renders the task, its project, creator and assignee', async () => {
    await load();

    expect(text(fixture)).toContain('Wire the API client');
    expect(text(fixture)).toContain('Typed services for every contract endpoint.');
    expect(text(fixture)).toContain('created by Alice Ng');
    expect(text(fixture)).toContain(bob.name);
    expect(query<HTMLAnchorElement>(fixture, 'a[href="/projects/20"]')).not.toBeNull();
  });

  it('shows the server message when the task cannot be read', async () => {
    http
      .expectOne(`${API}/tasks/42`)
      .flush(
        { status: 404, error: 'NOT_FOUND', message: 'Task 42 was not found.' },
        { status: 404, statusText: 'Not Found' },
      );
    await fixture.whenStable();

    expect(text(fixture)).toContain('Task 42 was not found.');
    expect(byTestId(fixture, 'edit-card')).toBeNull();
  });

  // -------------------------------------------------------------------------
  // The control surface comes from TaskPermissions and nothing else
  // -------------------------------------------------------------------------

  it('renders one status control per entry in allowedTransitions', async () => {
    await load();
    expect(transitionButtons()).toEqual(['In Progress', 'Canceled']);
  });

  it('renders a DIFFERENT control set purely because the server sent a different list', async () => {
    await load(withPermissions({ allowedTransitions: ['Todo', 'Canceled'] }));
    expect(transitionButtons()).toEqual(['Todo', 'Canceled']);
  });

  it('renders NO status control when the server allows none, even on a live task', async () => {
    await load(withPermissions({ allowedTransitions: [] }));

    expect(transitionButtons()).toEqual([]);
    expect(byTestId(fixture, 'no-transitions')).not.toBeNull();
  });

  it('sends the status the button was labelled with', async () => {
    await load();

    query<HTMLButtonElement>(fixture, '[data-transition="In Progress"] button')!.click();
    await fixture.whenStable();

    const request = http.expectOne((r) => r.url === `${API}/tasks/42/status` && r.method === 'PUT');
    expect(request.request.body).toEqual({ status: 'In Progress' });
    request.flush({ ...taskDetail, status: 'In Progress' });
    await fixture.whenStable();

    expect(text(fixture)).toContain('In Progress');
  });

  it('OMITS the edit form when canEdit is false', async () => {
    await load(withPermissions({ canEdit: false }));

    expect(byTestId(fixture, 'edit-card')).toBeNull();
    expect(byTestId(fixture, 'task-title')).toBeNull();
    expect(byTestId(fixture, 'save-task')).toBeNull();
  });

  it('shows the edit form prefilled when canEdit is true, and PATCHes both fields', async () => {
    await load();

    const title = byTestId<HTMLInputElement>(fixture, 'task-title')!;
    expect(title.value).toBe('Wire the API client');

    type(title, 'Wire the typed API client');
    await fixture.whenStable();
    query(fixture, '.task__form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    const request = http.expectOne((r) => r.url === `${API}/tasks/42` && r.method === 'PATCH');
    expect(request.request.body).toEqual({
      name: 'Wire the typed API client',
      description: taskDetail.description,
    });
    request.flush({ ...taskDetail, name: 'Wire the typed API client' });
    await fixture.whenStable();

    expect(text(fixture)).toContain('Wire the typed API client');
  });

  it('does not PATCH an emptied title', async () => {
    await load();

    type(byTestId<HTMLInputElement>(fixture, 'task-title')!, '   ');
    await fixture.whenStable();
    query(fixture, '.task__form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    expect(() => http.verify()).not.toThrow();
  });

  it('retries the read when the first attempt failed', async () => {
    http
      .expectOne(`${API}/tasks/42`)
      .flush(
        { status: 500, error: 'INTERNAL_ERROR', message: 'Tasks are unavailable.' },
        { status: 500, statusText: 'Server Error' },
      );
    await fixture.whenStable();

    byTestId<HTMLElement>(fixture, 'retry')!.querySelector('button')!.click();
    await fixture.whenStable();
    await load();

    expect(text(fixture)).toContain('Wire the API client');
  });

  it('OMITS the assignment panel when canAssign is false', async () => {
    await load(withPermissions({ canAssign: false }));

    expect(byTestId(fixture, 'assignment-card')).toBeNull();
    expect(byTestId(fixture, 'assignee-select')).toBeNull();
    // ...and never reads the team roster it would have needed.
    http.verify();
  });

  it('shows the assignment panel and PUTs the chosen assignee when canAssign is true', async () => {
    await load();

    expect(byTestId(fixture, 'assignment-card')).not.toBeNull();
    byTestId<HTMLElement>(fixture, 'unassign-task')!.querySelector('button')!.click();
    await fixture.whenStable();

    const request = http.expectOne(
      (r) => r.url === `${API}/tasks/42/assignee` && r.method === 'PUT',
    );
    expect(request.request.body).toEqual({ userId: null });
    request.flush({ ...taskDetail, assignee: null });
    await fixture.whenStable();

    expect(text(fixture)).toContain('Unassigned');
  });

  it('OMITS the comment form when canComment is false, while still showing the thread', async () => {
    await load(withPermissions({ canComment: false }));

    expect(byTestId(fixture, 'comment-form')).toBeNull();
    expect(text(fixture)).toContain('Starting on this now.');
  });

  it('strips every control at once for an actor the server granted nothing', async () => {
    await load(withPermissions(noRights));

    expect(byTestId(fixture, 'edit-card')).toBeNull();
    expect(byTestId(fixture, 'assignment-card')).toBeNull();
    expect(byTestId(fixture, 'comment-form')).toBeNull();
    expect(transitionButtons()).toEqual([]);
    // The record itself is still readable - that is what visibility means.
    expect(text(fixture)).toContain('Wire the API client');
  });

  // -------------------------------------------------------------------------
  // Terminal tasks
  // -------------------------------------------------------------------------

  it('renders a Completed task as visibly read-only', async () => {
    await load({
      ...taskDetail,
      status: 'Completed',
      permissions: { ...noRights, canComment: true },
    });

    expect(byTestId(fixture, 'terminal-notice')!.textContent).toContain('This task is Completed');
    expect(query(fixture, '.task--readonly')).not.toBeNull();
    expect(byTestId(fixture, 'edit-card')).toBeNull();
    expect(transitionButtons()).toEqual([]);
  });

  it('still lets a Completed task be commented on (TE-4)', async () => {
    await load({
      ...taskDetail,
      status: 'Completed',
      permissions: { ...noRights, canComment: true },
    });

    expect(byTestId(fixture, 'comment-form')).not.toBeNull();
  });

  // -------------------------------------------------------------------------
  // 409: the task moved underneath the viewer
  // -------------------------------------------------------------------------

  it('explains a 409 and reloads the task so the screen catches up', async () => {
    await load();

    query<HTMLButtonElement>(fixture, '[data-transition="In Progress"] button')!.click();
    await fixture.whenStable();

    http
      .expectOne((r) => r.url === `${API}/tasks/42/status`)
      .flush(
        {
          status: 409,
          error: 'CONFLICT',
          message: 'Task 42 is Canceled and can no longer be edited.',
        },
        { status: 409, statusText: 'Conflict' },
      );
    await fixture.whenStable();

    expect(byTestId(fixture, 'task-error')!.textContent).toContain(CONFLICT_HEADING);
    expect(byTestId(fixture, 'task-error')!.textContent).toContain(
      'Task 42 is Canceled and can no longer be edited.',
    );

    // The re-read is what makes the screen honest again: the task comes back
    // terminal, with no permissions, and every control disappears.
    http.expectOne(`${API}/tasks/42`).flush({
      ...taskDetail,
      status: 'Canceled',
      permissions: noRights,
    });
    await fixture.whenStable();
    const comments = http.match((r) => r.url === `${API}/tasks/42/comments`);
    for (const request of comments) {
      request.flush(pageOf([commentFixture]));
    }
    await fixture.whenStable();

    expect(byTestId(fixture, 'terminal-notice')!.textContent).toContain('This task is Canceled');
    expect(transitionButtons()).toEqual([]);
    expect(byTestId(fixture, 'edit-card')).toBeNull();
  });

  it('does NOT reload after an ordinary failure, which leaves the screen accurate', async () => {
    await load();

    query<HTMLButtonElement>(fixture, '[data-transition="In Progress"] button')!.click();
    await fixture.whenStable();

    http
      .expectOne((r) => r.url === `${API}/tasks/42/status`)
      .flush(
        { status: 403, error: 'FORBIDDEN', message: 'Only the assignee may start this task.' },
        { status: 403, statusText: 'Forbidden' },
      );
    await fixture.whenStable();

    expect(byTestId(fixture, 'task-error')!.textContent).toContain(
      'Only the assignee may start this task.',
    );
    expect(byTestId(fixture, 'task-error')!.textContent).not.toContain(CONFLICT_HEADING);
    http.verify();
  });

  it('lets the viewer dismiss the message', async () => {
    await load();

    query<HTMLButtonElement>(fixture, '[data-transition="Canceled"] button')!.click();
    await fixture.whenStable();
    http
      .expectOne((r) => r.url === `${API}/tasks/42/status`)
      .flush(
        { status: 403, error: 'FORBIDDEN', message: 'No.' },
        { status: 403, statusText: 'Forbidden' },
      );
    await fixture.whenStable();

    byTestId<HTMLElement>(fixture, 'task-error')!.querySelector('button')!.click();
    await fixture.whenStable();

    expect(byTestId(fixture, 'task-error')).toBeNull();
  });
});
