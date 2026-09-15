import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TaskSummary } from '../../core/api/models';
import { pageOf, taskSummary } from '../../core/test-support.spec';
import {
  API,
  byTestId,
  featureProviders,
  query,
  queryAll,
  text,
} from '../shared/feature-test-support.spec';
import { MyTasksPage } from './my-tasks';

const canceledAssignment: TaskSummary = { ...taskSummary, id: 44, status: 'Canceled' };

describe('MyTasksPage', () => {
  let fixture: ComponentFixture<MyTasksPage>;
  let http: HttpTestingController;

  const rows = () => queryAll(fixture, '.task-list__row');

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MyTasksPage],
      providers: featureProviders(),
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(MyTasksPage);
    await fixture.whenStable();
  });

  afterEach(() => http.verify({ ignoreCancelled: true }));

  it('shows a spinner while the page loads', () => {
    expect(query(fixture, 'hive-spinner')).not.toBeNull();
    http.expectOne((r) => r.url === `${API}/tasks/assigned-to-me`).flush(pageOf([]));
  });

  it('asks for the caller’s own assignments, unfiltered by status (VIS-5)', async () => {
    const request = http.expectOne((r) => r.url === `${API}/tasks/assigned-to-me`);
    expect(request.request.params.get('page')).toBe('0');
    expect(request.request.params.has('status')).toBeFalse();
    request.flush(pageOf([taskSummary, canceledAssignment]));
    await fixture.whenStable();

    expect(rows().length).toBe(2);
    expect(text(fixture)).toContain('Canceled');
  });

  it('offers the empty state when nothing is assigned', async () => {
    http.expectOne((r) => r.url === `${API}/tasks/assigned-to-me`).flush(pageOf([]));
    await fixture.whenStable();

    expect(text(fixture)).toContain('No tasks are assigned to you');
  });

  it('shows the server message when the list fails, with a retry', async () => {
    http
      .expectOne((r) => r.url === `${API}/tasks/assigned-to-me`)
      .flush(
        { status: 500, error: 'INTERNAL_ERROR', message: 'Tasks are unavailable.' },
        { status: 500, statusText: 'Server Error' },
      );
    await fixture.whenStable();

    expect(text(fixture)).toContain('Tasks are unavailable.');
    byTestId<HTMLElement>(fixture, 'retry')!.querySelector('button')!.click();
    await fixture.whenStable();
    http.expectOne((r) => r.url === `${API}/tasks/assigned-to-me`).flush(pageOf([taskSummary]));
    await fixture.whenStable();
    expect(rows().length).toBe(1);
  });

  it('hides the pager for a single page', async () => {
    http.expectOne((r) => r.url === `${API}/tasks/assigned-to-me`).flush(pageOf([taskSummary]));
    await fixture.whenStable();

    expect(byTestId(fixture, 'next-page')).toBeNull();
  });

  it('pages forward and back, announcing where the reader is', async () => {
    http
      .expectOne((r) => r.url === `${API}/tasks/assigned-to-me`)
      .flush(pageOf([taskSummary], { totalElements: 30, totalPages: 2 }));
    await fixture.whenStable();

    expect(text(fixture)).toContain('Page 1 of 2');
    expect(
      byTestId<HTMLElement>(fixture, 'previous-page')!.querySelector('button')!.disabled,
    ).toBeTrue();

    byTestId<HTMLElement>(fixture, 'next-page')!.querySelector('button')!.click();
    await fixture.whenStable();

    const request = http.expectOne((r) => r.url === `${API}/tasks/assigned-to-me`);
    expect(request.request.params.get('page')).toBe('1');
    request.flush(pageOf([canceledAssignment], { page: 1, totalElements: 30, totalPages: 2 }));
    await fixture.whenStable();

    expect(text(fixture)).toContain('Page 2 of 2');
    expect(
      byTestId<HTMLElement>(fixture, 'next-page')!.querySelector('button')!.disabled,
    ).toBeTrue();
  });
});
