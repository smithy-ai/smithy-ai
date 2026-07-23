# Design notes — Kubernetes setup

These are the notes from building this deployment: the reasoning, the structure,
what was verified, and the caveats worth knowing before you run it.

## What this is

A Kustomize deployment under `deploy/k8s/` — the Kubernetes equivalent of
`examples/full/docker-compose.yml`.

## The key design decision: Docker-in-Docker sidecar

The orchestrator doesn't just run itself — for every task it **creates, execs into,
and destroys Docker containers** through the `docker` CLI (`ContainerService` /
`DockerCli` in the backend). In docker-compose that works via the bind-mounted
`/var/run/docker.sock`.

Rather than rewrite the Java to talk to the Kubernetes API, the existing code is left
untouched and the orchestrator gets a **Docker-in-Docker (dind) sidecar** in the same
pod. The orchestrator's `docker` CLI points at it via `DOCKER_HOST=tcp://127.0.0.1:2375`,
and task containers run inside dind — so this works on any cluster regardless of the
node's container runtime (containerd, CRI-O, etc.).

## Structure

- `base/` — orchestrator Deployment (app + privileged dind sidecar), Service, Ingress
  (replaces Caddy), a PVC for dind storage, and the namespace (labelled
  `pod-security.kubernetes.io/enforce: privileged` so dind is allowed).
- `components/knowledgebase/` — optional add-on as a Kustomize component (Deployment,
  Service, Ingress, PVC, the `knowledgebase.yml` config), mirroring the compose
  `knowledgebase` profile. Enabled by uncommenting one line.
- `secrets/` — `.env`-style templates that map 1:1 to the compose `.env`: non-secret →
  ConfigMap, tokens/keys → Secret, both injected via `envFrom` with content-hash
  suffixes (edit + re-apply = automatic rolling restart).
- `README.md` — quickstart, the compose→k8s mapping table, webhook/dashboard URLs,
  operational notes, and a documented non-privileged `hostPath` fallback.

## Verified

- `kubectl kustomize .` renders 7 resources (default) / 13 (with knowledgebase).
- ConfigMap/Secret hash refs propagate into `envFrom` for both workloads.
- `kubectl apply --dry-run=client` passes schema validation on every resource.
- The build correctly **fails closed** until you create your env files, and real
  secret files are git-ignored (only `*.example` templates are tracked).

## Caveats

- **dind runs `privileged`.** The namespace is labelled to allow it. If your cluster
  forbids privileged pods, use the `hostPath` docker-socket fallback documented in
  `README.md` (only works on Docker-runtime nodes).
- **Restarting the orchestrator pod discards running task containers** (they live in
  dind). This differs from compose, where restarting the orchestrator left task
  containers alive. The orchestrator recovers in-flight work on startup by re-listing
  containers labelled `smithy.managed=true`.
- **Single replica, `Recreate` strategy** — only one dind may own the PVC and the
  managed task containers at a time. The orchestrator itself is stateless.
