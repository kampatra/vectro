# Design Document: EKS Tekton ArgoCD Deployment

## Overview

This document designs a complete GitOps CI/CD pipeline for the `vectro` Spring Boot microservice
(Java 21, Maven). The pipeline replaces the existing AWS CodeBuild (`buildspec.yaml`) with
Tekton running on EKS, and formalizes GitOps-based deployment via ArgoCD syncing the `k8s/`
folder from the GitHub repository `kampatra/vectro`.

The flow is: a `git push` to GitHub triggers a Tekton pipeline (via webhook) that builds the
Maven JAR, builds and pushes a Docker image to ECR, and updates the image tag in Git — which
ArgoCD detects and automatically rolls out to the `vectro` namespace on EKS.

---

## Architecture

### Full CI/CD Flow

```mermaid
flowchart TD
    DEV([Developer]) -->|git push| GH[GitHub\nkampatra/vectro]
    GH -->|webhook POST| TE[Tekton EventListener\ntekton-pipelines namespace]
    TE -->|creates| PR[PipelineRun]
    PR --> T1[Task: git-clone\nClone repo]
    T1 --> T2[Task: maven-build\nmvn clean package]
    T2 --> T3[Task: docker-build-push\nBuild + Push to ECR]
    T3 --> T4[Task: update-image-tag\nPatch deployment.yaml in Git]
    T4 -->|git push| GH
    GH -->|auto-sync poll| ARGO[ArgoCD\nargocd namespace]
    ARGO -->|kubectl apply| NS[Namespace: vectro]
    NS --> DEPLOY[Deployment: vectro\nECR image]
    NS --> SVC[Service: vectro-svc]
    NS --> ING[Ingress: ALB]
    NS --> CM[ConfigMap: vectro-config]

    subgraph AWS EKS Cluster
        TE
        PR
        ARGO
        NS
        DEPLOY
        SVC
        ING
        CM
    end

    subgraph AWS ECR
        ECR[208073622683.dkr.ecr.us-east-1.amazonaws.com/ibm/vetro]
    end

    T3 -->|docker push| ECR
    DEPLOY -->|pull image| ECR
```

### AWS Infrastructure Components

```mermaid
graph TD
    subgraph VPC [VPC - 10.0.0.0/16]
        subgraph PubSub [Public Subnets - 2 AZs]
            ALB[AWS ALB\nInternet-facing]
        end
        subgraph PrivSub [Private Subnets - 2 AZs]
            NG[EKS Node Group\nt3.medium x2]
        end
    end
    IGW[Internet Gateway] --> ALB
    ALB --> NG
    NG --> ECR2[ECR\nus-east-1]
    NG --> CW[CloudWatch Logs]

    subgraph IAM
        NodeRole[EKS Node Role\nAmazonEKSWorkerNodePolicy\nAmazonEC2ContainerRegistryReadOnly]
        TektonSA[Tekton ServiceAccount\nIRSA - ECR push permissions]
    end
    NG --- NodeRole
    TektonSA --- NG
```

---

## Components and Interfaces

### Component 1: EKS Cluster

**Purpose**: Runs all workloads — Tekton pipelines, ArgoCD, and the vectro application.

**Key Attributes**:
- Cluster name: `vectro-cluster`
- Region: `us-east-1`
- Kubernetes version: `1.29`
- Node group: `t3.medium`, min 2 / max 4, in private subnets
- Managed node group with public endpoint access enabled

**Managed Add-ons**:
- `aws-ebs-csi-driver` — persistent volumes for Tekton workspace
- `vpc-cni`, `coredns`, `kube-proxy` — standard EKS add-ons
- AWS Load Balancer Controller (Helm) — handles ALB Ingress

---

### Component 2: Tekton Pipelines

**Purpose**: Cloud-native CI engine running directly on EKS that replaces CodeBuild.

**Namespaces**:
- `tekton-pipelines` — Tekton system components
- `tekton-builds` — where PipelineRuns execute

**Core Objects**:

| Object | Name | Purpose |
|---|---|---|
| Task | `git-clone` | Clone GitHub repo (uses Tekton catalog task) |
| Task | `maven-build` | Run `mvn clean package -DskipTests` |
| Task | `docker-build-push` | Build Docker image, authenticate to ECR, push |
| Task | `update-image-tag` | Patch `k8s/deployment.yaml` image tag and git push |
| Pipeline | `vectro-ci-pipeline` | Chains all 4 tasks in sequence |
| TriggerTemplate | `vectro-trigger-template` | Creates PipelineRun from webhook event |
| TriggerBinding | `vectro-trigger-binding` | Extracts `gitRevision`, `gitUrl` from webhook payload |
| EventListener | `vectro-event-listener` | HTTP endpoint that receives GitHub webhook |
| ServiceAccount | `tekton-sa` | Bound to IRSA role with ECR push permissions |

---

### Component 3: ArgoCD

**Purpose**: GitOps controller that continuously syncs the `k8s/` folder from GitHub to EKS.

**Namespace**: `argocd`

**Key Configuration**:
- Tracks `main` branch of `https://github.com/kampatra/vectro`
- Monitors path: `k8s/`
- Auto-sync enabled with `prune: true` and `selfHeal: true`
- The `vectro-secrets` Secret is excluded from ArgoCD diff to protect credentials
- ArgoCD UI accessible via `kubectl port-forward` or a dedicated Ingress (optional)

---

### Component 4: ECR Repository

**Purpose**: Stores Docker images produced by Tekton.

**Repository URI**: `208073622683.dkr.ecr.us-east-1.amazonaws.com/ibm/vetro`

**Tagging Strategy**:
- `latest` — always points to the most recent build
- `<git-short-sha>` (e.g., `a3f9d12`) — immutable tag per commit; used in `deployment.yaml`

---

## IAM Roles and Policies

### Tekton ECR Push Policy

Tekton's ServiceAccount needs permission to authenticate to ECR and push images.
This is done via **IRSA (IAM Roles for Service Accounts)**.

**Policy** (`tekton-ecr-push-policy`):

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "ECRAuth",
      "Effect": "Allow",
      "Action": ["ecr:GetAuthorizationToken"],
      "Resource": "*"
    },
    {
      "Sid": "ECRPush",
      "Effect": "Allow",
      "Action": [
        "ecr:BatchCheckLayerAvailability",
        "ecr:CompleteLayerUpload",
        "ecr:InitiateLayerUpload",
        "ecr:PutImage",
        "ecr:UploadLayerPart",
        "ecr:BatchGetImage",
        "ecr:GetDownloadUrlForLayer"
      ],
      "Resource": "arn:aws:ecr:us-east-1:208073622683:repository/ibm/vetro"
    }
  ]
}
```

**Trust Relationship** (IRSA — assumes from EKS OIDC provider):

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": {
      "Federated": "arn:aws:iam::208073622683:oidc-provider/oidc.eks.us-east-1.amazonaws.com/id/<OIDC_ID>"
    },
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": {
      "StringEquals": {
        "oidc.eks.us-east-1.amazonaws.com/id/<OIDC_ID>:sub":
          "system:serviceaccount:tekton-builds:tekton-sa"
      }
    }
  }]
}
```

### EKS Node Role (existing / standard)

The EKS node IAM role must have:
- `AmazonEKSWorkerNodePolicy`
- `AmazonEKS_CNI_Policy`
- `AmazonEC2ContainerRegistryReadOnly` — allows pods to pull images from ECR


---

## All Kubernetes Manifests

All Tekton manifests live under a new `tekton/` directory in the repo root. The ArgoCD
manifest at `argocd/argocd-app.yaml` is updated to point to GitHub.

### Directory Layout

```
vectro/
├── Dockerfile
├── pom.xml
├── k8s/                          # ArgoCD syncs this folder → EKS
│   ├── namespace.yaml
│   ├── configmap.yaml
│   ├── deployment.yaml           # image tag updated by Tekton task
│   ├── service.yaml
│   ├── ingress.yaml
│   └── secret.yaml               # placeholder only (real secret applied manually)
├── tekton/                       # NEW — all Tekton pipeline manifests
│   ├── namespace.yaml
│   ├── serviceaccount.yaml
│   ├── rbac.yaml
│   ├── task-git-clone.yaml
│   ├── task-maven-build.yaml
│   ├── task-docker-build-push.yaml
│   ├── task-update-image-tag.yaml
│   ├── pipeline.yaml
│   ├── trigger-binding.yaml
│   ├── trigger-template.yaml
│   └── event-listener.yaml
└── argocd/
    └── argocd-app.yaml           # UPDATED to GitHub
```

---

### eksctl Cluster Config (`eksctl-cluster.yaml`)

```yaml
apiVersion: eksctl.io/v1alpha5
kind: ClusterConfig

metadata:
  name: vectro-cluster
  region: us-east-1
  version: "1.29"

iam:
  withOIDC: true   # required for IRSA

managedNodeGroups:
  - name: vectro-nodes
    instanceType: t3.medium
    minSize: 2
    maxSize: 4
    desiredCapacity: 2
    privateNetworking: true
    iam:
      attachPolicyARNs:
        - arn:aws:iam::aws:policy/AmazonEKSWorkerNodePolicy
        - arn:aws:iam::aws:policy/AmazonEKS_CNI_Policy
        - arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly

addons:
  - name: vpc-cni
  - name: coredns
  - name: kube-proxy
  - name: aws-ebs-csi-driver
```

---

### `tekton/namespace.yaml`

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: tekton-builds
```

---

### `tekton/serviceaccount.yaml`

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: tekton-sa
  namespace: tekton-builds
  annotations:
    # IRSA: replace <TEKTON_ECR_ROLE_ARN> after creating the IAM role
    eks.amazonaws.com/role-arn: arn:aws:iam::208073622683:role/tekton-ecr-push-role
```

---

### `tekton/rbac.yaml`

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: tekton-role
  namespace: tekton-builds
rules:
  - apiGroups: ["tekton.dev"]
    resources: ["taskruns", "pipelineruns"]
    verbs: ["get", "list", "create", "update", "patch", "watch"]
  - apiGroups: [""]
    resources: ["pods", "pods/log", "secrets", "configmaps", "persistentvolumeclaims"]
    verbs: ["get", "list", "create", "update", "patch", "watch", "delete"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: tekton-rolebinding
  namespace: tekton-builds
subjects:
  - kind: ServiceAccount
    name: tekton-sa
    namespace: tekton-builds
roleRef:
  kind: Role
  name: tekton-role
  apiGroup: rbac.authorization.k8s.io
```


---

### `tekton/task-git-clone.yaml`

```yaml
# Uses the official Tekton Catalog git-clone task (v0.9)
# Install separately: kubectl apply -f https://raw.githubusercontent.com/tektoncd/catalog/main/task/git-clone/0.9/git-clone.yaml -n tekton-builds
# This file keeps a reference for documentation; rely on the catalog installation.
apiVersion: tekton.dev/v1
kind: Task
metadata:
  name: git-clone-ref
  namespace: tekton-builds
  annotations:
    description: "Reference — use Tekton Catalog git-clone v0.9 task directly"
```

---

### `tekton/task-maven-build.yaml`

```yaml
apiVersion: tekton.dev/v1
kind: Task
metadata:
  name: maven-build
  namespace: tekton-builds
spec:
  workspaces:
    - name: source
      description: Cloned source code workspace
  steps:
    - name: mvn-package
      image: public.ecr.aws/amazoncorretto/amazoncorretto:21
      workingDir: $(workspaces.source.path)
      script: |
        #!/usr/bin/env bash
        set -euo pipefail
        echo "=== Installing Maven ==="
        yum install -y maven -q
        echo "=== Java version ==="
        java -version
        echo "=== Maven version ==="
        mvn -version
        echo "=== Building artifact ==="
        mvn clean package -DskipTests --batch-mode
        echo "=== Build complete. JAR files: ==="
        ls -lh target/*.jar
```

---

### `tekton/task-docker-build-push.yaml`

```yaml
apiVersion: tekton.dev/v1
kind: Task
metadata:
  name: docker-build-push
  namespace: tekton-builds
spec:
  params:
    - name: IMAGE
      description: Full ECR image URI with tag
      type: string
    - name: GIT_REVISION
      description: Git commit SHA (short)
      type: string
  workspaces:
    - name: source
      description: Workspace containing Dockerfile and built JAR
  steps:
    - name: ecr-login
      image: public.ecr.aws/amazonlinux/amazonlinux:2023
      script: |
        #!/usr/bin/env bash
        set -euo pipefail
        yum install -y awscli docker -q
        aws ecr get-login-password --region us-east-1 \
          | docker login --username AWS --password-stdin \
            208073622683.dkr.ecr.us-east-1.amazonaws.com
      env:
        - name: AWS_REGION
          value: us-east-1

    - name: build-and-push
      image: docker:24-dind
      workingDir: $(workspaces.source.path)
      securityContext:
        privileged: true
      script: |
        #!/usr/bin/env sh
        set -eu
        ECR_URI="208073622683.dkr.ecr.us-east-1.amazonaws.com/ibm/vetro"
        TAG="$(params.GIT_REVISION)"

        echo "=== Building Docker image ==="
        docker build -t "${ECR_URI}:${TAG}" -t "${ECR_URI}:latest" .

        echo "=== Pushing to ECR ==="
        docker push "${ECR_URI}:${TAG}"
        docker push "${ECR_URI}:latest"

        echo "=== Push complete: ${ECR_URI}:${TAG} ==="
```

---

### `tekton/task-update-image-tag.yaml`

```yaml
apiVersion: tekton.dev/v1
kind: Task
metadata:
  name: update-image-tag
  namespace: tekton-builds
spec:
  params:
    - name: GIT_REVISION
      description: Short git SHA to set as image tag
      type: string
    - name: GITHUB_TOKEN_SECRET
      description: Name of the Secret containing GITHUB_TOKEN
      default: github-token
  workspaces:
    - name: source
  steps:
    - name: patch-and-push
      image: bitnami/git:latest
      workingDir: $(workspaces.source.path)
      env:
        - name: GITHUB_TOKEN
          valueFrom:
            secretKeyRef:
              name: $(params.GITHUB_TOKEN_SECRET)
              key: token
      script: |
        #!/usr/bin/env bash
        set -euo pipefail
        ECR_URI="208073622683.dkr.ecr.us-east-1.amazonaws.com/ibm/vetro"
        NEW_TAG="$(params.GIT_REVISION)"

        # Configure git identity
        git config user.email "tekton-bot@vectro.ci"
        git config user.name "Tekton Bot"

        # Update image tag in deployment.yaml using sed
        sed -i "s|${ECR_URI}:.*|${ECR_URI}:${NEW_TAG}|g" k8s/deployment.yaml

        echo "=== Updated deployment.yaml to image tag: ${NEW_TAG} ==="

        # Commit and push back to GitHub
        git add k8s/deployment.yaml
        git commit -m "chore: update image tag to ${NEW_TAG} [skip ci]"
        git push https://x-access-token:${GITHUB_TOKEN}@github.com/kampatra/vectro.git HEAD:main
```


---

### `tekton/pipeline.yaml`

```yaml
apiVersion: tekton.dev/v1
kind: Pipeline
metadata:
  name: vectro-ci-pipeline
  namespace: tekton-builds
spec:
  params:
    - name: git-url
      type: string
      description: GitHub repository URL
      default: https://github.com/kampatra/vectro
    - name: git-revision
      type: string
      description: Git branch or commit SHA to build
      default: main
    - name: git-short-sha
      type: string
      description: Short commit SHA (7 chars) used as image tag

  workspaces:
    - name: shared-workspace
      description: Shared PVC workspace for all tasks

  tasks:
    - name: fetch-source
      taskRef:
        name: git-clone           # from Tekton Catalog
      workspaces:
        - name: output
          workspace: shared-workspace
      params:
        - name: url
          value: $(params.git-url)
        - name: revision
          value: $(params.git-revision)

    - name: maven-build
      taskRef:
        name: maven-build
      runAfter:
        - fetch-source
      workspaces:
        - name: source
          workspace: shared-workspace

    - name: docker-build-push
      taskRef:
        name: docker-build-push
      runAfter:
        - maven-build
      workspaces:
        - name: source
          workspace: shared-workspace
      params:
        - name: IMAGE
          value: 208073622683.dkr.ecr.us-east-1.amazonaws.com/ibm/vetro:$(params.git-short-sha)
        - name: GIT_REVISION
          value: $(params.git-short-sha)

    - name: update-image-tag
      taskRef:
        name: update-image-tag
      runAfter:
        - docker-build-push
      workspaces:
        - name: source
          workspace: shared-workspace
      params:
        - name: GIT_REVISION
          value: $(params.git-short-sha)
```

---

### `tekton/trigger-binding.yaml`

```yaml
apiVersion: triggers.tekton.dev/v1beta1
kind: TriggerBinding
metadata:
  name: vectro-trigger-binding
  namespace: tekton-builds
spec:
  params:
    - name: git-url
      value: $(body.repository.clone_url)
    - name: git-revision
      value: $(body.after)
    - name: git-short-sha
      # Extract first 7 characters of the full SHA
      value: $(body.after[0:7])
```

---

### `tekton/trigger-template.yaml`

```yaml
apiVersion: triggers.tekton.dev/v1beta1
kind: TriggerTemplate
metadata:
  name: vectro-trigger-template
  namespace: tekton-builds
spec:
  params:
    - name: git-url
    - name: git-revision
    - name: git-short-sha

  resourcetemplates:
    - apiVersion: v1
      kind: PersistentVolumeClaim
      metadata:
        name: vectro-workspace-$(uid)
      spec:
        accessModes:
          - ReadWriteOnce
        resources:
          requests:
            storage: 1Gi

    - apiVersion: tekton.dev/v1
      kind: PipelineRun
      metadata:
        name: vectro-pipeline-run-$(uid)
        namespace: tekton-builds
      spec:
        serviceAccountName: tekton-sa
        pipelineRef:
          name: vectro-ci-pipeline
        params:
          - name: git-url
            value: $(tt.params.git-url)
          - name: git-revision
            value: $(tt.params.git-revision)
          - name: git-short-sha
            value: $(tt.params.git-short-sha)
        workspaces:
          - name: shared-workspace
            persistentVolumeClaim:
              claimName: vectro-workspace-$(uid)
```

---

### `tekton/event-listener.yaml`

```yaml
apiVersion: triggers.tekton.dev/v1beta1
kind: EventListener
metadata:
  name: vectro-event-listener
  namespace: tekton-builds
spec:
  serviceAccountName: tekton-sa
  triggers:
    - name: github-push-trigger
      interceptors:
        - ref:
            name: github
          params:
            - name: secretRef
              value:
                secretName: github-webhook-secret
                secretKey: secret
            - name: eventTypes
              value:
                - push
        - ref:
            name: cel
          params:
            - name: filter
              # Only trigger on pushes to main branch
              value: "body.ref == 'refs/heads/main'"
      bindings:
        - ref: vectro-trigger-binding
      template:
        ref: vectro-trigger-template
---
# Service to expose EventListener externally via LoadBalancer
# (or use an Ingress with ALB — see operational guide)
apiVersion: v1
kind: Service
metadata:
  name: el-vectro-event-listener
  namespace: tekton-builds
spec:
  type: LoadBalancer
  selector:
    eventlistener: vectro-event-listener
  ports:
    - port: 8080
      targetPort: 8080
```


---

### `argocd/argocd-app.yaml` (Updated — GitHub instead of CodeCommit)

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: vectro
  namespace: argocd
  finalizers:
    - resources-finalizer.argocd.argoproj.io
spec:
  project: default

  # ── Source — GitHub repository ──────────────────────────────────────────
  source:
    repoURL: https://github.com/kampatra/vectro   # CHANGED from CodeCommit
    targetRevision: main
    path: k8s

  # ── Destination — EKS cluster ─────────────────────────────────────────────
  destination:
    server: https://kubernetes.default.svc
    namespace: vectro

  # ── Sync Policy ───────────────────────────────────────────────────────────
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
      - ApplyOutOfSyncOnly=true
    retry:
      limit: 3
      backoff:
        duration: 10s
        factor: 2
        maxDuration: 1m

  # ── Ignore the live secret data to protect credentials ────────────────────
  ignoreDifferences:
    - group: ""
      kind: Secret
      name: vectro-secrets
      namespace: vectro
      jsonPointers:
        - /data
```

---

## Required Secrets (Created Manually — Never Committed to Git)

Before running the pipeline, create these Kubernetes secrets by hand:

```bash
# 1. GitHub webhook secret (for EventListener HMAC verification)
kubectl create secret generic github-webhook-secret \
  --from-literal=secret=<YOUR_WEBHOOK_SECRET> \
  -n tekton-builds

# 2. GitHub personal access token (for Tekton to push back to repo)
kubectl create secret generic github-token \
  --from-literal=token=<YOUR_GITHUB_PAT> \
  -n tekton-builds

# 3. Vectro application secrets (Cognito credentials)
kubectl create secret generic vectro-secrets \
  --from-literal=AWS_ACCESS_KEY_ID=<VALUE> \
  --from-literal=AWS_SECRET_ACCESS_KEY=<VALUE> \
  --from-literal=COGNITO_CLIENT_SECRET=<VALUE> \
  -n vectro
```

> **GitHub PAT requirements**: needs `repo` scope (read/write) to push the updated `deployment.yaml` back to `main`.

---

## Step-by-Step Operational Guide

### Step 1: Create EKS Cluster

**Prerequisites**: Install `eksctl`, `kubectl`, `awscli` v2, `helm` on your local machine.

```bash
# Configure AWS credentials
aws configure
# AWS Access Key ID: <your key>
# AWS Secret Access Key: <your secret>
# Default region: us-east-1

# Create the cluster using the config file
eksctl create cluster -f eksctl-cluster.yaml

# This takes ~15-20 minutes. When done, verify:
kubectl get nodes
# Should show 2 nodes in Ready state

# Verify OIDC provider was created (needed for IRSA)
aws eks describe-cluster --name vectro-cluster \
  --query "cluster.identity.oidc.issuer" --output text
```

**Install AWS Load Balancer Controller** (required for ALB Ingress):

```bash
# Add the Helm repo
helm repo add eks https://aws.github.io/eks-charts
helm repo update

# Install the controller
helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
  -n kube-system \
  --set clusterName=vectro-cluster \
  --set serviceAccount.create=true \
  --set serviceAccount.name=aws-load-balancer-controller

# Verify
kubectl get deployment -n kube-system aws-load-balancer-controller
```


---

### Step 2: Install and Configure Tekton

```bash
# Install Tekton Pipelines (v0.59 LTS)
kubectl apply -f https://storage.googleapis.com/tekton-releases/pipeline/latest/release.yaml

# Install Tekton Triggers (for GitHub webhook support)
kubectl apply -f https://storage.googleapis.com/tekton-releases/triggers/latest/release.yaml
kubectl apply -f https://storage.googleapis.com/tekton-releases/triggers/latest/interceptors.yaml

# Wait for all pods to be Running
kubectl get pods -n tekton-pipelines --watch
# Exit watch with Ctrl+C when all are Running

# Install git-clone task from Tekton Catalog
kubectl apply -f https://raw.githubusercontent.com/tektoncd/catalog/main/task/git-clone/0.9/git-clone.yaml -n tekton-builds

# Create the tekton-builds namespace and apply all manifests
kubectl apply -f tekton/namespace.yaml

# Create the IRSA role for Tekton (replace <OIDC_ID> with your cluster's OIDC ID)
OIDC_ID=$(aws eks describe-cluster --name vectro-cluster \
  --query "cluster.identity.oidc.issuer" --output text | cut -d'/' -f5)

aws iam create-role \
  --role-name tekton-ecr-push-role \
  --assume-role-policy-document "{
    \"Version\": \"2012-10-17\",
    \"Statement\": [{
      \"Effect\": \"Allow\",
      \"Principal\": {
        \"Federated\": \"arn:aws:iam::208073622683:oidc-provider/oidc.eks.us-east-1.amazonaws.com/id/${OIDC_ID}\"
      },
      \"Action\": \"sts:AssumeRoleWithWebIdentity\",
      \"Condition\": {
        \"StringEquals\": {
          \"oidc.eks.us-east-1.amazonaws.com/id/${OIDC_ID}:sub\":
            \"system:serviceaccount:tekton-builds:tekton-sa\"
        }
      }
    }]
  }"

# Attach the ECR push policy (create it first from the JSON in IAM section above)
aws iam put-role-policy \
  --role-name tekton-ecr-push-role \
  --policy-name tekton-ecr-push-policy \
  --policy-document file://tekton-ecr-push-policy.json

# Apply all Tekton manifests
kubectl apply -f tekton/serviceaccount.yaml
kubectl apply -f tekton/rbac.yaml
kubectl apply -f tekton/task-maven-build.yaml
kubectl apply -f tekton/task-docker-build-push.yaml
kubectl apply -f tekton/task-update-image-tag.yaml
kubectl apply -f tekton/pipeline.yaml
kubectl apply -f tekton/trigger-binding.yaml
kubectl apply -f tekton/trigger-template.yaml
kubectl apply -f tekton/event-listener.yaml

# Create required secrets (see "Required Secrets" section above)
kubectl create secret generic github-webhook-secret \
  --from-literal=secret=<YOUR_WEBHOOK_SECRET> -n tekton-builds

kubectl create secret generic github-token \
  --from-literal=token=<YOUR_GITHUB_PAT> -n tekton-builds

# Get the EventListener external URL (for GitHub webhook)
kubectl get svc el-vectro-event-listener -n tekton-builds
# Note the EXTERNAL-IP — this is your webhook URL: http://<EXTERNAL-IP>:8080
```


---

### Step 3: Install and Configure ArgoCD

```bash
# Create argocd namespace and install ArgoCD
kubectl create namespace argocd
kubectl apply -n argocd -f \
  https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml

# Wait for ArgoCD pods to be ready
kubectl get pods -n argocd --watch
# Exit with Ctrl+C when all pods show Running/Completed

# Get the initial admin password
ARGOCD_PASS=$(kubectl -n argocd get secret argocd-initial-admin-secret \
  -o jsonpath="{.data.password}" | base64 -d)
echo "ArgoCD admin password: $ARGOCD_PASS"

# Access the ArgoCD UI via port-forward
kubectl port-forward svc/argocd-server -n argocd 8443:443
# Open https://localhost:8443 in your browser
# Login: admin / <password from above>

# Register GitHub repository with ArgoCD
# Option A: Public repo — no credentials needed
# Option B: Private repo — add via UI or CLI:
argocd repo add https://github.com/kampatra/vectro \
  --username kampatra \
  --password <GITHUB_PAT>

# Apply the updated ArgoCD Application manifest
kubectl apply -f argocd/argocd-app.yaml

# Verify the application is synced
kubectl get application vectro -n argocd
# STATUS should show: Synced / Healthy

# If it shows OutOfSync, trigger a manual sync:
argocd app sync vectro
```

---

### Step 4: Configure GitHub Webhook for Tekton

```bash
# Get the EventListener public URL
EL_URL=$(kubectl get svc el-vectro-event-listener -n tekton-builds \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}')
echo "Webhook URL: http://${EL_URL}:8080"
```

In GitHub (`https://github.com/kampatra/vectro`):

1. Go to **Settings → Webhooks → Add webhook**
2. **Payload URL**: `http://<EL_URL>:8080` (from command above)
3. **Content type**: `application/json`
4. **Secret**: The same value used in `github-webhook-secret` secret
5. **Which events**: Select **Just the push event**
6. Click **Add webhook**

GitHub will send a ping event. You should see a green checkmark confirming delivery.

**Verify a webhook trigger**:

```bash
# After making a git push to main, watch for a new PipelineRun:
kubectl get pipelineruns -n tekton-builds --watch

# Follow the logs of a running PipelineRun:
kubectl logs -n tekton-builds -l tekton.dev/pipeline=vectro-ci-pipeline --follow

# Or use the Tekton CLI (tkn):
tkn pipelinerun logs -n tekton-builds --last -f
```

---

### Step 5: Verify End-to-End GitOps Deployment

```bash
# 1. Make a code change and push to main
git commit --allow-empty -m "test: trigger CI pipeline"
git push origin main

# 2. Watch Tekton PipelineRun progress (~5-8 minutes total)
tkn pipelinerun list -n tekton-builds
tkn pipelinerun logs -n tekton-builds --last -f

# 3. After Tekton completes, the image tag in k8s/deployment.yaml
#    is updated automatically and pushed back to GitHub.

# 4. ArgoCD polls GitHub every 3 minutes (default) and detects the change.
#    Force an immediate sync if you don't want to wait:
argocd app sync vectro

# 5. Verify the rollout in the vectro namespace:
kubectl rollout status deployment/vectro -n vectro

# 6. Get the ALB DNS name to test the application:
kubectl get ingress vectro-ingress -n vectro
# Access: http://<ALB_DNS>/actuator/health  → should return {"status":"UP"}
```

---

## Data Models

### PipelineRun Lifecycle

```mermaid
stateDiagram-v2
    [*] --> Pending: GitHub push triggers EventListener
    Pending --> Running: TriggerTemplate creates PipelineRun
    Running --> TaskRunning_Clone: Task 1 - git-clone
    TaskRunning_Clone --> TaskRunning_Build: Task 2 - maven-build
    TaskRunning_Build --> TaskRunning_Docker: Task 3 - docker-build-push
    TaskRunning_Docker --> TaskRunning_UpdateTag: Task 4 - update-image-tag
    TaskRunning_UpdateTag --> Succeeded: All tasks complete
    Running --> Failed: Any task fails
    Succeeded --> [*]: ArgoCD detects new tag → deploys
    Failed --> [*]: Check logs with tkn pipelinerun logs
```

### Image Tagging Flow

```mermaid
sequenceDiagram
    participant GH as GitHub
    participant EL as EventListener
    participant TK as Tekton Pipeline
    participant ECR as ECR Registry
    participant ARGO as ArgoCD
    participant EKS as EKS vectro ns

    GH->>EL: POST /push (SHA: a3f9d12...)
    EL->>TK: Create PipelineRun (git-short-sha=a3f9d12)
    TK->>TK: Clone repo, mvn build
    TK->>ECR: Push ibm/vetro:a3f9d12 + ibm/vetro:latest
    TK->>GH: Update k8s/deployment.yaml image=ibm/vetro:a3f9d12
    ARGO->>GH: Poll — detects deployment.yaml changed
    ARGO->>EKS: kubectl apply k8s/ (new image tag)
    EKS->>ECR: Pull ibm/vetro:a3f9d12
    EKS-->>ARGO: Deployment healthy
```


---

## Error Handling

### Scenario 1: Maven Build Failure

**Condition**: `mvn clean package` exits non-zero (compilation error, test failure)
**Response**: The `maven-build` TaskRun moves to `Failed` state; the pipeline stops — no Docker image is built or pushed
**Detection**: `kubectl get taskrun -n tekton-builds` shows Failed; `tkn pipelinerun logs --last -f` shows Maven error
**Recovery**: Fix the code, push to `main` again — a new PipelineRun starts automatically

### Scenario 2: ECR Push Failure (Permission Denied)

**Condition**: IRSA role not attached or ECR policy misconfigured
**Response**: `docker-build-push` task fails with `no basic auth credentials` or `AccessDenied`
**Detection**: Task logs show ECR authentication error
**Recovery**:
1. Verify the ServiceAccount annotation: `kubectl get sa tekton-sa -n tekton-builds -o yaml`
2. Confirm the IRSA role ARN matches: `arn:aws:iam::208073622683:role/tekton-ecr-push-role`
3. Check IAM role trust policy includes correct OIDC provider ID
4. Re-run: `argocd app sync vectro` or trigger a new push

### Scenario 3: ArgoCD Out of Sync

**Condition**: Drift between Git state and cluster state (e.g., manual `kubectl apply`)
**Response**: `selfHeal: true` causes ArgoCD to automatically revert the change within ~3 minutes
**Detection**: ArgoCD UI shows "OutOfSync" badge; `kubectl get application vectro -n argocd`
**Recovery**: ArgoCD handles it automatically; for immediate fix: `argocd app sync vectro`

### Scenario 4: GitHub Webhook Not Firing

**Condition**: EventListener pod not reachable or webhook secret mismatch
**Detection**: In GitHub → Settings → Webhooks, the delivery shows a red ✗ with HTTP error
**Recovery**:
1. Check EventListener pod: `kubectl get pods -n tekton-builds`
2. Check the external IP is still valid: `kubectl get svc el-vectro-event-listener -n tekton-builds`
3. Verify the webhook secret in GitHub matches `github-webhook-secret` in cluster

---

## Security Considerations

- **IRSA over static credentials**: Tekton uses IRSA (IAM Roles for Service Accounts) so no long-lived AWS access keys are stored in the cluster. The existing `buildspec.yaml` pattern of relying on CodeBuild's instance role is replicated with IRSA.
- **GitHub PAT scope**: The PAT used by `update-image-tag` task only needs `repo` scope. Rotate it regularly via GitHub → Settings → Developer Settings → Personal access tokens.
- **Webhook HMAC validation**: The Tekton GitHub interceptor validates the `X-Hub-Signature-256` header using the shared secret, preventing unauthorized pipeline triggers.
- **vectro-secrets protection**: The `ignoreDifferences` block in ArgoCD's Application manifest prevents ArgoCD from overwriting the real secret with Git placeholder values.
- **Private subnets for nodes**: EKS worker nodes are in private subnets; only the ALB is internet-facing. Tekton's EventListener LoadBalancer is internet-facing — restrict it with a Security Group to known GitHub webhook IP ranges if needed.
- **ECR image scanning**: Enable ECR image scanning on push to detect vulnerabilities: `aws ecr put-image-scanning-configuration --repository-name ibm/vetro --image-scanning-configuration scanOnPush=true --region us-east-1`
- **Resource limits on TaskRuns**: Each Tekton Task has resource `requests`/`limits` to prevent runaway builds from starving other workloads.

---

## Performance Considerations

- **Build caching**: The `maven-build` task re-downloads Maven dependencies on every run because the workspace PVC is ephemeral (created per PipelineRun). To speed up builds, use a persistent PVC for the Maven `.m2` cache and mount it as a second workspace in the maven-build task.
- **t3.medium sizing**: `t3.medium` (2 vCPU, 4GB RAM) comfortably runs Tekton tasks alongside ArgoCD. The vectro Spring Boot pod requires ~256MB RAM, leaving headroom.
- **Parallel tasks**: The current pipeline is fully sequential. If additional independent tasks are added (e.g., unit tests alongside docker build), they can be parallelized using Tekton's `runAfter` DAG syntax.
- **ECR lifecycle policy**: Configure an ECR lifecycle policy to retain only the last 20 images and prevent unbounded storage growth.

---

## Dependencies

| Dependency | Version | Purpose |
|---|---|---|
| EKS | 1.29 | Kubernetes cluster |
| Tekton Pipelines | latest stable | CI task runner |
| Tekton Triggers | latest stable | GitHub webhook → PipelineRun |
| ArgoCD | latest stable | GitOps deployment controller |
| AWS Load Balancer Controller | latest | ALB Ingress support |
| AWS EBS CSI Driver | EKS add-on | PVC storage for Tekton workspaces |
| eksctl | latest | EKS cluster provisioning CLI |
| helm | 3.x | Helm chart installations |
| tkn CLI | latest | Tekton pipeline inspection (optional) |
| argocd CLI | latest | ArgoCD management (optional) |
| ECR | hosted | Docker image registry |
| GitHub | hosted | Source code + GitOps config repository |

---

## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions of a system — essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.*

### Property 1: HMAC Validation Rejects Invalid Signatures

*For any* incoming webhook payload and any candidate HMAC signature, the EventListener SHALL accept the request if and only if the HMAC-SHA256 of the payload body using the shared secret equals the provided signature. Any payload whose signature does not match SHALL be rejected and SHALL NOT trigger a PipelineRun.

**Validates: Requirements 4.2**

---

### Property 2: Non-Main Branch Events Are Discarded

*For any* GitHub push event whose `ref` field is not `refs/heads/main`, the EventListener SHALL not create a PipelineRun. This must hold regardless of the branch name, including branches that share a prefix with `main` (e.g., `refs/heads/main-feature`).

**Validates: Requirements 4.3**

---

### Property 3: Git Short SHA Derivation is Always 7 Characters

*For any* full commit SHA string of length ≥ 7, the derived Git_Short_SHA SHALL equal exactly the first 7 characters of the full SHA string. If the input is already 7 characters, it is returned unchanged. If it is longer, it is truncated to 7.

**Validates: Requirements 4.4, 6.3**

---

### Property 4: Image Push Always Produces Two ECR Tags

*For any* valid Git short SHA produced during a pipeline run, the Task_Docker step SHALL push exactly two tags to ECR — `<Git_Short_SHA>` and `latest` — and both tags SHALL resolve to the same image digest.

**Validates: Requirements 6.1**

---

### Property 5: Deployment Manifest Always References the SHA Tag

*For any* valid Git short SHA, after Task_UpdateTag completes successfully, the `image` field in `k8s/deployment.yaml` SHALL reference `208073622683.dkr.ecr.us-east-1.amazonaws.com/ibm/vetro:<Git_Short_SHA>` and SHALL NOT reference the `latest` tag.

**Validates: Requirements 6.2**

---

### Property 6: Pipeline Halts on Any Task Failure (No Downstream Execution)

*For any* of the four pipeline tasks (Task_Clone, Task_Build, Task_Docker, Task_UpdateTag), if that task fails, then all tasks that are sequenced after it in the DAG SHALL NOT execute, and the PipelineRun SHALL transition to `Failed` state. No partial execution state — such as a pushed ECR image without a corresponding manifest update — shall be possible when a downstream task fails.

**Validates: Requirements 5.7, 11.1, 11.2, 11.3**

---

### Property 7: Build Failure Prevents ECR Push

*For any* Maven build that exits with a non-zero status code, the pipeline SHALL terminate before Task_Docker executes, ensuring no new Docker image is pushed to ECR and the ECR repository state is unchanged.

**Validates: Requirements 11.1**

---

### Property 8: Deployment Manifest is Unchanged After Upstream Task Failure

*For any* pipeline run in which Task_Docker or Task_UpdateTag fails, the `image` field in `k8s/deployment.yaml` on the `main` branch of GitHub SHALL retain its value from before the pipeline run began. The Git commit history SHALL contain no new commit from Tekton Bot for that run.

**Validates: Requirements 11.2, 11.3**

---

### Property 9: ArgoCD Self-Heal Reverts Any Manual Drift

*For any* Kubernetes resource in the `vectro` namespace that is tracked in the `k8s/` Git folder, if the live resource is manually modified (e.g., via `kubectl edit` or `kubectl apply`), ArgoCD SHALL revert it to the Git-tracked state within the polling interval when `selfHeal: true` is configured.

**Validates: Requirements 12.1**

---

### Property 10: ArgoCD Prune Removes Git-Deleted Resources

*For any* Kubernetes resource that is removed from the `k8s/` folder in the `main` branch, ArgoCD SHALL delete the corresponding resource from the `vectro` namespace on the next sync cycle when `prune: true` is configured.

**Validates: Requirements 12.2**
