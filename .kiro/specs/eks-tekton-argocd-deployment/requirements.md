# Requirements Document

## Introduction

This document defines the formal requirements for the EKS Tekton ArgoCD Deployment system —
a complete GitOps CI/CD pipeline for the `vectro` Spring Boot microservice. The system replaces
the existing AWS CodeBuild-based workflow with Tekton running on Amazon EKS for CI, and
ArgoCD for automated GitOps-based deployment. A push to the `main` branch of
`kampatra/vectro` on GitHub triggers a Tekton pipeline that builds the Maven artifact, builds
and pushes a Docker image to ECR, updates the Kubernetes deployment manifest with the new
image tag, and ArgoCD automatically reconciles the cluster state with the Git-tracked manifests.

---

## Glossary

- **EKS_Cluster**: The Amazon EKS cluster named `vectro-cluster` in `us-east-1` that hosts all workloads.
- **Tekton**: The cloud-native CI engine (Tekton Pipelines + Triggers) installed on the EKS_Cluster.
- **Pipeline**: The Tekton Pipeline named `vectro-ci-pipeline` that chains the four CI tasks.
- **EventListener**: The Tekton Triggers resource that exposes an HTTP endpoint to receive GitHub webhook events.
- **TriggerTemplate**: The Tekton Triggers resource that creates a PipelineRun when the EventListener fires.
- **TriggerBinding**: The Tekton Triggers resource that extracts parameters from the GitHub webhook payload.
- **PipelineRun**: A single execution instance of the Pipeline, created by the TriggerTemplate on each qualifying push.
- **Task_Clone**: The first Pipeline task that clones the GitHub repository using the Tekton Catalog `git-clone` task.
- **Task_Build**: The second Pipeline task that compiles the Maven project and produces a JAR artifact.
- **Task_Docker**: The third Pipeline task that builds the Docker image and pushes it to ECR.
- **Task_UpdateTag**: The fourth Pipeline task that patches `k8s/deployment.yaml` with the new image tag and pushes the change to GitHub.
- **ArgoCD**: The GitOps controller installed in the `argocd` namespace that continuously syncs the `k8s/` folder from GitHub to the EKS_Cluster.
- **Application**: The ArgoCD Application resource named `vectro` that tracks the `k8s/` path on the `main` branch.
- **ECR**: Amazon Elastic Container Registry repository at `208073622683.dkr.ecr.us-east-1.amazonaws.com/ibm/vetro`.
- **IRSA**: IAM Roles for Service Accounts — the mechanism by which the Tekton ServiceAccount assumes the `tekton-ecr-push-role` IAM role without static credentials.
- **Tekton_SA**: The Kubernetes ServiceAccount named `tekton-sa` in the `tekton-builds` namespace, annotated with the IRSA role ARN.
- **Git_Short_SHA**: The first 7 characters of the full Git commit SHA, used as the Docker image tag.
- **Deployment_Manifest**: The file `k8s/deployment.yaml` in the GitHub repository, whose `image` field is updated by Task_UpdateTag.
- **ALB_Controller**: The AWS Load Balancer Controller Helm release installed in `kube-system` that provisions internet-facing Application Load Balancers from Kubernetes Ingress resources.
- **Vectro_App**: The Spring Boot application deployed in the `vectro` namespace on EKS_Cluster.
- **Vectro_Secrets**: The Kubernetes Secret named `vectro-secrets` in the `vectro` namespace containing sensitive application credentials.
- **GitHub_Webhook_Secret**: The Kubernetes Secret named `github-webhook-secret` containing the HMAC shared secret used to validate GitHub webhook payloads.
- **GitHub_Token**: The Kubernetes Secret named `github-token` containing a GitHub Personal Access Token with `repo` scope.
- **Shared_Workspace**: The ephemeral PersistentVolumeClaim created per PipelineRun and mounted by all four tasks as a shared file system.

---

## Requirements

### Requirement 1: EKS Cluster Provisioning

**User Story:** As a platform engineer, I want an EKS cluster provisioned with the correct configuration, so that all CI/CD workloads run on a secure, scalable Kubernetes platform.

#### Acceptance Criteria

1. THE EKS_Cluster SHALL be named `vectro-cluster` and provisioned in the `us-east-1` region using Kubernetes version `1.29`.
2. THE EKS_Cluster SHALL have a managed node group with `t3.medium` instances, a minimum of 2 nodes, a maximum of 4 nodes, and an initial desired capacity of 2 nodes placed in private subnets.
3. THE EKS_Cluster SHALL have OIDC federation enabled (`withOIDC: true`) to support IRSA for ServiceAccount-level IAM permissions.
4. THE EKS_Cluster SHALL have the following managed add-ons installed: `vpc-cni`, `coredns`, `kube-proxy`, and `aws-ebs-csi-driver`.
5. THE ALB_Controller SHALL be installed via Helm in the `kube-system` namespace with `clusterName=vectro-cluster` so that Ingress resources provision AWS ALBs.
6. THE EKS_Cluster node IAM role SHALL have the policies `AmazonEKSWorkerNodePolicy`, `AmazonEKS_CNI_Policy`, and `AmazonEC2ContainerRegistryReadOnly` attached to allow nodes to pull images from ECR.

---

### Requirement 2: Tekton Installation and Namespace Setup

**User Story:** As a platform engineer, I want Tekton Pipelines and Triggers installed on the cluster with a dedicated build namespace, so that CI pipeline executions are isolated from other workloads.

#### Acceptance Criteria

1. THE Tekton SHALL have Tekton Pipelines installed in the `tekton-pipelines` namespace.
2. THE Tekton SHALL have Tekton Triggers and Interceptors installed so that GitHub webhook events can create PipelineRuns.
3. THE Tekton SHALL use a dedicated namespace named `tekton-builds` as the execution namespace for all Pipeline, Task, and EventListener resources.
4. THE Tekton_SA SHALL be created in the `tekton-builds` namespace with the annotation `eks.amazonaws.com/role-arn: arn:aws:iam::208073622683:role/tekton-ecr-push-role` to enable IRSA.
5. THE Tekton_SA SHALL be bound via a Role and RoleBinding that grant permissions to manage `taskruns`, `pipelineruns`, `pods`, `pods/log`, `secrets`, `configmaps`, and `persistentvolumeclaims` within the `tekton-builds` namespace.

---

### Requirement 3: IRSA Configuration for ECR Access

**User Story:** As a security engineer, I want Tekton to authenticate to ECR using IRSA, so that no long-lived AWS credentials are stored in the cluster.

#### Acceptance Criteria

1. THE IRSA SHALL have an IAM role named `tekton-ecr-push-role` with a trust relationship that allows `sts:AssumeRoleWithWebIdentity` from the EKS_Cluster OIDC provider scoped to the service account `system:serviceaccount:tekton-builds:tekton-sa`.
2. THE IRSA SHALL attach an inline IAM policy that grants `ecr:GetAuthorizationToken` on all resources and the actions `ecr:BatchCheckLayerAvailability`, `ecr:CompleteLayerUpload`, `ecr:InitiateLayerUpload`, `ecr:PutImage`, `ecr:UploadLayerPart`, `ecr:BatchGetImage`, `ecr:GetDownloadUrlForLayer` on the ECR repository `arn:aws:ecr:us-east-1:208073622683:repository/ibm/vetro`.
3. IF Task_Docker encounters an ECR authentication error for any reason (including annotation mismatch, expired token, or misconfigured IAM trust policy), THEN the Task_Docker SHALL fail only when authentication actually fails at runtime and the PipelineRun SHALL transition to `Failed` state.
4. THE Tekton_SA SHALL NOT use static AWS access key environment variables or Kubernetes Secrets for ECR authentication.

---

### Requirement 4: GitHub Webhook Integration

**User Story:** As a developer, I want a push to the `main` branch of the GitHub repository to automatically trigger the CI pipeline, so that every merge to main results in a new build and deployment.

#### Acceptance Criteria

1. THE EventListener SHALL be deployed in the `tekton-builds` namespace and expose an HTTP endpoint on port `8080` via a Kubernetes Service of type `LoadBalancer`.
2. WHEN a GitHub push event is received, THE EventListener SHALL validate the `X-Hub-Signature-256` HMAC header against the GitHub_Webhook_Secret before processing the event.
3. WHEN a GitHub push event targets a branch other than `refs/heads/main`, THE EventListener SHALL discard the event and SHALL NOT create a PipelineRun.
4. WHEN a GitHub push event targeting `refs/heads/main` passes HMAC validation, THE EventListener SHALL use the TriggerBinding to extract `git-url` from `body.repository.clone_url`, `git-revision` from `body.after`, and `git-short-sha` from the first 7 characters of `body.after`.
5. WHEN the TriggerBinding parameters are extracted, THE TriggerTemplate SHALL create a new ephemeral PersistentVolumeClaim of 1Gi with `ReadWriteOnce` access mode and a new PipelineRun referencing the `vectro-ci-pipeline` and the Shared_Workspace.

---

### Requirement 5: Tekton CI Pipeline — Task Execution

**User Story:** As a developer, I want the CI pipeline to clone the source, build the Maven artifact, build and push the Docker image, and update the deployment manifest, so that each push to main produces a deployable artifact.

#### Acceptance Criteria

1. WHEN a PipelineRun is created, THE Task_Clone SHALL clone the `kampatra/vectro` repository at the specified `git-revision` into the Shared_Workspace using the Tekton Catalog `git-clone` task.
2. WHEN Task_Clone completes successfully, THE Task_Build SHALL execute `mvn clean package -DskipTests --batch-mode` using the `amazoncorretto:21` base image within the Shared_Workspace and produce a JAR file under `target/`.
3. WHEN Task_Build completes successfully, THE Task_Docker SHALL authenticate to ECR using IRSA, build a Docker image from the Shared_Workspace Dockerfile, tag it with both `<Git_Short_SHA>` and `latest`, and push both tags to the ECR repository.
4. WHEN Task_Docker completes successfully, THE Task_UpdateTag SHALL update the `image` field in `k8s/deployment.yaml` to `208073622683.dkr.ecr.us-east-1.amazonaws.com/ibm/vetro:<Git_Short_SHA>` using `sed`.
5. WHEN the Deployment_Manifest is updated, THE Task_UpdateTag SHALL commit the change with message `chore: update image tag to <Git_Short_SHA> [skip ci]` and push it to the `main` branch of the GitHub repository using the GitHub_Token.
6. THE Pipeline SHALL execute all four tasks sequentially: Task_Clone → Task_Build → Task_Docker → Task_UpdateTag.
7. IF any task in the Pipeline fails, THEN the Pipeline SHALL halt and all subsequent tasks SHALL NOT execute, leaving the PipelineRun in `Failed` state.
8. THE Shared_Workspace SHALL be a PersistentVolumeClaim backed by the `aws-ebs-csi-driver` add-on, mounted by all four tasks and deleted after the PipelineRun completes.

---

### Requirement 6: Docker Image Tagging Strategy

**User Story:** As a platform engineer, I want each Docker image tagged with an immutable Git SHA and a mutable `latest` tag, so that deployments are traceable and rollbacks are straightforward.

#### Acceptance Criteria

1. WHEN Task_Docker pushes an image, THE ECR SHALL receive two tags for the same image digest: `<Git_Short_SHA>` (7 characters, immutable per commit) and `latest` (mutable, always points to the most recent build).
2. WHEN Task_UpdateTag updates the Deployment_Manifest, THE Deployment_Manifest image field SHALL reference the `<Git_Short_SHA>` tag, not the `latest` tag, to ensure ArgoCD deploys the exact commit-specific image.
3. THE Git_Short_SHA SHALL be derived by taking the first 7 characters of the full commit SHA received in the GitHub webhook payload; if the derivation logic produces a value longer than 7 characters it SHALL be truncated to exactly 7 characters.

---

### Requirement 7: ArgoCD GitOps Synchronization

**User Story:** As a platform engineer, I want ArgoCD to automatically reconcile the Kubernetes cluster state with the `k8s/` folder in GitHub, so that every merge to main that produces a new image tag results in an automatic deployment without manual intervention.

#### Acceptance Criteria

1. THE Application SHALL be installed in the `argocd` namespace and configured to track the `k8s/` path of the `main` branch at `https://github.com/kampatra/vectro`.
2. THE Application SHALL have automated sync enabled with `prune: true` so that Kubernetes resources deleted from Git are also deleted from the cluster.
3. THE Application SHALL have `selfHeal: true` so that any manual change to cluster resources tracked in Git is automatically reverted within the ArgoCD polling interval.
4. WHEN ArgoCD detects that the Deployment_Manifest image tag in Git differs from the running Deployment, THE Application SHALL apply the updated manifest to the `vectro` namespace on EKS_Cluster.
5. THE Application SHALL use the `CreateNamespace=true` sync option so that the `vectro` namespace is created automatically if it does not exist.
6. THE Application SHALL use the `ApplyOutOfSyncOnly=true` sync option to reduce unnecessary API calls by only applying resources that differ from Git state.
7. THE Application sync retry policy SHALL retry up to 3 times with exponential backoff starting at 10s, doubling each attempt, with a maximum backoff of 1 minute.

---

### Requirement 8: Secret Protection and Credential Management

**User Story:** As a security engineer, I want application secrets never stored in Git and protected from ArgoCD overwrite, so that live credentials are not exposed or accidentally reset.

#### Acceptance Criteria

1. THE Application SHALL have an `ignoreDifferences` rule targeting the `vectro-secrets` Secret in the `vectro` namespace on the `/data` JSON pointer so that ArgoCD does not overwrite live secret data with Git placeholder values.
2. THE Vectro_Secrets SHALL be created manually via `kubectl create secret generic` and SHALL NOT be committed to the Git repository with real credential values.
3. THE GitHub_Webhook_Secret SHALL be created manually in the `tekton-builds` namespace and SHALL NOT be committed to the Git repository.
4. THE GitHub_Token SHALL be created manually in the `tekton-builds` namespace with a Personal Access Token holding `repo` scope and SHALL NOT be committed to the Git repository.
5. IF the Task_UpdateTag step attempts to authenticate with GitHub and the GitHub_Token secret is absent or contains an invalid token, THEN the Task_UpdateTag SHALL fail with an authentication error and the PipelineRun SHALL transition to `Failed` state; if authentication is never attempted (e.g., due to a prior task failure or network issue before the push step), the pipeline outcome SHALL be governed by the failing step rather than the token state.

---

### Requirement 9: Application Deployment Configuration

**User Story:** As a developer, I want the vectro Spring Boot application deployed to Kubernetes with health checks, resource limits, and proper configuration injection, so that the application runs reliably and Kubernetes can manage its lifecycle.

#### Acceptance Criteria

1. THE Vectro_App SHALL run in the `vectro` namespace with a Deployment named `vectro` having `imagePullPolicy: Always` so that each rollout pulls the latest image from ECR.
2. THE Vectro_App container SHALL have resource requests of `250m` CPU and `256Mi` memory, and resource limits of `500m` CPU and `512Mi` memory.
3. THE Vectro_App SHALL have a liveness probe configured as an HTTP GET to `/actuator/health` on port `8080`, with an `initialDelaySeconds` of `45`, `periodSeconds` of `15`, and `failureThreshold` of `3`.
4. THE Vectro_App SHALL have a readiness probe configured as an HTTP GET to `/actuator/health` on port `8080`, with an `initialDelaySeconds` of `30`, `periodSeconds` of `10`, and `failureThreshold` of `3`.
5. THE Vectro_App SHALL receive non-sensitive configuration via a ConfigMap named `vectro-config` and sensitive configuration (AWS credentials, Cognito client secret) via the Vectro_Secrets Secret injected as environment variables.
6. THE Vectro_App container SHALL have a `terminationGracePeriodSeconds` of `30` to allow graceful shutdown before forced termination.

---

### Requirement 10: External Traffic Routing via ALB

**User Story:** As an end user, I want the vectro application accessible over HTTP via an internet-facing AWS ALB, so that I can reach the API without needing direct cluster access.

#### Acceptance Criteria

1. THE ALB_Controller SHALL provision an internet-facing ALB for the `vectro` namespace when an Ingress resource with `ingressClassName: alb` is applied.
2. THE Vectro_App Ingress SHALL route all HTTP traffic on port `80` to the `vectro-svc` Service on port `80` using path type `Prefix` with path `/`, and the ALB_Controller SHALL enforce the `Prefix` path type as declared in the Ingress spec.
3. THE ALB_Controller SHALL configure the ALB health check to target `/actuator/health` with a `15`-second interval, `5`-second timeout, a healthy threshold of `2`, an unhealthy threshold of `3`, and expected HTTP status code `200`.

---

### Requirement 11: Pipeline Failure Handling

**User Story:** As a developer, I want failed pipeline runs to be clearly detectable and recoverable by a new push to main, so that build failures do not leave the system in an inconsistent state.

#### Acceptance Criteria

1. IF the Task_Build step exits with a non-zero code, THEN the Pipeline SHALL terminate with the PipelineRun in `Failed` state and SHALL NOT build or push any Docker image to ECR.
2. IF the Task_Docker step fails due to an ECR authentication error, THEN the Pipeline SHALL terminate with the PipelineRun in `Failed` state and SHALL NOT update the Deployment_Manifest.
3. IF the Task_UpdateTag step fails due to a GitHub authentication error, THEN the Pipeline SHALL terminate with the PipelineRun in `Failed` state and the Deployment_Manifest SHALL remain unchanged in Git.
4. WHEN a PipelineRun transitions to `Failed` state, THE Pipeline logs SHALL be retrievable via `tkn pipelinerun logs -n tekton-builds --last -f` to enable diagnosis.
5. WHEN the developer resolves the failure and pushes to `main`, THE EventListener SHALL trigger a new PipelineRun, providing automatic retry without manual pipeline intervention.

---

### Requirement 12: GitOps Drift Reconciliation

**User Story:** As a platform engineer, I want ArgoCD to automatically detect and correct any drift between the live cluster state and the Git-tracked manifests, so that manual changes to production resources are reverted to maintain GitOps integrity.

#### Acceptance Criteria

1. WHEN a resource in the `vectro` namespace is modified outside of ArgoCD (e.g., manual `kubectl apply`) and `selfHeal: true` is enabled, THE Application SHALL detect the drift within the ArgoCD polling interval and revert the resource to match the Git state.
2. WHEN a resource tracked in the `k8s/` folder is deleted from the Git repository and `prune: true` is enabled, THE Application SHALL delete the corresponding Kubernetes resource from the `vectro` namespace.
3. WHEN the Application sync status is `OutOfSync`, THE Application SHALL attempt to re-sync up to 3 times with exponential backoff before reporting a sync failure.
