# CS6650 Assignment 4 — Load Tester

Multi-threaded load tester for the distributed KV store.
Measures latency, throughput, stale reads, and read-write intervals
across different W/R quorum configurations.

---

## Project Structure

```
load-tester/
├── pom.xml
├── plot_results.py                 graph generation
├── README.md
├── output/                        generated CSV and PNG files (gitignored)
└── src/main/java/edu/neu/cs6650/loadtester/
    ├── LoadTester.java             main entry point, thread orchestrator
    ├── LoadTestConfig.java         CLI argument parser
    ├── KvClient.java               HTTP client (PUT + GET with retry)
    ├── StatsController.java        thread-safe metrics + stale detection
    └── LatencyRecord.java          single CSV row
```

---

## Build

```bash
cd load-tester
mvn clean package -DskipTests
```

Produces `target/load-tester-1.0-SNAPSHOT.jar` (fat JAR — no classpath setup needed).

---

## Run

```bash
java -jar target/load-tester-1.0-SNAPSHOT.jar \
  --write-url=<URL> \
  [--read-url=<URL>] \
  [--config=<label>] \
  [--requests=<N>] \
  [--threads=<N>] \
  [--write-pct=<0-100>] \
  [--num-keys=<N>]
```

| Flag | Description | Default |
|------|-------------|---------|
| `--write-url` | URL for writes — leader in LF mode, ALB in leaderless | required |
| `--read-url` | URL for reads — omit to use same as `--write-url` | same as write-url |
| `--config` | Label for output files, e.g. `lf-w5r1-write50` | `default` |
| `--requests` | Total number of requests | `10000` |
| `--threads` | Worker threads | `32` |
| `--write-pct` | Percentage of requests that are writes (0–100) | `50` |
| `--num-keys` | Key pool size — smaller = more temporal locality | `50` |

Fixed parameters for all assignment runs: `--requests=10000 --threads=16 --num-keys=10`

---

## Local Run — Leader-Follower

### Prerequisites
- Docker Desktop running
- Java 17+
- Load tester built (`mvn clean package -DskipTests`)

### 1. Start the cluster

```bash
# from repo root — terminal 1
WRITE_QUORUM=5 READ_QUORUM=1 docker compose up --build
```

Leader on `:8080`, followers on `:8081–8084`.

### 2. Verify the leader is up (wait ~10 sec)

```bash
curl http://localhost:8080/node/info
# {"role":"leader","writeQuorumSize":5,"readQuorumSize":1}
```

> **Screenshot here:** terminal showing `node/info` — confirms cluster is up with correct quorum.

### 3. Run the load tester

```bash
# terminal 2 — from load-tester/
java -jar target/load-tester-1.0-SNAPSHOT.jar \
  --write-url=http://localhost:8080/leader \
  --config=lf-w5r1-write50 \
  --requests=10000 --threads=16 --write-pct=50 --num-keys=10
```

> **Screenshot here:** terminal output at end of run — throughput, P99 latency, stale read count.

### 4. Generate graphs

```bash
cd load-tester
source bin/activate
python3 plot_results.py
```

> **Save:** the three PNGs from `output/` — go in the report.

### 5. Tear down

```bash
docker compose down
```

### Local LF experiment matrix

Change `WRITE_QUORUM`/`READ_QUORUM` and restart docker compose between each W/R config.
Fixed parameters: `--requests=10000 --threads=16 --num-keys=10`

| Config name | WRITE_QUORUM | READ_QUORUM | --write-pct |
|---|---|---|---|
| `lf-w5r1-write1`  | 5 | 1 | 1  |
| `lf-w5r1-write10` | 5 | 1 | 10 |
| `lf-w5r1-write50` | 5 | 1 | 50 |
| `lf-w5r1-write90` | 5 | 1 | 90 |
| `lf-w1r5-write1`  | 1 | 5 | 1  |
| `lf-w1r5-write10` | 1 | 5 | 10 |
| `lf-w1r5-write50` | 1 | 5 | 50 |
| `lf-w1r5-write90` | 1 | 5 | 90 |
| `lf-w3r3-write1`  | 3 | 3 | 1  |
| `lf-w3r3-write10` | 3 | 3 | 10 |
| `lf-w3r3-write50` | 3 | 3 | 50 |
| `lf-w3r3-write90` | 3 | 3 | 90 |

---

## Local Run — Leaderless

### 1. Start the cluster

```bash
# from repo root — terminal 1
WRITE_QUORUM=5 READ_QUORUM=1 docker compose -f docker-compose.leaderless.yml up --build
```

nginx load balancer on `:8080` → round-robins across 5 peer nodes internally.

### 2. Verify a node is up (wait ~15 sec)

```bash
curl http://localhost:8080/node/info
```

> **Screenshot here:** terminal showing `node/info` response — confirms cluster is up.

### 3. Run the load tester

```bash
# terminal 2 — from load-tester/
java -jar target/load-tester-1.0-SNAPSHOT.jar \
  --write-url=http://localhost:8080/leaderless \
  --config=ll-w5r1-write50 \
  --requests=10000 --threads=16 --write-pct=50 --num-keys=10
```

Reads and writes both go through nginx (same URL) — nginx distributes them across all 5 nodes.

> **Screenshot here:** terminal output at end of run.

### 4. Tear down

```bash
docker compose -f docker-compose.leaderless.yml down
```

### Local leaderless experiment matrix

Only W=N=5, R=1 (one quorum config, four write ratios).

| Config name | WRITE_QUORUM | READ_QUORUM | --write-pct |
|---|---|---|---|
| `ll-w5r1-write1`  | 5 | 1 | 1  |
| `ll-w5r1-write10` | 5 | 1 | 10 |
| `ll-w5r1-write50` | 5 | 1 | 50 |
| `ll-w5r1-write90` | 5 | 1 | 90 |

---

## AWS Run — Leader-Follower

### Prerequisites
- Northeastern AWS Learner Lab credentials
- AWS CLI + Terraform installed
- Docker Hub account

### 1. Set AWS credentials

Log into Northeastern Learner Lab, copy temporary credentials:

```bash
export AWS_ACCESS_KEY_ID=...
export AWS_SECRET_ACCESS_KEY=...
export AWS_SESSION_TOKEN=...
```

> Credentials expire after a few hours — re-export if terraform gives auth errors.

### 2. Log into Docker Hub

```bash
docker login
```

### 3. Configure terraform

Edit `terraform/terraform.tfvars`:
```hcl
region            = "us-east-1"
app_image         = "<your-dockerhub-username>/quorum-kv:latest"
write_quorum_size = 5
read_quorum_size  = 1
subnet_ids        = ["subnet-0937c8ad4880238d4","subnet-0111689a3365d0f52",
                     "subnet-0a8fb8c8ed8f8d7c6","subnet-04380d2a5f3270db2",
                     "subnet-07d14fe84f35670cd"]
```

### 4. Deploy

```bash
chmod +x deploy.sh
./deploy.sh <WRITE_QUORUM> <READ_QUORUM>
# e.g. ./deploy.sh 5 1
```

Builds image, pushes to Docker Hub, spins up EC2 (1 leader + 4 followers, t3.micro).

```bash
cd terraform && terraform output leader_public_ip
```

> **Screenshot here:** AWS EC2 console showing all 5 instances running.

### 5. Verify the leader

```bash
curl http://<leader-ip>:8080/node/info
```

> **Screenshot here:** terminal showing correct `writeQuorumSize` / `readQuorumSize`.

### 6. Run all 4 write ratios on this cluster

```bash
LEADER_IP=<leader-ip>
for pct in 1 10 50 90; do
  java -jar target/load-tester-1.0-SNAPSHOT.jar \
    --write-url=http://$LEADER_IP:8080/leader \
    --config=lf-w5r1-write${pct} \
    --write-pct=$pct \
    --requests=10000 --threads=16 --num-keys=10
done
```

> **Screenshot here:** terminal output after all 4 runs complete.

### 7. Generate graphs

```bash
cd load-tester && source bin/activate
python3 plot_results.py lf-w5r1-write1 lf-w5r1-write10 lf-w5r1-write50 lf-w5r1-write90
```

### 8. Tear down

```bash
cd terraform && terraform destroy -auto-approve
```

**Do not leave instances running** — they drain Learner Lab credits.

### AWS LF experiment matrix

**3 deploys total** (one per W/R config). All 4 write-pct runs share the same live cluster.

```
for each W/R config (3 times):
    1. edit terraform.tfvars  ← change write_quorum_size / read_quorum_size
    2. ./deploy.sh W R        ← bring up 5 EC2 instances
    3. run write-pct=1,10,50,90  ← 4 load tester runs, no AWS change between them
    4. terraform destroy      ← tear down before next config
```

| terraform.tfvars | Config names |
|---|---|
| `write=5, read=1` | `lf-w5r1-write1/10/50/90` |
| `write=1, read=5` | `lf-w1r5-write1/10/50/90` |
| `write=3, read=3` | `lf-w3r3-write1/10/50/90` |

---

## AWS Run — Leaderless

### 1. Configure terraform for leaderless

The Terraform ALB (`terraform/alb.tf`) is already provisioned for leaderless mode.
`terraform.tfvars` should have:

```hcl
write_quorum_size = 5
read_quorum_size  = 1
```

### 2. Deploy

```bash
./deploy.sh 5 1 --leaderless
```

The `--leaderless` flag sets `-var="leaderless=true"`, which:
- Assigns a **static private IP** (`.100` host in each subnet) to every node so peer URLs are stable across instance replacements
- Computes `NODE_PEER_URLS` for all 5 nodes at plan time — no two-phase handoff needed
- Sets `NODE_ROLE=leaderless` on every node
- Sets `NODE_SELF_URL` from EC2 instance metadata at boot so each node filters itself from the peer list

This takes ~2 minutes total (single terraform apply + boot wait).

Get the ALB DNS name:

```bash
cd terraform && terraform output -raw leaderless_alb_dns_name
```

### 3. Verify any node is reachable (wait ~15 sec after deploy)

```bash
curl http://<alb-dns>:8080/node/info
# expect: {"role":"leaderless","writeQuorumSize":5,"readQuorumSize":1}
```

> The ALB listener is on port **8080** (same as the nodes). There is no port-80 listener.

> **Screenshot here:** AWS EC2 console + ALB target group showing all 5 targets healthy.

### 4. Run all 4 write ratios

`sleep 10` between runs lets any in-flight async replication from the previous run finish before the next one starts.

```bash
ALB=<alb-dns-name>
cd load-tester
for pct in 1 10 50 90; do
  java -jar target/load-tester-1.0-SNAPSHOT.jar \
    --write-url=http://$ALB:8080/leaderless \
    --config=ll-w5r1-write${pct} \
    --write-pct=$pct \
    --requests=10000 --threads=16 --num-keys=10
  sleep 10
done
```

> **Screenshot here:** terminal output after all 4 runs.

### 5. Tear down

```bash
cd terraform && terraform destroy -auto-approve
```

---

## Output Files

Each run produces two CSVs in `output/`:

### `latencies_<config>.csv`

```
start_time,request_type,latency,response_code,key,version,stale
1775116255332,PUT,298,201,key-21,1,false
1775116255160,GET,134,404,key-42,-1,false
```

| Column | Description |
|---|---|
| `start_time` | Epoch ms when request was sent |
| `request_type` | `PUT` or `GET` |
| `latency` | Round-trip ms (`0` if connection failed) |
| `response_code` | HTTP status (`-1` if no response) |
| `key` | Which key was accessed |
| `version` | Version from server response (`-1` if unavailable) |
| `stale` | `true` if GET returned a version older than our last recorded write |

### `intervals_<config>.csv`

```
read_write_interval_ms
143
87
```

Time elapsed between a write and the next read on the same key.
Short intervals = temporal locality is working as intended.

---

## Generate Graphs

```bash
python3 -m venv .   # first time only — run from inside load-tester/
source bin/activate
pip3 install matplotlib        # first time only
python3 plot_results.py        # process all configs in output/
python3 plot_results.py lf-w5r1-write50   # specific config only
```

Produces per config in `output/`:

| File | What it shows |
|---|---|
| `latency_boxplot_<config>.png` | Read vs write latency — two side-by-side panels |
| `throughput_<config>.png` | Requests/sec over time, stacked by operation type |
| `interval_hist_<config>.png` | Read-write interval distribution (x-axis capped at P95) |

---

## How Stale Reads Are Detected

The client tracks the highest version seen per key across all threads:

```
Thread A: PUT key-0  → server returns version=3
          StatsController records: lastWrittenVersion["key-0"] = 3

Thread B: GET key-0  → server returns version=2
          2 < 3  →  STALE READ  →  stale=true in CSV
```

A **small key pool** (`--num-keys=10`) ensures reads and writes on the same key
happen milliseconds apart, maximising the chance of catching stale reads.

---

## Video Script — What to Explain

- **`LoadTester.java`** — spawns N threads; each loop picks PUT or GET by `--write-pct`, records start/end time, hands result to `StatsController`
- **`KvClient.java`** — thin HTTP wrapper; PUT sends `{key,value}` to `writeUrl/kv`, GET hits `readUrl/kv?key=`; retry on failure
- **`StatsController.java`** — thread-safe; stale detection compares read version vs last written version per key; writes CSVs at end
- **`LatencyRecord.java`** — one row per request

Key point: server bakes in 50ms per read / 200ms per write artificially. All latency numbers reflect this by design — it creates a large enough inconsistency window to observe stale reads at load.
