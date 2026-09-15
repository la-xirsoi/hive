import { TestBed } from '@angular/core/testing';
import { Subject, throwError } from 'rxjs';
import { ApiError } from '../../core/api/api-error';
import { CONFLICT_HEADING, CONFLICT_SUFFIX, Mutation, mutation } from './mutation';

describe('Mutation', () => {
  function make(): Mutation {
    return TestBed.runInInjectionContext(() => mutation());
  }

  function failing(status: number, message: string) {
    return throwError(
      () => new ApiError({ status, error: status === 409 ? 'CONFLICT' : 'FORBIDDEN', message }),
    );
  }

  it('is idle before anything runs', () => {
    const write = make();
    expect(write.busy()).toBeFalse();
    expect(write.errorMessage()).toBeNull();
  });

  it('reports busy while in flight and hands the body to the caller', () => {
    const write = make();
    const source = new Subject<string>();
    const seen: string[] = [];
    write.run(source, { next: (value) => seen.push(value) });

    expect(write.busy()).toBeTrue();
    source.next('saved');
    expect(write.busy()).toBeFalse();
    expect(seen).toEqual(['saved']);
  });

  it('renders the server message for an ordinary failure', () => {
    const write = make();
    write.run(failing(403, 'Only the project owner may edit this task.'));

    expect(write.errorMessage()).toBe('Only the project owner may edit this task.');
    expect(write.isConflict()).toBeFalse();
    expect(write.heading()).toBe('That did not work');
  });

  it('marks a 409 as a conflict and says the screen has been refreshed', () => {
    const write = make();
    write.run(failing(409, 'Task 42 is Completed and can no longer be edited.'));

    expect(write.isConflict()).toBeTrue();
    expect(write.heading()).toBe(CONFLICT_HEADING);
    expect(write.errorMessage()).toBe(
      `Task 42 is Completed and can no longer be edited. ${CONFLICT_SUFFIX}`,
    );
  });

  it('invokes the refresh hook on a 409 and only on a 409', () => {
    const conflict = jasmine.createSpy('conflict');

    make().run(failing(403, 'nope'), { conflict });
    expect(conflict).not.toHaveBeenCalled();

    make().run(failing(409, 'moved'), { conflict });
    expect(conflict).toHaveBeenCalledTimes(1);
  });

  it('clears a message when the user dismisses it', () => {
    const write = make();
    write.run(failing(500, 'Something went wrong.'));
    write.clear();
    expect(write.errorMessage()).toBeNull();
  });

  it('drops a stale error when the next attempt starts', () => {
    const write = make();
    write.run(failing(409, 'moved'));
    write.run(new Subject<string>());
    expect(write.errorMessage()).toBeNull();
    expect(write.busy()).toBeTrue();
  });
});
