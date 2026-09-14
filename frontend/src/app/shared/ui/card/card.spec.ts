import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HiveCard } from './card';

@Component({
  imports: [HiveCard],
  template: `
    <hive-card
      [padding]="padding()"
      [elevation]="elevation()"
      [accent]="accent()"
      [interactive]="interactive()"
    >
      @if (withHeader()) {
        <h3 hive-card-header>Sprint 42</h3>
      }
      <p>Body copy</p>
      @if (withFooter()) {
        <span hive-card-footer>3 tasks</span>
      }
    </hive-card>
  `,
})
class CardHost {
  readonly padding = signal<'none' | 'sm' | 'md' | 'lg'>('md');
  readonly elevation = signal<'flat' | 'raised' | 'floating'>('raised');
  readonly accent = signal(false);
  readonly interactive = signal(false);
  readonly withHeader = signal(true);
  readonly withFooter = signal(true);
}

describe('HiveCard', () => {
  let fixture: ComponentFixture<CardHost>;
  let host: CardHost;

  const root = () => fixture.nativeElement as HTMLElement;
  const card = () => root().querySelector('.hive-card') as HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [CardHost] }).compileComponents();
    fixture = TestBed.createComponent(CardHost);
    host = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('projects header, body and footer into their slots', () => {
    expect(root().querySelector('.hive-card__header')?.textContent).toContain('Sprint 42');
    expect(root().querySelector('.hive-card__body')?.textContent).toContain('Body copy');
    expect(root().querySelector('.hive-card__footer')?.textContent).toContain('3 tasks');
  });

  it('leaves unused slots empty so the :empty rule collapses them', () => {
    host.withHeader.set(false);
    host.withFooter.set(false);
    fixture.detectChanges();
    expect(root().querySelector('.hive-card__header')?.textContent?.trim()).toBe('');
    expect(root().querySelector('.hive-card__footer')?.textContent?.trim()).toBe('');
  });

  it('applies padding and elevation modifiers', () => {
    host.padding.set('lg');
    host.elevation.set('floating');
    fixture.detectChanges();
    expect(card().classList).toContain('hive-card--pad-lg');
    expect(card().classList).toContain('hive-card--floating');
  });

  it('adds the gold accent bar only when asked', () => {
    expect(card().classList).not.toContain('hive-card--accent');
    host.accent.set(true);
    fixture.detectChanges();
    expect(card().classList).toContain('hive-card--accent');
  });

  it('adds interactive affordances only when asked', () => {
    expect(card().classList).not.toContain('hive-card--interactive');
    host.interactive.set(true);
    fixture.detectChanges();
    expect(card().classList).toContain('hive-card--interactive');
  });

  it('is a plain container - it never steals focus or invents a role', () => {
    expect(card().getAttribute('role')).toBeNull();
    expect(card().getAttribute('tabindex')).toBeNull();
  });
});
