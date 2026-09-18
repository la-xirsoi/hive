import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { ProjectApi } from '../../core/api/project-api';
import { TeamApi } from '../../core/api/team-api';
import { ProjectSummary, TeamDetail, UserSummary } from '../../core/api/models';
import {
  HiveButton,
  HiveCard,
  HiveFormField,
  HivePageHeader,
  HiveToast,
  HiveUserChip,
} from '../../shared/ui';
import { CurrentUser } from '../shared/current-user';
import { inputValue } from '../shared/dom';
import { HiveLoadState } from '../shared/load-state';
import { loadable } from '../shared/loadable';
import { mutation } from '../shared/mutation';
import { HiveUserSearch } from '../shared/user-search';

/**
 * One team: who is on it, what it works on, and - for its lead - the controls
 * that change that.
 *
 * WHICH CONTROLS APPEAR. Every control on this screen is gated on
 * `TeamDetail.permissions`, computed by the same domain policy that enforces
 * TM-5 to TM-9, exactly as the task screens are gated on `TaskPermissions`.
 * Nothing here re-derives a rule or compares ids to decide what may be done;
 * the acting user's id is used only to word the subtitle.
 *
 * The lead is deliberately given **no remove button on their own row**: INV-1
 * makes the lead a member for as long as they lead, so removing them is a 409
 * (TM-7). Transferring the lead is the operation that moves that role. That is
 * also why `canRemoveMember` and `canTransferLead` are false for a lead who is
 * alone on their team - there is nobody to remove and nobody to hand it to.
 */
@Component({
  selector: 'app-team-detail',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterLink,
    HiveButton,
    HiveCard,
    HiveFormField,
    HivePageHeader,
    HiveToast,
    HiveUserChip,
    HiveLoadState,
    HiveUserSearch,
  ],
  template: `
    <hive-load-state
      [loading]="team.isLoading() && team.value() === null"
      [error]="team.errorMessage()"
      loadingLabel="Loading the team"
      (retried)="load()"
    >
      @if (team.value(); as detail) {
        <hive-page-header
          [heading]="detail.name"
          eyebrow="Team"
          [subtitle]="isLead() ? 'You lead this team.' : 'Led by ' + detail.teamLead.name"
        />

        @if (save.errorMessage(); as message) {
          <hive-toast
            variant="error"
            [heading]="save.heading()"
            dismissible
            (dismissed)="save.clear()"
          >
            {{ message }}
          </hive-toast>
        }

        <div class="team">
          <hive-card padding="lg">
            <h2 hive-card-header class="team__title">Members ({{ detail.members.length }})</h2>
            <ul class="team__members" aria-label="Team members">
              @for (member of detail.members; track member.id) {
                <li class="team__member" [attr.data-user-id]="member.id">
                  <hive-user-chip
                    [name]="member.name"
                    [secondary]="member.email"
                    [outlined]="isTheLead(detail, member)"
                  />
                  @if (isTheLead(detail, member)) {
                    <span class="team__badge" data-testid="lead-badge">Team lead</span>
                  }
                  @if (canRemoveMember() && !isTheLead(detail, member)) {
                    <hive-button
                      size="sm"
                      variant="tertiary"
                      class="team__remove"
                      [disabled]="save.busy()"
                      [attr.data-remove-user]="member.id"
                      [ariaLabel]="'Remove ' + member.name + ' from the team'"
                      (clicked)="removeMember(member)"
                    >
                      Remove
                    </hive-button>
                  }
                </li>
              }
            </ul>
          </hive-card>

          @if (showLeadControls()) {
            <hive-card padding="lg" accent data-testid="lead-controls">
              <h2 hive-card-header class="team__title">Lead controls</h2>

              @if (canRename()) {
                <section class="team__section" aria-labelledby="team-rename-heading">
                  <h3 class="team__subtitle" id="team-rename-heading">Rename the team</h3>
                  <form class="team__form" (submit)="rename($event)">
                    <hive-form-field label="Team name" required>
                      <input
                        class="hive-input"
                        name="name"
                        data-testid="rename-input"
                        [value]="name()"
                        (input)="name.set(value($event))"
                      />
                    </hive-form-field>
                    <hive-button type="submit" variant="secondary" [loading]="save.busy()">
                      Rename
                    </hive-button>
                  </form>
                </section>
              }

              @if (canAddMember()) {
                <section class="team__section" aria-labelledby="team-add-heading">
                  <h3 class="team__subtitle" id="team-add-heading">Add a member</h3>
                  <hive-user-search
                    label="Find a person to add"
                    selectLabel="Add to team"
                    [excludeIds]="memberIds()"
                    [busy]="save.busy()"
                    (picked)="addMember($event)"
                  />
                </section>
              }

              @if (canTransferLead()) {
                <section class="team__section" aria-labelledby="team-transfer-heading">
                  <h3 class="team__subtitle" id="team-transfer-heading">Transfer the lead role</h3>
                  <p class="hive-text-secondary">
                    The new lead keeps their membership and you remain a member of the team.
                  </p>
                  <form class="team__form" (submit)="transferLead($event)">
                    <hive-form-field label="New team lead" required>
                      <select
                        class="hive-select"
                        name="lead"
                        data-testid="lead-select"
                        [value]="newLeadId()"
                        (change)="newLeadId.set(value($event))"
                      >
                        <option value="">Choose a member</option>
                        @for (member of transferable(); track member.id) {
                          <option [value]="member.id">{{ member.name }}</option>
                        }
                      </select>
                    </hive-form-field>
                    <hive-button
                      type="submit"
                      variant="secondary"
                      [loading]="save.busy()"
                      [disabled]="!newLeadId()"
                      data-testid="transfer-lead"
                    >
                      Transfer lead
                    </hive-button>
                  </form>
                </section>
              }
            </hive-card>
          }

          <hive-card padding="lg">
            <div hive-card-header class="team__head">
              <h2 class="team__title">Projects</h2>
              <a class="team__more" routerLink="/projects">New project</a>
            </div>
            <hive-load-state
              [loading]="projects.isLoading()"
              [error]="projects.errorMessage()"
              [empty]="projects.isReady() && projectList().length === 0"
              loadingLabel="Loading the team's projects"
              emptyHeading="No projects yet"
              emptyMessage="This team has no projects you can see."
              (retried)="loadProjects()"
            >
              <ul class="team__projects" aria-label="Team projects">
                @for (project of projectList(); track project.id) {
                  <li class="team__project">
                    <a class="team__link" [routerLink]="['/projects', project.id]">
                      {{ project.name }}
                    </a>
                    <p class="team__meta">Owned by {{ project.projectOwner.name }}</p>
                  </li>
                }
              </ul>
            </hive-load-state>
          </hive-card>
        </div>
      }
    </hive-load-state>
  `,
  styles: `
    :host {
      display: block;
    }

    .team {
      display: flex;
      flex-direction: column;
      gap: var(--hive-space-6);
      margin-top: var(--hive-space-6);
    }

    .team__title {
      margin: 0;
      font-size: var(--hive-font-size-lg);
      font-weight: var(--hive-font-weight-bold);
      color: var(--hive-color-text);
    }

    .team__subtitle {
      margin: 0 0 var(--hive-space-2);
      font-size: var(--hive-font-size-md);
      font-weight: var(--hive-font-weight-semibold);
      color: var(--hive-color-text);
    }

    .team__head {
      display: flex;
      flex-wrap: wrap;
      align-items: baseline;
      justify-content: space-between;
      gap: var(--hive-space-3);
      width: 100%;
    }

    .team__section {
      padding-top: var(--hive-space-5);
      margin-top: var(--hive-space-5);
      border-top: 1px solid var(--hive-color-border-subtle);
    }

    .team__section:first-of-type {
      padding-top: 0;
      margin-top: 0;
      border-top: none;
    }

    .team__form {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-end;
      gap: var(--hive-space-3);
      margin-top: var(--hive-space-3);
    }

    .team__form hive-form-field {
      flex: 1 1 16rem;
    }

    .team__members,
    .team__projects {
      margin: 0;
      padding: 0;
      list-style: none;
    }

    .team__member,
    .team__project {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--hive-space-3);
      padding: var(--hive-space-2) 0;
      border-bottom: 1px solid var(--hive-color-border-subtle);
    }

    .team__project {
      display: block;
    }

    .team__member:last-child,
    .team__project:last-child {
      border-bottom: none;
    }

    .team__remove {
      margin-left: auto;
    }

    .team__badge {
      font-size: var(--hive-font-size-xs);
      font-weight: var(--hive-font-weight-semibold);
      text-transform: uppercase;
      letter-spacing: 0.04em;
      color: var(--hive-color-text-secondary);
    }

    .team__link {
      font-weight: var(--hive-font-weight-semibold);
      color: var(--hive-color-text);
      text-decoration: none;
    }

    .team__link:hover {
      text-decoration: underline;
    }

    .team__meta {
      margin: var(--hive-space-1) 0 0;
      font-size: var(--hive-font-size-xs);
      color: var(--hive-color-text-secondary);
    }

    .team__more {
      font-size: var(--hive-font-size-sm);
      font-weight: var(--hive-font-weight-semibold);
    }
  `,
})
export class TeamDetailPage {
  private readonly api = inject(TeamApi);
  private readonly projectApi = inject(ProjectApi);
  private readonly currentUser = inject(CurrentUser);

  /** Bound from the `:id` route parameter by `withComponentInputBinding`. */
  readonly id = input.required<string>();

  protected readonly team = loadable<TeamDetail>();
  protected readonly projects = loadable<readonly ProjectSummary[]>();
  protected readonly save = mutation();
  protected readonly name = signal('');
  protected readonly newLeadId = signal('');
  protected readonly value = inputValue;

  protected readonly projectList = computed(() => this.projects.value() ?? []);
  protected readonly memberIds = computed(() =>
    (this.team.value()?.members ?? []).map((m) => m.id),
  );

  /**
   * Identity, not authorization: this only decides how the subtitle is worded.
   * Every control is gated on the server's `permissions` block below.
   */
  protected readonly isLead = computed(() =>
    this.currentUser.isMe(this.team.value()?.teamLead.id ?? null),
  );

  /** TM-5 to TM-9, as computed by the server for this caller. */
  protected readonly canRename = computed(() => this.team.value()?.permissions.canRename ?? false);
  protected readonly canAddMember = computed(
    () => this.team.value()?.permissions.canAddMember ?? false,
  );
  protected readonly canRemoveMember = computed(
    () => this.team.value()?.permissions.canRemoveMember ?? false,
  );
  protected readonly canTransferLead = computed(
    () => this.team.value()?.permissions.canTransferLead ?? false,
  );

  /** The card exists only if at least one of the controls inside it does. */
  protected readonly showLeadControls = computed(
    () => this.canRename() || this.canAddMember() || this.canTransferLead(),
  );

  /** Candidates for the lead role: the current members, minus the current lead. */
  protected readonly transferable = computed(() => {
    const detail = this.team.value();
    return detail ? detail.members.filter((m) => m.id !== detail.teamLead.id) : [];
  });

  constructor() {
    // Keep the rename field showing the team's current name. It re-runs only
    // when a new TeamDetail arrives (load, or the body of a mutation), so it
    // never clobbers typing mid-edit.
    effect(() => {
      const detail = this.team.value();
      if (detail) {
        this.name.set(detail.name);
      }
    });
    // An effect, not a direct call: `id` is a required input, readable only
    // once the router has bound it.
    effect(() => {
      this.id();
      this.load();
      this.loadProjects();
    });
    this.currentUser.load().subscribe({ error: () => undefined });
  }

  private teamId(): number {
    return Number(this.id());
  }

  protected load(): void {
    this.team.load(this.api.getById(this.teamId()));
  }

  /**
   * Re-read after a 409. `keepValue` holds the stale record on screen so the
   * conflict message stays readable while the fresh one is fetched.
   */
  private refresh(): void {
    this.team.load(this.api.getById(this.teamId()), { keepValue: true });
  }

  protected loadProjects(): void {
    this.projects.load(this.api.listProjects(this.teamId()));
  }

  protected isTheLead(detail: TeamDetail, member: UserSummary): boolean {
    return detail.teamLead.id === member.id;
  }

  private adopt(detail: TeamDetail): void {
    this.team.set(detail);
    this.newLeadId.set('');
  }

  protected rename(event: Event): void {
    event.preventDefault();
    const name = this.name().trim();
    if (!name) {
      return;
    }
    this.save.run(this.api.rename(this.teamId(), { name }), {
      next: (detail) => this.adopt(detail),
      conflict: () => this.refresh(),
    });
  }

  protected addMember(user: UserSummary): void {
    this.save.run(this.api.addMember(this.teamId(), { userId: user.id }), {
      next: (detail) => this.adopt(detail),
      conflict: () => this.refresh(),
    });
  }

  protected removeMember(user: UserSummary): void {
    this.save.run(this.api.removeMember(this.teamId(), user.id), {
      next: (detail) => this.adopt(detail),
      conflict: () => this.refresh(),
    });
  }

  protected transferLead(event: Event): void {
    event.preventDefault();
    const userId = Number(this.newLeadId());
    if (!userId) {
      return;
    }
    this.save.run(this.api.transferLead(this.teamId(), { userId }), {
      next: (detail) => this.adopt(detail),
      conflict: () => this.refresh(),
    });
  }
}
