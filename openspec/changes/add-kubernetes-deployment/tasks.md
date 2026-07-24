## 1. Container runtime abstraction (refactor, no behavior change)

- [x] 1.1 Create `service/runtime` package and a `ContainerRuntime` interface exposing the operations `ContainerSession` and consumers use: `createSession`, `create`, `exec`, `destroy`, `fetchLogs`, `fetchOwnLogs`, `fetchSessionTranscript`, `copyToContainer`, `copyFromContainer`, `readState`, `writeState`, `readStateSafe`, `containerExists`, `isManagedContainer`, `listManagedContainers`.
- [x] 1.2 Move runtime-neutral DTOs (`ContainerConfig`, `ContainerState`, `ExecResult`, `WorkflowType`) so both implementations share them; keep imports compiling.
- [x] 1.3 Change `ContainerSession` to hold a `ContainerRuntime` instead of the concrete `ContainerService`; adjust its delegating calls.
- [x] 1.4 Rename/convert the existing `ContainerService` into `DockerContainerRuntime implements ContainerRuntime` (body unchanged; keep `DockerCli` as its private dependency). Annotate so it is only wired when `runtime.type=docker`.
- [x] 1.5 Update consumers (`WorkflowService`, `DashboardController`, `SmithyWorkflowFactory`, `ArchitectReviewFactory`, `ArchitectLearnFactory`, and any `ContainerService`/`ContainerSession` importers) to inject `ContainerRuntime`.
- [x] 1.6 Compile backend (`./gradlew :backend:compileJava`) and run existing tests to confirm no behavior change with the Docker runtime.

## 2. Runtime configuration & selection

- [x] 2.1 Add a `runtime` block to `orchestrator.yml` (`type: ${SMITHY_RUNTIME:docker}`, plus a `kubernetes` sub-block: namespace, task service account, cpu/memory requests+limits, image-pull-secret, cache-persistence toggle).
- [x] 2.2 Add `RuntimeConfig` record + nested `KubernetesRuntimeConfig`; expose a `RuntimeConfig` bean from `ConfigLoader` and add it to `SmithyConfig`.
- [x] 2.3 Add a `RuntimeConfiguration` class that constructs exactly one `ContainerRuntime` bean based on `runtime.type`, failing fast with a clear message on unknown values (`docker` default).

## 3. Kubernetes runtime implementation

- [x] 3.1 Add `io.fabric8:kubernetes-client` (pin a JDK 21+ compatible 7.x) to `backend/build.gradle.kts`; provide an in-cluster `KubernetesClient` bean (only when `runtime.type=kubernetes`).
- [x] 3.2 Implement `KubernetesContainerRuntime implements ContainerRuntime`: `create` builds and creates a Pod (task image, `smithy-init`, `restartPolicy: Never`, labels `smithy.managed=true` / `smithy.workflow=<type>`, env from `ContainerConfig`, `automountServiceAccountToken: false`, optional imagePullSecret, cache volumes as PVC/emptyDir per config).
- [x] 3.3 Implement `exec` via Fabric8 `ExecWatch` (working dir `/workspace`, env injection, stdin, timeout via latch) returning `ExecResult` with stdout/stderr/exit code.
- [x] 3.4 Implement `fetchLogs`/`fetchOwnLogs` via pod log tailing; implement `copyToContainer`/`copyFromContainer` and `readState`/`writeState`/`readStateSafe` via `exec cat`/`exec 'cat > path'` reusing the existing byte-transfer + quoting approach.
- [x] 3.5 Implement `containerExists`, `isManagedContainer`, `listManagedContainers`, `destroy`, and init-wait (`/tmp/smithy-init-done`/`/tmp/smithy-init-failed` polling + Pod-phase/`ImagePullBackOff` detection with recent logs on failure).
- [x] 3.6 Add a unit test for `KubernetesContainerRuntime` using the Fabric8 mock server (Pod create, list-by-label, destroy; exec/log where mock supports it).

## 4. Helm chart (`deploy/helm/smithy`)

- [x] 4.1 Scaffold chart (`Chart.yaml`, `values.yaml`, `_helpers.tpl`) with image repo/tag defaulting to `ghcr.io/smithy-ai/orchestrator`, replicas, resources, and `SMITHY_RUNTIME=kubernetes` default.
- [x] 4.2 Template `Deployment` (env from ConfigMap + Secret, ServiceAccount, probes on `/api/health`, port 8080) and `Service`.
- [x] 4.3 Template `ServiceAccount` + namespaced `Role` + `RoleBinding` granting `pods`/`pods/log` (get/list/watch/create/delete) and `pods/exec` (create) only.
- [x] 4.4 Template `ConfigMap` (non-secret config: VCS URLs/provider, runtime, namespace, task image, knowledgebase, bots) and `Secret` (Claude/VCS tokens, webhook secret) with `existingSecret` override support.
- [x] 4.5 Template optional `Ingress` (host, class, TLS, annotations) exposing port 8080.
- [x] 4.6 Template optional Forgejo (default off) and knowledgebase (default off) workloads + Services + PVCs, and wire the orchestrator to their in-cluster URLs when enabled or external URLs when disabled.
- [x] 4.7 `helm lint` and `helm template` render cleanly with default and knowledgebase-enabled values.

## 5. Plain manifests (`deploy/k8s`) & validation

- [x] 5.1 Provide a kustomize base equivalent to a default chart render (namespace, ServiceAccount, RBAC, ConfigMap, Secret placeholder, Deployment, Service).
- [x] 5.2 Validate with `kubectl apply --dry-run=server -k deploy/k8s` (or client dry-run where no cluster is available).

## 6. Docs & smoke test

- [x] 6.1 Add a Kubernetes quickstart to `README.md` and `docs` (install via Helm and via kustomize, required secrets, runtime selection, RBAC notes).
- [ ] 6.2 Smoke-test on kind/minikube: install the chart, trigger one task, confirm a `smithy.managed=true` task Pod is created, exec'd, and cleaned up; capture and document any gaps.
