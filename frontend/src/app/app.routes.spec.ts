import { Route, Routes } from '@angular/router';
import { authGuard } from './core/auth/auth-guard';
import { AUTH_CALLBACK_PATH } from './core/auth/auth-service';
import { routes } from './app.routes';

/**
 * The route table is a security surface as much as a navigation one, so it is
 * asserted directly: exactly two routes may be reachable without a session, and
 * everything else must sit under the guarded parent.
 */
describe('app routes', () => {
  const publicRoutes = routes.filter((route) => route.path !== '');
  const shell = routes.find((route) => route.path === '')!;
  const children: Routes = shell.children ?? [];

  function pathsOf(list: Routes): string[] {
    return list.map((route) => route.path ?? '');
  }

  async function resolve(route: Route): Promise<unknown> {
    const loaded = await (route.loadComponent as () => Promise<unknown>)();
    return loaded;
  }

  it('leaves exactly the sign-in screen and the OAuth callback unguarded', () => {
    expect(pathsOf(publicRoutes).sort()).toEqual([AUTH_CALLBACK_PATH, 'login'].sort());
    for (const route of publicRoutes) {
      expect(route.canActivate).toBeUndefined();
    }
  });

  it('puts every other screen behind the auth guard, via one guarded parent', () => {
    expect(shell.canActivate).toEqual([authGuard]);
    expect(children.length).toBeGreaterThan(0);
  });

  it('routes the whole feature set', () => {
    expect(pathsOf(children)).toEqual([
      '',
      'tasks/unassigned',
      'tasks',
      'tasks/:id',
      'teams',
      'teams/:id',
      'projects',
      'projects/:id',
      '**',
    ]);
  });

  it('declares the unassigned queue before the task id pattern, which would swallow it', () => {
    const paths = pathsOf(children);
    expect(paths.indexOf('tasks/unassigned')).toBeLessThan(paths.indexOf('tasks/:id'));
  });

  it('loads every screen lazily and resolves each one', async () => {
    const all = [...publicRoutes, shell, ...children];
    for (const route of all) {
      expect(route.loadComponent)
        .withContext(route.path ?? '(shell)')
        .toBeDefined();
      expect(await resolve(route))
        .withContext(route.path ?? '(shell)')
        .toBeDefined();
    }
  });

  it('gives each screen a page title', () => {
    for (const route of [...children, routes.find((r) => r.path === 'login')!]) {
      expect(route.title)
        .withContext(route.path ?? '')
        .toBeDefined();
    }
  });
});
