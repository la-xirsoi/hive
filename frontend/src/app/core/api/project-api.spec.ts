import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import {
  TEST_API_BASE,
  bob,
  pageOf,
  projectSummary,
  provideTestConfig,
  taskSummary,
} from '../test-support.spec';
import { ApiError } from './api-error';
import { ProjectApi } from './project-api';

describe('ProjectApi (contract section 4)', () => {
  let api: ProjectApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideTestConfig()],
    });
    api = TestBed.inject(ProjectApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('POST /projects', async () => {
    const result = firstValueFrom(api.create({ name: 'Hive Core', teamId: 10 }));
    const req = http.expectOne(`${TEST_API_BASE}/projects`);

    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ name: 'Hive Core', teamId: 10 });
    req.flush(projectSummary, { status: 201, statusText: 'Created' });
    expect(await result).toEqual(projectSummary);
  });

  it('GET /projects/mine returns a bare array', async () => {
    const result = firstValueFrom(api.listMine());
    const req = http.expectOne(`${TEST_API_BASE}/projects/mine`);

    expect(req.request.method).toBe('GET');
    req.flush([projectSummary]);
    expect(await result).toEqual([projectSummary]);
  });

  it('GET /projects/{id}', async () => {
    const result = firstValueFrom(api.getById(20));
    const req = http.expectOne(`${TEST_API_BASE}/projects/20`);

    expect(req.request.method).toBe('GET');
    req.flush(projectSummary);
    expect(await result).toEqual(projectSummary);
  });

  it('PATCH /projects/{id}', async () => {
    const result = firstValueFrom(api.rename(20, { name: 'Hive Core v2' }));
    const req = http.expectOne(`${TEST_API_BASE}/projects/20`);

    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ name: 'Hive Core v2' });
    req.flush({ ...projectSummary, name: 'Hive Core v2' });
    expect((await result).name).toBe('Hive Core v2');
  });

  it('PUT /projects/{id}/owner', async () => {
    const result = firstValueFrom(api.transferOwner(20, { userId: 2 }));
    const req = http.expectOne(`${TEST_API_BASE}/projects/20/owner`);

    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ userId: 2 });
    req.flush({ ...projectSummary, projectOwner: bob });
    expect((await result).projectOwner).toEqual(bob);
  });

  it('keeps the blocking task ids from a PR-8 409 message', async () => {
    const result = firstValueFrom(api.transferOwner(20, { userId: 2 }));
    http.expectOne(`${TEST_API_BASE}/projects/20/owner`).flush(
      {
        status: 409,
        error: 'CONFLICT',
        message: 'Bob Ito is the assignee of live tasks 42, 43 in this project.',
      },
      { status: 409, statusText: 'Conflict' },
    );

    await expectAsync(result).toBeRejectedWithError(ApiError, /tasks 42, 43/);
  });

  it('GET /projects/{id}/tasks with paging', async () => {
    const result = firstValueFrom(api.listTasks(20, { page: 1, size: 25 }));
    const req = http.expectOne((r) => r.url === `${TEST_API_BASE}/projects/20/tasks`);

    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('page')).toBe('1');
    expect(req.request.params.get('size')).toBe('25');
    expect(req.request.params.get('status')).toBeNull();
    req.flush(pageOf([taskSummary], { page: 1, size: 25 }));
    expect((await result).content).toEqual([taskSummary]);
  });

  it('GET /projects/{id}/tasks sends the status filter as a comma-separated list', async () => {
    const result = firstValueFrom(api.listTasks(20, { status: ['Todo', 'In Progress'] }));
    const req = http.expectOne((r) => r.url === `${TEST_API_BASE}/projects/20/tasks`);

    expect(req.request.params.get('status')).toBe('Todo,In Progress');
    req.flush(pageOf([taskSummary]));
    await result;
  });

  it('GET /projects/{id}/tasks omits an empty status filter', async () => {
    const result = firstValueFrom(api.listTasks(20, { status: [] }));
    const req = http.expectOne(`${TEST_API_BASE}/projects/20/tasks`);

    expect(req.request.params.keys()).toEqual([]);
    req.flush(pageOf([]));
    await result;
  });

  it('DELETE /projects/{id} always fails with 405 (PR-10)', async () => {
    const result = firstValueFrom(api.deleteProject(20));
    const req = http.expectOne(`${TEST_API_BASE}/projects/20`);

    expect(req.request.method).toBe('DELETE');
    req.flush(
      { status: 405, error: 'METHOD_NOT_ALLOWED', message: 'Projects cannot be deleted.' },
      { status: 405, statusText: 'Method Not Allowed' },
    );

    await expectAsync(result).toBeRejectedWithError(ApiError, 'Projects cannot be deleted.');
  });
});
