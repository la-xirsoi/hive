import { Routes } from '@angular/router';
import { AUTH_CALLBACK_PATH } from './core/auth/auth-service';
import { authGuard } from './core/auth/auth-guard';

/**
 * Application routes.
 *
 * Shape: two public routes (the sign-in screen and the OAuth `redirect_uri`
 * target) and one guarded parent that owns every authenticated screen. Placing
 * `authGuard` on the parent rather than on each child means the check runs once
 * per navigation into the authenticated area and the chrome (`ShellLayout`) is
 * instantiated once for the whole session instead of per navigation.
 *
 * Every feature is `loadComponent`, so a visitor who only ever opens the
 * dashboard never downloads the team, project or task screens.
 *
 * Ordering note: `tasks/unassigned` is declared before `tasks/:id` because the
 * router matches in declaration order and `:id` would otherwise swallow it.
 */
export const routes: Routes = [
  {
    // OAuth redirect_uri target. Must stay in sync with `oauth.redirectUri` in
    // src/environments/environment.ts, which is built from AUTH_CALLBACK_PATH.
    path: AUTH_CALLBACK_PATH,
    loadComponent: () => import('./core/auth/auth-callback').then((m) => m.AuthCallback),
  },
  {
    path: 'login',
    title: 'Sign in - Hive',
    loadComponent: () => import('./features/auth/login').then((m) => m.LoginPage),
  },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./features/shell/shell-layout').then((m) => m.ShellLayout),
    children: [
      {
        path: '',
        pathMatch: 'full',
        title: 'Dashboard - Hive',
        loadComponent: () => import('./features/dashboard/dashboard').then((m) => m.DashboardPage),
      },
      {
        path: 'tasks/unassigned',
        title: 'Unassigned tasks - Hive',
        loadComponent: () =>
          import('./features/tasks/unassigned-queue').then((m) => m.UnassignedQueuePage),
      },
      {
        path: 'tasks',
        pathMatch: 'full',
        title: 'My tasks - Hive',
        loadComponent: () => import('./features/tasks/my-tasks').then((m) => m.MyTasksPage),
      },
      {
        path: 'tasks/:id',
        title: 'Task - Hive',
        loadComponent: () => import('./features/tasks/task-detail').then((m) => m.TaskDetailPage),
      },
      {
        path: 'teams',
        pathMatch: 'full',
        title: 'Teams - Hive',
        loadComponent: () => import('./features/teams/teams').then((m) => m.TeamsPage),
      },
      {
        path: 'teams/:id',
        title: 'Team - Hive',
        loadComponent: () => import('./features/teams/team-detail').then((m) => m.TeamDetailPage),
      },
      {
        path: 'projects',
        pathMatch: 'full',
        title: 'Projects - Hive',
        loadComponent: () => import('./features/projects/projects').then((m) => m.ProjectsPage),
      },
      {
        path: 'projects/:id',
        title: 'Project - Hive',
        loadComponent: () =>
          import('./features/projects/project-detail').then((m) => m.ProjectDetailPage),
      },
      {
        path: '**',
        title: 'Not found - Hive',
        loadComponent: () => import('./features/shell/not-found').then((m) => m.NotFoundPage),
      },
    ],
  },
];
