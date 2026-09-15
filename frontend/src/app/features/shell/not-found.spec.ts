import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { NotFoundPage } from './not-found';

describe('NotFoundPage', () => {
  it('explains the miss without confirming whether the record exists', async () => {
    await TestBed.configureTestingModule({
      imports: [NotFoundPage],
      providers: [provideRouter([])],
    }).compileComponents();

    const fixture = TestBed.createComponent(NotFoundPage);
    await fixture.whenStable();
    const root = fixture.nativeElement as HTMLElement;

    expect(root.textContent).toContain('We could not find that');
    expect(root.textContent).toContain('may not be yours to see');
    expect(root.querySelector<HTMLAnchorElement>('a[href="/"]')).not.toBeNull();
  });
});
