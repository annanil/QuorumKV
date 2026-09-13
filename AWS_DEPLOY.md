# AWS Deployment Guide — QuorumKV

**Rule: `terraform destroy` within 5 minutes of finishing every session.**
Each EC2 instance costs money from the moment it starts (~$0.06/hr total). A full 12-config sweep
takes ~2.5 hours → **< $0.15 total** if you tear down immediately after.

---

## Prerequisites — Do These Once

### 1. Tools

```bash
terraform --version      # need >= 1.5
docker --version         # need >= 24
docker buildx version    # need buildx plugin
aws --version            # need >= 2

# Install Terraform if missing (macOS)
brew tap hashicorp/tap && brew install hashicorp/tap/terraform
```

### 2. AWS credentials

Student credentials expire every ~4 hours. **Always re-export before deploying.**

```bash
# Log into AWS Academy → Start Lab → copy credentials to ~/.aws/credentials, then verify:
aws sts get-caller-identity
```

### 3. Docker Hub login

```bash
docker login   # account: lin1annaaa
```

### 4. Terraform init

```bash
cd terraform && terraform init && cd ..   # only needed once, or after adding providers
```

---

## Step 0 — Validate Locally First (Free)

Never push and deploy code you haven't run locally.

```bash
# Build JARs
cd shard-controller && mvn install -DskipTests -q && cd ..
./mvnw package -DskipTests -q

# Start 5-node cluster
WRITE_QUORUM=3 READ_QUORUM=3 docker compose up --build -d

# Wait for all nodes
for port in 8080 8081 8082 8083 8084; do
  until curl -s -o /dev/null -w "%{http_code}" "http://localhost:$port/kv?key=warmup" | grep -qE "^(200|404)$"; do
    echo "waiting for :$port..."; sleep 2; done; echo ":$port ready"
done

# Smoke test
cd load-tester && mvn package -DskipTests -q
java -jar target/load-tester-1.0-SNAPSHOT.jar \
  --write-url=http://localhost:8080 \
  --config=local-smoke --requests=200 --threads=4 --write-pct=50
python3 check_results.py output/latencies_local-smoke.csv   # must exit 0

docker compose down && cd ..
```

If the smoke test fails, **do not proceed to AWS.**

---

## Step 1 — Build and Push the Docker Image

Only needed when code has changed since the last push.

```bash
./mvnw package -DskipTests -q

docker buildx build --platform linux/amd64 \
  -t lin1annaaa/quorum-kv:latest . --load

docker push lin1annaaa/quorum-kv:latest

# Verify
docker manifest inspect lin1annaaa/quorum-kv:latest | grep architecture
# Should show "amd64"
```

---

## Step 2 — Run the Full Load Test Sweep

### Leader-follower (6 configs)

**Initial deploy:**
```bash
./deploy.sh 3 3
LEADER=$(cd terraform && terraform output -raw leader_public_ip)
```

**For each config** — verify, sweep, then redeploy with new W/R:

```bash
# Verify cluster
curl -s http://$LEADER:8080/node/info | python3 -m json.tool

# Run 4-ratio sweep (~8-10 min)
cd load-tester
WRITE_URL=http://$LEADER:8080 MODE=leader ./run_sweep.sh <config-label>
cd ..
```

Run in this order:

| Config label | W | R | Terraform vars |
|---|---|---|---|
| `lf-w3r3` | 3 | 3 | already deployed |
| `lf-w5r1` | 5 | 1 | `write_quorum_size=5 read_quorum_size=1` |
| `lf-w1r5` | 1 | 5 | `write_quorum_size=1 read_quorum_size=5` |

**To switch to a new W/R config** (no full redeploy needed):
```bash
cd terraform
terraform apply -auto-approve \
  -var="write_quorum_size=<W>" \
  -var="read_quorum_size=<R>"
sleep 120
cd ..
LEADER=$(cd terraform && terraform output -raw leader_public_ip)
```

---

### Leaderless (6 configs)

Destroy the leader-follower cluster first, then do a fresh deploy in leaderless mode.

**Initial deploy:**
```bash
cd terraform && terraform destroy -auto-approve && cd ..
./deploy.sh 3 3 --leaderless
ALB=$(cd terraform && terraform output -raw leaderless_alb_dns_name)
```

**For each config** — verify, sweep, then redeploy with new W/R:

```bash
# Verify
curl -s http://$ALB:8080/node/info | python3 -m json.tool

# Run 4-ratio sweep
cd load-tester
WRITE_URL=http://$ALB:8080 READ_URL=http://$ALB:8080 MODE=leaderless ./run_sweep.sh <config-label>
cd ..
```

Run in the same order as leader-follower (`ll-w3r3`, `ll-w5r1`, `ll-w1r5`).

**To switch W/R config:**
```bash
cd terraform
terraform apply -auto-approve \
  -var="write_quorum_size=<W>" \
  -var="read_quorum_size=<R>" \
  -var="leaderless=true"
sleep 120
cd ..
ALB=$(cd terraform && terraform output -raw leaderless_alb_dns_name)
```

---

## Step 3 — Tear Down

**Do this immediately after every session.**

```bash
cd terraform && terraform destroy -auto-approve
```

Verify in the AWS console that all EC2 instances show "terminated".

---

## Debugging Common Problems

### "Connection refused" on leader IP

Instance is still booting. Wait 2 more minutes, then:
```bash
ssh -i your-key.pem ec2-user@$LEADER
sudo journalctl -u cloud-final -n 50   # user-data execution log
docker ps                               # is the container running?
docker logs quorum-kv                  # Spring Boot startup errors?
```

### Leader starts but writes return 503

W-quorum can't be satisfied — a follower isn't reachable:
```bash
# From the leader EC2
curl -s http://<FOLLOWER_PRIVATE_IP>:8080/node/info
```

### Wrong W/R values in /node/info

User-data didn't run with updated variables. Re-replace the instance:
```bash
cd terraform
terraform apply -auto-approve \
  -var="write_quorum_size=<W>" -var="read_quorum_size=<R>" \
  -replace=aws_instance.leader
sleep 120
```

### Docker image not found on EC2

```bash
ssh -i your-key.pem ec2-user@$LEADER
docker pull lin1annaaa/quorum-kv:latest   # see the actual error
```

### High 5xx rate in load test results

Check the `response_code` column in `output/latencies_<label>.csv`:
- `503` — quorum not reachable, a node went down during the test
- `500` — unexpected server error, check `docker logs quorum-kv` on the leader
- `-1` — connection timeout, load tester can't reach the leader

---

## Credentials Expiry

Student AWS credentials expire every ~4 hours. Before each session:

```bash
# 1. AWS Academy → Start Lab → copy credentials to ~/.aws/credentials
aws sts get-caller-identity   # verify

# 2. If Terraform state references old credentials
cd terraform && terraform init -reconfigure
```

If credentials expire mid-deploy, run `terraform destroy` with fresh credentials before retrying
to avoid orphaned (and billable) resources.
