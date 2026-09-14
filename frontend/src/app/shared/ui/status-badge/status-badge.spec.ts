import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HiveStatusBadge, TASK_STATUSES, type TaskStatus } from './status-badge';

describe('HiveStatusBadge', () => {
  let fixture: ComponentFixture<HiveStatusBadge>;

  const root = () => fixture.nativeElement as HTMLElement;
  const badge = () => root().querySelector('.hive-status') as HTMLElement;
  const label = () => root().querySelector('.hive-status__label') as HTMLElement;

  const render = (status: TaskStatus) => {
    fixture.componentRef.setInput('status', status);
    fixture.detectChanges();
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [HiveStatusBadge] }).compileComponents();
    fixture = TestBed.createComponent(HiveStatusBadge);
  });

  it('exposes exactly the five statuses of the API contract', () => {
    expect(TASK_STATUSES).toEqual(['Draft', 'Todo', 'In Progress', 'Completed', 'Canceled']);
  });

  it('renders the status text for every status - colour is never the only cue', () => {
    for (const status of TASK_STATUSES) {
      render(status);
      expect(label().textContent?.trim()).toBe(status);
    }
  });

  it('gives each status its own modifier class', () => {
    const expected: Record<TaskStatus, string> = {
      Draft: 'hive-status--draft',
      Todo: 'hive-status--todo',
      'In Progress': 'hive-status--in-progress',
      Completed: 'hive-status--completed',
      Canceled: 'hive-status--canceled',
    };
    for (const status of TASK_STATUSES) {
      render(status);
      expect(badge().classList).toContain(expected[status]);
    }
  });

  it('adds a distinct shape glyph per status, hidden from assistive tech', () => {
    const glyphs = new Set<string>();
    for (const status of TASK_STATUSES) {
      render(status);
      const glyph = root().querySelector('.hive-status__glyph') as HTMLElement;
      expect(glyph.getAttribute('aria-hidden')).toBe('true');
      glyphs.add(glyph.textContent?.trim() ?? '');
    }
    expect(glyphs.size).toBe(TASK_STATUSES.length);
  });

  it('does not add a live region role (badges are static content)', () => {
    render('Todo');
    expect(badge().getAttribute('role')).toBeNull();
  });

  it('supports a large size', () => {
    fixture.componentRef.setInput('status', 'Completed');
    fixture.componentRef.setInput('size', 'lg');
    fixture.detectChanges();
    expect(badge().classList).toContain('hive-status--lg');
  });
});
