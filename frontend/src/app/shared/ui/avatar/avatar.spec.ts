import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HiveAvatar, initialsFor } from './avatar';

describe('initialsFor', () => {
  it('takes the first and last initial', () => {
    expect(initialsFor('Ada Lovelace')).toBe('AL');
  });

  it('handles a single name', () => {
    expect(initialsFor('Ada')).toBe('A');
  });

  it('ignores middle names and extra whitespace', () => {
    expect(initialsFor('  ada  byron   lovelace ')).toBe('AL');
  });

  it('falls back to a question mark for an empty name', () => {
    expect(initialsFor('   ')).toBe('?');
  });
});

describe('HiveAvatar', () => {
  let fixture: ComponentFixture<HiveAvatar>;

  const root = () => fixture.nativeElement as HTMLElement;
  const avatar = () => root().querySelector('.hive-avatar') as HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [HiveAvatar] }).compileComponents();
    fixture = TestBed.createComponent(HiveAvatar);
    fixture.componentRef.setInput('name', 'Ada Lovelace');
    fixture.detectChanges();
  });

  it('renders the initials', () => {
    expect(root().querySelector('.hive-avatar__initials')?.textContent?.trim()).toBe('AL');
  });

  it('is an image labelled with the full name, not the initials', () => {
    expect(avatar().getAttribute('role')).toBe('img');
    expect(avatar().getAttribute('aria-label')).toBe('Ada Lovelace');
  });

  it('can be marked decorative when an adjacent element already names the user', () => {
    fixture.componentRef.setInput('decorative', true);
    fixture.detectChanges();
    expect(avatar().getAttribute('role')).toBeNull();
    expect(avatar().getAttribute('aria-hidden')).toBe('true');
    expect(avatar().getAttribute('aria-label')).toBeNull();
  });

  it('renders a photo with an empty alt when an image url is given', () => {
    // Inline pixel: keeps the test off the network (no 404 in the karma log).
    fixture.componentRef.setInput(
      'imageUrl',
      'data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7',
    );
    fixture.detectChanges();
    const img = root().querySelector('img') as HTMLImageElement;
    expect(img.getAttribute('alt')).toBe('');
    expect(root().querySelector('.hive-avatar__initials')).toBeNull();
  });

  it('picks a deterministic tone from the verified-contrast set', () => {
    const toneOf = () =>
      Array.from(avatar().classList).find(
        (name) => name.startsWith('hive-avatar--') && !name.match(/--(xs|sm|md|lg)$/),
      );
    const first = toneOf();
    fixture.componentRef.setInput('name', 'Ada Lovelace');
    fixture.detectChanges();
    expect(toneOf()).toBe(first);
    expect(['hive-avatar--gold', 'hive-avatar--ink', 'hive-avatar--neutral']).toContain(first!);
  });

  it('honours an explicit tone and size', () => {
    fixture.componentRef.setInput('tone', 'ink');
    fixture.componentRef.setInput('size', 'lg');
    fixture.detectChanges();
    expect(avatar().classList).toContain('hive-avatar--ink');
    expect(avatar().classList).toContain('hive-avatar--lg');
  });
});
