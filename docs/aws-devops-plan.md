# AWS DevOps Deployment Plan — Risk Engine

## Architecture Overview

```
GitHub Repo
    │
    ▼
GitHub Actions (CI/CD pipeline)
    │
    ├─ Build + Test (Maven)
    ├─ Docker build + push → Amazon ECR
    └─ Deploy → AWS ECS (2 tasks) or EC2 (2 instances)
                    │
                    ├─ ALB (Application Load Balancer)
                    ├─ RDS (H2 → PostgreSQL migration)
                    └─ CloudWatch (logs + alerts)
```

---

## 1 — Source Control Strategy (GitHub)

**Branch model:**

| Branch | Purpose |
|---|---|
| `main` | Production-ready, protected |
| `develop` | Integration branch |
| `feature/*` | Individual features |
| `hotfix/*` | Production patches |

**Branch protection rules on `main`:**
- Require PR + 1 reviewer
- Require all CI checks to pass
- No direct pushes

---

## 2 — CI Pipeline (GitHub Actions)

```yaml
name: CI

on:
  push:
    branches: [develop, main]
  pull_request:
    branches: [main]

jobs:
  build-and-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Build and Test
        run: mvn clean verify

      - name: Upload test results
        uses: actions/upload-artifact@v4
        with:
          name: test-results
          path: target/surefire-reports/
```

---

## 3 — Docker (Containerise the app)

```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/risk-engine-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

---

## 4 — CD Pipeline (GitHub Actions → ECR → ECS)

```yaml
name: CD

on:
  push:
    branches: [main]

env:
  AWS_REGION: eu-west-1
  ECR_REPOSITORY: risk-engine
  ECS_CLUSTER: risk-engine-cluster
  ECS_SERVICE: risk-engine-service

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Build JAR
        run: mvn clean package -DskipTests

      - name: Configure AWS credentials
        uses: aws-actions/configure-aws-credentials@v4
        with:
          aws-access-key-id: ${{ secrets.AWS_ACCESS_KEY_ID }}
          aws-secret-access-key: ${{ secrets.AWS_SECRET_ACCESS_KEY }}
          aws-region: ${{ env.AWS_REGION }}

      - name: Login to Amazon ECR
        id: login-ecr
        uses: aws-actions/amazon-ecr-login@v2

      - name: Build, tag, push image to ECR
        id: build-image
        env:
          ECR_REGISTRY: ${{ steps.login-ecr.outputs.registry }}
          IMAGE_TAG: ${{ github.sha }}
        run: |
          docker build -t $ECR_REGISTRY/$ECR_REPOSITORY:$IMAGE_TAG .
          docker push $ECR_REGISTRY/$ECR_REPOSITORY:$IMAGE_TAG
          echo "image=$ECR_REGISTRY/$ECR_REPOSITORY:$IMAGE_TAG" >> $GITHUB_OUTPUT

      - name: Deploy to ECS (rolling update, 2 tasks)
        uses: aws-actions/amazon-ecs-deploy-task-definition@v1
        with:
          task-definition: ecs-task-def.json
          service: ${{ env.ECS_SERVICE }}
          cluster: ${{ env.ECS_CLUSTER }}
          wait-for-service-stability: true
```

---

## 5 — AWS Infrastructure (2 instances)

**Recommended: ECS Fargate over raw EC2**
- No server management
- Native rolling deployments
- Scale tasks independently

```
ALB (port 443 HTTPS)
 ├── Target Group
 │    ├── ECS Task 1  (risk-engine container)
 │    └── ECS Task 2  (risk-engine container)
 │
 ├── RDS PostgreSQL (replaces H2)
 ├── Amazon ECR (Docker image registry)
 ├── Secrets Manager (DB credentials, API keys)
 └── CloudWatch (logs, metrics, alarms)
```

**ECS Service config:**
- `desiredCount: 2`
- `deploymentConfiguration: rollingUpdate` (min 50%, max 200%) — ensures zero downtime
- `healthCheckGracePeriodSeconds: 30`

---

## 6 — Secrets Management

**Never store secrets in GitHub code.** Use:

| Secret | Where |
|---|---|
| DB password | AWS Secrets Manager |
| Symphony API key | AWS Secrets Manager |
| SMTP credentials | AWS Secrets Manager |
| AWS keys for GH Actions | GitHub Secrets (`Settings → Secrets`) |

Inject into ECS task via `secrets` block in task definition — available as environment variables at runtime.

---

## 7 — H2 → RDS PostgreSQL Migration

H2 in-memory is not suitable for 2 instances (no shared state). Replace with **RDS PostgreSQL**:

- Multi-AZ enabled (high availability)
- Both ECS tasks point to the same RDS endpoint
- Add HikariCP connection pool (already flagged in [threading-implementation-plan.md](threading-implementation-plan.md))

---

## 8 — Monitoring & Alerting

| Tool | Purpose |
|---|---|
| CloudWatch Logs | Container stdout/stderr |
| CloudWatch Metrics | CPU, memory, JVM heap |
| CloudWatch Alarms | Breach count spike, error rate |
| AWS X-Ray (optional) | Distributed tracing per `recalculate()` call |

---

## 9 — GitHub Environments

Configure two GitHub Environments:

- **`staging`** — auto-deploy on merge to `develop`, points to staging ECS cluster
- **`production`** — auto-deploy on merge to `main`, requires manual approval gate

---

## Summary Checklist

- [ ] Add branch protection to `main`
- [ ] Add `Dockerfile` to repo
- [ ] Create ECR repository in AWS
- [ ] Create ECS cluster + service (2 tasks, Fargate)
- [ ] Set up ALB + target group
- [ ] Migrate H2 → RDS PostgreSQL
- [ ] Store all secrets in AWS Secrets Manager
- [ ] Add GitHub Secrets (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`)
- [ ] Add `.github/workflows/ci.yml` and `cd.yml`
- [ ] Configure CloudWatch alarms
