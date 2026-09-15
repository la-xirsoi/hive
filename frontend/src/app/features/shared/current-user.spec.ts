import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { alice, bob } from '../../core/test-support.spec';
import { CurrentUser } from './current-user';
import { API, featureProviders } from './feature-test-support.spec';

describe('CurrentUser', () => {
  let http: HttpTestingController;
  let currentUser: CurrentUser;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: featureProviders() });
    http = TestBed.inject(HttpTestingController);
    currentUser = TestBed.inject(CurrentUser);
  });

  afterEach(() => http.verify());

  it('has no user before the first load', () => {
    expect(currentUser.user()).toBeNull();
    expect(currentUser.id()).toBeNull();
  });

  it('loads `GET /users/me` and publishes the acting user', () => {
    currentUser.load().subscribe();
    http.expectOne(`${API}/users/me`).flush(alice);

    expect(currentUser.user()).toEqual(alice);
    expect(currentUser.id()).toBe(alice.id);
  });

  it('issues one request no matter how many screens ask', () => {
    currentUser.load().subscribe();
    currentUser.load().subscribe();
    http.expectOne(`${API}/users/me`).flush(alice);

    currentUser.load().subscribe();
    expect(() => http.verify()).not.toThrow();
  });

  it('answers isMe from the loaded identity', () => {
    currentUser.load().subscribe();
    http.expectOne(`${API}/users/me`).flush(alice);

    expect(currentUser.isMe(alice.id)).toBeTrue();
    expect(currentUser.isMe(bob.id)).toBeFalse();
  });

  it('never matches a null or missing id, even before the user is known', () => {
    expect(currentUser.isMe(alice.id)).toBeFalse();

    currentUser.load().subscribe();
    http.expectOne(`${API}/users/me`).flush(alice);

    expect(currentUser.isMe(null)).toBeFalse();
    expect(currentUser.isMe(undefined)).toBeFalse();
  });

  it('forgets the user and re-requests after a reset, as sign-out needs', () => {
    currentUser.load().subscribe();
    http.expectOne(`${API}/users/me`).flush(alice);

    currentUser.reset();
    expect(currentUser.user()).toBeNull();

    currentUser.load().subscribe();
    http.expectOne(`${API}/users/me`).flush(bob);
    expect(currentUser.id()).toBe(bob.id);
  });
});
