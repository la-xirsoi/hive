import { HttpTestingController } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TaskDetail, TeamDetail, UserSummary } from '../../core/api/models';
import { alice, bob, taskDetail, teamDetail } from '../../core/test-support.spec';
import { API, featureProviders, type } from '../shared/feature-test-support.spec';
import { HiveTaskAssignment } from './task-assignment';

const carol: UserSummary = { id: 3, name: 'Carol Diaz', email: 'carol@hive.test' };

@Component({
  imports: [HiveTaskAssignment],
  template: `
    <hive-task-assignment [task]="task()" [busy]="busy()" (assigned)="assigned.set($event)" />
  `,
})
class AssignmentHost {
  readonly task = signal<TaskDetail>(taskDetail);
  readonly busy = signal(false);
  readonly assigned = signal<number | null | undefined>(undefined);
}

describe('HiveTaskAssignment', () => {
  let fixture: ComponentFixture<AssignmentHost>;
  let host: AssignmentHost;
  let http: HttpTestingController;

  const root = () => fixture.nativeElement as HTMLElement;
  const text = () => root().textContent ?? '';
  const select = () => root().querySelector<HTMLSelectElement>('[data-testid="assignee-select"]');
  const options = () =>
    Array.from(select()!.querySelectorAll('option'))
      .map((option) => option.textContent?.trim())
      .filter((label) => label !== 'Choose a member');

  /** `teamDetail` is Platform: lead alice, members alice and bob. */
  async function withTeam(team: TeamDetail = teamDetail): Promise<void> {
    http.expectOne(`${API}/teams/${team.id}`).flush(team);
    await fixture.whenStable();
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AssignmentHost],
      providers: featureProviders(),
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AssignmentHost);
    await fixture.whenStable();
    host = fixture.componentInstance;
  });

  afterEach(() => http.verify());

  it('reads the roster of the project’s own team, not the directory (AS-2)', () => {
    const request = http.expectOne(`${API}/teams/${taskDetail.project.team.id}`);
    expect(request.request.method).toBe('GET');
    request.flush(teamDetail);
  });

  it('shows a spinner while the roster loads', () => {
    expect(root().querySelector('hive-spinner')).not.toBeNull();
    http.expectOne(`${API}/teams/10`).flush(teamDetail);
  });

  it('excludes the project owner from the candidates (AS-4)', async () => {
    // alice owns Hive Core, so she can never be its assignee - even though she
    // is a member of Platform and the list is drawn from Platform's members.
    host.task.set({ ...taskDetail, assignee: null });
    await fixture.whenStable();
    await withTeam({ ...teamDetail, members: [alice, bob, carol] });

    expect(options()).toEqual(['Bob Ito', 'Carol Diaz']);
    expect(options()).not.toContain('Alice Ng');
  });

  it('lists the members who may actually take the task', async () => {
    await withTeam({ ...teamDetail, members: [alice, bob, carol] });

    // alice is the project owner (AS-4) and bob is already the assignee.
    expect(options()).toEqual(['Carol Diaz']);
  });

  it('keeps the project owner out even when they lead the team doing the assigning', async () => {
    await withTeam({ ...teamDetail, teamLead: alice, members: [alice, carol] });

    expect(options()).toEqual(['Carol Diaz']);
    expect(options()).not.toContain('Alice Ng');
  });

  it('offers a team lead who does not own the project, since AS-5 allows self-assignment', async () => {
    host.task.set({
      ...taskDetail,
      assignee: null,
      project: { ...taskDetail.project, projectOwner: carol },
    });
    await fixture.whenStable();
    await withTeam({ ...teamDetail, teamLead: bob, members: [alice, bob] });

    expect(options()).toEqual(['Alice Ng', 'Bob Ito']);
  });

  it('emits the chosen user id', async () => {
    await withTeam({ ...teamDetail, members: [alice, bob, carol] });

    type(select()!, String(carol.id), 'change');
    await fixture.whenStable();
    root().querySelector<HTMLButtonElement>('[data-testid="assign-task"] button')!.click();

    expect(host.assigned()).toBe(carol.id);
  });

  it('emits null to unassign, and offers that only when somebody holds the task', async () => {
    await withTeam({ ...teamDetail, members: [alice, bob, carol] });

    root().querySelector<HTMLButtonElement>('[data-testid="unassign-task"] button')!.click();
    expect(host.assigned()).toBeNull();

    host.task.set({ ...taskDetail, assignee: null });
    await fixture.whenStable();
    expect(root().querySelector('[data-testid="unassign-task"]')).toBeNull();
    expect(root().querySelector('[data-testid="currently-unassigned"]')).not.toBeNull();
  });

  it('explains the dead end when the owner is the only member', async () => {
    await withTeam({ ...teamDetail, members: [alice] });

    expect(root().querySelector('[data-testid="no-candidates"]')!.textContent).toContain(
      'The project owner cannot be assigned work in their own project',
    );
    expect(select()).toBeNull();
  });

  it('shows the server message when the roster cannot be read', async () => {
    http
      .expectOne(`${API}/teams/10`)
      .flush(
        { status: 404, error: 'NOT_FOUND', message: 'Team 10 was not found.' },
        { status: 404, statusText: 'Not Found' },
      );
    await fixture.whenStable();

    expect(text()).toContain('Team 10 was not found.');
  });

  it('does not re-read the roster when the task object is replaced by a mutation body', async () => {
    await withTeam();

    host.task.set({ ...taskDetail, status: 'In Progress' });
    await fixture.whenStable();

    expect(() => http.verify()).not.toThrow();
  });
});
