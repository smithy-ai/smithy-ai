## Context

The orchestrator (`orchestrator/backend`, Java 25 / Spring Boot 4 / Gradle 9) runs task work by shelling out to the `docker` CLI. `DockerCli` wraps `ProcessBuilder`; `ContainerService` builds `docker create/start/exec/logs/stop/rm` argument lists and drives the task-container lifecycle; `ContainerSession` is the per-task handle that workflow code holds. Task containers are **long-lived** — created with `smithy-init`, kept running, and repeatedly `exec`'d into. State lives in `/tmp/smithy-state.json` inside the container; there is no database. On startup `WorkflowService.recoverInstances()` lists `smithy.managed=true` containers and rebuilds in-memory state from each container's file.

Deployment today is Docker Compose only (`examples/*/docker-compose.yml`), and the orchestrator container mounts `/var/run/docker.sock`. There is no runtime abstraction — Docker is hardcoded across `service/docker`. Consumers (`WorkflowService`, `DashboardController`, the workflow factories) depend on the concrete `ContainerService` and `ContainerSession`.

We want to run Smithy on Kubernetes: the orchestrator as a Deployment, and each task as a **Pod** launched through the Kubernetes API — no Docker socket, no `docker` CLI in the runtime image path.

## Goals / Non-Goals

**Goals:**
- Deploy the orchestrator on Kubernetes via a Helm chart (and equivalent kustomize manifests) with config, secrets, RBAC, Service, and optional Ingress.
- Introduce a `ContainerRuntime` abstraction so the workflow layer is runtime-agnostic; keep Docker as the default, add a Kubernetes implementation that launches task Pods and drives them via the Kubernetes exec/log/API.
- Preserve the existing long-lived-container + `/tmp/smithy-state.json` + `smithy.managed=true` recovery model unchanged, just backed by Pods.
- Zero behavior change for existing Docker Compose users (Docker remains default).

**Non-Goals:**
- Rewriting the state model to use a database, CRDs, or an operator. The Pod-with-state-file model is kept.
- Autoscaling, multi-tenant isolation policies, NetworkPolicies beyond a sane default, or GitOps packaging (Argo/Flux). Left to operators.
- Replacing the bundled Forgejo/runner for production; they remain optional dev conveniences.
- Migrating the Compose examples away from Docker.

## Decisions

### D1: Abstract at `ContainerService`, not at `DockerCli`
`DockerCli.run(List<String> args)` is inherently Docker-CLI-shaped, so the clean seam is one level up. We extract a `ContainerRuntime` interface capturing the operations `ContainerSession` and the consumers actually use:

```
createSession(name) -> ContainerSession
create(name, ContainerConfig)         // package→interface
exec(name, cmd, env, timeout, stdin) -> ExecResult
destroy(name)
fetchLogs(name, tail) / fetchOwnLogs(tail)
fetchSessionTranscript(name, sessionId)
copyToContainer / copyFromContainer
readState / writeState / readStateSafe
containerExists / isManagedContainer / listManagedContainers
```

`ContainerSession` is moved to depend on `ContainerRuntime` (constructor takes the interface). The current `ContainerService` becomes `DockerContainerRuntime implements ContainerRuntime` with essentially the same body. Consumers change their injected type from `ContainerService` to `ContainerRuntime` — a mechanical, low-risk edit. The DTOs (`ContainerConfig`, `ContainerState`, `ExecResult`, `ContainerState`) are runtime-neutral and move under `service/runtime/dto` (or stay, with the interface importing them).

_Alternative considered_: implement a `KubernetesCli` that emulates `docker` arg strings behind `DockerCli`. Rejected — brittle string-matching, and it wouldn't remove the `docker` binary assumption.

### D2: Fabric8 Kubernetes client for the Kubernetes runtime
Use `io.fabric8:kubernetes-client` for `KubernetesContainerRuntime`. It provides in-cluster config auto-detection (ServiceAccount token), typed Pod builders, `pods().withName(x).exec(...)` with stdin/stdout/stderr streams and exit-code listener, log tailing, and file upload/download — a close match to the Docker operations. In-cluster the client authenticates automatically via the mounted ServiceAccount.

_Alternative considered_: official `io.kubernetes:client-java`. More verbose exec/copy ergonomics; Fabric8's `ExecWatch` + `writingOutput`/`redirectingInput` maps more directly onto our synchronous `ExecResult` contract.

### D3: Map Docker concepts → Kubernetes
- **container create/start** → build and `create` a `Pod` (single container, task image, `smithy-init` command). No Deployment/Job — we need a stable, long-lived, exec-able Pod matching the current model, and `restartPolicy: Never` so a failed init surfaces instead of crash-looping.
- **`--network forgejo-net`** → Kubernetes cluster networking; task Pods reach the VCS via its Service DNS name. `runtime.network` is unused for Kubernetes; VCS URL comes from config.
- **`-v cache-pnpm:/path`** → per-cache `PersistentVolumeClaim`s (RWX where the platform supports it) or `emptyDir` when persistence is disabled. `values.yaml` toggles cache persistence and storage class.
- **labels** → Pod `metadata.labels` (`smithy.managed=true`, `smithy.workflow=<type>`); discovery uses a label selector.
- **`docker exec`** → Fabric8 `PodResource.redirectingInput().writingOutput(...).writingError(...).withTTY(false).exec(cmd)`, working dir `/workspace` (prefixed via `sh -c 'cd /workspace && …'` since the exec API has no cwd option), env injected by wrapping the command, timeout enforced by awaiting the `ExecWatch` latch.
- **`docker logs`** → `pods().withName(x).tailingLines(n).getLog()`.
- **copy files** → write via exec (`sh -c 'cat > path'` with stdin, as today) to avoid tar-based `cp` edge cases; read via `exec cat`. This reuses the exact byte-transfer approach already proven in `ContainerService`.
- **init wait** → poll for `/tmp/smithy-init-done` / `/tmp/smithy-init-failed` via exec, same as Docker, plus Pod-phase check (`Failed`/`Succeeded` ⇒ abort with logs).

### D4: Runtime selection via config + Spring conditional wiring
Add a `runtime` block to `orchestrator.yml`:
```yaml
runtime:
  type: ${SMITHY_RUNTIME:docker}      # docker | kubernetes
  kubernetes:
    namespace: ${KUBERNETES_NAMESPACE:smithy}
    task-service-account: ${TASK_SERVICE_ACCOUNT:}
    task-cpu-request / task-memory-request / limits ...
    image-pull-secret: ${TASK_IMAGE_PULL_SECRET:}
```
`ConfigLoader` exposes a `RuntimeConfig` bean. Wire exactly one `ContainerRuntime` with `@ConditionalOnProperty`-style selection driven by the resolved `runtime.type` (implemented via a `@Bean` factory in a `RuntimeConfiguration` that reads `RuntimeConfig` and constructs the right impl, failing fast on unknown values). Default `docker` keeps Compose users unaffected.

### D5: Deployment packaging — Helm chart primary, kustomize mirror
`deploy/helm/smithy` is the primary artifact (templated Deployment, Service, ServiceAccount, Role+RoleBinding, ConfigMap, Secret, optional Ingress, optional Forgejo + knowledgebase with PVCs). `deploy/k8s` is a kustomize base equivalent to a default render for non-Helm users. The chart defaults `SMITHY_RUNTIME=kubernetes`. RBAC is a **namespaced Role** (no ClusterRole): `pods`, `pods/log` (get/list/watch/create/delete) and `pods/exec` (create). README + `docs` get a Kubernetes quickstart.

## Risks / Trade-offs

- **[Fabric8 / Java 25 / Spring Boot 4 compatibility]** → Pin a recent Fabric8 (7.x) known to run on JDK 21+; the client is plain and does not depend on Spring. Verify with `./gradlew :backend:compileJava` and a smoke unit test using the mock server if feasible.
- **[Exec working-directory & env semantics differ from `docker exec -w -e`]** → Wrap commands as `sh -lc 'cd /workspace && exec "$@"'`-style with env exported, and cover with a unit test against the Fabric8 mock server / kind in CI. Risk of shell-quoting bugs; mitigated by reusing the existing single-quote escaping helper.
- **[Task Pod cannot pull image / no pull secret]** → Surface Pod `Waiting` reason (`ImagePullBackOff`) during init-wait and fail with a clear message; expose `imagePullSecret` in config and chart.
- **[Ephemeral Pod state on eviction]** → Same failure mode as Docker today (container loss loses `/tmp` state); recovery already tolerates missing/unreadable state (`readStateSafe`). No regression; documented as a known limitation.
- **[RBAC too broad if misused]** → Ship namespaced Role only; document that the orchestrator and its task Pods should live in a dedicated namespace.
- **[Socket-mount habit]** → Chart must NOT mount `/var/run/docker.sock`; the Kubernetes runtime never calls `docker`. Guard by keeping the `docker` CLI out of the Kubernetes code path (it stays only in `DockerContainerRuntime`).

## Migration Plan

1. Land the `ContainerRuntime` refactor with Docker as the sole implementation — no behavior change; existing tests + Compose demo stay green.
2. Add Fabric8 dependency, `KubernetesContainerRuntime`, `RuntimeConfig`, and conditional wiring; default remains `docker`.
3. Add `deploy/helm/smithy` + `deploy/k8s` + docs.
4. Validate: `helm template` / `helm lint`, `kubectl apply --dry-run=server -k deploy/k8s`, backend compile + unit tests, and a manual kind/minikube smoke run of a single task Pod.
5. **Rollback**: set `SMITHY_RUNTIME=docker` (or redeploy the Compose stack). The Kubernetes path is additive and opt-in; reverting is config-only.

## Open Questions

- Cache volumes on Kubernetes: RWX PVC vs per-task `emptyDir`? Default to `emptyDir` (no cross-task cache) unless a storage class is provided — revisit if cache reuse proves important.
- Should task Pods get their own restricted ServiceAccount (no API access)? Default yes: tasks run under a token-less/`automountServiceAccountToken: false` Pod to avoid granting them cluster access.
