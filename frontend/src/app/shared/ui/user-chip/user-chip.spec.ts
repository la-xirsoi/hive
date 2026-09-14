import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HiveUserChip } from './user-chip';

describe('HiveUserChip', () => {
  let fixture: ComponentFixture<HiveUserChip>;

  const root = () => fixture.nativeElement as HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [HiveUserChip] }).compileComponents();
    fixture = TestBed.createComponent(HiveUserChip);
    fixture.componentRef.setInput('name', 'Ada Lovelace');
    fixture.detectChanges();
  });

  it('shows the name as visible text', () => {
    expect(root().querySelector('.hive-chip__name')?.textContent?.trim()).toBe('Ada Lovelace');
  });

  it('marks the avatar decorative so the name is not announced twice', () => {
    const avatar = root().querySelector('.hive-avatar') as HTMLElement;
    expect(avatar.getAttribute('aria-hidden')).toBe('true');
    expect(avatar.getAttribute('role')).toBeNull();
  });

  it('omits the secondary line unless provided', () => {
    expect(root().querySelector('.hive-chip__secondary')).toBeNull();
    fixture.componentRef.setInput('secondary', 'ada@hive.test');
    fixture.detectChanges();
    expect(root().querySelector('.hive-chip__secondary')?.textContent?.trim()).toBe(
      'ada@hive.test',
    );
  });

  it('has no remove button by default', () => {
    expect(root().querySelector('.hive-chip__remove')).toBeNull();
  });

  it('gives the remove button a name-bearing accessible label', () => {
    fixture.componentRef.setInput('removable', true);
    fixture.detectChanges();
    const remove = root().querySelector('.hive-chip__remove') as HTMLButtonElement;
    expect(remove.getAttribute('aria-label')).toBe('Remove Ada Lovelace');
    expect(remove.getAttribute('type')).toBe('button');
  });

  it('emits removed when the remove button is pressed', () => {
    fixture.componentRef.setInput('removable', true);
    fixture.detectChanges();
    let removed = 0;
    fixture.componentInstance.removed.subscribe(() => removed++);
    (root().querySelector('.hive-chip__remove') as HTMLButtonElement).click();
    expect(removed).toBe(1);
  });

  it('supports an outlined pill', () => {
    fixture.componentRef.setInput('outlined', true);
    fixture.detectChanges();
    expect((root().querySelector('.hive-chip') as HTMLElement).classList).toContain(
      'hive-chip--outlined',
    );
  });
});
