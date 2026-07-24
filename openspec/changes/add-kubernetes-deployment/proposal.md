## Why

Smithy-AI currently runs only as a Docker Compose stack: the orchestrator mounts `/var/run/docker.sock` and shells out to the `docker` CLI to spawn long-lived task containers. This ties every deployment to a single Docker host, blocks horizontal scaling and scheduling, and is an anti-pattern (socket mounting) on managed platforms. To run Smithy on real infrastructure we need first-class Kubernetes support: deploy the orchestrator as a workload, and let it launch task workloads as Pods instead of Docker containers.

## What Changes

- **Add Kubernetes deployment manifests** (a Helm chart under `deploy/helm/smithy` plus plain-manifest/kustomize equivalents under `deploy/k8s`) to run the orchestrator on a cluster: `Deployment`, `Service`, `Ingress`, `ConfigMap`, `Secret`, `ServiceAccount` + RBAC. Optional bundled Forgejo and knowledgebase are toggleable.
- **Introduce a pluggable container runtime** behind a `ContainerRuntime` interface. The existing Docker-CLI behavior becomes `DockerContainerRuntime`; a new `KubernetesContainerRuntime` launches each task as a Pod and drives it through the Kubernetes API (create Pod, exec, stream logs, copy files, delete) instead of `docker`.
- **Add a runtime selector** config (`runtime.type: docker|kubernetes`, env `SMITHY_RUNTIME`) so the same image runs unchanged on Docker Compose or Kubernetes.
- **Grant least-privilege RBAC** for the Kubernetes runtime: a namespaced `Role` allowing `pods`, `pods/exec`, `pods/log` create/get/list/delete, bound to the orchestrator `ServiceAccount`.
- **Document** the Kubernetes install path in the README / `docs`, mirroring the existing Compose demo.
- **BREAKING**: none for existing Docker users — Docker stays the default runtime. Kubernetes is opt-in via config.

## Capabilities

### New Capabilities
- `kubernetes-deployment`: Deploying the orchestrator (and optional dependencies) onto a Kubernetes cluster via Helm/manifests — workload, networking, config, secrets, and RBAC.
- `container-runtime`: A pluggable task-container runtime abstraction with Docker and Kubernetes implementations, selected by configuration, so tasks run as Docker containers or Kubernetes Pods with identical orchestrator behavior.

### Modified Capabilities
<!-- No existing OpenSpec specs yet; this is the first change. -->

## Impact

- **Code**: `orchestrator/backend` — new `service/runtime` package (`ContainerRuntime` interface, `DockerContainerRuntime`, `KubernetesContainerRuntime`, Kubernetes client wrapper); `ContainerService`/`ContainerSession` refactored to depend on the interface; new `RuntimeConfig`; Spring conditional wiring. New dependency: Fabric8 `kubernetes-client`.
- **Config**: new `runtime` block in `orchestrator.yml`; env `SMITHY_RUNTIME`, `KUBERNETES_NAMESPACE`, task Pod resource/label settings.
- **Deploy**: new `deploy/helm/smithy` chart and `deploy/k8s` manifests; CI image already published to `ghcr.io/smithy-ai/orchestrator`.
- **Ops**: requires a namespace, ServiceAccount, and RBAC; task Pods scheduled in-cluster reach the VCS Service over cluster networking (replacing `DOCKER_NETWORK`).
- **Docs**: README + `docs` gain a Kubernetes quickstart.
