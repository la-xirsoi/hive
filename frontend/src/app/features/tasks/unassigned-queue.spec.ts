import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TaskSummary } from '../../core/api/models';
import { pageOf } from '../../core/test-support.spec';
import {
  API,
  byTestId,
  featureProviders,
  query,
  queryAll,
  text,
} from '../shared/feature-test-support.spec';
import { UnassignedQueuePage } from './unassigned-queue';

const waiting: TaskSummary = {
  id: 43,
  name: 'Write the runbook',
  status: 'Todo',
  projectId: 20,
  projectName: 'Hive Core',
  assignee: null,
};

describe('UnassignedQueuePage', () => {
  let fixture: ComponentFixture<UnassignedQueuePage>;
  let http: HttpTestingController;

  const rows = () => queryAll(fixture, '.task-list__row');

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [UnassignedQueuePage],
      providers: featureProviders(),
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(UnassignedQueuePage);
    await fixture.whenStable();
  });

  afterEach(() => http.verify({ ignoreCancelled: true }));

  it('shows a spinner while the queue loads', () => {
    expect(query(fixture, 'hive-spinner')).not.toBeNull();
    http.expectOne((r) => r.url === `${API}/tasks/unassigned`).flush(pageOf([]));
  });

  it('reads the queue from the server and never re-derives who leads what', async () => {
    const request = http.expectOne((r) => r.url === `${API}/tasks/unassigned`);
    request.flush(pageOf([waiting]));
    await fixture.whenStable();

    expect(rows().length).toBe(1);
    expect(text(fixture)).toContain('Write the runbook');
    expect(text(fixture)).toContain('Assigning these is a priority');
    // No `teams/mine` lookup: the server alone decides what belongs here.
    http.verify();
  });

  it('links each waiting task to the screen that can assign it', async () => {
    http.expectOne((r) => r.url === `${API}/tasks/unassigned`).flush(pageOf([waiting]));
    await fixture.whenStable();

    expect(query<HTMLAnchorElement>(fixture, 'a[href="/tasks/43"]')).not.toBeNull();
  });

  it('tells a non-lead why the queue is empty rather than looking broken', async () => {
    http.expectOne((r) => r.url === `${API}/tasks/unassigned`).flush(pageOf([]));
    await fixture.whenStable();

    expect(text(fixture)).toContain('The queue is clear');
    expect(text(fixture)).toContain('If you do not lead a team, this queue is always empty.');
  });

  it('shows the server message when the queue fails', async () => {
    http
      .expectOne((r) => r.url === `${API}/tasks/unassigned`)
      .flush(
        { status: 500, error: 'INTERNAL_ERROR', message: 'The queue is unavailable.' },
        { status: 500, statusText: 'Server Error' },
      );
    await fixture.whenStable();

    expect(text(fixture)).toContain('The queue is unavailable.');
  });

  it('pages through a long queue, saying how much is waiting', async () => {
    http
      .expectOne((r) => r.url === `${API}/tasks/unassigned`)
      .flush(pageOf([waiting], { totalElements: 25, totalPages: 2 }));
    await fixture.whenStable();

    expect(text(fixture)).toContain('25 waiting');

    byTestId<HTMLElement>(fixture, 'next-page')!.querySelector('button')!.click();
    await fixture.whenStable();

    const request = http.expectOne((r) => r.url === `${API}/tasks/unassigned`);
    expect(request.request.params.get('page')).toBe('1');
    request.flush(pageOf([waiting], { page: 1, totalElements: 25, totalPages: 2 }));
    await fixture.whenStable();

    expect(text(fixture)).toContain('Page 2 of 2');
  });
});
