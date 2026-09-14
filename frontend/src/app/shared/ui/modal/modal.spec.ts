import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HiveModal } from './modal';

@Component({
  imports: [HiveModal],
  template: `
    <button type="button" id="opener" (click)="open.set(true)">Open</button>
    <hive-modal
      [open]="open()"
      heading="Delete task"
      [dismissible]="dismissible()"
      (closed)="onClosed($event)"
    >
      <p>This cannot be undone.</p>
      <button hive-modal-footer type="button" id="confirm">Delete</button>
    </hive-modal>
  `,
})
class ModalHost {
  readonly open = signal(false);
  readonly dismissible = signal(true);
  readonly reasons = signal<string[]>([]);

  onClosed(reason: string): void {
    this.reasons.update((list) => [...list, reason]);
    this.open.set(false);
  }
}

describe('HiveModal', () => {
  let fixture: ComponentFixture<ModalHost>;
  let host: ModalHost;

  const root = () => fixture.nativeElement as HTMLElement;
  const dialog = () => root().querySelector('[role="dialog"]') as HTMLElement | null;
  const backdrop = () => root().querySelector('.hive-modal__backdrop') as HTMLElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [ModalHost] }).compileComponents();
    fixture = TestBed.createComponent(ModalHost);
    host = fixture.componentInstance;
    fixture.detectChanges();
  });

  const openModal = () => {
    host.open.set(true);
    fixture.detectChanges();
  };

  it('renders nothing while closed, so nothing behind it is reachable', () => {
    expect(dialog()).toBeNull();
  });

  it('renders a modal dialog labelled by its heading', () => {
    openModal();
    const el = dialog()!;
    expect(el.getAttribute('aria-modal')).toBe('true');
    const labelledBy = el.getAttribute('aria-labelledby');
    expect(labelledBy).toBeTruthy();
    const title = root().querySelector(`#${labelledBy}`) as HTMLElement;
    expect(title.textContent?.trim()).toBe('Delete task');
  });

  it('projects the body and the footer slot', () => {
    openModal();
    expect(root().querySelector('.hive-modal__body')?.textContent).toContain('cannot be undone');
    expect(root().querySelector('.hive-modal__footer')?.textContent).toContain('Delete');
  });

  it('moves focus into the dialog when it opens', () => {
    openModal();
    expect(dialog()!.contains(document.activeElement)).toBeTrue();
  });

  it('restores focus to the previously focused element when it closes', () => {
    const opener = root().querySelector('#opener') as HTMLButtonElement;
    opener.focus();
    expect(document.activeElement).toBe(opener);

    openModal();
    expect(document.activeElement).not.toBe(opener);

    host.open.set(false);
    fixture.detectChanges();
    expect(document.activeElement).toBe(opener);
  });

  it('closes on Escape with the escape reason', () => {
    openModal();
    dialog()!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    fixture.detectChanges();
    expect(host.reasons()).toEqual(['escape']);
    expect(dialog()).toBeNull();
  });

  it('closes on a backdrop click but not on a click inside the dialog', () => {
    openModal();
    dialog()!.click();
    fixture.detectChanges();
    expect(host.reasons()).toEqual([]);

    backdrop().click();
    fixture.detectChanges();
    expect(host.reasons()).toEqual(['backdrop']);
  });

  it('closes from the labelled close button', () => {
    openModal();
    const close = root().querySelector('.hive-modal__close') as HTMLButtonElement;
    expect(close.getAttribute('aria-label')).toBe('Close dialog');
    close.click();
    fixture.detectChanges();
    expect(host.reasons()).toEqual(['close-button']);
  });

  it('hides the close button and ignores Escape when not dismissible', () => {
    host.dismissible.set(false);
    openModal();
    expect(root().querySelector('.hive-modal__close')).toBeNull();
    dialog()!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    fixture.detectChanges();
    expect(host.reasons()).toEqual([]);
    expect(dialog()).not.toBeNull();
  });

  it('traps Tab inside the dialog', () => {
    openModal();

    const focusable = Array.from(
      dialog()!.querySelectorAll<HTMLElement>('button, [tabindex]:not([tabindex="-1"])'),
    );
    expect(focusable.length).toBeGreaterThan(1);
    const last = focusable[focusable.length - 1]!;
    last.focus();

    const event = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true });
    dialog()!.dispatchEvent(event);
    fixture.detectChanges();

    expect(event.defaultPrevented).toBeTrue();
    expect(document.activeElement).toBe(focusable[0]!);
  });
});
