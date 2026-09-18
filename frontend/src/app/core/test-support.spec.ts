import { Provider } from '@angular/core';
import { AppConfig, APP_CONFIG } from './config/app-config';
import {
  Comment,
  Page,
  ProjectPermissions,
  ProjectSummary,
  TaskDetail,
  TaskSummary,
  TeamDetail,
  TeamPermissions,
  TeamSummary,
  UserSummary,
} from './api/models';
import { AUTH_STORAGE, BrowserLocation, BROWSER_LOCATION, MemoryStorage } from './auth/browser';

/**
 * Shared fixtures and providers for the core specs.
 *
 * This lives in a `.spec.ts` file on purpose: `tsconfig.app.json` excludes
 * `*.spec.ts`, so nothing here can leak into an application build even by
 * accident, while `tsconfig.spec.json` still compiles it.
 */

export const TEST_API_BASE = '/api/v1';
export const TEST_ISSUER = 'https://id.test.example/realms/hive';
export const TEST_AUTHORIZE = `${TEST_ISSUER}/authorize`;
export const TEST_TOKEN_ENDPOINT = `${TEST_ISSUER}/token`;
export const TEST_END_SESSION = `${TEST_ISSUER}/logout`;
export const TEST_REDIRECT_URI = 'http://localhost:9876/auth/callback';

export function testConfig(overrides: Partial<AppConfig> = {}): AppConfig {
  return {
    apiBaseUrl: TEST_API_BASE,
    oauth: {
      issuer: TEST_ISSUER,
      clientId: 'hive-web-test',
      redirectUri: TEST_REDIRECT_URI,
      scope: 'openid profile email offline_access',
    },
    devAuth: false,
    ...overrides,
  };
}

export function provideTestConfig(overrides: Partial<AppConfig> = {}): Provider {
  return { provide: APP_CONFIG, useValue: testConfig(overrides) };
}

/** A recording stand-in for `window.location`. */
export class FakeLocation implements BrowserLocation {
  readonly assigned: string[] = [];
  href = 'http://localhost:9876/';
  search = '';
  origin = 'http://localhost:9876';

  assign(url: string): void {
    this.assigned.push(url);
    this.href = url;
  }

  /** The most recent navigation target, parsed. */
  lastUrl(): URL {
    const last = this.assigned[this.assigned.length - 1];
    if (!last) {
      throw new Error('No navigation was performed.');
    }
    return new URL(last);
  }
}

export function provideFakeBrowser(location = new FakeLocation(), storage = new MemoryStorage()) {
  return [
    { provide: BROWSER_LOCATION, useValue: location },
    { provide: AUTH_STORAGE, useValue: storage },
  ];
}

// ---------------------------------------------------------------------------
// Wire fixtures
// ---------------------------------------------------------------------------

export const alice: UserSummary = { id: 1, name: 'Alice Ng', email: 'alice@hive.test' };
export const bob: UserSummary = { id: 2, name: 'Bob Ito', email: 'bob@hive.test' };

export const teamSummary: TeamSummary = {
  id: 10,
  name: 'Platform',
  teamLead: alice,
  memberCount: 2,
};

/** What the server computes for a lead looking at their own team. */
export const teamPermissions: TeamPermissions = {
  canRename: true,
  canAddMember: true,
  canRemoveMember: true,
  canTransferLead: true,
};

/** What it computes for everybody else. */
export const noTeamPermissions: TeamPermissions = {
  canRename: false,
  canAddMember: false,
  canRemoveMember: false,
  canTransferLead: false,
};

export const teamDetail: TeamDetail = {
  id: 10,
  name: 'Platform',
  teamLead: alice,
  members: [alice, bob],
  permissions: teamPermissions,
};

/** What the server computes for an owner looking at their own project. */
export const projectPermissions: ProjectPermissions = {
  canRename: true,
  canTransferOwnership: true,
  canCreateTask: true,
};

/** What it computes for everybody else. */
export const noProjectPermissions: ProjectPermissions = {
  canRename: false,
  canTransferOwnership: false,
  canCreateTask: false,
};

export const projectSummary: ProjectSummary = {
  id: 20,
  name: 'Hive Core',
  team: teamSummary,
  projectOwner: alice,
  permissions: projectPermissions,
};

export const taskSummary: TaskSummary = {
  id: 42,
  name: 'Wire the API client',
  status: 'Todo',
  projectId: 20,
  projectName: 'Hive Core',
  assignee: bob,
};

export const taskDetail: TaskDetail = {
  id: 42,
  name: 'Wire the API client',
  description: 'Typed services for every contract endpoint.',
  status: 'Todo',
  project: projectSummary,
  creator: alice,
  assignee: bob,
  permissions: {
    canEdit: true,
    canAssign: true,
    canComment: true,
    allowedTransitions: ['In Progress', 'Canceled'],
  },
};

export const commentFixture: Comment = {
  id: 7,
  taskId: 42,
  author: bob,
  timestamp: '2026-09-13T18:30:00Z',
  content: 'Starting on this now.',
};

export function pageOf<T>(content: T[], overrides: Partial<Page<T>> = {}): Page<T> {
  return {
    content,
    page: 0,
    size: 50,
    totalElements: content.length,
    totalPages: content.length === 0 ? 0 : 1,
    ...overrides,
  };
}

// ---------------------------------------------------------------------------
// JWT helpers
// ---------------------------------------------------------------------------

function base64Url(value: string): string {
  // UTF-8 first: `btoa` alone throws on any character above U+00FF, and real
  // tokens carry names with accents.
  let binary = '';
  for (const byte of new TextEncoder().encode(value)) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/**
 * Builds an unsigned JWT with the given claims. The client never verifies
 * signatures (that is the API server's job), so an unsigned token exercises
 * exactly the code paths the real one would.
 */
export function makeJwt(claims: Record<string, unknown>): string {
  return `${base64Url(JSON.stringify({ alg: 'none', typ: 'JWT' }))}.${base64Url(
    JSON.stringify(claims),
  )}.sig`;
}

/** An access token for `alice` expiring `secondsFromNow` from now. */
export function accessTokenFor(
  secondsFromNow = 3600,
  claims: Record<string, unknown> = {},
): string {
  return makeJwt({
    sub: 'auth0|alice',
    email: alice.email,
    name: alice.name,
    exp: Math.floor(Date.now() / 1000) + secondsFromNow,
    ...claims,
  });
}

describe('test-support fixtures', () => {
  it('builds a decodable JWT', () => {
    const token = accessTokenFor(60);
    expect(token.split('.').length).toBe(3);
  });

  it('builds a page envelope matching the contract', () => {
    expect(pageOf([taskSummary])).toEqual({
      content: [taskSummary],
      page: 0,
      size: 50,
      totalElements: 1,
      totalPages: 1,
    });
  });
});
