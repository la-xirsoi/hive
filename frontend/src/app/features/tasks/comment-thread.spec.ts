import { HttpTestingController } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Comment } from '../../core/api/models';
import { alice, bob, commentFixture, pageOf } from '../../core/test-support.spec';
import { API, featureProviders, type } from '../shared/feature-test-support.spec';
import { HiveCommentThread } from './comment-thread';

const laterComment: Comment = {
  id: 8,
  taskId: 42,
  author: alice,
  timestamp: '2026-09-13T21:05:00Z',
  content: 'Thanks - ping me when it is ready for review.',
};

@Component({
  imports: [HiveCommentThread],
  template: `
    <hive-comment-thread [taskId]="42" [canComment]="canComment()" [timeZone]="timeZone()" />
  `,
})
class ThreadHost {
  readonly canComment = signal(true);
  readonly timeZone = signal<string | undefined>('Asia/Tokyo');
}

describe('HiveCommentThread', () => {
  let fixture: ComponentFixture<ThreadHost>;
  let host: ThreadHost;
  let http: HttpTestingController;

  const root = () => fixture.nativeElement as HTMLElement;
  const text = () => root().textContent ?? '';
  const items = () => Array.from(root().querySelectorAll('.thread__item'));

  async function withComments(comments: Comment[]): Promise<void> {
    http.expectOne((r) => r.url === `${API}/tasks/42/comments`).flush(pageOf(comments));
    await fixture.whenStable();
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ThreadHost],
      providers: featureProviders(),
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ThreadHost);
    host = fixture.componentInstance;
    await fixture.whenStable();
  });

  afterEach(() => http.verify());

  it('reads the thread of the task it was given', () => {
    const request = http.expectOne((r) => r.url === `${API}/tasks/42/comments`);
    expect(request.request.method).toBe('GET');
    request.flush(pageOf([]));
  });

  it('shows a spinner while the thread loads', () => {
    expect(root().querySelector('hive-spinner')).not.toBeNull();
    http.expectOne((r) => r.url === `${API}/tasks/42/comments`).flush(pageOf([]));
  });

  it('shows each comment with its author', async () => {
    await withComments([commentFixture, laterComment]);

    expect(items().length).toBe(2);
    expect(items()[0].textContent).toContain('Bob Ito');
    expect(items()[0].textContent).toContain('Starting on this now.');
    expect(items()[1].textContent).toContain('Alice Ng');
  });

  it('renders the server’s UTC timestamp in the viewer’s local time', async () => {
    // 2026-09-13T18:30:00Z read in Tokyo (UTC+9) is 03:30 the next morning.
    await withComments([commentFixture]);

    const stamp = root().querySelector('time')!;
    expect(stamp.textContent!.trim()).toBe('14 Sep 2026, 03:30');
    expect(stamp.getAttribute('datetime')).toBe('2026-09-13T18:30:00.000Z');
  });

  it('follows the viewer to another zone', async () => {
    host.timeZone.set('America/New_York');
    await fixture.whenStable();
    await withComments([commentFixture]);

    expect(root().querySelector('time')!.textContent!.trim()).toBe('13 Sep 2026, 14:30');
  });

  it('invites the first comment when the thread is empty', async () => {
    await withComments([]);
    expect(text()).toContain('No comments yet');
  });

  it('shows the server message when the thread cannot be read', async () => {
    http
      .expectOne((r) => r.url === `${API}/tasks/42/comments`)
      .flush(
        { status: 404, error: 'NOT_FOUND', message: 'Task 42 was not found.' },
        { status: 404, statusText: 'Not Found' },
      );
    await fixture.whenStable();

    expect(text()).toContain('Task 42 was not found.');
  });

  it('posts a comment and reloads the thread, sending only the content', async () => {
    await withComments([commentFixture]);

    type(root().querySelector<HTMLTextAreaElement>('[data-testid="comment-input"]')!, 'On it.');
    await fixture.whenStable();
    root().querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    const post = http.expectOne((r) => r.url === `${API}/tasks/42/comments` && r.method === 'POST');
    expect(post.request.body).toEqual({ content: 'On it.' });
    post.flush(laterComment);
    await fixture.whenStable();

    http
      .expectOne((r) => r.url === `${API}/tasks/42/comments`)
      .flush(pageOf([commentFixture, laterComment]));
    await fixture.whenStable();
    expect(items().length).toBe(2);
  });

  it('surfaces a failed post without losing the draft', async () => {
    await withComments([]);

    type(root().querySelector<HTMLTextAreaElement>('[data-testid="comment-input"]')!, 'Hello');
    await fixture.whenStable();
    root().querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();

    http
      .expectOne((r) => r.method === 'POST')
      .flush(
        { status: 400, error: 'BAD_REQUEST', message: 'Content is required.' },
        { status: 400, statusText: 'Bad Request' },
      );
    await fixture.whenStable();

    expect(text()).toContain('Content is required.');
    expect(root().querySelector<HTMLTextAreaElement>('[data-testid="comment-input"]')!.value).toBe(
      'Hello',
    );
  });

  it('OMITS the add-comment form when the server says the actor may not comment', async () => {
    host.canComment.set(false);
    await fixture.whenStable();
    await withComments([commentFixture]);

    expect(root().querySelector('[data-testid="comment-form"]')).toBeNull();
    expect(root().querySelector('textarea')).toBeNull();
    expect(root().querySelector('[data-testid="comments-read-only"]')!.textContent).toContain(
      'You can read this discussion but not add to it.',
    );
    expect(items().length).toBe(1);
  });

  it('keeps the form on a terminal task, because canComment stays true there (TE-4)', async () => {
    // The flag is the only input; the thread knows nothing about task status.
    expect(host.canComment()).toBeTrue();
    await withComments([commentFixture]);

    expect(root().querySelector('[data-testid="comment-form"]')).not.toBeNull();
  });

  it('never offers the author or timestamp as fields, since the server assigns both', async () => {
    await withComments([]);

    expect(root().querySelector('input[name="author"]')).toBeNull();
    expect(root().querySelector('input[name="timestamp"]')).toBeNull();
  });

  it('reads the mention of bob in the fixture as the author, not the acting user', async () => {
    await withComments([commentFixture]);
    expect(items()[0].textContent).toContain(bob.name);
  });
});
