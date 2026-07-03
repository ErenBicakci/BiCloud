# BiCloud

BiCloud is a lightweight, multi-tenant container orchestration platform built
around Docker. Users sign up, create projects, define services from Docker
images, choose replica counts and resource limits, and BiCloud schedules those
containers onto registered worker machines. The platform then exposes the
services through a dynamic gateway, monitors worker/container health, and
reconciles runtime state in the background.

The project is best described as a **Kubernetes-inspired mini cloud / mini PaaS**
for containerized services. It is not Kubernetes itself: there is no
kube-apiserver, etcd, CNI, Pod API, CRD system, or Kubernetes scheduler. The goal
is a smaller, readable orchestration platform that implements the core ideas
needed for a graduation-scale cloud infrastructure project: desired state,
scheduling, worker agents, health checks, self-healing, service routing, and
tenant isolation.

## Table of Contents

- [What BiCloud Does](#what-bicloud-does)
- [Core Features](#core-features)
- [Architecture](#architecture)
- [Components](#components)
- [How It Works](#how-it-works)
- [Setup and Running](#setup-and-running)
- [Typical Usage](#typical-usage)
- [API Overview](#api-overview)
- [Security and Isolation](#security-and-isolation)
- [Data Model](#data-model)
- [Frontend Features](#frontend-features)
- [Tests](#tests)
- [Limitations and Notes](#limitations-and-notes)
- [Troubleshooting](#troubleshooting)

## What BiCloud Does

BiCloud moves Docker container management from single-machine commands into a
small platform model.

With BiCloud:

- Users register and authenticate.
- Each user can create one or more projects.
- Each project acts as a tenant boundary.
- A project can contain multiple service definitions.
- Each service is defined by a Docker image, container port, desired replica
  count, CPU/RAM limits, environment variables, and optional internet egress.
- The control plane selects suitable worker nodes.
- Worker agents create, start, stop, remove, inspect, and monitor Docker
  containers on their local machines.
- Gateways route external and internal service traffic to the right containers.
- Background controllers detect missing replicas, dead containers, offline
  workers, stale gateway routes, and state drift between the database and Docker.

## Core Features

- **Multi-tenant project model:** Each project is treated as a tenant boundary.
- **User and role management:** The platform supports `USER` and `ADMIN` roles.
- **JWT authentication:** User-facing APIs are protected with JWT bearer tokens.
- **Internal API key authentication:** Worker, gateway, and control-plane
  internal APIs are protected with `X-Api-Key`.
- **Project and service management:** Create/delete projects, add/update/delete
  services, deploy projects, undeploy projects, and reset service failure state.
- **Replica management:** Services can be scaled between 0 and 10 replicas.
- **Resource limits:** Services define memory and CPU limits.
- **Environment variables:** Services can define environment variables. The
  `BICLOUD_` prefix is reserved for platform-managed values.
- **Score-based scheduling:** Workers are selected using live CPU/RAM usage,
  database-backed resource reservations, and soft anti-affinity.
- **Async deployments:** Slow operations such as Docker image pulls run outside
  request threads.
- **Self-healing:** Missing replicas are started again, excess replicas are
  scaled down, and stale `PENDING` rows are failed so they can be retried.
- **Crash-loop cooldown:** Services with repeated failures are temporarily put
  into cooldown.
- **Worker heartbeat:** Workers send regular heartbeat messages. Silent workers
  become `OFFLINE`, and after a longer grace period their containers are marked
  `FAILED`.
- **Container reconciliation:** Worker snapshots are compared with the database.
  Missing Docker containers are marked `FAILED`; falsely failed but still-running
  containers can be recovered to `RUNNING`.
- **Dynamic gateway routing:** Services are exposed with
  `{service}.{project}.bicloud.local`.
- **Internal service mesh-style routing:** Containers use `BICLOUD_MESH_BASE` to
  reach other services in the same project.
- **Tenant network isolation:** Each project gets its own internal Docker bridge
  network.
- **Admin-controlled internet egress:** Containers are isolated from the internet
  by default. Only admins can enable egress for a service.
- **Live metrics:** Workers send container CPU/RAM samples to the control plane.
- **Container logs:** Users can view container log tails from the UI.
- **Audit trail:** User and system actions are stored in the database.
- **Admin panel:** Admins can manage users, inspect workers, toggle maintenance
  mode, and trigger gateway resync.

## Architecture

```text
                           +------------------------+
                           |  bicloud-front-end     |
                           |  React + Vite          |
                           +-----------+------------+
                                       |
                                       | JWT
                                       v
                           +------------------------+
                           | bicloud-control-plane  |
                           | Spring Boot + Postgres |
                           | auth, projects,        |
                           | scheduling, healing    |
                           +-----+-------------+----+
                                 |             |
                         X-Api-Key             | gateway API key
                                 |             |
                                 v             v
        +-----------------------------+   +-----------------------------+
        | Machine A                   |   | Machine B                   |
        |                             |   |                             |
        | +-------------------------+ |   | +-------------------------+ |
        | | bicloud-worker          | |   | | bicloud-worker          | |
        | | Spring Boot + Docker    | |   | | Spring Boot + Docker    | |
        | +------------+------------+ |   | +------------+------------+ |
        |              |              |   |              |              |
        |              v              |   |              v              |
        | +-------------------------+ |   | +-------------------------+ |
        | | Docker containers       | |   | | Docker containers       | |
        | | project networks        | |   | | project networks        | |
        | +------------+------------+ |   | +------------+------------+ |
        |              ^              |   |              ^              |
        |              |              |   |              |              |
        | +------------+------------+ |   | +------------+------------+ |
        | | bicloud-gateway         |<---->| bicloud-gateway         | |
        | | Spring Cloud Gateway    | |   | | Spring Cloud Gateway    | |
        | +-------------------------+ |   | +-------------------------+ |
        +-----------------------------+   +-----------------------------+
```

The control plane is the central authority. Workers apply the desired state to
local Docker daemons. Gateways keep route state in memory and use the control
plane discovery endpoint when a request must be forwarded to another machine.

## Components

| Component | Stack | Responsibility |
| --- | --- | --- |
| `bicloud-control-plane` | Java 21, Spring Boot, Spring Security, JPA, PostgreSQL | Users, projects, services, workers, containers, scheduling, self-healing, audit |
| `bicloud-worker` | Java 21, Spring Boot, docker-java | Per-machine Docker container lifecycle, networks, logs, metrics, heartbeat |
| `bicloud-gateway` | Java 21, Spring Cloud Gateway, WebFlux, docker-java | Dynamic routing, round-robin load balancing, mesh routing, gateway resync |
| `bicloud-front-end` | React 19, Vite, Axios, React Router | User and admin management UI |
| `deploy-ubuntu` | Docker Compose + native worker jar | Ubuntu worker-node deployment package |

### Repository Layout

```text
.
|-- bicloud-control-plane/   # Central API, database model, schedulers
|-- bicloud-worker/          # Worker node agent
|-- bicloud-gateway/         # Dynamic gateway and mesh proxy
|-- bicloud-front-end/       # React SPA
|-- deploy-ubuntu/           # Ubuntu node deployment files
|-- docs/                    # Architecture, review, and security notes
|-- docker-compose.yml       # Gateway container for local/default setup
|-- docker-compose.ubuntu.yml# Ubuntu gateway + socket-proxy setup
|-- .env.example             # Example compose environment variables
`-- README.md
```

## How It Works

### 1. User and Project Flow

1. A user registers with `/auth/register` or logs in with `/auth/login`.
2. The frontend sends the JWT as `Authorization: Bearer ...`.
3. The user creates a project.
4. The project name must be globally unique. It is used as the tenant identity
   for Docker networks, gateway route keys, mesh paths, and service discovery.
5. The user defines one or more services inside the project.
6. When deployed, the control plane schedules service replicas onto workers.

### 2. Service Deployment Flow

1. The frontend calls `POST /project/{id}/deploy`.
2. The control plane loads all service definitions in the project.
3. Each service deployment is queued asynchronously.
4. `DeploymentService` calculates how many `RUNNING` or `PENDING` replicas
   already exist.
5. If more replicas are needed, `WorkerScoringService` selects a worker.
6. The control plane first creates a `PENDING` `ContainerInstance` row. This is
   visible to the UI and also works as a scheduling reservation.
7. The control plane calls the selected worker's `/api/containers/create`
   endpoint.
8. The worker pulls the Docker image if it is not already present locally.
9. The worker creates or verifies the project network, connects the container to
   it, removes the container from Docker's default `bridge` network, and starts
   the container.
10. The worker returns the Docker container ID and the container's internal IP.
11. The control plane marks the instance `RUNNING`.
12. The control plane registers the instance with the gateway running on the
   same machine as the worker.
13. The gateway adds the instance to its in-memory route registry.

### 3. Scheduling Logic

Worker selection does not rely only on instantaneous OS usage. BiCloud combines:

- Live CPU/RAM usage from worker heartbeats.
- Database reservations calculated from `RUNNING` and `PENDING` container
  resource limits.

Because a `PENDING` row is committed before the worker call, a burst of deploys
does not keep selecting the same worker before CPU usage has had time to rise.

BiCloud also applies soft anti-affinity for replicas of the same service. When
possible, replicas are spread across different workers. If only one worker is
available, scheduling can still place all replicas there.

### 4. Scale, Update, and Undeploy

- **Scale up:** `desiredReplicas` is increased and missing replicas are started
  asynchronously.
- **Scale down:** Extra containers are deregistered from the gateway, stopped on
  the worker, removed, and marked `STOPPED`.
- **Scale to zero:** Replica count becomes 0. Self-healing does not restart the
  service.
- **Update service:** Image, port, resource limits, environment variables, or
  internet egress settings are changed. Existing containers are stopped and the
  service is redeployed with the new configuration.
- **Undeploy project:** Services are marked `stoppedByUser=true`, so
  self-healing does not resurrect them.
- **Delete project/service:** Containers are stopped first, then database records
  are deleted.

### 5. Self-Healing and Reconciliation

The control plane runs several background loops:

- Worker health check: every 20 seconds, stale worker heartbeats are detected.
- Self-healing: every 30 seconds, desired replica counts are compared with
  current `RUNNING`/`PENDING` counts.
- Metrics cleanup: silent metric series are removed after 10 minutes.

The worker runs:

- Heartbeat every 5 seconds.
- Container health checks every 10 seconds.
- Container snapshots every 20 seconds.

The gateway runs:

- Route registry reconciliation.
- Docker network scanning.
- Control-plane route resync requests after restarts or missed notifications.
- Stale route pruning based on local Docker network reality.

## Setup and Running

### Requirements

- Java 21+
- Docker
- Docker Compose
- PostgreSQL 14+ or PostgreSQL through Docker
- Node.js 20+ and npm
- For multi-machine deployments: flat L3 connectivity between machines. A
  Tailscale-style overlay network is enough.

Default ports:

| Service | Default port |
| --- | --- |
| Control plane | `8080` |
| Worker | `8081` |
| Gateway | `9000` |
| Frontend dev server | `5173` |
| Frontend production Nginx | `80` |

### 1. Start PostgreSQL

For local development, PostgreSQL can be started with Docker:

```powershell
docker run --name bicloud-postgres `
  -e POSTGRES_DB=bicloud `
  -e POSTGRES_USER=postgres `
  -e POSTGRES_PASSWORD=postgres `
  -p 5432:5432 `
  -d postgres:16
```

If you use an existing PostgreSQL installation, create a database named
`bicloud`.

### 2. Create Configuration Files

Copy the example files into real configuration files.

PowerShell:

```powershell
Copy-Item .env.example .env
Copy-Item bicloud-control-plane\src\main\resources\application.properties.example `
  bicloud-control-plane\src\main\resources\application.properties
Copy-Item bicloud-worker\src\main\resources\application.properties.example `
  bicloud-worker\src\main\resources\application.properties
Copy-Item bicloud-gateway\src\main\resources\application.properties.example `
  bicloud-gateway\src\main\resources\application.properties
```

Bash:

```bash
cp .env.example .env
cp bicloud-control-plane/src/main/resources/application.properties.example \
   bicloud-control-plane/src/main/resources/application.properties
cp bicloud-worker/src/main/resources/application.properties.example \
   bicloud-worker/src/main/resources/application.properties
cp bicloud-gateway/src/main/resources/application.properties.example \
   bicloud-gateway/src/main/resources/application.properties
```

### 3. Fill Secrets and Addresses

Control-plane `application.properties`:

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/bicloud
spring.datasource.username=postgres
spring.datasource.password=postgres

# Shared key for worker/gateway access to control-plane internal endpoints.
bicloud.api-key=CHANGE_ME_WORKER_CP_KEY

# Used by the control plane when calling /gateway/register and /gateway/deregister.
bicloud.gateway.url=http://localhost:9000
bicloud.gateway.port=9000
bicloud.gateway.api-key=CHANGE_ME_GATEWAY_KEY

# Use a random secret with at least 32 characters.
jwt.secret=CHANGE_ME_MIN_32_CHARS
```

Worker `application.properties`:

```properties
server.port=8081
worker.name=worker-1
worker.control-plane-url=http://CONTROL_PLANE_IP:8080

# Linux:
docker.host=unix:///var/run/docker.sock

# Windows Docker Desktop alternative:
# docker.host=npipe:////./pipe/docker_engine

# Must match control-plane bicloud.api-key.
bicloud.api-key=CHANGE_ME_WORKER_CP_KEY
```

Gateway `application.properties`:

```properties
server.port=9000

# Used for gateway management and gateway-to-gateway requests.
# Must match control-plane bicloud.gateway.api-key.
bicloud.gateway.api-key=CHANGE_ME_GATEWAY_KEY

bicloud.gateway.container-name=bicloud-gateway
bicloud.gateway.docker-host=unix:///var/run/docker.sock
bicloud.control-plane.url=http://CONTROL_PLANE_IP:8080

# Must match control-plane bicloud.api-key.
bicloud.control-plane.api-key=CHANGE_ME_WORKER_CP_KEY
```

Root `.env` is used by the gateway compose file:

```env
BICLOUD_CONTROL_PLANE_URL=http://CONTROL_PLANE_IP:8080
BICLOUD_GATEWAY_API_KEY=CHANGE_ME_GATEWAY_KEY
```

Spring Boot relaxed binding maps environment variables such as
`BICLOUD_CONTROL_PLANE_URL` to `bicloud.control-plane.url` and
`BICLOUD_GATEWAY_API_KEY` to `bicloud.gateway.api-key`.

The gateway also needs `bicloud.control-plane.api-key` for control-plane
discovery calls. You can set it in `application.properties` or pass
`BICLOUD_CONTROL_PLANE_API_KEY` through the gateway environment.

### 4. Run the Services in Development Mode

#### Control Plane

PowerShell:

```powershell
cd bicloud-control-plane
.\mvnw.cmd spring-boot:run
```

Linux/macOS:

```bash
cd bicloud-control-plane
./mvnw spring-boot:run
```

The control plane uses Hibernate `ddl-auto=update`, so tables are created or
updated automatically.

#### Worker

Run a worker on each machine that should execute containers:

```powershell
cd bicloud-worker
.\mvnw.cmd spring-boot:run
```

On startup, the worker registers with the control plane and writes its stable
ID to `worker-id.txt`. On restart, it reuses the same worker identity.

#### Gateway

The gateway needs access to Docker networks, so it is normally run as a Docker
container. The root `docker-compose.yml` starts the gateway only; it is not a
full-stack compose file.

The gateway Dockerfile expects a built jar under `target`, so package it first:

```powershell
cd bicloud-gateway
.\mvnw.cmd package -DskipTests
cd ..
docker compose up --build -d
```

On Windows Docker Desktop, the root compose file assumes Docker daemon access
through `tcp://host.docker.internal:2375`. If that TCP endpoint is not enabled,
the gateway will not be able to manage Docker networks. Use a suitable Docker
daemon configuration or the Ubuntu socket-proxy setup.

Ubuntu/Linux gateway setup:

```bash
docker compose -f docker-compose.ubuntu.yml up --build -d
```

Gateway health check:

```powershell
curl http://localhost:9000/gateway/health
```

#### Frontend

```powershell
cd bicloud-front-end
npm install
npm run dev
```

The Vite dev server runs at:

```text
http://localhost:5173
```

In development, Vite proxies `/api-cp/*` to `http://localhost:8080/*`.

### 5. First Admin User

The public registration endpoint creates a normal `USER`. The
`POST /auth/admin/register` endpoint requires an existing admin. The current
codebase does not include an automatic first-admin bootstrap mechanism.

For local development:

1. Register the first user through the UI or `/auth/register`.
2. Promote that user in PostgreSQL:

```sql
UPDATE bicloud_users
SET role = 'ADMIN'
WHERE username = 'admin_username';
```

For production-like usage, a proper seed/bootstrap mechanism should be added.

## Typical Usage

1. Open the frontend: `http://localhost:5173`.
2. Register or log in.
3. Create a project.
   - Example project name: `demo`
   - The name must start with a lowercase letter, contain only lowercase
     letters, digits, and hyphens, and must not start with `bicloud-` or
     `egress-`.
4. Add a service in the project.
   - Example service name: `api`
   - Image: `nginx:alpine` or your own image
   - Container port: `80`
   - Replicas: `1`
   - Memory: `128`
   - CPU: `0.5`
5. Click Deploy.
6. The worker pulls the image if needed and starts the container.
7. The service detail page shows replica health, endpoints, metrics,
   environment variables, and container instances.

External access uses the gateway host format:

```text
http://api.demo.bicloud.local:9000
```

For local testing without DNS or hosts-file entries, send the Host header
manually:

```powershell
curl -H "Host: api.demo.bicloud.local" http://localhost:9000/
```

Inside a container, BiCloud injects:

```text
BICLOUD_MESH_BASE=http://bicloud-gateway:9000/_bicloud/mesh/demo
```

To reach service `api` from another service in the same project:

```text
${BICLOUD_MESH_BASE}/api/health
```

This address works even if the target service runs on another worker machine.

## API Overview

### User-Facing Control-Plane APIs

These endpoints use JWT authentication. Except for login/register, requests
must include `Authorization: Bearer <token>`.

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/auth/register` | Create a normal user |
| `POST` | `/auth/login` | Get a JWT token |
| `GET` | `/auth/me` | Get the current user |
| `POST` | `/auth/admin/register` | Create an admin user; admin-only |
| `POST` | `/project` | Create a project |
| `GET` | `/project` | List projects |
| `GET` | `/project/{id}` | Get project details |
| `DELETE` | `/project/{id}` | Delete a project and its services |
| `POST` | `/project/image` | Add a service to a project |
| `PUT` | `/project/image/{imageId}` | Update service configuration |
| `DELETE` | `/project/image/{imageId}` | Delete a service |
| `POST` | `/project/{id}/deploy` | Deploy a project |
| `POST` | `/project/{id}/undeploy` | Stop all containers in a project |
| `PUT` | `/project/image/{imageId}/scale` | Change replica count |
| `POST` | `/project/image/{imageId}/reset-failures` | Reset service failure counters |
| `GET` | `/containers/project/{projectId}` | List project containers |
| `GET` | `/containers/project/{projectId}/search` | Search/filter/paginate containers |
| `GET` | `/containers/project/{projectId}/metrics` | Get container metrics |
| `POST` | `/containers/{instanceId}/stop` | Stop a container |
| `DELETE` | `/containers/{instanceId}` | Stop and remove a container |
| `GET` | `/containers/{instanceId}/logs` | Get container log tail |
| `GET` | `/workers` | List workers |
| `GET` | `/workers/{workerId}` | Get worker details |
| `PUT` | `/workers/{workerId}/maintenance` | Toggle worker maintenance mode; admin-only |
| `GET` | `/audit` | Audit feed |
| `GET` | `/audit/project/{projectId}` | Project audit feed |
| `GET` | `/admin/users` | List users; admin-only |
| `PUT` | `/admin/users/{userId}/role` | Change user role; admin-only |
| `DELETE` | `/admin/users/{userId}` | Delete user; admin-only |
| `POST` | `/admin/gateway/resync` | Re-register running containers with gateways; admin-only |

### Internal Control-Plane APIs

These endpoints are used by workers and gateways and require `X-Api-Key`.

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/api/workers/register` | Register a worker |
| `POST` | `/api/workers/heartbeat` | Worker heartbeat |
| `POST` | `/api/workers/deregister` | Deregister a worker on shutdown |
| `POST` | `/api/workers/container-status` | Worker container status update |
| `POST` | `/api/workers/container-snapshot` | Worker Docker snapshot and metrics |
| `GET` | `/api/workers/discover/{projectName}/{serviceName}` | Gateway service discovery |
| `POST` | `/api/workers/gateway-resync` | Gateway asks the control plane to resend route registrations |

### Worker APIs

The control plane calls worker APIs with `X-Api-Key`.

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/api/containers/create` | Create and start a container |
| `POST` | `/api/containers/{containerId}/stop` | Stop a container |
| `POST` | `/api/containers/{containerId}/restart` | Restart a container |
| `DELETE` | `/api/containers/{containerId}` | Remove a container |
| `GET` | `/api/containers/{containerId}/logs` | Get log tail |

### Gateway APIs

`/gateway/health` is public. Other `/gateway/*` endpoints require the gateway
API key.

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/gateway/register` | Register a service instance route |
| `DELETE` | `/gateway/deregister` | Deregister a service instance route |
| `GET` | `/gateway/routes` | Snapshot of the in-memory route registry |
| `GET` | `/gateway/health` | Gateway health |

## Security and Isolation

### User Boundary

- Users authenticate with JWT.
- The backend is stateless.
- Passwords are stored with BCrypt hashes.
- Admin endpoints are protected by Spring Security path/method rules.
- A normal user can manage only their own projects. Admins can inspect and
  manage all projects.

### Internal Service Boundary

- Worker-to-control-plane calls use `bicloud.api-key`.
- Gateway-to-control-plane discovery calls use `bicloud.control-plane.api-key`.
- Control-plane-to-gateway management calls use `bicloud.gateway.api-key`.
- Gateway-to-gateway mesh forwarding uses the gateway API key.
- API key comparisons use constant-time comparison.

### Tenant Isolation

- Each project gets a Docker network named `bicloud-{projectName}`.
- The project network is created as an internal bridge network.
- Containers are detached from Docker's default `bridge` network before start.
- The gateway joins tenant networks with the `bicloud-gateway` alias.
- Mesh routing derives the caller project from the source IP subnet.
- A container can access only services in its own project over the mesh.
- Cross-machine gateway hops carry a verified caller-project header and gateway
  key.

### Internet Egress

Services cannot reach the internet by default. If an admin enables
`allowInternet=true`, the worker also attaches that container to a
project-specific egress bridge named `bicloud-egress-{projectName}`.

The egress bridge is per-project. Different tenants do not share a single egress
L2 segment.

## Data Model

Persistent state lives in PostgreSQL through the control plane.

Main tables:

| Table | Purpose |
| --- | --- |
| `bicloud_users` | Users, password hashes, roles |
| `user_projects` | Tenant/project records |
| `project_images` | Service definitions |
| `project_image_env_vars` | Service environment variable map |
| `container_instances` | Runtime container instance records |
| `worker_nodes` | Worker identity and capacity |
| `worker_states` | Worker IP, heartbeat, CPU/RAM, status |
| `audit_events` | User and system audit events |

Non-persistent runtime state:

- Control-plane metric history: in-memory ring buffer, up to 90 points per
  container.
- Gateway route registry: in memory, refilled by resync.
- Gateway subnet-to-project map: built from Docker network scans.
- Frontend token/user state: browser session storage.

## Frontend Features

The frontend is a React SPA.

User pages:

- Login/register.
- Dashboard with project, service, and running container summaries.
- Projects page with search, create, deploy, undeploy, and delete actions.
- Project detail page with:
  - Services tab.
  - Containers tab.
  - Activity/audit tab.
  - Add/edit/scale/delete service actions.
  - Container log modal.
- Service detail page with:
  - Replica health.
  - CPU/RAM metric cards and sparklines.
  - Internal mesh endpoint and external gateway endpoint.
  - Environment variable display/copy.
  - Cooldown reset.
  - Container stop/remove/log actions.
- Activity page with audit timeline.

Admin pages:

- System overview.
- Users: list users, change roles, delete users.
- Workers: worker list, CPU/RAM usage, heartbeat, running container count,
  scheduler score.
- Worker detail page.
- Worker maintenance toggle.
- Gateway resync button.

## Tests

Control-plane tests:

```powershell
cd bicloud-control-plane
.\mvnw.cmd test
```

Worker tests:

```powershell
cd bicloud-worker
.\mvnw.cmd test
```

Gateway tests:

```powershell
cd bicloud-gateway
.\mvnw.cmd test
```

Frontend lint/build:

```powershell
cd bicloud-front-end
npm run lint
npm run build
```

Existing tests cover areas such as:

- Worker scoring and resource reservations.
- Self-healing behavior.
- Container reconciliation.
- Worker heartbeat and worker status transitions.
- Spring context loading.

## Limitations and Notes

- BiCloud is not Kubernetes. It does not implement Kubernetes APIs or use
  Kubernetes components.
- There is no image build/push pipeline. Users provide existing Docker image
  names.
- There is no automatic DNS management. Use wildcard DNS, hosts-file entries,
  or explicit Host headers for `*.bicloud.local`.
- Gateway route state is in memory. Automatic and manual resync mechanisms
  refill it after restarts.
- Container metrics are not persisted. Metric history resets after a
  control-plane restart.
- The current codebase has no automatic first-admin bootstrap.
- Production usage would need TLS, proper secret management, registry auth, log
  aggregation, stricter network policy, and operational hardening.
- Self-healing is intentionally eventually consistent. Docker and network drift
  are corrected by periodic loops rather than instant transactions.
- The root `docker-compose.yml` is not a full-stack deployment. It runs the
  gateway only.
- The gateway must have correct Docker daemon access to join tenant networks.

## Troubleshooting

### Worker Does Not Register

- Is `worker.control-plane-url` correct?
- Can the worker machine reach `http://CONTROL_PLANE_IP:8080`?
- Does worker `bicloud.api-key` match control-plane `bicloud.api-key`?
- Does the worker log show a sensible `worker.ip auto-detected` value?
- Is worker port `8081` reachable from the control plane?

### Worker Becomes OFFLINE

- Workers send heartbeat every 5 seconds.
- The control plane marks a worker `OFFLINE` after 30 seconds without heartbeat.
- Check network connectivity, worker IP detection, firewall rules, and API key
  mismatches.
- `MAINTENANCE` status is not overwritten automatically by heartbeat.

### Gateway Returns 401

- Does control-plane `bicloud.gateway.api-key` match gateway
  `bicloud.gateway.api-key`?
- Is `BICLOUD_GATEWAY_API_KEY` set correctly in compose?

### Gateway Returns 404

The Host header may not match the expected format:

```text
{service}.{project}.bicloud.local
```

Example:

```powershell
curl -H "Host: api.demo.bicloud.local" http://localhost:9000/
```

### Gateway Returns 503

- The service may have no `RUNNING` containers.
- The gateway route registry may be empty.
- Try Gateway Resync from the admin panel.
- Check that the gateway can join the relevant Docker tenant network.

### Container Cannot Reach the Internet

That is the default behavior. The service must have `allowInternet=true`, and
only an admin can enable it.

### Deployment Takes a Long Time

If the Docker image is not in the worker's local cache, the worker pulls it
first. Worker pull timeout is 10 minutes; control-plane deploy HTTP read timeout
is 15 minutes. Large images or slow registries can leave replicas in `PENDING`
for a while.

### Docker Network Error

The worker verifies the internal/external mode of existing Docker networks. If a
network with the expected name already exists but has the wrong mode, the worker
refuses to reuse it. Remove the incorrect network and let BiCloud recreate it.

## Summary

BiCloud consists of a Spring Boot control plane, worker agents, a dynamic
Spring Cloud Gateway layer, and a React management UI. It implements a small but
coherent orchestration model: desired state, worker scheduling, heartbeat,
reconciliation, self-healing, service routing, and tenant isolation over Docker.
