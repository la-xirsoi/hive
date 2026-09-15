import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import {
  TEST_API_BASE,
  bob,
  projectSummary,
  provideTestConfig,
  teamDetail,
  teamSummary,
} from '../test-support.spec';
import { ApiError } from './api-error';
import { TeamApi } from './team-api';

describe('TeamApi (contract section 3)', () => {
  let api: TeamApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideTestConfig()],
    });
    api = TestBed.inject(TeamApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('POST /teams', async () => {
    const result = firstValueFrom(api.create({ name: 'Platform' }));
    const req = http.expectOne(`${TEST_API_BASE}/teams`);

    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ name: 'Platform' });
    req.flush(teamDetail, { status: 201, statusText: 'Created' });
    expect(await result).toEqual(teamDetail);
  });

  it('GET /teams/mine returns a bare array', async () => {
    const result = firstValueFrom(api.listMine());
    const req = http.expectOne(`${TEST_API_BASE}/teams/mine`);

    expect(req.request.method).toBe('GET');
    req.flush([teamSummary]);
    expect(await result).toEqual([teamSummary]);
  });

  it('GET /teams/{id}', async () => {
    const result = firstValueFrom(api.getById(10));
    const req = http.expectOne(`${TEST_API_BASE}/teams/10`);

    expect(req.request.method).toBe('GET');
    req.flush(teamDetail);
    expect(await result).toEqual(teamDetail);
  });

  it('PATCH /teams/{id}', async () => {
    const result = firstValueFrom(api.rename(10, { name: 'Platform Eng' }));
    const req = http.expectOne(`${TEST_API_BASE}/teams/10`);

    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ name: 'Platform Eng' });
    req.flush({ ...teamDetail, name: 'Platform Eng' });
    expect((await result).name).toBe('Platform Eng');
  });

  it('POST /teams/{id}/members', async () => {
    const result = firstValueFrom(api.addMember(10, { userId: 2 }));
    const req = http.expectOne(`${TEST_API_BASE}/teams/10/members`);

    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ userId: 2 });
    req.flush(teamDetail);
    expect((await result).members).toContain(bob);
  });

  it('DELETE /teams/{id}/members/{userId}', async () => {
    const result = firstValueFrom(api.removeMember(10, 2));
    const req = http.expectOne(`${TEST_API_BASE}/teams/10/members/2`);

    expect(req.request.method).toBe('DELETE');
    req.flush({ ...teamDetail, members: [teamDetail.teamLead] });
    expect((await result).members.length).toBe(1);
  });

  it('surfaces the 409 when removing the current lead (TM-7)', async () => {
    const result = firstValueFrom(api.removeMember(10, 1));
    http.expectOne(`${TEST_API_BASE}/teams/10/members/1`).flush(
      {
        status: 409,
        error: 'CONFLICT',
        message: 'Alice Ng leads this team and cannot be removed.',
      },
      { status: 409, statusText: 'Conflict' },
    );

    await expectAsync(result).toBeRejectedWithError(
      ApiError,
      'Alice Ng leads this team and cannot be removed.',
    );
  });

  it('PUT /teams/{id}/lead', async () => {
    const result = firstValueFrom(api.transferLead(10, { userId: 2 }));
    const req = http.expectOne(`${TEST_API_BASE}/teams/10/lead`);

    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ userId: 2 });
    req.flush({ ...teamDetail, teamLead: bob });
    expect((await result).teamLead).toEqual(bob);
  });

  it('GET /teams/{id}/projects returns a bare array', async () => {
    const result = firstValueFrom(api.listProjects(10));
    const req = http.expectOne(`${TEST_API_BASE}/teams/10/projects`);

    expect(req.request.method).toBe('GET');
    req.flush([projectSummary]);
    expect(await result).toEqual([projectSummary]);
  });

  it('DELETE /teams/{id} always fails with 405 (TM-11)', async () => {
    const result = firstValueFrom(api.deleteTeam(10));
    const req = http.expectOne(`${TEST_API_BASE}/teams/10`);

    expect(req.request.method).toBe('DELETE');
    req.flush(
      { status: 405, error: 'METHOD_NOT_ALLOWED', message: 'Teams cannot be deleted.' },
      { status: 405, statusText: 'Method Not Allowed' },
    );

    await expectAsync(result).toBeRejectedWithError(ApiError, 'Teams cannot be deleted.');
  });
});
