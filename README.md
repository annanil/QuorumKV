# QuorumKV

A distributed in-memory key-value store built to explore how NWR quorum parameters, replication
topology, and sharding actually trade off in practice — not just on paper.

It implements two replication modes (leader-follower and leaderless/Dynamo-style) with tunable
write/read quorum sizes, a sharding layer with live shard migration between replica groups, a
multithreaded load tester, and an AWS deployment via Terraform. Every latency and consistency
number below comes from runs against a real 5-node EC2 cluster, not a simulation.

## Key finding

Quorum overlap (`W + R > N`) is often stated as sufficient for strong consistency. It isn't, on
its own — and the gap is precisely measurable. Under a 64-thread / 50,000-request load against a
5-node AWS cluster, leader-follower reads went from a mechanically-guaranteed **0.00% stale at
R=1** to **99.9% stale at R=5** at the same write pressure, even though `W + R > N` held in every
case. The cause: a quorum read's snapshot is correct the instant it's taken, but the read itself
takes time (longer as R grows), and a hot key can be rewritten many times during that window —
so "correct when read" and "still correct when checked" stop being the same moment. A second,
independent mechanism shows up in the **leaderless** topology: even at R=1 (immune to the above),
staleness reaches **31.7%**, because any node can coordinate a write and concurrent writes to the
same key from different coordinators can apply out of order with no serialization point to
prevent it. Full experiment writeup, mechanism, and per-configuration analysis in
[`analysis.md`](analysis.md).

## Architecture

```
Client
  │
  ├─ PUT/GET /kv               (KvController)     basic path, replicates to all, no quorum
  ├─ PUT/GET /leader/kv        (LeaderController)  W/R-quorum, leader-coordinated
  └─ PUT/GET /leaderless/kv    (LeaderlessController) W/R-quorum, any node coordinates
       │
       ▼
  KvService (ConcurrentHashMap<String, VersionedValue>)
       │  each write increments a per-key version number, used as the logical clock
       │  a quorum read returns the highest-versioned response across R nodes
       ▼
  Peers / Followers via PUT /kv/internal/write

ShardController (standalone Spring Boot service)
  ├─ GET  /config            current shard → replica-group assignment (clients poll every 5s)
  └─ PUT  /config/rebalance  moves one shard from the most-loaded group to the least-loaded one,
                             pulling data from the donor leader (GET /kv/shard/{id}) and pushing
                             it to the recipient leader (POST /kv/shard/{id}/import), with retry
```

Three independent Maven modules:

| Module | Purpose | Stack |
|---|---|---|
| `src/` | KV server | Java 17, Spring Boot 3, Apache HttpClient 5 |
| `shard-controller/` | Shard-to-group assignment + live migration | Java 17, Spring Boot 3 |
| `load-tester/` | Multithreaded load generator, stale-read detection | Java 17, `java.net.http`, no Spring |

## Quickstart

```bash
# Leader-follower cluster (1 leader + 4 followers)
./mvnw clean package -DskipTests
docker compose up --build

# Leaderless cluster (5 peers, all equal)
docker compose -f docker-compose.leaderless.yml up --build

# Sharded cluster (adds the ShardController + 2 replica groups)
(cd shard-controller && ./mvnw clean package -DskipTests)
docker compose -f docker-compose.sharded.yml up --build
```

Quorum sizes and topology are set via environment variables (see `NodeProperties.java` /
`docker-compose*.yml`): `NODE_ROLE`, `NODE_WRITE_QUORUM_SIZE`, `NODE_READ_QUORUM_SIZE`,
`NODE_FOLLOWER_URLS` (leader-follower), `NODE_PEER_URLS` / `NODE_SELF_URL` (leaderless).

## API

| Method | Path | Notes |
|---|---|---|
| `PUT`/`GET` | `/kv` | No quorum logic — writes go to every follower best-effort |
| `PUT`/`GET` | `/leader/kv` | True W/R-quorum, must be the leader (403 otherwise) |
| `PUT`/`GET` | `/leaderless/kv` | True W/R-quorum, any node can coordinate |
| `PUT` | `/kv/internal/write` | Internal replication target |
| `GET` | `/kv/local_read` | Local-only read, used by the quorum coordinator |
| `GET` | `/kv/shard/{id}` | Dumps local entries for a shard (migration) |
| `POST` | `/kv/shard/{id}/import` | Imports entries for a shard (migration) |
| `GET` | `/config` (ShardController) | Current shard → group assignment |
| `PUT` | `/config/rebalance` (ShardController) | Triggers a migration |

## Load testing & results

`load-tester/` drives the cluster with configurable thread count, write/read ratio, and key-pool
size, and detects stale reads by tracking the highest version each thread has observed per key.
`MigrationTestRunner` runs a three-phase test (baseline / migration-in-progress / post-migration)
to isolate the latency impact of a live shard rebalance.

Selected results (64 threads, 50,000 requests, AWS EC2 `t3.micro`, 5 nodes — full tables and
per-config reasoning in [`analysis.md`](analysis.md)):

| Config | Topology | Write mean | Read mean | Stale reads (1%→90% write) |
|---|---|---|---|---|
| W=5, R=1 | Leader-follower | ~1086–1117 ms | ~130–154 ms | 0.00% (every ratio) |
| W=3, R=3 | Leader-follower | ~689–695 ms | ~339–349 ms | 0.00%–0.06% |
| W=1, R=5 | Leader-follower | ~278–282 ms | ~528–550 ms | 4.73%–**99.90%** |
| W=5, R=1 | Leaderless | ~1088–1097 ms | ~130–151 ms | 1.46%–**31.69%** |

## Deployment

`terraform/` provisions a 5-node cluster on AWS (EC2 + Application Load Balancer + VPC/security
groups); `deploy.sh` wraps a one-command deploy. `.github/workflows/load-tester-ci.yml` builds all
three modules, brings up a Docker Compose cluster, and runs a smoke test against it on every push.

## Testing

- `W5R1LeaderFollowerTest` / `W1R1LeaderFollowerTest` — strong vs. weak consistency under
  leader-follower replication
- `LeaderlessInconsistencyTest` — the leaderless divergence window, isolated with mocks
- `ConsistencyStressTest` — concurrent load against a live cluster

## Notes

Originally started as a distributed-systems coursework assignment (NWR quorum replication,
leader-follower + leaderless); the sharding layer, live migration, load tester, CI pipeline, and
AWS deployment were designed and built independently afterward.
