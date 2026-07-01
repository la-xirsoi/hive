# Hive App Specification

Hive is a simple, multi-user, task tracking app. It tracks team membership, projects, and tasks.

## Models

The core concepts in Hive are the User, Team, Project, and Task.
Alongside these are Comments, which a User may leave on a Task.

User:

| Field  | Type            |
|--------|-----------------|
| Id     | Number          |
| Name   | String(1..200)  |
| Email  | String(5..254)  |

- Name is required. Email is required, must be valid format, and must be unique.

Team:

| Field     | Type        |
|-----------|-------------|
| Id        | Number      |
| Name      | String      |
| TeamLead  | User Id     |

Project:

| Field         | Type        |
|---------------|-------------|
| Id            | Number      |
| Name          | String      |
| Team          | Team Id     |
| ProjectOwner  | User Id     |

Task:

| Field         | Type           |
|---------------|----------------|
| Id            | Number         |
| Name          | String(1..200) |
| Description   | String         |
| Project       | Project Id     |
| Creator       | User Id        |
| Assignee      | User Id?       |
| Status        | String*        |

- Note: Status may be one of the following values ['Draft', 'Todo', 'In Progress', 'Completed', 'Canceled']. Default is 'Draft'.

### Status Transitions

| From         | To           | Allowed By               |
|--------------|--------------|--------------------------|
| Draft        | Todo         | Project Owner            |
| Draft        | Canceled     | Project Owner            |
| Todo         | In Progress  | Team Member (Assignee)   |
| Todo         | Canceled     | Project Owner            |
| In Progress  | Completed    | Team Member (Assignee)   |
| In Progress  | Canceled     | Project Owner            |
| Canceled     | —            | (terminal)               |
| Completed    | —            | (terminal)               |

Comment:

| Field         | Type        |
|---------------|-------------|
| Id            | Number      |
| TimeStamp     | DateTime    |
| Task          | Task Id     |
| Author        | User Id     |
| Content       | String      |

- TimeStamp precision is to the minute. TimeZone is UTC on the server; browsers translate to local time.

A User can be a Member of any number of Teams, a Team may have any number of Projects. A Project can only have one Team. A Task can only belong to one Project. Projects can only have one ProjectOwner, and Teams can only have one TeamLead. However, a User can be the ProjectOwner on any number of Projects or be the TeamLead on any number of Teams. A single User can be both a TeamLead and a ProjectOwner.

Team Leads may transfer ownership of a Team to another User. Project Owners may transfer ownership of a Project to another User.

## Roles

There are three major roles that Hive must cover, but there are also common needs for all three.

### Common Needs

All Users need to be able to see a list of their Projects and Teams. They need to be able to see all Tasks assigned to them.
Any User should be able to create a Team or a Project. When a Team or Project is created, the User doing so is by default the Team Lead or Project Owner respectively. These roles may be assigned to another User, however.

### R01: The Project Owner

The Project Owner is a User who is the 'owner' of one or more Projects. They are responsible for creating new Tasks. They cannot be assigned a Task in their own project.

The Project Owner needs to be able to see all Tasks, regardless of assignment, for their Projects. They also need to be able to create Tasks and update the title and description. They cannot change the status of a Task, except to move it from 'Draft' to 'Todo' or to 'Cancel' it.

### R02: The Team Lead

The Team Lead is a User who is the 'lead' on a Team. They are responsible for assigning Tasks from their Teams' Projects to Members of the Team. They *can* assign Tasks to themselves.

Team Leads need to be able to assign Tasks from their Teams' Projects to Members of that Project's Team. They can see all Tasks that are not in 'Draft' status for their Teams' Projects. They are capable of re-assigning Tasks. Unassigned Tasks should be brought to the Team Lead's attention; assigning these are a priority.

### R03: The Team Member

A Team Member is a User assigned to a Team. A Team Lead is also a Team Member, but a Project Owner is not. Team Members are assigned Tasks by the Team Lead. They are responsible for updating the Status of a Task. They should be able to see any Task not in 'Draft' or 'Canceled' Status that is in a Project belonging to one of their Teams.

## Technology Constraints

The ONLY allowed programming languages are Kotlin ≥ 2.4 and TypeScript ≥ 6.0.

All components MUST be containerized.

Podman MUST be used for containerization.

The app MUST use a standard login system that is OAuth compatible. JWT bearer tokens are used for authentication. HTTPS MUST be enforced.

The backing datastore must be MS SQL Server 2022 or a compatible technology supported by Azure.

Testing:
- Backend: JUnit 5 + Mockk for Kotlin
- Frontend: Jasmine + Karma for Angular
- At least 70% line coverage on all production code
- Unit tests for domain logic, integration tests for adapters

Use SpringBoot ≥ 4.0.5 for the server and Angular ≥ 22.0.2 for the front end.

The architecture MUST match the Hexagonal Architectural Pattern:

| Layer       | Package                  | Contents                                   |
|-------------|--------------------------|--------------------------------------------|
| Domain      | `hive/domain/`           | Entities, Value Objects, Repository interfaces |
| Application | `hive/application/`      | Use case / service interfaces              |
| Adapter In  | `hive/adapter/in/`       | REST controllers, DTOs                     |
| Adapter Out | `hive/adapter/out/`      | JPA repositories, DB entities              |

## Error Handling

All error responses MUST include a descriptive message and the appropriate HTTP status code:

| Scenario                          | HTTP Status | Description                                        |
|-----------------------------------|-------------|----------------------------------------------------|
| Input validation failure          | 400         | Return details on which fields are invalid         |
| Missing or invalid authentication | 401         | Unauthorized                                       |
| Authorization failure             | 403         | User lacks required role for the operation         |
| Resource not found                | 404         | The requested entity does not exist                |
| Conflict (e.g., editing a Completed task) | 409 | The operation cannot be performed on the resource in its current state |
| Server error                      | 500         | Internal error; must not leak implementation details |

Tasks cannot be edited (title, description, or status) once they are in a terminal state (Completed or Canceled).
