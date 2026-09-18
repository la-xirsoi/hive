import { ADA, ApiRejections, GRACE, expect, openSession, signIn, test } from './support/fixtures';
import type { Page } from '@playwright/test';

/**
 * One task, from the sentence that creates it to the moment it is Completed,
 * driven through the compose stack by the two people the rules require.
 *
 * WHY TWO SESSIONS. `TaskTransitions.TABLE` gives `Draft -> Todo` to the project
 * owner and `Todo -> In Progress` and `In Progress -> Completed` to the
 * assignee, while AS-4 forbids the project owner from being the assignee of a
 * task in their own project. A single signed-in user therefore *cannot* take a
 * task to Completed, no matter what the screen offers them. Ada leads the team
 * and owns the project; Grace does the work.
 *
 * WHY IT BUILDS ITS OWN GRAPH. The compose stack runs the `prod` profile, so
 * `DevDataSeeder` never runs and there is no fixture team, project or task to
 * borrow. Creating them here is not setup cost -- the team, project and task
 * screens are exactly what `hive-onj` says are otherwise only ever exercised
 * against mocked HTTP (docs/verification.md 8.3), and hive-m50 is what a screen
 * that passes a unit test and fails in a browser costs.
 *
 * EVERY NAME IS STAMPED. The stack is long-lived and shared, so a fixed name
 * would collide with the previous run's rows and make locators ambiguous rather
 * than failing cleanly.
 */
test.describe('task lifecycle', () => {
  // Two sign-ins through the identity provider, three screens and eight writes,
  // against containers rather than a dev server. It lands in about 2.5s on a
  // warm stack; the ceiling is here so that a step which genuinely hangs is
  // reported as a failure rather than waiting out a longer default.
  test.describe.configure({ timeout: 120_000 });

  test('carries a task from Draft to Completed across its project owner and its assignee', async ({
    page,
    browser,
    baseURL,
  }) => {
    const stamp = Date.now().toString(36);
    const teamName = `Lifecycle team ${stamp}`;
    const projectName = `Lifecycle project ${stamp}`;
    const taskName = `Lifecycle task ${stamp}`;
    const commentText = `Started this from the browser suite (${stamp}).`;

    const rejections = new ApiRejections();
    /**
     * The acceptance criterion's second half. A refused write can leave the
     * previous render in place, so a screen assertion on its own can go green
     * over a step the server never performed; this makes the refusal itself the
     * failure, with the method, path and status on it.
     */
    const serverAccepted = (step: string) =>
      expect(rejections.all(), `the server refused a call while ${step}`).toEqual([]);

    // Grace signs in first: the stack provisions a Hive user from the token on
    // first contact, and until that has happened she is not in the directory
    // Ada is about to search.
    rejections.watch(page);
    const grace = await openSession(browser, GRACE, baseURL!, rejections);

    try {
      await signIn(page, ADA);
      serverAccepted('signing both users in');

      // --- Ada builds the team ---------------------------------------------
      await page.goto('/teams');
      await page.getByTestId('new-team').click();
      await page.getByTestId('team-name').fill(teamName);
      await page.getByTestId('create-team').click();

      await expect(page.getByText(`${teamName} was created. You are its team lead.`)).toBeVisible();
      await page.getByRole('link', { name: teamName }).click();
      await expect(page.getByTestId('lead-badge')).toBeVisible();
      serverAccepted('creating the team');

      // TM-6, through the directory search rather than a fixed list.
      const leadControls = page.getByTestId('lead-controls');
      await leadControls.getByRole('searchbox').fill(GRACE.username);
      await leadControls.getByRole('button', { name: 'Search' }).click();
      await page.getByRole('button', { name: `Add to team ${GRACE.fullName}` }).click();

      const members = page.getByRole('list', { name: 'Team members' });
      await expect(members.getByText(GRACE.fullName)).toBeVisible();
      await expect(members.getByText(ADA.fullName)).toBeVisible();
      serverAccepted('adding Grace to the team');

      // --- Ada creates the project on that team -----------------------------
      await page.goto('/projects');
      await page.getByTestId('new-project').click();
      await page.getByTestId('project-name').fill(projectName);
      // The picker is populated from `GET /teams/mine`, so choosing by label
      // also proves the team Ada just created came back from the server.
      await page.getByTestId('project-team').selectOption({ label: teamName });
      await page.getByTestId('create-project').click();

      await expect(page.getByText(`${projectName} was created.`)).toBeVisible();
      await page.getByRole('link', { name: projectName }).click();
      await expect(page.getByTestId('owner-controls')).toBeVisible();
      const projectUrl = page.url();
      serverAccepted('creating the project');

      // --- Ada creates the task ---------------------------------------------
      const ownerControls = page.getByTestId('owner-controls');
      await ownerControls.getByTestId('task-name').fill(taskName);
      await ownerControls
        .getByTestId('task-description')
        .fill('Created, assigned, started and completed by the browser-driven suite.');
      await ownerControls.getByTestId('create-task').click();

      await expect(page.getByText(`${taskName} was created as a Draft.`)).toBeVisible();

      // TK-2: a new task is Draft and unassigned, and the row says so.
      const row = page.locator('hive-task-list li', { hasText: taskName });
      await expect(row.locator('.hive-status__label')).toHaveText('Draft');
      await expect(row.locator('.task-list__unassigned')).toBeVisible();
      serverAccepted('creating the task');

      await row.getByRole('link', { name: taskName }).click();
      const taskUrl = page.url();

      // --- Draft -> Todo, by the project owner -------------------------------
      await expect(status(page)).toHaveText(/Draft/);
      await transition(page, 'Todo');
      await expect(status(page)).toHaveText(/Todo/);
      serverAccepted('publishing the task to Todo');

      // --- Ada assigns it to Grace -------------------------------------------
      const assignment = page.getByTestId('assignment-card');
      await expect(assignment.getByTestId('currently-unassigned')).toBeVisible();

      // AS-2 and AS-4 together: the only candidate is the member who is not the
      // project owner. Ada leads this team and is a member of it, and she is
      // still absent from her own candidate list.
      await expect(assignment.getByTestId('assignee-select').locator('option')).toHaveText([
        'Choose a member',
        GRACE.fullName,
      ]);

      await assignment.getByTestId('assignee-select').selectOption({ label: GRACE.fullName });
      await assignment.getByTestId('assign-task').click();

      await expect(assignment.getByText(GRACE.email)).toBeVisible();
      await expect(assignment.getByTestId('currently-unassigned')).toHaveCount(0);
      serverAccepted('assigning the task to Grace');

      // Ada may not start it: TR-1 gives that to the assignee, and the button is
      // absent from the DOM rather than disabled.
      await expect(page.locator('hive-button[data-transition="In Progress"]')).toHaveCount(0);

      // --- Grace picks the work up -------------------------------------------
      // Through the project, so the row she clicks is the server's answer to
      // *her* token: a fresh project holds exactly this one task, which a
      // paginated "My tasks" could not promise on a long-lived stack.
      await grace.goto(projectUrl);
      const graceRow = grace.locator('hive-task-list li', { hasText: taskName });
      await expect(graceRow.locator('.hive-status__label')).toHaveText('Todo');
      await expect(graceRow).toContainText(GRACE.fullName);

      await graceRow.getByRole('link', { name: taskName }).click();
      expect(grace.url()).toBe(taskUrl);

      // Grace is the assignee and nothing else: she gets the assignee's
      // transition, and the lead's assignment panel is not in her DOM.
      await expect(grace.getByTestId('assignment-card')).toHaveCount(0);
      await transition(grace, 'In Progress');
      await expect(status(grace)).toHaveText(/In Progress/);
      serverAccepted('starting the work as Grace');

      // --- Grace comments -----------------------------------------------------
      await grace.getByTestId('comment-input').fill(commentText);
      await grace.getByTestId('add-comment').click();

      // CM-4/CM-5: the author and the timestamp are the server's, never sent.
      const posted = grace.locator('[data-comment-id]').filter({ hasText: commentText });
      await expect(posted).toContainText(GRACE.fullName);
      await expect(posted.locator('time')).toHaveAttribute('datetime', /^\d{4}-\d{2}-\d{2}T/);
      serverAccepted('posting a comment');

      // --- Grace finishes it ---------------------------------------------------
      await transition(grace, 'Completed');
      await expect(status(grace)).toHaveText(/Completed/);

      // TE-2/TK-4: terminal is read-only, and it says so in words rather than
      // only by desaturating the workspace.
      await expect(grace.getByTestId('terminal-notice')).toBeVisible();
      await expect(grace.locator('hive-button[data-transition]')).toHaveCount(0);
      await expect(grace.getByTestId('edit-card')).toHaveCount(0);
      // TE-4/CM-3: a finished task is still open for commentary.
      await expect(grace.getByTestId('comment-form')).toBeVisible();
      serverAccepted('completing the task');

      // --- It is the stack that changed, not one tab ---------------------------
      // Ada's tab has been sitting on the pre-assignment render throughout. A
      // reload re-reads the task, its assignee and its permissions from the
      // backend, so everything above is asserted once more against a document
      // that has never seen any of it happen.
      await page.reload();
      await expect(status(page)).toHaveText(/Completed/);
      await expect(page.locator('.task__meta')).toContainText(GRACE.fullName);
      await expect(page.getByTestId('terminal-notice')).toBeVisible();
      await expect(
        page.locator('[data-comment-id]').filter({ hasText: commentText }),
      ).toBeVisible();
      serverAccepted('re-reading the finished task as Ada');
    } finally {
      await grace.context().close();
    }
  });
});

/** The status badge of the task workspace, not the one in the page header. */
function status(page: Page) {
  return page.locator('hive-task-status-actions hive-status-badge');
}

/**
 * Click the button that moves the task to `target`.
 *
 * `data-transition` is rendered one-to-one from `permissions.allowedTransitions`
 * (the server's answer), so a missing button here is the policy talking, and the
 * failure should read that way rather than as a bad selector.
 */
async function transition(page: Page, target: string): Promise<void> {
  const button = page.locator(`hive-button[data-transition="${target}"] button`);
  await expect(
    button,
    `the server did not offer "${target}" to this user for this task`,
  ).toBeVisible();
  await button.click();
}
