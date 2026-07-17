# Vectro — Complete EKS Deployment Guide

This guide walks a beginner through deploying the **vectro** Spring Boot authentication
microservice to AWS EKS from scratch. Every command is explained so you know what it does
and why.

**What is vectro?**  
A REST API that handles login and JWT validation using AWS Cognito. Users call it to log in
and receive tokens, which they then use to access protected resources.

**Key details**
| Item | Value |
|---|---|
| GitHub repo | https://github.com/kampatra/vectro |
| AWS Account ID | `208073622683` |
| AWS Region | `us-east-1` |
| EKS Cluster | `verto-cluster` |
| Node Group | `verto-nodes` |
| ECR Image | `208073622683.dkr.ecr.us-east-1.amazonaws.com/ibm/vetro` |
| App namespace | `vectro` |

---

## How the system works

```
Developer → git push → GitHub
                           │  webhook triggers on push
                           ▼
                  Tekton (runs inside EKS)
                  ├─ 1. Clone source code
                  ├─ 2. Build JAR with Maven
                  ├─ 3. Build Docker image → push to ECR
                  └─ 4. Update image tag in deployment.yaml → push to GitHub
                           │
                           │  ArgoCD polls GitHub every 3 min
                           ▼
                  ArgoCD (runs inside EKS)
                  └─ Applies k8s/ manifests to the vectro namespace
                           │
                           ▼
                  EKS Pod running the Spring Boot app
                  └─ Exposed via AWS ALB (public URL)
```

**Secrets are never stored in code or Git.** The Cognito client secret lives in
AWS Secrets Manager and is injected into the pod at startup. AWS credentials for the pod
come from an IAM role attached to the pod's service account (IRSA) — no access keys needed.

---

## Prerequisites — Tools to install on your machine

Install these tools once. Open PowerShell as Administrator and run each block.

### 1. AWS CLI v2
The AWS CLI lets you talk to your AWS account from the command line.
```powershell
# Download and install
msiexec.exe /i https://awscli.amazonaws.com/AWSCLIV2.msi /quiet
# Verify — you should see a version number
aws --version
```

### 2. eksctl
`eksctl` is the official CLI for creating and managing EKS clusters.
```powershell
winget install --id Amazon.EKSCtl -e
eksctl version
```

### 3. kubectl
`kubectl` is the command-line tool for controlling Kubernetes clusters.
```powershell
winget install --id Kubernetes.kubectl -e
kubectl version --client
```

### 4. Helm v3
Helm is a package manager for Kubernetes. We use it to install controllers.
```powershell
winget install --id Helm.Helm -e
helm version
```

### 5. Tekton CLI (optional but helpful)
Lets you watch pipeline logs in real time.
```powershell
# Download tkn.exe from: https://github.com/tektoncd/cli/releases/latest
# Place tkn.exe somewhere in your PATH (e.g. C:\Windows\System32)
tkn version
```

### 6. ArgoCD CLI (optional)
Lets you interact with ArgoCD from the command line.
```powershell
# Download argocd.exe from: https://github.com/argoproj/argo-cd/releases/latest
# Place argocd.exe somewhere in your PATH
argocd version --client
```

---

## Phase 1 — One-time AWS setup

These steps create AWS resources that persist beyond a single deployment. Run them once.

### Step 1.1 — Configure AWS credentials on your machine

This tells the AWS CLI which account to use.
```bash
aws configure
# Prompts:
#   AWS Access Key ID:     <your IAM user key — from AWS Console → IAM → Users → Security credentials>
#   AWS Secret Access Key: <your IAM user secret>
#   Default region name:   us-east-1
#   Default output format: json
```

Verify it works:
```bash
aws sts get-caller-identity
# Should print your Account ID, UserId, and ARN — confirms your credentials are valid
```

### Step 1.2 — Create the ECR repository

ECR (Elastic Container Registry) is where Docker images are stored before Kubernetes pulls them.
```bash
aws ecr create-repository \
  --repository-name ibm/vetro \
  --region us-east-1
# Creates: 208073622683.dkr.ecr.us-east-1.amazonaws.com/ibm/vetro
```

Enable vulnerability scanning so AWS automatically checks images for known CVEs:
```bash
aws ecr put-image-scanning-configuration \
  --repository-name ibm/vetro \
  --image-scanning-configuration scanOnPush=true \
  --region us-east-1
```

### Step 1.3 — Store the Cognito client secret in AWS Secrets Manager

Secrets Manager is a secure vault for sensitive values. We store the Cognito client secret
here instead of in Kubernetes or in code.
```bash
aws secretsmanager create-secret \
  --name verto/vectro/cognito \
  --description "Cognito app client secret for the vectro microservice" \
  --secret-string '{"cognito_client_secret":"<YOUR_COGNITO_CLIENT_SECRET>"}' \
  --region us-east-1
# Replace <YOUR_COGNITO_CLIENT_SECRET> with the actual value from:
# AWS Console → Cognito → User Pools → us-east-1_T6dPgrH15 → App clients → Show client secret
```

To update the secret later:
```bash
aws secretsmanager update-secret \
  --secret-id verto/vectro/cognito \
  --secret-string '{"cognito_client_secret":"<NEW_VALUE>"}' \
  --region us-east-1
```

### Step 1.4 — Create the IAM policy for the ALB Controller

The AWS Load Balancer Controller (which provisions the ALB for your ingress) needs specific
IAM permissions. The policy JSON is already in the repo.
```bash
aws iam create-policy \
  --policy-name AWSLoadBalancerControllerIAMPolicy \
  --policy-document file://alb-controller-iam-policy.json
# Outputs a Policy ARN — copy it, you'll need it in Step 2.4
# Example: arn:aws:iam::208073622683:policy/AWSLoadBalancerControllerIAMPolicy
```

### Step 1.5 — Create the IAM policy for Tekton (ECR push)

Tekton needs permission to push Docker images to ECR. The policy JSON is already in the repo.
```bash
aws iam create-policy \
  --policy-name TektonECRPushPolicy \
  --policy-document file://tekton-ecr-push-policy.json
# Example output: arn:aws:iam::208073622683:policy/TektonECRPushPolicy
```

### Step 1.6 — Create the IAM policy for the vectro app (Cognito + Secrets Manager)

The running pod needs to call Cognito APIs and read its secret from Secrets Manager.
```bash
aws iam create-policy \
  --policy-name VectroAppPolicy \
  --policy-document '{
    "Version": "2012-10-17",
    "Statement": [
      {
        "Sid": "CognitoAccess",
        "Effect": "Allow",
        "Action": [
          "cognito-idp:InitiateAuth",
          "cognito-idp:GetUser",
          "cognito-idp:AdminGetUser"
        ],
        "Resource": "arn:aws:cognito-idp:us-east-1:208073622683:userpool/us-east-1_T6dPgrH15"
      },
      {
        "Sid": "SecretsManagerAccess",
        "Effect": "Allow",
        "Action": ["secretsmanager:GetSecretValue"],
        "Resource": "arn:aws:secretsmanager:us-east-1:208073622683:secret:verto/vectro/cognito*"
      }
    ]
  }'
# Example output: arn:aws:iam::208073622683:policy/VectroAppPolicy
```

---

## Phase 2 — Create the EKS Cluster

### Step 2.1 — Create the cluster

The cluster definition is in `eksctl-cluster.yaml`. It creates:
- A cluster named `verto-cluster` with Kubernetes 1.32
- One managed node group named `verto-nodes` (1x `t3.medium` in a private subnet)
- OIDC enabled — required so pods can assume IAM roles (IRSA)
- Standard add-ons: VPC CNI, CoreDNS, kube-proxy, EBS CSI driver, Secrets Store CSI driver

```bash
eksctl create cluster -f eksctl-cluster.yaml
# This takes 15–20 minutes. eksctl creates the VPC, subnets, and node group.
# It also updates ~/.kube/config so kubectl connects to the new cluster automatically.
```

Watch progress (in a second terminal):
```bash
eksctl utils describe-stacks --region us-east-1 --cluster verto-cluster
```

Verify the cluster is ready:
```bash
kubectl get nodes
# Expected: 1 node with STATUS = Ready

kubectl get pods -A
# Expected: system pods in kube-system all Running
```

### Step 2.2 — Install the AWS Load Balancer Controller

This controller watches for Kubernetes Ingress resources and automatically creates an
AWS Application Load Balancer (ALB) for each one.

```bash
# Add the EKS Helm chart repository
helm repo add eks https://aws.github.io/eks-charts
helm repo update
# "helm repo add" registers a remote chart source. "helm repo update" fetches the latest index.

# Create a Kubernetes ServiceAccount linked to an IAM role (IRSA)
# This gives the controller the IAM permissions it needs without static credentials
eksctl create iamserviceaccount \
  --cluster=verto-cluster \
  --namespace=kube-system \
  --name=aws-load-balancer-controller \
  --attach-policy-arn=arn:aws:iam::208073622683:policy/AWSLoadBalancerControllerIAMPolicy \
  --approve
# --approve skips the confirmation prompt

# Install the controller via Helm
helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
  --namespace kube-system \
  --set clusterName=verto-cluster \
  --set serviceAccount.create=false \
  --set serviceAccount.name=aws-load-balancer-controller
# --set overrides default chart values
# serviceAccount.create=false because we already created it above via eksctl

# Verify the controller is running (may take 1 minute)
kubectl get deployment -n kube-system aws-load-balancer-controller
# Expected: READY = 2/2
```

### Step 2.3 — Install the Secrets Store CSI driver and AWS provider

This is what mounts secrets from AWS Secrets Manager directly into pods as environment
variables. It is already declared as an addon in `eksctl-cluster.yaml`, but the Helm-based
installation ensures `syncSecret` (env-var sync) is enabled.

```bash
# Add the CSI driver Helm repo
helm repo add secrets-store-csi-driver \
  https://kubernetes-sigs.github.io/secrets-store-csi-driver/charts
helm repo update

# Install the driver with secret syncing to env vars enabled
helm install csi-secrets-store secrets-store-csi-driver/secrets-store-csi-driver \
  --namespace kube-system \
  --set syncSecret.enabled=true
# syncSecret.enabled=true is required — without it secrets cannot be used as env vars

# Install the AWS provider (translates SecretProviderClass → Secrets Manager API calls)
kubectl apply -f \
  https://raw.githubusercontent.com/aws/secrets-store-csi-driver-provider-aws/main/deployment/aws-provider-installer.yaml

# Verify both are running
kubectl get pods -n kube-system -l app=secrets-store-csi-driver
kubectl get pods -n kube-system -l app=csi-secrets-store-provider-aws
# All pods should show Running
```

### Step 2.4 — Create the vectro app IAM role (IRSA)

This role gives the vectro pod the right to call Cognito and read from Secrets Manager.
The pod assumes this role automatically via IRSA — no access keys are stored anywhere.

```bash
# Get the OIDC provider ID that eksctl created for your cluster
OIDC_ID=$(aws eks describe-cluster \
  --name verto-cluster \
  --query "cluster.identity.oidc.issuer" \
  --output text | cut -d'/' -f5)

echo "OIDC Provider ID: $OIDC_ID"
# Example: abc123def456abc123def456abc12345

# Create the IAM role with a trust policy that allows the vectro-sa
# ServiceAccount (in the vectro namespace) to assume it
aws iam create-role \
  --role-name vectro-irsa-role \
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
            \"system:serviceaccount:vectro:vectro-sa\"
        }
      }
    }]
  }"

# Attach the VectroAppPolicy created in Step 1.6
aws iam attach-role-policy \
  --role-name vectro-irsa-role \
  --policy-arn arn:aws:iam::208073622683:policy/VectroAppPolicy

# Verify the role ARN (you'll need this in the next step)
aws iam get-role --role-name vectro-irsa-role --query "Role.Arn" --output text
# Expected: arn:aws:iam::208073622683:role/vectro-irsa-role
```

The role ARN is already set in `k8s/serviceaccount.yaml`. If your account ID differs,
update that file before applying it.

---

## Phase 3 — Install Tekton (CI pipeline)

Tekton runs inside your cluster and handles building and pushing the Docker image.

### Step 3.1 — Install Tekton Pipelines and Triggers

```bash
# Install Tekton Pipelines — the core engine that runs tasks and pipelines
kubectl apply -f https://storage.googleapis.com/tekton-releases/pipeline/latest/release.yaml

# Install Tekton Triggers — enables GitHub webhooks to start pipeline runs automatically
kubectl apply -f https://storage.googleapis.com/tekton-releases/triggers/latest/release.yaml

# Install Interceptors — processes and validates the webhook payload
kubectl apply -f https://storage.googleapis.com/tekton-releases/triggers/latest/interceptors.yaml

# Wait for all Tekton pods to reach Running state (takes ~2 minutes)
kubectl get pods -n tekton-pipelines --watch
# Press Ctrl+C when all pods show Running or Completed
```

### Step 3.2 — Create the Tekton namespace and install the git-clone task

```bash
# Create the tekton-builds namespace where all pipeline resources will live
kubectl apply -f tekton/namespace.yaml

# Install the official git-clone task from the Tekton catalog
# This task clones a GitHub repository inside the pipeline
kubectl apply -n tekton-builds \
  -f https://raw.githubusercontent.com/tektoncd/catalog/main/task/git-clone/0.9/git-clone.yaml
```

### Step 3.3 — Create the Tekton IAM role (IRSA) for ECR push

Tekton needs to push Docker images to ECR. We use IRSA so no AWS keys are stored in the cluster.

```bash
# Re-use the OIDC_ID variable from Step 2.4, or run this again:
OIDC_ID=$(aws eks describe-cluster \
  --name verto-cluster \
  --query "cluster.identity.oidc.issuer" \
  --output text | cut -d'/' -f5)

# Create the IAM role trusted by the tekton-sa ServiceAccount in tekton-builds namespace
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

# Attach the ECR push policy created in Step 1.5
aws iam attach-role-policy \
  --role-name tekton-ecr-push-role \
  --policy-arn arn:aws:iam::208073622683:policy/TektonECRPushPolicy

# Confirm the role was created
aws iam get-role --role-name tekton-ecr-push-role --query "Role.Arn" --output text
# Expected: arn:aws:iam::208073622683:role/tekton-ecr-push-role
```

### Step 3.4 — Apply all Tekton manifests

```bash
# ServiceAccount — the identity Tekton pipeline pods run as
# It is annotated with the IAM role ARN so IRSA works
kubectl apply -f tekton/serviceaccount.yaml

# RBAC — grants the ServiceAccount permission to create PipelineRuns and read secrets
kubectl apply -f tekton/rbac.yaml

# Custom Task definitions (the actual build steps)
kubectl apply -f tekton/task-maven-build.yaml        # runs: mvn clean package -DskipTests
kubectl apply -f tekton/task-docker-build-push.yaml  # builds the Docker image and pushes to ECR
kubectl apply -f tekton/task-update-image-tag.yaml   # patches deployment.yaml and pushes to GitHub

# The Pipeline that chains the 4 tasks together
kubectl apply -f tekton/pipeline.yaml

# Trigger resources — these receive the GitHub webhook and start a PipelineRun
kubectl apply -f tekton/trigger-binding.yaml    # extracts values from the webhook payload
kubectl apply -f tekton/trigger-template.yaml   # defines the PipelineRun to create
kubectl apply -f tekton/event-listener.yaml     # creates the HTTP endpoint that receives the webhook
```

### Step 3.5 — Create the required Kubernetes Secrets for Tekton

These secrets are never committed to Git. You create them manually once.

```bash
# Secret 1: GitHub webhook HMAC secret
# Choose any random string (e.g. a password). You will enter the same string
# in GitHub when setting up the webhook so GitHub can prove it sent the request.
kubectl create secret generic github-webhook-secret \
  --from-literal=secret=PICK_ANY_RANDOM_STRING_HERE \
  --namespace tekton-builds
# Example: --from-literal=secret=myWebhookSecret42

# Secret 2: GitHub Personal Access Token (PAT)
# The update-image-tag task needs to push a commit back to GitHub.
# Create a PAT: GitHub → Settings → Developer settings → Personal access tokens → Generate new token
# Required scope: repo (full control of private repositories)
kubectl create secret generic github-token \
  --from-literal=token=YOUR_GITHUB_PAT_HERE \
  --namespace tekton-builds
```

### Step 3.6 — Get the Tekton EventListener URL (for the GitHub webhook)

```bash
# Wait for the EventListener's LoadBalancer to get a public DNS name
kubectl get svc el-vectro-event-listener -n tekton-builds --watch
# Press Ctrl+C when EXTERNAL-IP shows a hostname instead of <pending>

# Print the webhook URL
EL_HOSTNAME=$(kubectl get svc el-vectro-event-listener \
  -n tekton-builds \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}')
echo "Webhook URL: http://${EL_HOSTNAME}:8080"
# Save this URL — you'll enter it in GitHub in Phase 5
```

---

## Phase 4 — Install ArgoCD (CD / GitOps)

ArgoCD continuously watches your GitHub repo and applies any changes in the `k8s/` folder
to the cluster automatically. You never need to run `kubectl apply` for the application
manifests yourself after this is set up.

### Step 4.1 — Install ArgoCD

```bash
# Create the argocd namespace
kubectl create namespace argocd

# Install ArgoCD (all components: server, repo-server, application-controller, etc.)
kubectl apply -n argocd \
  -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml

# Wait for all ArgoCD pods to be Running (takes ~3 minutes)
kubectl get pods -n argocd --watch
# Press Ctrl+C when all pods show Running or Completed
```

### Step 4.2 — Get the initial admin password

ArgoCD generates a random password on first install. Retrieve it:
```bash
kubectl -n argocd get secret argocd-initial-admin-secret \
  -o jsonpath="{.data.password}" | base64 --decode
# Save this password — you will use it to log in
```

### Step 4.3 — Open the ArgoCD UI

```bash
# Forward the ArgoCD web server to your local machine on port 8443
kubectl port-forward svc/argocd-server -n argocd 8443:443
# Leave this terminal open while you browse the UI
```

Open **https://localhost:8443** in your browser.  
- Accept the self-signed certificate warning  
- Username: `admin`  
- Password: from Step 4.2  

### Step 4.4 — Deploy the ArgoCD Application

The `argocd-app.yaml` file tells ArgoCD what to deploy and where.  
It watches the `k8s/` folder of the GitHub repo and syncs it to the `vectro` namespace.

```bash
kubectl apply -f argocd/argocd-app.yaml
# Creates an ArgoCD "Application" resource in the argocd namespace

# Check the sync status
kubectl get application vectro -n argocd
# STATUS = Synced means ArgoCD has applied the manifests successfully
# HEALTH = Healthy means all pods are running
```

If it shows `OutOfSync`, trigger a manual sync:
```bash
# Via CLI (requires argocd CLI from Prerequisites)
argocd login localhost:8443 --username admin --password YOUR_PASSWORD --insecure
argocd app sync vectro

# Or click "Sync" in the ArgoCD web UI
```

After ArgoCD syncs, it applies all files in `k8s/` to the cluster:
- `namespace.yaml` — creates the `vectro` namespace
- `serviceaccount.yaml` — creates `vectro-sa` with the IRSA role annotation
- `configmap.yaml` — injects non-sensitive Cognito config as env vars
- `secret.yaml` — creates the `SecretProviderClass` (tells CSI driver where to fetch the secret)
- `deployment.yaml` — runs the Spring Boot pod
- `service.yaml` — exposes the pod internally on port 80
- `ingress.yaml` — creates the public AWS ALB

---

## Phase 5 — Configure the GitHub Webhook

The webhook tells GitHub to call your Tekton EventListener every time code is pushed,
which automatically triggers the CI pipeline.

1. Go to **https://github.com/kampatra/vectro/settings/hooks**
2. Click **Add webhook**
3. Fill in the form:

| Field | Value |
|---|---|
| Payload URL | The URL from Step 3.6 — `http://<hostname>:8080` |
| Content type | `application/json` |
| Secret | The same random string you used in `github-webhook-secret` (Step 3.5) |
| Which events? | Select **Just the push event** |

4. Click **Add webhook**

GitHub sends a test ping immediately. A green ✓ confirms it was delivered successfully.

---

## Phase 6 — Verify the full end-to-end flow

### Trigger the pipeline with a code push

```bash
# Clone the repo if you haven't already
git clone https://github.com/kampatra/vectro.git
cd vectro

# Make a small change to trigger the pipeline
git commit --allow-empty -m "chore: trigger first Tekton build"
git push origin main
```

### Watch the Tekton pipeline run

```bash
# List pipeline runs — a new one should appear within ~30 seconds
kubectl get pipelineruns -n tekton-builds

# Stream logs for the current run (requires tkn CLI)
tkn pipelinerun logs -n tekton-builds --last -f

# Without tkn CLI, view logs pod by pod:
kubectl get pods -n tekton-builds
kubectl logs -n tekton-builds <pod-name> --all-containers -f
```

The pipeline runs 4 tasks in sequence (total: 8–12 minutes):

| Task | What it does | Success output |
|---|---|---|
| `fetch-source` | Clones the GitHub repo | `Successfully cloned` |
| `maven-build` | Runs `mvn clean package -DskipTests` | `BUILD SUCCESS` |
| `docker-build-push` | Builds the image and pushes to ECR | `Pushed image to ECR` |
| `update-image-tag` | Updates `deployment.yaml` with new image tag and pushes to GitHub | Git commit + push |

### Watch ArgoCD roll out the new image

```bash
# ArgoCD detects the new commit in ~3 minutes and applies it automatically.
# Force an immediate sync if you don't want to wait:
argocd app sync vectro

# Watch the rollout (how Kubernetes updates pods to the new image)
kubectl rollout status deployment/vectro -n vectro
# Expected: deployment "vectro" successfully rolled out
```

### Test the running application

```bash
# Get the public ALB DNS name
kubectl get ingress vectro-ingress -n vectro
# Copy the value in the ADDRESS column (takes ~2 minutes to provision on first run)

# Test the health endpoint — should return {"status":"UP"}
curl http://<ALB_ADDRESS>/actuator/health

# Test the login endpoint
curl -X POST http://<ALB_ADDRESS>/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"YOUR_COGNITO_USERNAME","password":"YOUR_COGNITO_PASSWORD"}'
# Expected: {"accessToken":"...","idToken":"...","refreshToken":"..."}

# Open the Swagger UI to explore all API endpoints
# http://<ALB_ADDRESS>/swagger-ui.html
```

---

## Quick-reference: useful commands

```bash
# ── Cluster ───────────────────────────────────────────────────────────────────
kubectl get nodes                                   # list nodes and their status
kubectl get pods -A                                 # list all pods in all namespaces
kubectl get pods -n vectro                          # list vectro app pods
kubectl describe pod -n vectro <pod-name>           # detailed info + events for a pod
kubectl logs -n vectro <pod-name> -f                # stream app logs

# ── Application ───────────────────────────────────────────────────────────────
kubectl get ingress -n vectro                       # get ALB DNS name
kubectl get svc -n vectro                           # list services
kubectl rollout status deployment/vectro -n vectro  # check rollout progress
kubectl rollout restart deployment/vectro -n vectro # force a pod restart

# ── Secrets ───────────────────────────────────────────────────────────────────
# View the synced secret (value is base64 encoded)
kubectl get secret vectro-cognito-secret -n vectro -o yaml

# Update the secret in Secrets Manager (pod will pick it up on next restart)
aws secretsmanager update-secret \
  --secret-id verto/vectro/cognito \
  --secret-string '{"cognito_client_secret":"NEW_VALUE"}'

# ── Tekton ────────────────────────────────────────────────────────────────────
kubectl get pipelineruns -n tekton-builds           # list all pipeline runs
tkn pipelinerun logs -n tekton-builds --last -f     # stream last pipeline log
kubectl get taskruns -n tekton-builds               # list individual task runs

# ── ArgoCD ────────────────────────────────────────────────────────────────────
kubectl get application vectro -n argocd            # check sync + health status
argocd app sync vectro                              # force an immediate sync
argocd app get vectro                               # detailed app status

# ── ECR ───────────────────────────────────────────────────────────────────────
# List all images in the ECR repository
aws ecr list-images \
  --repository-name ibm/vetro \
  --region us-east-1 \
  --query 'imageIds[*].imageTag' \
  --output table
```

---

## Troubleshooting

### Pod is in `Pending` state
```bash
kubectl describe pod -n vectro -l app=vectro
# Look at the "Events" section at the bottom
# Common causes: not enough CPU/memory on nodes, image pull failure
```

### Pod is in `ImagePullBackOff`
```bash
kubectl describe pod -n vectro -l app=vectro
# Check "Events" for the exact error
# Fix: verify the EKS node role has AmazonEC2ContainerRegistryReadOnly
aws iam list-attached-role-policies \
  --role-name $(aws ec2 describe-instances \
    --filters "Name=tag:eks:cluster-name,Values=verto-cluster" \
    --query "Reservations[0].Instances[0].IamInstanceProfile.Arn" \
    --output text | cut -d'/' -f2)
```

### Pod is in `CrashLoopBackOff`
```bash
# The app started but crashed. Read the logs to find the error:
kubectl logs -n vectro -l app=vectro --previous
# Common causes: missing env var, can't connect to Cognito, Secrets Manager access denied
```

### Secret not mounted (COGNITO_CLIENT_SECRET is empty)
```bash
# Check that the SecretProviderClass was applied
kubectl get secretproviderclass -n vectro

# Check that the secret synced correctly
kubectl get secret vectro-cognito-secret -n vectro

# Check CSI driver logs for errors
kubectl logs -n kube-system -l app=secrets-store-csi-driver
```

### Tekton pipeline not triggering on git push
```bash
# 1. Check GitHub webhook delivery:
#    GitHub → repo → Settings → Webhooks → click your webhook → Recent Deliveries
#    A red X means the EventListener was unreachable

# 2. Verify the EventListener pod is running:
kubectl get pods -n tekton-builds -l eventlistener=vectro-event-listener

# 3. Check EventListener logs for errors:
kubectl logs -n tekton-builds -l eventlistener=vectro-event-listener
```

### Tekton pipeline fails at `docker-build-push`
```bash
# Check the TaskRun log
tkn taskrun logs -n tekton-builds --last -f

# Common fix: verify tekton-sa has the correct IAM role annotation
kubectl get sa tekton-sa -n tekton-builds -o yaml
# The eks.amazonaws.com/role-arn annotation must be present
```

### ArgoCD shows `OutOfSync`
```bash
# Force a sync
argocd app sync vectro

# If still failing, check for manifest errors
argocd app get vectro
kubectl describe application vectro -n argocd
```

### ALB not getting an address (Ingress ADDRESS is blank)
```bash
# Check the Load Balancer Controller is running
kubectl get deployment -n kube-system aws-load-balancer-controller

# Check its logs for errors
kubectl logs -n kube-system -l app.kubernetes.io/name=aws-load-balancer-controller
# Common cause: IAM policy missing a permission, or subnets not tagged for EKS
```

---

## Repository file reference

```
vectro/
├── eksctl-cluster.yaml              EKS cluster definition (verto-cluster, verto-nodes)
├── alb-controller-iam-policy.json   IAM policy for the ALB Controller
├── tekton-ecr-push-policy.json      IAM policy for Tekton → ECR
├── Dockerfile                       Builds the Spring Boot container image
├── pom.xml                          Maven project and dependency definitions
│
├── k8s/                             ArgoCD syncs this folder to the vectro namespace
│   ├── namespace.yaml               Creates the vectro namespace
│   ├── serviceaccount.yaml          vectro-sa — annotated with IRSA role ARN
│   ├── configmap.yaml               Non-sensitive config (Cognito URLs, pool ID, etc.)
│   ├── secret.yaml                  SecretProviderClass — fetches secret from Secrets Manager
│   ├── deployment.yaml              Runs the Spring Boot pod (image auto-updated by Tekton)
│   ├── service.yaml                 ClusterIP service — internal routing to the pod
│   └── ingress.yaml                 ALB Ingress — creates the public load balancer
│
├── tekton/                          Tekton CI pipeline (runs inside the cluster)
│   ├── namespace.yaml               tekton-builds namespace
│   ├── serviceaccount.yaml          tekton-sa — annotated with IRSA role ARN
│   ├── rbac.yaml                    RBAC permissions for the Tekton ServiceAccount
│   ├── task-maven-build.yaml        Builds the JAR with mvn clean package
│   ├── task-docker-build-push.yaml  Builds and pushes the Docker image to ECR
│   ├── task-update-image-tag.yaml   Patches deployment.yaml and pushes to GitHub
│   ├── pipeline.yaml                Chains the 4 tasks into a single pipeline
│   ├── trigger-binding.yaml         Extracts git-url and git-sha from webhook payload
│   ├── trigger-template.yaml        Creates a PipelineRun from webhook data
│   └── event-listener.yaml          HTTP endpoint that receives GitHub webhook calls
│
├── argocd/
│   └── argocd-app.yaml              Tells ArgoCD to sync k8s/ from GitHub to EKS
│
└── src/                             Spring Boot application source code
    └── main/
        ├── java/com/example/auth/   Java source files
        └── resources/
            └── application.yml      App config (all sensitive values read from env vars)
```

---

## Security summary

| Concern | How it is handled |
|---|---|
| AWS credentials for the app pod | IRSA — pod assumes an IAM role via service account annotation. No access keys anywhere. |
| Cognito client secret | Stored in AWS Secrets Manager. Injected into the pod as an env var by the Secrets Store CSI driver at startup. |
| AWS credentials for Tekton | IRSA — Tekton service account assumes its own IAM role. No access keys in the cluster. |
| GitHub PAT for Tekton | Stored as a Kubernetes Secret, created manually once. Never committed to Git. |
| Webhook authenticity | GitHub signs every webhook with HMAC-SHA256 using the shared secret. Tekton rejects requests with invalid signatures. |
| Image vulnerability scanning | ECR scans every image on push and reports CVEs in the AWS Console. |
