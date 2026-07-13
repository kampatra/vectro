# Vectro — EKS + Tekton + ArgoCD Deployment Guide

This guide walks you through deploying the `vectro` Spring Boot microservice to AWS EKS
using Tekton for CI (build + push to ECR) and ArgoCD for CD (GitOps sync to EKS).

**GitHub repo**: https://github.com/kampatra/vectro  
**ECR repo**: `208073622683.dkr.ecr.us-east-1.amazonaws.com/ibm/vetro`  
**AWS Account**: `208073622683` | **Region**: `us-east-1`

---

## Architecture Overview

```
Developer → git push → GitHub (kampatra/vectro)
                           │
                           │ webhook
                           ▼
                   Tekton EventListener (EKS)
                           │
                           ▼
                   Tekton PipelineRun
                     ├── Task 1: git-clone
                     ├── Task 2: maven-build  (mvn clean package)
                     ├── Task 3: docker-build-push  → ECR
                     └── Task 4: update-image-tag  → commits back to GitHub
                           │
                           │ ArgoCD polls GitHub every 3 min
                           ▼
                   ArgoCD (EKS) → kubectl apply k8s/
                           │
                           ▼
                   EKS Namespace: vectro
                     ├── Deployment (vectro pod)
                     ├── Service (ClusterIP)
                     └── Ingress (AWS ALB)
```

---

## Prerequisites — Install Tools on Your Local Machine

Before you begin, install these tools. Open a terminal and run each block.

### AWS CLI v2
```bash
# Windows (PowerShell) — download and install
msiexec.exe /i https://awscli.amazonaws.com/AWSCLIV2.msi
# Verify
aws --version
```

### eksctl
```bash
# Windows — using winget
winget install --id Amazon.EKSCtl -e
# Verify
eksctl version
```

### kubectl
```bash
# Windows — using winget
winget install --id Kubernetes.kubectl -e
# Verify
kubectl version --client
```

### Helm (v3)
```bash
# Windows — using winget
winget install --id Helm.Helm -e
# Verify
helm version
```

### Tekton CLI (tkn) — optional but recommended
```bash
# Windows — download from GitHub releases
# https://github.com/tektoncd/cli/releases/latest
# Add tkn.exe to your PATH
tkn version
```

### ArgoCD CLI — optional
```bash
# Windows — download from GitHub releases
# https://github.com/argoproj/argo-cd/releases/latest
# Add argocd.exe to your PATH
argocd version --client
```

---

## Step 1: Create the EKS Cluster

### 1.1 Configure AWS credentials
```bash
aws configure
# AWS Access Key ID:     <your IAM user access key>
# AWS Secret Access Key: <your IAM user secret key>
# Default region name:   us-east-1
# Default output format: json
```

### 1.2 Create the cluster
The `eksctl-cluster.yaml` file in the repo root defines a cluster named `vectro-cluster`
with 2x `t3.medium` nodes in private subnets and OIDC enabled (required for IRSA).

```bash
eksctl create cluster -f eksctl-cluster.yaml
```

> This takes **15-20 minutes**. eksctl creates the VPC, subnets, node group, and configures
> `~/.kube/config` automatically.

### 1.3 Verify the cluster
```bash
kubectl get nodes
# Expected output: 2 nodes in Ready state

kubectl get pods -A
# Expected: system pods in kube-system running
```

### 1.4 Install the AWS Load Balancer Controller
This controller is required to provision the ALB for the vectro Ingress.

```bash
# Add the EKS Helm repository
helm repo add eks https://aws.github.io/eks-charts
helm repo update

# Create the IAM service account for the controller
eksctl create iamserviceaccount \
  --cluster=vectro-cluster \
  --namespace=kube-system \
  --name=aws-load-balancer-controller \
  --attach-policy-arn=arn:aws:iam::aws:policy/ElasticLoadBalancingFullAccess \
  --approve

# Install the controller via Helm
helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
  -n kube-system \
  --set clusterName=vectro-cluster \
  --set serviceAccount.create=false \
  --set serviceAccount.name=aws-load-balancer-controller

# Verify
kubectl get deployment -n kube-system aws-load-balancer-controller
# Should show: 1/1 Ready
```

---

## Step 2: Install and Configure Tekton

### 2.1 Install Tekton Pipelines and Triggers
```bash
# Install Tekton Pipelines
kubectl apply -f https://storage.googleapis.com/tekton-releases/pipeline/latest/release.yaml

# Install Tekton Triggers (enables GitHub webhook → PipelineRun)
kubectl apply -f https://storage.googleapis.com/tekton-releases/triggers/latest/release.yaml
kubectl apply -f https://storage.googleapis.com/tekton-releases/triggers/latest/interceptors.yaml

# Wait for all Tekton pods to be Running (~2 minutes)
kubectl get pods -n tekton-pipelines --watch
# Press Ctrl+C when all pods show Running
```

### 2.2 Create the tekton-builds namespace and apply manifests
```bash
# Create namespace
kubectl apply -f tekton/namespace.yaml

# Install the official git-clone task from Tekton Catalog
kubectl apply -f https://raw.githubusercontent.com/tektoncd/catalog/main/task/git-clone/0.9/git-clone.yaml -n tekton-builds
```

### 2.3 Create the IRSA role so Tekton can push to ECR

Tekton needs AWS permissions to push Docker images to ECR. We use IRSA (IAM Roles for
Service Accounts) — no long-lived credentials stored in the cluster.

```bash
# Get the OIDC provider ID for your cluster
OIDC_ID=$(aws eks describe-cluster --name vectro-cluster \
  --query "cluster.identity.oidc.issuer" --output text | cut -d'/' -f5)

echo "OIDC ID: $OIDC_ID"
# Example: abc1234567890abcdef1234567890ab

# Create the IAM role with a trust policy for the Tekton ServiceAccount
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

# Attach the ECR push policy (file is in the repo root)
aws iam put-role-policy \
  --role-name tekton-ecr-push-role \
  --policy-name tekton-ecr-push-policy \
  --policy-document file://tekton-ecr-push-policy.json

# Verify the role was created
aws iam get-role --role-name tekton-ecr-push-role --query "Role.Arn" --output text
# Expected: arn:aws:iam::208073622683:role/tekton-ecr-push-role
```

### 2.4 Apply all Tekton manifests
```bash
kubectl apply -f tekton/serviceaccount.yaml
kubectl apply -f tekton/rbac.yaml
kubectl apply -f tekton/task-maven-build.yaml
kubectl apply -f tekton/task-docker-build-push.yaml
kubectl apply -f tekton/task-update-image-tag.yaml
kubectl apply -f tekton/pipeline.yaml
kubectl apply -f tekton/trigger-binding.yaml
kubectl apply -f tekton/trigger-template.yaml
kubectl apply -f tekton/event-listener.yaml
```

### 2.5 Create the required Kubernetes Secrets

These secrets are **never committed to Git** — create them manually once.

```bash
# Secret 1: GitHub webhook HMAC secret
#   Pick any random string — you will use the SAME value when setting up
#   the webhook in GitHub (Step 4).
kubectl create secret generic github-webhook-secret \
  --from-literal=secret=<PICK_A_RANDOM_SECRET_STRING> \
  -n tekton-builds

# Secret 2: GitHub Personal Access Token (PAT)
#   Go to: GitHub → Settings → Developer Settings → Personal access tokens → Tokens (classic)
#   Create a token with "repo" scope (full control of private repositories).
#   This allows Tekton to push the updated deployment.yaml back to GitHub.
kubectl create secret generic github-token \
  --from-literal=token=<YOUR_GITHUB_PAT> \
  -n tekton-builds

# Secret 3: Vectro application secrets (Cognito credentials)
kubectl create secret generic vectro-secrets \
  --from-literal=AWS_ACCESS_KEY_ID=<YOUR_AWS_KEY> \
  --from-literal=AWS_SECRET_ACCESS_KEY=<YOUR_AWS_SECRET> \
  --from-literal=COGNITO_CLIENT_SECRET=<YOUR_COGNITO_SECRET> \
  -n vectro
```

### 2.6 Get the EventListener webhook URL
```bash
# Wait for the LoadBalancer to get an external hostname (~1-2 minutes)
kubectl get svc el-vectro-event-listener -n tekton-builds --watch
# Press Ctrl+C when EXTERNAL-IP column shows a hostname (not <pending>)

# Save the URL for Step 4
EL_URL=$(kubectl get svc el-vectro-event-listener -n tekton-builds \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}')
echo "Webhook URL: http://${EL_URL}:8080"
```

---

## Step 3: Install and Configure ArgoCD

### 3.1 Install ArgoCD
```bash
kubectl create namespace argocd

kubectl apply -n argocd -f \
  https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml

# Wait for all ArgoCD pods to be Running (~3 minutes)
kubectl get pods -n argocd --watch
# Press Ctrl+C when all show Running or Completed
```

### 3.2 Get the initial admin password
```bash
kubectl -n argocd get secret argocd-initial-admin-secret \
  -o jsonpath="{.data.password}" | base64 -d
# Save this password — you will use it to log in to the ArgoCD UI
```

### 3.3 Access the ArgoCD UI
```bash
# Forward the ArgoCD server to your local machine
kubectl port-forward svc/argocd-server -n argocd 8443:443
```

Open your browser at **https://localhost:8443**  
Username: `admin`  
Password: *(from step 3.2)*

> Accept the self-signed certificate warning in your browser.

### 3.4 Register the GitHub repository with ArgoCD

Since `kampatra/vectro` is a **public** GitHub repo, no credentials are needed.
If the repo is private:

```bash
# Login via CLI
argocd login localhost:8443 --username admin --password <PASSWORD> --insecure

# Add repo (only needed for private repos)
argocd repo add https://github.com/kampatra/vectro \
  --username kampatra \
  --password <GITHUB_PAT>
```

### 3.5 Deploy the ArgoCD Application
```bash
kubectl apply -f argocd/argocd-app.yaml

# Check the status
kubectl get application vectro -n argocd
# STATUS column should show: Synced
# HEALTH column should show: Healthy

# If OutOfSync, trigger a manual sync:
argocd app sync vectro
```

---

## Step 4: Configure the GitHub Webhook

This tells GitHub to notify Tekton every time code is pushed to the `main` branch.

1. Go to **https://github.com/kampatra/vectro/settings/hooks**
2. Click **Add webhook**
3. Fill in the form:
   - **Payload URL**: `http://<EL_URL>:8080`  (the URL from Step 2.6)
   - **Content type**: `application/json`
   - **Secret**: The same random string you used in `github-webhook-secret` (Step 2.5)
   - **Which events would you like to trigger this webhook?**: Select **Just the push event**
4. Click **Add webhook**

GitHub sends a test ping. You should see a green ✓ checkmark confirming delivery.

---

## Step 5: Build and Deploy — Verify End-to-End

### Trigger the pipeline with a code push
```bash
# Make any small change and push
git add .
git commit -m "feat: trigger first Tekton build"
git push origin main
```

### Watch the Tekton PipelineRun
```bash
# List all pipeline runs (refresh to see new one appear)
kubectl get pipelineruns -n tekton-builds

# Stream logs from the currently running pipeline
tkn pipelinerun logs -n tekton-builds --last -f

# Or without tkn CLI:
kubectl logs -n tekton-builds -l tekton.dev/pipeline=vectro-ci-pipeline -f
```

The pipeline runs 4 tasks in sequence (total time ~8-12 minutes):
1. `fetch-source` — clones the GitHub repo
2. `maven-build` — runs `mvn clean package -DskipTests`
3. `docker-build-push` — builds the Docker image and pushes to ECR
4. `update-image-tag` — patches `k8s/deployment.yaml` and pushes to GitHub

### Watch ArgoCD sync the new image
```bash
# ArgoCD polls GitHub every 3 minutes automatically.
# Force an immediate sync:
argocd app sync vectro

# Watch the rollout
kubectl rollout status deployment/vectro -n vectro
# Expected: "deployment 'vectro' successfully rolled out"
```

### Test the application
```bash
# Get the ALB DNS name
kubectl get ingress vectro-ingress -n vectro
# Copy the ADDRESS column value

# Test the health endpoint
curl http://<ALB_ADDRESS>/actuator/health
# Expected: {"status":"UP"}

# Test the login endpoint (example)
curl -X POST http://<ALB_ADDRESS>/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"testpass"}'
```

---

## Repository File Structure

After setup, your repo should have this layout:

```
vectro/
├── Dockerfile                        # Container image definition
├── pom.xml                           # Maven build config
├── eksctl-cluster.yaml               # EKS cluster definition
├── tekton-ecr-push-policy.json       # IAM policy for Tekton → ECR
│
├── k8s/                              # ArgoCD syncs this folder to EKS
│   ├── namespace.yaml
│   ├── configmap.yaml
│   ├── deployment.yaml               # Image tag auto-updated by Tekton
│   ├── service.yaml
│   ├── ingress.yaml
│   └── secret.yaml                   # Placeholder only — real secret applied manually
│
├── tekton/                           # Tekton CI pipeline manifests
│   ├── namespace.yaml
│   ├── serviceaccount.yaml
│   ├── rbac.yaml
│   ├── task-maven-build.yaml
│   ├── task-docker-build-push.yaml
│   ├── task-update-image-tag.yaml
│   ├── pipeline.yaml
│   ├── trigger-binding.yaml
│   ├── trigger-template.yaml
│   └── event-listener.yaml
│
└── argocd/
    └── argocd-app.yaml               # Updated to GitHub (was CodeCommit)
```

---

## Troubleshooting

### Pipeline not triggering on git push
```bash
# Check GitHub webhook delivery: GitHub → Settings → Webhooks → Recent Deliveries
# Check EventListener pod is running:
kubectl get pods -n tekton-builds
# Check EventListener logs:
kubectl logs -n tekton-builds -l eventlistener=vectro-event-listener
```

### ECR push fails (permission denied)
```bash
# Verify the ServiceAccount has the correct IRSA annotation
kubectl get sa tekton-sa -n tekton-builds -o yaml
# The eks.amazonaws.com/role-arn annotation must point to your role

# Verify the role exists
aws iam get-role --role-name tekton-ecr-push-role
```

### ArgoCD shows OutOfSync
```bash
# Force sync
argocd app sync vectro

# Check for errors
argocd app get vectro
kubectl describe application vectro -n argocd
```

### Maven build fails
```bash
# View the full task logs
tkn taskrun logs -n tekton-builds --last -f

# Or find the specific TaskRun
kubectl get taskruns -n tekton-builds
kubectl logs -n tekton-builds <taskrun-pod-name> -c step-mvn-package
```

### Pod stuck in ImagePullBackOff
```bash
kubectl describe pod -n vectro -l app=vectro
# Verify the EKS node role has AmazonEC2ContainerRegistryReadOnly attached
aws iam list-attached-role-policies --role-name <node-instance-role>
```

---

## Security Notes

- **IRSA over static credentials**: Tekton authenticates to ECR via IRSA — no AWS keys
  stored in the cluster. The old `buildspec.yaml` pattern (CodeBuild instance role) is
  replicated with IRSA on EKS.
- **Webhook HMAC**: The Tekton GitHub interceptor validates `X-Hub-Signature-256` on every
  webhook call, preventing unauthorized pipeline triggers.
- **Secret protection**: `vectro-secrets` is never in Git with real values. ArgoCD
  `ignoreDifferences` prevents it from overwriting the real secret you apply manually.
- **GitHub PAT scope**: The PAT used by the `update-image-tag` task only needs the `repo`
  scope. Rotate it periodically in GitHub → Settings → Developer Settings.
- **ECR image scanning**: Enable vulnerability scanning on push:
  ```bash
  aws ecr put-image-scanning-configuration \
    --repository-name ibm/vetro \
    --image-scanning-configuration scanOnPush=true \
    --region us-east-1
  ```
