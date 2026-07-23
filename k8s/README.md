# Running Smithy-AI on Kubernetes

This directory contains a Helm chart (`helm/smithy`) that runs the same stack as
the `examples/*/docker-compose.yml` files, but on Kubernetes:

| docker-compose            | Kubernetes (this chart)                                   |
| ------------------------- | --------------------------------------------------------- |
| `orchestrator` service    | `Deployment` + `Service` (+ `Ingress`)                    |
| `/var/run/docker.sock`    | Docker-in-Docker **sidecar** in the orchestrator pod      |
| `knowledgebase` service   | `Deployment` + `Service` + `PersistentVolumeClaim`        |
| `caddy` reverse proxy     | `Ingress`                                                 |
| `.env` variables          | `values.yaml` → `ConfigMap` (config) + `Secret` (tokens)  |
| named volumes             | `emptyDir` (task caches) / `PVC` (KB vector store)        |

The orchestrator's Java code is **unchanged**. It still shells out to the
`docker` CLI; the chart just points that CLI at an in-pod Docker engine instead
of the host's.

## Why Docker-in-Docker?

The orchestrator does not run tasks as one-shot Kubernetes Jobs. It
`docker create`s a **long-lived** task container, waits for its init to finish,
then `docker exec`s into it many times and streams state files in and out
(`orchestrator/backend/.../service/docker/ContainerService.java`). That model
maps directly onto a Docker daemon, not onto the Kubernetes Pod API.

Rather than rewrite that subsystem against the Kubernetes API, the chart gives
the orchestrator a Docker daemon it fully controls:

- **`engine.mode: dind`** (default) — a privileged `docker:dind` container runs
  as a [native sidecar](https://kubernetes.io/docs/concepts/workloads/pods/sidecar-containers/)
  in the orchestrator pod. The two containers share the Docker socket through an
  `emptyDir`. Portable to any cluster; requires the pod to run a privileged
  container.
- **`engine.mode: hostSocket`** — mounts the node's `/var/run/docker.sock`
  directly. No privileged sidecar, but only works on nodes whose container
  runtime is Docker (most modern clusters use containerd, so this is the
  exception, not the rule).

### Networking note (important)

Task containers launched by the DinD engine live on a user-defined Docker
network (`config.dockerNetwork`, default `smithy-net`) that the chart creates via
an init container. Because DinD shares the pod's network namespace, outbound
traffic from task containers is SNAT'd to the pod IP, so it can still reach
in-cluster Services and the internet.

DNS, however, goes through Docker's embedded resolver, which does **not** append
the Kubernetes search domain. That is why the chart injects `KNOWLEDGEBASE_URL`
as a fully-qualified name
(`http://<release>-knowledgebase.<namespace>.svc.cluster.local:8000/mcp`) — a
bare `knowledgebase` host would not resolve from inside a task container.

## Requirements

- Kubernetes **1.29+** (native sidecar containers are GA) for `engine.mode: dind`.
- Helm 3.
- For `dind` mode, the target namespace must permit privileged pods. With
  [Pod Security Admission](https://kubernetes.io/docs/concepts/security/pod-security-admission/),
  label the namespace `pod-security.kubernetes.io/enforce=privileged`.
- A default `StorageClass` (or set `knowledgebase.persistence.storageClass`) if
  you enable the knowledgebase.
- An Ingress controller if `ingress.enabled=true`.

## Quick start (GitHub, no knowledgebase)

```bash
kubectl create namespace smithy
kubectl label namespace smithy pod-security.kubernetes.io/enforce=privileged

helm install smithy ./k8s/helm/smithy -n smithy \
  -f k8s/helm/smithy/ci/values-github.example.yaml \
  --set-string secrets.claudeCodeOauthToken="$CLAUDE_CODE_OAUTH_TOKEN" \
  --set-string secrets.smithyGithubToken="$SMITHY_GITHUB_TOKEN" \
  --set-string secrets.architectGithubToken="$ARCHITECT_GITHUB_TOKEN" \
  --set-string secrets.githubWebhookSecret="$GITHUB_WEBHOOK_SECRET"

kubectl -n smithy rollout status deploy/smithy-orchestrator
```

Reach it without an ingress:

```bash
kubectl -n smithy port-forward svc/smithy-orchestrator 8080:8080
# open http://localhost:8080
```

## Full stack (GitLab + knowledgebase)

See `helm/smithy/ci/values-full.example.yaml`:

```bash
helm install smithy ./k8s/helm/smithy -n smithy --create-namespace \
  -f k8s/helm/smithy/ci/values-full.example.yaml \
  --set-string secrets.claudeCodeOauthToken="$CLAUDE_CODE_OAUTH_TOKEN" \
  --set-string secrets.smithyGitlabToken="$SMITHY_GITLAB_TOKEN" \
  --set-string secrets.gitlabWebhookSecret="$GITLAB_WEBHOOK_SECRET" \
  --set-string knowledgebase.config.openaiApiKey="$OPENAI_API_KEY" \
  --set-string knowledgebase.config.vcsToken="$SMITHY_GITLAB_TOKEN"
```

## Configuration reference

Every value maps to an env var the apps already understand (see
`orchestrator/backend/src/main/resources/orchestrator.yml` and
`knowledgebase/src/main/resources/knowledgebase.yml`). The most common knobs:

| Value                                   | Purpose                                              | Default |
| --------------------------------------- | ---------------------------------------------------- | ------- |
| `image.registry` / `image.tag`          | Image source + tag for all components                | `ghcr.io/smithy-ai` / `dev` |
| `config.vcsProvider`                    | `github` \| `gitlab` \| `forgejo`                    | `github` |
| `config.taskImage`                      | Task container image (pulled by the DinD engine)     | `<registry>/claude-task-default:<tag>` |
| `config.dockerNetwork`                  | Docker network created for task DNS                  | `smithy-net` |
| `orchestrator.engine.mode`              | `dind` \| `hostSocket`                               | `dind` |
| `orchestrator.engine.dind.storage`      | Size of the DinD layer store (`emptyDir`)            | `20Gi` |
| `secrets.claudeCodeOauthToken` / `secrets.anthropicApiKey` | Claude auth (supply one)          | — |
| `secrets.existingSecret`                | Reference a pre-created Secret instead of generating one | — |
| `knowledgebase.enabled`                 | Deploy the KB MCP server                             | `false` |
| `knowledgebase.repositories`            | Repos the KB indexes (list of `{name,cloneUrl,branch}`) | `[]` |
| `knowledgebase.persistence.*`           | Vector-store PVC                                     | `5Gi`, cluster default SC |
| `ingress.*`                             | Ingress host / TLS / class                           | disabled |

Full list with comments: [`helm/smithy/values.yaml`](helm/smithy/values.yaml).

### Bring your own Secrets

To keep tokens out of Helm values, create the Secrets yourself and reference
them:

```bash
kubectl -n smithy create secret generic my-smithy-secret \
  --from-literal=CLAUDE_CODE_OAUTH_TOKEN=... \
  --from-literal=SMITHY_GITHUB_TOKEN=...

helm install smithy ./k8s/helm/smithy -n smithy \
  --set secrets.existingSecret=my-smithy-secret
```

The keys in the Secret must match the env var names from
`orchestrator.yml` (e.g. `SMITHY_GITHUB_TOKEN`, `GITHUB_WEBHOOK_SECRET`).
For the knowledgebase, use `knowledgebase.config.existingSecret` with keys
`OPENAI_API_KEY`, `VCS_TOKEN`, `WEBHOOK_SECRET`.

## Operations

```bash
# Logs (orchestrator and its DinD engine)
kubectl -n smithy logs deploy/smithy-orchestrator -c orchestrator -f
kubectl -n smithy logs deploy/smithy-orchestrator -c dind

# List the task containers the orchestrator is running, from inside the engine
kubectl -n smithy exec deploy/smithy-orchestrator -c orchestrator -- \
  docker ps --filter label=smithy.managed=true

# Upgrade after changing values
helm upgrade smithy ./k8s/helm/smithy -n smithy -f my-values.yaml

# Uninstall (task caches in emptyDir are discarded; the KB PVC is retained)
helm uninstall smithy -n smithy
```

## Caveats & limitations

- **Single replica.** The orchestrator owns stateful task containers inside its
  pod, so it must not be scaled horizontally. The chart hard-codes `replicas: 1`
  and a `Recreate` strategy.
- **Task caches are ephemeral.** The `CACHE_VOLUMES` (pnpm/npm/maven/gradle)
  live in the DinD engine's `emptyDir` and are lost when the pod restarts. This
  is a performance optimization, not durable state.
- **Privileged DinD.** `engine.mode: dind` runs a privileged container. If your
  security posture forbids that, switch to `engine.mode: hostSocket` on a
  Docker-runtime node pool, or run this stack on a dedicated node.
- **DinD memory/disk.** Building large task workspaces happens inside the pod.
  Tune `orchestrator.engine.dind.resources` and `orchestrator.engine.dind.storage`
  for your workloads.
