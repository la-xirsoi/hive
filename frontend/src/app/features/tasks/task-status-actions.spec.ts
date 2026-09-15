import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TaskStatus } from '../../core/api/models';
import { HiveTaskStatusActions } from './task-status-actions';

@Component({
  imports: [HiveTaskStatusActions],
  template: `
    <hive-task-status-actions
      [status]="status()"
      [transitions]="transitions()"
      [busy]="busy()"
      (requested)="requested.set($event)"
    />
  `,
})
class StatusHost {
  readonly status = signal<TaskStatus>('Todo');
  readonly transitions = signal<readonly TaskStatus[]>(['In Progress', 'Canceled']);
  readonly busy = signal(false);
  readonly requested = signal<TaskStatus | null>(null);
}

describe('HiveTaskStatusActions', () => {
  let fixture: ComponentFixture<StatusHost>;
  let host: StatusHost;

  const root = () => fixture.nativeElement as HTMLElement;
  const text = () => root().textContent ?? '';
  const buttons = () => Array.from(root().querySelectorAll('[data-transition]'));
  const button = (target: string) =>
    root().querySelector<HTMLElement>(`[data-transition="${target}"] button`);

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [StatusHost] }).compileComponents();
    fixture = TestBed.createComponent(StatusHost);
    host = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('renders exactly one control per allowed transition', () => {
    expect(buttons().length).toBe(2);
    expect(button('In Progress')!.textContent).toContain('Start work');
    expect(button('Canceled')!.textContent).toContain('Cancel task');
  });

  it('emits the target status the server named', () => {
    button('In Progress')!.click();
    expect(host.requested()).toBe('In Progress');
  });

  it('renders NO control for a transition the server left out, however plausible', async () => {
    // A Todo task whose actor is the project owner: the owner may cancel it but
    // is not its assignee, so `In Progress` is simply not in the list.
    host.transitions.set(['Canceled']);
    await fixture.whenStable();

    expect(buttons().length).toBe(1);
    expect(button('In Progress')).toBeNull();
    expect(button('Completed')).toBeNull();
  });

  it('says plainly that the actor cannot move a live task, rather than offering a dead button', async () => {
    host.transitions.set([]);
    await fixture.whenStable();

    expect(buttons().length).toBe(0);
    expect(root().querySelector('[data-testid="no-transitions"]')!.textContent).toContain(
      'You cannot change the status of this task.',
    );
  });

  it('announces a Completed task as finished and read-only', async () => {
    host.status.set('Completed');
    host.transitions.set([]);
    await fixture.whenStable();

    const notice = root().querySelector('[data-testid="terminal-notice"]')!;
    expect(notice.textContent).toContain('This task is Completed');
    expect(notice.textContent).toContain('read-only');
    expect(root().querySelector('[data-testid="no-transitions"]')).toBeNull();
    expect(buttons().length).toBe(0);
  });

  it('announces a Canceled task the same way', async () => {
    host.status.set('Canceled');
    host.transitions.set([]);
    await fixture.whenStable();

    expect(root().querySelector('[data-testid="terminal-notice"]')!.textContent).toContain(
      'This task is Canceled',
    );
  });

  it('keeps the status visible in words as well as colour', () => {
    expect(root().querySelector('hive-status-badge')!.textContent).toContain('Todo');
    expect(text()).toContain('Status');
  });

  it('groups the controls for screen readers', () => {
    const group = root().querySelector('[role="group"]');
    expect(group?.getAttribute('aria-label')).toBe('Change status');
  });

  it('disables the controls while a transition is in flight', async () => {
    host.busy.set(true);
    await fixture.whenStable();

    expect(
      root().querySelector<HTMLButtonElement>('[data-transition] button')!.disabled,
    ).toBeTrue();
  });
});
