## ADDED Requirements

### Requirement: Orchestrator deployable via Helm chart

The project SHALL provide a Helm chart (`deploy/helm/smithy`) that deploys the orchestrator onto a Kubernetes cluster as a `Deployment` fronted by a `Service`, using the published `ghcr.io/smithy-ai/orchestrator` image. Image repository/tag, replica count, resource requests/limits, service type, and ingress SHALL be configurable through `values.yaml`. The chart SHALL pass `SMITHY_RUNTIME=kubernetes` by default so tasks run as Pods.

#### Scenario: Fresh install renders valid manifests

- **WHEN** an operator runs `helm install smithy deploy/helm/smithy` with a minimal `values.yaml`
- **THEN** the chart renders and applies a Deployment, Service, ServiceAccount, ConfigMap, and Secret without error, and the orchestrator Pod reaches Ready

#### Scenario: Runtime defaults to kubernetes

- **WHEN** the chart is installed with default values
- **THEN** the orchestrator container receives `SMITHY_RUNTIME=kubernetes` and launches task Pods rather than attempting to use a Docker socket

### Requirement: Configuration via ConfigMap and Secret

Non-secret configuration (VCS provider/URLs, runtime type, namespace, task image, knowledgebase settings, bot identities) SHALL be delivered via a `ConfigMap`, and secret values (Claude OAuth token / Anthropic API key, VCS tokens, webhook secret) via a `Secret`. The orchestrator SHALL read these as environment variables matching the existing `orchestrator.yml` placeholders. Secrets SHALL be referenceable from an existing (externally managed) Secret name for users who manage secrets out-of-band.

#### Scenario: Environment mapping matches config placeholders

- **WHEN** the ConfigMap and Secret are mounted as env vars
- **THEN** the orchestrator resolves `FORGEJO_URL`, `SMITHY_FORGEJO_TOKEN`, `CLAUDE_CODE_OAUTH_TOKEN`, `WEBHOOK_SECRET`, `TASK_IMAGE`, and related keys exactly as it does under Docker Compose

#### Scenario: Externally managed secret

- **WHEN** `values.yaml` sets `existingSecret` to a pre-created Secret name
- **THEN** the chart does not template its own Secret and instead wires the Deployment to the named Secret

### Requirement: Least-privilege RBAC for the task runtime

The chart SHALL create a `ServiceAccount` for the orchestrator and a namespaced `Role` + `RoleBinding` granting only the permissions the Kubernetes runtime needs: `create`, `get`, `list`, `watch`, and `delete` on `pods`, `pods/log`, and `create` on `pods/exec` within the orchestrator's namespace. The orchestrator Deployment SHALL run under this ServiceAccount.

#### Scenario: Runtime can manage task Pods

- **WHEN** the orchestrator, running under its ServiceAccount, creates, execs into, reads logs from, and deletes a task Pod
- **THEN** all operations succeed within the configured namespace

#### Scenario: No cluster-wide privileges granted

- **WHEN** an operator audits the chart's RBAC
- **THEN** only a namespaced Role (no ClusterRole) is created, scoped to pod lifecycle, exec, and logs

### Requirement: External access via Ingress

The chart SHALL optionally create an `Ingress` (toggleable, with configurable host, class, TLS, and annotations) exposing the orchestrator's port 8080 so VCS webhooks and the dashboard are reachable from outside the cluster. When ingress is disabled the Service SHALL still allow in-cluster and port-forward access.

#### Scenario: Ingress exposes webhook and dashboard

- **WHEN** ingress is enabled with a host and TLS
- **THEN** external webhook POSTs and dashboard requests reach the orchestrator Service on port 8080 over the configured host

### Requirement: Optional bundled dependencies

The chart SHALL optionally deploy a Forgejo instance and the knowledgebase MCP server as in-cluster workloads (each toggleable, default off for Forgejo since production users bring their own VCS), with persistent storage for Forgejo data and the knowledgebase vector store via `PersistentVolumeClaim`s. When disabled, the orchestrator SHALL be configured to point at externally provided URLs.

#### Scenario: Knowledgebase enabled with persistence

- **WHEN** `knowledgebase.enabled=true`
- **THEN** the chart deploys the knowledgebase Deployment, Service, and a PVC for its vector store, and sets `KNOWLEDGEBASE_ENABLED=true` and `KNOWLEDGEBASE_URL` on the orchestrator

#### Scenario: Bring-your-own VCS

- **WHEN** Forgejo is disabled and `vcs.forgejo.url` points to an external instance
- **THEN** the orchestrator uses the external VCS and no Forgejo workload is created

### Requirement: Plain-manifest install path

In addition to the Helm chart, the project SHALL provide plain Kubernetes manifests (kustomize base under `deploy/k8s`) equivalent to a default chart render, plus documented quickstart steps, so operators who do not use Helm can deploy with `kubectl apply -k`.

#### Scenario: Kustomize apply deploys the orchestrator

- **WHEN** an operator edits the kustomize secret/config inputs and runs `kubectl apply -k deploy/k8s`
- **THEN** the orchestrator, its Service, ServiceAccount, RBAC, ConfigMap, and Secret are created and the orchestrator becomes Ready
