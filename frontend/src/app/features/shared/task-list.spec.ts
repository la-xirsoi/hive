import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { TaskSummary } from '../../core/api/models';
import { taskSummary } from '../../core/test-support.spec';
import { HiveTaskList } from './task-list';

const unassignedTask: TaskSummary = {
  id: 43,
  name: 'Write the runbook',
  status: 'Todo',
  projectId: 20,
  projectName: 'Hive Core',
  assignee: null,
};

@Component({
  imports: [HiveTaskList],
  template: `
    <hive-task-list
      [tasks]="tasks()"
      [showProject]="showProject()"
      listLabel="Tasks assigned to me"
    />
  `,
})
class TaskListHost {
  readonly tasks = signal<readonly TaskSummary[]>([taskSummary, unassignedTask]);
  readonly showProject = signal(true);
}

describe('HiveTaskList', () => {
  let fixture: ComponentFixture<TaskListHost>;
  let host: TaskListHost;

  const root = () => fixture.nativeElement as HTMLElement;
  const rows = () => Array.from(root().querySelectorAll('.task-list__row'));

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [TaskListHost],
      providers: [provideRouter([])],
    }).compileComponents();
    fixture = TestBed.createComponent(TaskListHost);
    host = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('renders one row per task, linked to the task', () => {
    expect(rows().length).toBe(2);
    const link = root().querySelector<HTMLAnchorElement>('.task-list__link');
    expect(link?.textContent).toContain('Wire the API client');
    expect(link?.getAttribute('href')).toBe('/tasks/42');
  });

  it('names the list for assistive technology', () => {
    expect(root().querySelector('ul')?.getAttribute('aria-label')).toBe('Tasks assigned to me');
  });

  it('states the status in words, never by colour alone', () => {
    expect(root().querySelector('hive-status-badge')?.textContent).toContain('Todo');
  });

  it('says "Unassigned" in words when nobody holds the task', () => {
    expect(rows()[1].textContent).toContain('Unassigned');
    expect(rows()[0].textContent).toContain('Bob Ito');
  });

  it('hides the project name when the surrounding screen already names it', async () => {
    expect(root().querySelector('.task-list__project')).not.toBeNull();

    host.showProject.set(false);
    await fixture.whenStable();
    expect(root().querySelector('.task-list__project')).toBeNull();
  });

  it('renders nothing but the labelled list when there are no tasks', async () => {
    host.tasks.set([]);
    await fixture.whenStable();
    expect(rows().length).toBe(0);
  });
});
