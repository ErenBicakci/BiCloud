# BiCloud

A mini cloud — a multi-tenant PaaS where users sign up, create projects and deploy
their own containerized services, built from scratch on a Kubernetes-inspired
control plane. It manages Docker containers spread across multiple machines from a
single control plane: self-service deployment, scaling, self-healing, load
balancing and per-tenant isolation.

## Architecture

```
                        ┌─────────────────────────┐
                        │       Front-end         │
                        │     (React + Vite)      │
                        └───────────┬─────────────┘
                                    │ JWT + RBAC
                        ┌───────────▼─────────────┐
                        │      Control Plane      │
                        │ Spring Boot + PostgreSQL│
                        │  scheduling · healing   │
                        │ reconciliation · audit  │
                        └──────┬───────────┬──────┘
                    X-Api-Key  │           │  X-Api-Key
              ┌────────────────▼──┐   ┌────▼──────────────┐
              │     Machine A     │   │     Machine B     │
              │ ┌───────────────┐ │   │ ┌───────────────┐ │
              │ │    Worker     │ │   │ │    Worker     │ │
              │ │ (docker-java) │ │   │ │ (docker-java) │ │
              │ └───────────────┘ │   │ └───────────────┘ │
              │ ┌───────────────┐ │   │ ┌───────────────┐ │
              │ │    Gateway    │◄┼───┼─►    Gateway    │ │
              │ │   (WebFlux)   │ │   │ │   (WebFlux)   │ │
              │ └───────┬───────┘ │   │ └───────┬───────┘ │
              │   ┌─────▼─────┐   │   │   ┌─────▼─────┐   │
              │   │ Containers │  │   │   │ Containers │  │
              │   └───────────┘   │   │   └───────────┘   │
              └───────────────────┘   └───────────────────┘
```

| Component | Stack | Responsibility |
|-----------|-------|----------------|
| [bicloud-control-plane](bicloud-control-plane/) | Spring Boot, PostgreSQL, Spring Security | User/project/service management, score-based scheduling, self-healing, reconciliation, audit |
| [bicloud-worker](bicloud-worker/) | Spring Boot, docker-java | Per-machine agent: container lifecycle, metric collection, heartbeat |
| [bicloud-gateway](bicloud-gateway/) | Spring Cloud Gateway (WebFlux) | North-south and east-west (mesh) routing, round-robin load balancing, tenant isolation |
| [bicloud-front-end](bicloud-front-end/) | React 19, Vite, React Router | Management UI: live metrics, container management, audit timeline |

## How it works (the Kubernetes parallels)

- **Desired state in the database.** The CP database is the single source of
  truth. Workers and gateways run stateless and reconcile reality against it
  periodically (level-triggered reconciliation), so a missed push notification
  still converges. This mirrors the kube-apiserver / etcd split.
- **Self-healing control loop.** A 30-second loop tops up missing replicas,
  scales down excess ones, and re-schedules the containers of a dead worker onto
  healthy ones — the controller-manager idea. It includes crash-loop detection
  and cooldown/backoff (a simplified CrashLoopBackOff).
- **Score-based scheduling.** Workers are ranked by free CPU/RAM combined with a
  DB reservation of already-assigned containers, so a burst of deploys spreads
  out instead of piling onto one node — a simplified version of Kubernetes'
  request-based scheduling.
- **Two-way reconciliation.** Zombie records (in the DB, gone from Docker) are
  marked FAILED, and live containers falsely marked FAILED (e.g. after a brief
  worker flap) are recovered to RUNNING.
- **Multi-layer tenant isolation.** Each project gets its own internal Docker
  network. Mesh requests are authorized by the caller's source-IP subnet (which
  cannot be forged) plus a gateway key on cross-machine hops.
- **Location-transparent access.** A client reaches a service no matter which
  gateway it connects to; if the service is elsewhere the request is forwarded to
  the right machine's gateway, with a hop header to prevent loops.
- **Container hardening.** `capDrop(ALL)`, a pids-limit, memory/CPU limits, and
  detaching from the default bridge.

## Security

- User → platform: JWT + role-based access control (RBAC).
- Service → service: `X-Api-Key`; rate limiting (token bucket).
- Every critical operation is written to an audit trail.

## Setup

Requirements: Java 21+, Docker, PostgreSQL, Node.js. A flat L3 network between
machines (e.g. Tailscale) is enough.

Config files that contain secrets are not committed; copy them from the examples:

```bash
cp .env.example .env
cp bicloud-control-plane/src/main/resources/application.properties.example \
   bicloud-control-plane/src/main/resources/application.properties
cp bicloud-worker/src/main/resources/application.properties.example \
   bicloud-worker/src/main/resources/application.properties
cp bicloud-gateway/src/main/resources/application.properties.example \
   bicloud-gateway/src/main/resources/application.properties
```

Then fill in the `CHANGE_ME` values (the API keys must match across services).

### Running

```bash
# 1) Control plane (needs a "bicloud" database in PostgreSQL)
cd bicloud-control-plane && ./mvnw spring-boot:run

# 2) Worker — on every machine with Docker access
cd bicloud-worker && ./mvnw spring-boot:run

# 3) Gateway — on every machine
docker compose up --build -d
# On Ubuntu: docker compose -f docker-compose.ubuntu.yml up --build -d

# 4) Front-end
cd bicloud-front-end && npm install && npm run dev
```

## Tests

```bash
cd bicloud-control-plane && ./mvnw test
```

Scheduling (worker scoring), reconciliation, self-healing and heartbeat logic
are covered by unit tests.

---

Built as a graduation project (Computer Engineering).
