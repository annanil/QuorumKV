# CS6650 Assignment 4 — Distributed Key-Value Store

**Team:** David Conrad, Emily Huang, Hao Wang, Ni Li

## Requirement Map

| Requirement | Location |
|---|---|
| **d.** Java code — Leader-Follower DB | `src/.../controller/LeaderController.java` — PUT/GET endpoints with NWR quorum<br>`src/.../service/KvService.java` — `leaderWrite()`, `leaderRead()` |
| **e.** Java code — Leaderless DB | `src/.../controller/LeaderlessController.java` — PUT/GET endpoints<br>`src/.../service/KvService.java` — `leaderlessWrite()`, `leaderlessRead()` |
| **f.** Java code — Load tester | `load-tester/src/.../loadtester/` — `LoadTester.java`, `KvClient.java`, `StatsController.java` |
| **g.** Correct delays (200ms write, 50ms read) | `src/.../service/KvService.java` — `sleepMillis()` called in every read/write path |
| **h.** Leaderless read/write distribution | `src/.../service/KvService.java` — `getPeerUrlList()` distributes writes to all peers; ALB distributes incoming requests across nodes |
| **i.** Dockerfiles | `Dockerfile` — server image<br>`docker-compose.yml` — leader-follower cluster (5 nodes)<br>`docker-compose.leaderless.yml` — leaderless cluster (5 nodes) |
| **j.** Spring Boot configuration | `src/main/resources/application.properties`<br>`pom.xml` |
| **k.** Terraform configuration | `terraform/` — EC2 instances, ALB, VPC, security groups, auto-deploy scripts |
| **l.** Unit tests (3 test cases) | `src/test/.../consistency/W5R1LeaderFollowerTest.java` — Test 1 (leader read after W=5 write), Test 2 (all followers consistent after W=5 write)<br>`src/test/.../consistency/W1R1LeaderFollowerTest.java` — Test 3 (followers stale after W=1 write)<br>`src/test/.../service/LeaderlessInconsistencyTest.java` — leaderless inconsistency window |
| **m.** Other config | `nginx-leaderless.conf` — load balancer config for leaderless cluster<br>`deploy.sh` — AWS deployment script<br>`terraform/terraform.tfvars.example` — infrastructure variable reference |

---

## Project Structure

```
assignment4/
├── src/                              # Server (Spring Boot)
│   ├── main/java/.../kv/
│   │   ├── controller/
│   │   │   ├── LeaderController.java       # Leader-follower PUT/GET (req d)
│   │   │   ├── LeaderlessController.java   # Leaderless PUT/GET (req e)
│   │   │   └── KvController.java           # /kv/local_read, /kv/internal/write
│   │   ├── service/
│   │   │   └── KvService.java              # All quorum logic + artificial delays (req d,e,g,h)
│   │   ├── config/
│   │   │   ├── NodeProperties.java         # NODE_ROLE, quorum sizes, peer/follower URLs
│   │   │   └── AppConfig.java              # RestTemplate bean
│   │   └── model/ dto/                     # VersionedValue, request/response DTOs
│   └── main/resources/
│       └── application.properties          # Spring Boot config (req j)
│
├── src/test/java/.../kv/
│   ├── consistency/
│   │   ├── W5R1LeaderFollowerTest.java     # Test 1 & 2: strong consistency (req l)
│   │   └── W1R1LeaderFollowerTest.java     # Test 3: stale followers with W=1 (req l)
│   └── service/
│       └── LeaderlessInconsistencyTest.java # Leaderless inconsistency window (req l)
│
├── load-tester/                      # Load tester (req f)
│   └── src/.../loadtester/
│       ├── LoadTester.java                 # Entry point, thread pool, --write-pct logic
│       ├── KvClient.java                   # HTTP PUT/GET to server
│       └── StatsController.java            # Latency stats, stale read tracking, CSV output
│
├── terraform/                        # AWS infrastructure (req k)
│   ├── ec2.tf                              # 5 EC2 instances (1 leader + 4 followers or 5 peers)
│   ├── alb.tf                              # Application Load Balancer
│   ├── network.tf / security.tf            # VPC, subnets, security groups
│   └── templates/app_node.sh.tftpl        # EC2 user-data: pulls Docker image, sets env vars
│
├── Dockerfile                        # Server container image (req i)
├── docker-compose.yml                # Leader-follower 5-node local cluster (req i)
├── docker-compose.leaderless.yml     # Leaderless 5-node local cluster (req i)
├── nginx-leaderless.conf             # Nginx load balancer for leaderless (req m)
└── deploy.sh                         # One-command AWS deploy script (req m)
```

---

## Running the Tests

```bash
# Test 1 & 2 — start cluster with W=5, R=1
WRITE_QUORUM=5 READ_QUORUM=1 docker compose up --build -d
./mvnw test -Dtest=W5R1LeaderFollowerTest

# Test 3 — restart cluster with W=1, R=1
WRITE_QUORUM=1 READ_QUORUM=1 docker compose up --build -d
./mvnw test -Dtest=W1R1LeaderFollowerTest

# Leaderless inconsistency test (no cluster needed — uses mocks)
./mvnw test -Dtest=LeaderlessInconsistencyTest
```
