import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { TEST_API_BASE, pageOf, provideTestConfig, taskDetail, taskSummary } from '../test-support.spec';
import { ApiError } from './api-error';
import { TaskApi } from './task-api';

describe('TaskApi (contract section 5)', () => {
  let api: TaskApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideTestConfig()],
    });
    api = TestBed.inject(TaskApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('POST /tasks', async () => {
    const body = { projectId: 20, name: 'Wire the API client', description: 'Typed services.' };
    const result = firstValueFrom(api.create(body));
    const req = http.expectOne(`${TEST_API_BASE}/tasks`);

    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(body);
    req.flush({ ...taskDetail, status: 'Draft' }, { status: 201, statusText: 'Created' });
    expect((await result).status).toBe('Draft');
  });

  it('GET /tasks/{id} carries the server-computed permissions', async () => {
    const result = firstValueFrom(api.getById(42));
    const req = http.expectOne(`${TEST_API_BASE}/tasks/42`);

    expect(req.request.method).toBe('GET');
    req.flush(taskDetail);
    expect((await result).permissions.allowedTransitions).toEqual(['In Progress', 'Canceled']);
  });

  it('PATCH /tasks/{id} sends only the fields provided', async () => {
    const result = firstValueFrom(api.update(42, { description: 'Updated.' }));
    const req = http.expectOne(`${TEST_API_BASE}/tasks/42`);

    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ description: 'Updated.' });
    req.flush({ ...taskDetail, description: 'Updated.' });
    expect((await result).description).toBe('Updated.');
  });

  it('PUT /tasks/{id}/status', async () => {
    const result = firstValueFrom(api.updateStatus(42, { status: 'In Progress' }));
    const req = http.expectOne(`${TEST_API_BASE}/tasks/42/status`);

    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ status: 'In Progress' });
    req.flush({ ...taskDetail, status: 'In Progress' });
    expect((await result).status).toBe('In Progress');
  });

  it('surfaces the 409 for an illegal transition (section 2.1)', async () => {
    const result = firstValueFrom(api.updateStatus(42, { status: 'Completed' }));
    http.expectOne(`${TEST_API_BASE}/tasks/42/status`).flush(
      { status: 409, error: 'CONFLICT', message: 'A Todo task cannot move directly to Completed.' },
      { status: 409, statusText: 'Conflict' },
    );

    await expectAsync(result).toBeRejectedWithError(ApiError, /cannot move directly to Completed/);
  });

  it('PUT /tasks/{id}/assignee', async () => {
    const result = firstValueFrom(api.updateAssignee(42, { userId: 2 }));
    const req = http.expectOne(`${TEST_API_BASE}/tasks/42/assignee`);

    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ userId: 2 });
    req.flush(taskDetail);
    expect((await result).assignee?.id).toBe(2);
  });

  it('PUT /tasks/{id}/assignee sends an explicit null to unassign (AS-7)', async () => {
    const result = firstValueFrom(api.updateAssignee(42, { userId: null }));
    const req = http.expectOne(`${TEST_API_BASE}/tasks/42/assignee`);

    expect(req.request.body).toEqual({ userId: null });
    req.flush({ ...taskDetail, assignee: null });
    expect((await result).assignee).toBeNull();
  });

  it('GET /tasks/assigned-to-me', async () => {
    const result = firstValueFrom(api.listAssignedToMe({ page: 0, size: 20 }));
    const req = http.expectOne((r) => r.url === `${TEST_API_BASE}/tasks/assigned-to-me`);

    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('size')).toBe('20');
    req.flush(pageOf([taskSummary], { size: 20 }));
    expect((await result).content).toEqual([taskSummary]);
  });

  it('GET /tasks/unassigned returns an empty page for a non-lead (UQ-1)', async () => {
    const result = firstValueFrom(api.listUnassigned());
    const req = http.expectOne(`${TEST_API_BASE}/tasks/unassigned`);

    expect(req.request.method).toBe('GET');
    expect(req.request.params.keys()).toEqual([]);
    req.flush(pageOf([]));
    expect((await result).totalElements).toBe(0);
  });

  it('DELETE /tasks/{id} always fails with 405 (TK-6)', async () => {
    const result = firstValueFrom(api.deleteTask(42));
    const req = http.expectOne(`${TEST_API_BASE}/tasks/42`);

    expect(req.request.method).toBe('DELETE');
    req.flush(
      { status: 405, error: 'METHOD_NOT_ALLOWED', message: 'Cancel the task instead.' },
      { status: 405, statusText: 'Method Not Allowed' },
    );

    await expectAsync(result).toBeRejectedWithError(ApiError, 'Cancel the task instead.');
  });
});
