-- =============================================================================
-- Hive baseline schema.
--
-- Target: Microsoft SQL Server 2022.
--
-- Conventions:
--   * Surrogate keys are BIGINT IDENTITY(1,1); an unsaved domain entity carries
--     a null id, so the database -- not the application -- allocates them.
--   * All text is NVARCHAR; lengths mirror the domain value objects in
--     hive.domain.model.ValueObjects exactly, so a value that constructs in the
--     domain always fits, and one that does not is rejected before it ever
--     reaches SQL.
--   * Every constraint is named explicitly (PK_/UQ_/FK_/CK_/IX_). An
--     auto-generated name cannot be referred to in a later migration and turns
--     an error message into a puzzle.
--   * No ON DELETE CASCADE anywhere: Hive supports no delete operation at all
--     (TK-6, TM-11, PR-10 -- all 405). Restrict is the correct default, and a
--     cascade that can never fire is a trap for a future migration.
--
-- This file is deliberately written in the intersection of SQL Server and H2's
-- MSSQLServer compatibility mode so that the test suite runs the migration
-- itself rather than a hand-kept replica of it. See docs/testing.md.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- users -- US-1, US-2, US-3
--
-- `email` holds the NORMALIZED (lower-cased) address. EmailAddress in the
-- domain defines equality case-insensitively, so uniqueness must be
-- case-insensitive too; normalizing on write makes that a plain unique
-- constraint instead of a collation-dependent one. SQL Server's default
-- collation happens to be case-insensitive, but relying on that would make
-- correctness a deployment setting.
-- -----------------------------------------------------------------------------
CREATE TABLE users (
    id    BIGINT        NOT NULL IDENTITY(1,1),
    name  NVARCHAR(200) NOT NULL,
    email NVARCHAR(254) NOT NULL,
    CONSTRAINT PK_users PRIMARY KEY (id),
    CONSTRAINT UQ_users_email UNIQUE (email)
);

-- -----------------------------------------------------------------------------
-- teams -- INV-1, INV-3
--
-- `team_lead` is NOT NULL: a team always has exactly one lead. The lead is also
-- always present in team_members (INV-1, enforced in the Team constructor).
-- -----------------------------------------------------------------------------
CREATE TABLE teams (
    id        BIGINT        NOT NULL IDENTITY(1,1),
    name      NVARCHAR(200) NOT NULL,
    team_lead BIGINT        NOT NULL,
    CONSTRAINT PK_teams PRIMARY KEY (id),
    CONSTRAINT FK_teams_team_lead FOREIGN KEY (team_lead) REFERENCES users (id)
);

-- UQ-1 scans teams by lead; PR-4 and TM-4 do too.
CREATE INDEX IX_teams_team_lead ON teams (team_lead);

-- -----------------------------------------------------------------------------
-- team_members -- R03
--
-- The composite primary key makes membership a set, which is what the domain
-- calls it (Team.memberIds is a Set<UserId>) -- a duplicate row is impossible
-- rather than merely unlikely.
-- -----------------------------------------------------------------------------
CREATE TABLE team_members (
    team_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    CONSTRAINT PK_team_members PRIMARY KEY (team_id, user_id),
    CONSTRAINT FK_team_members_team FOREIGN KEY (team_id) REFERENCES teams (id),
    CONSTRAINT FK_team_members_user FOREIGN KEY (user_id) REFERENCES users (id)
);

-- The PK covers (team_id, user_id); "which teams is this user in" (TM-4, VIS-4)
-- needs the other direction.
CREATE INDEX IX_team_members_user ON team_members (user_id);

-- -----------------------------------------------------------------------------
-- projects -- INV-2, INV-3
--
-- `project_owner` references users directly and is INDEPENDENT of team
-- membership (INV-2): owning a project confers no membership of its team. That
-- independence is exactly what makes VIS-2 and AS-4 non-trivial, so the schema
-- must not quietly imply otherwise by routing the owner through team_members.
-- -----------------------------------------------------------------------------
CREATE TABLE projects (
    id            BIGINT        NOT NULL IDENTITY(1,1),
    name          NVARCHAR(200) NOT NULL,
    team_id       BIGINT        NOT NULL,
    project_owner BIGINT        NOT NULL,
    CONSTRAINT PK_projects PRIMARY KEY (id),
    CONSTRAINT FK_projects_team FOREIGN KEY (team_id) REFERENCES teams (id),
    CONSTRAINT FK_projects_owner FOREIGN KEY (project_owner) REFERENCES users (id)
);

CREATE INDEX IX_projects_team ON projects (team_id);
CREATE INDEX IX_projects_owner ON projects (project_owner);

-- -----------------------------------------------------------------------------
-- tasks -- section 2, section 4
--
-- `status` stores the exact wire spelling from spec.md ('In Progress', with the
-- space) rather than a Kotlin enum constant name. TaskStatus keeps wireName
-- separate from the constant precisely so that renaming the constant cannot
-- silently rewrite the database, and the CHECK constraint below is the other
-- half of that promise: a typo in the mapper fails loudly at INSERT instead of
-- creating a sixth status nobody can transition out of.
--
-- `assignee` is the only nullable foreign key in the schema: TK-2 creates every
-- task unassigned, and AS-7 allows returning a Todo task to the unassigned
-- queue.
-- -----------------------------------------------------------------------------
CREATE TABLE tasks (
    id          BIGINT         NOT NULL IDENTITY(1,1),
    name        NVARCHAR(200)  NOT NULL,
    description NVARCHAR(4000) NOT NULL,
    project_id  BIGINT         NOT NULL,
    creator     BIGINT         NOT NULL,
    assignee    BIGINT         NULL,
    status      NVARCHAR(20)   NOT NULL,
    CONSTRAINT PK_tasks PRIMARY KEY (id),
    CONSTRAINT FK_tasks_project FOREIGN KEY (project_id) REFERENCES projects (id),
    CONSTRAINT FK_tasks_creator FOREIGN KEY (creator) REFERENCES users (id),
    CONSTRAINT FK_tasks_assignee FOREIGN KEY (assignee) REFERENCES users (id),
    CONSTRAINT CK_tasks_status CHECK (
        status IN ('Draft', 'Todo', 'In Progress', 'Completed', 'Canceled')
    )
);

-- findVisibleInProject filters by project and then by status.
CREATE INDEX IX_tasks_project_status ON tasks (project_id, status);
-- findAssignedTo (VIS-1) and findLiveTasksAssignedTo (TM-8, PR-8).
CREATE INDEX IX_tasks_assignee_status ON tasks (assignee, status);

-- -----------------------------------------------------------------------------
-- comments -- section 6
--
-- CM-5: the timestamp is UTC truncated to the MINUTE. DATETIME2(0) is the
-- narrowest type that holds it without loss; it also makes the truncation
-- visible in the schema instead of being a convention the next developer has to
-- infer. The column is named created_at rather than `timestamp`, which is a
-- reserved word in both SQL Server and H2.
--
-- CM-6: comments are immutable. There is no updated_at because there is no
-- update, and the repository port offers neither.
-- -----------------------------------------------------------------------------
CREATE TABLE comments (
    id         BIGINT         NOT NULL IDENTITY(1,1),
    task_id    BIGINT         NOT NULL,
    author     BIGINT         NOT NULL,
    created_at DATETIME2(0)   NOT NULL,
    content    NVARCHAR(4000) NOT NULL,
    CONSTRAINT PK_comments PRIMARY KEY (id),
    CONSTRAINT FK_comments_task FOREIGN KEY (task_id) REFERENCES tasks (id),
    CONSTRAINT FK_comments_author FOREIGN KEY (author) REFERENCES users (id)
);

-- CM-2 reads a task's comments oldest first; id ascending is that order.
CREATE INDEX IX_comments_task ON comments (task_id, id);
