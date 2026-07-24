## ADDED Requirements

### Requirement: Pluggable container runtime abstraction

The orchestrator SHALL drive task containers through a `ContainerRuntime` interface rather than calling the Docker CLI directly. The interface SHALL expose the operations the workflow layer needs: create a task container, exec a command (with optional stdin, environment, timeout), fetch logs, copy files in and out, read/write task state, list managed containers, check existence, and destroy a container. `ContainerService` and `ContainerSession` SHALL depend only on this interface.

#### Scenario: Workflow code is runtime-agnostic

- **WHEN** a workflow creates a session and execs commands against a task container
- **THEN** it calls `ContainerRuntime` methods without referencing Docker- or Kubernetes-specific types, and behaves identically regardless of the selected runtime

#### Scenario: Docker behavior preserved as an implementation

- **WHEN** the runtime type is `docker`
- **THEN** the `DockerContainerRuntime` implementation performs the same `docker create/start/exec/logs/stop/rm` operations the current `ContainerService` performs, with no observable behavior change for existing Docker Compose users

### Requirement: Runtime selection by configuration

The orchestrator SHALL select the active runtime from configuration key `runtime.type` (env `SMITHY_RUNTIME`), accepting `docker` or `kubernetes`, defaulting to `docker`. Exactly one runtime implementation SHALL be wired into the application context at startup.

#### Scenario: Default is docker

- **WHEN** `runtime.type` / `SMITHY_RUNTIME` is unset
- **THEN** the orchestrator starts with the Docker runtime active

#### Scenario: Kubernetes runtime selected

- **WHEN** `SMITHY_RUNTIME=kubernetes`
- **THEN** the orchestrator wires `KubernetesContainerRuntime` and no Docker CLI invocation occurs at runtime

#### Scenario: Invalid runtime rejected

- **WHEN** `runtime.type` is set to an unsupported value
- **THEN** the application fails fast at startup with a clear error message naming the accepted values

### Requirement: Kubernetes task containers run as Pods

When the Kubernetes runtime is active, each task container SHALL be created as a Kubernetes Pod in a configured namespace, running the configured task image with the `smithy-init` entrypoint and the same environment variables (VCS URL/token, clone URL, branch, Claude credentials, git identity, extra repos) the Docker runtime passes. The Pod SHALL carry the label `smithy.managed=true` and a `smithy.workflow=<type>` label so it can be discovered and reconciled.

#### Scenario: Task Pod created and reachable

- **WHEN** a workflow starts a task under the Kubernetes runtime
- **THEN** a Pod is created in the configured namespace with the task image, the `smithy.managed=true` label, and the task environment, and it can reach the VCS service over in-cluster networking

#### Scenario: Init completion is awaited

- **WHEN** a task Pod is created
- **THEN** the runtime waits for the init-success marker (`/tmp/smithy-init-done`) via exec, and on the init-failure marker (`/tmp/smithy-init-failed`) or premature Pod termination it raises an error including recent Pod logs

### Requirement: Kubernetes exec, logs, and file transfer

The Kubernetes runtime SHALL implement command execution, log retrieval, and file copy against task Pods using the Kubernetes API (exec/attach and log endpoints), preserving the semantics of the Docker implementation: exec runs in the `/workspace` working directory, supports environment overrides, stdin, and timeouts, and returns stdout, stderr, and an exit code; state is read/written as `/tmp/smithy-state.json` inside the Pod.

#### Scenario: Exec returns captured output and exit code

- **WHEN** a command is exec'd in a task Pod with an environment map and a timeout
- **THEN** the runtime returns an `ExecResult` with stdout, stderr, and the process exit code, and enforces the timeout

#### Scenario: State round-trips through the Pod

- **WHEN** the orchestrator writes task state and later reads it back
- **THEN** the state is persisted to `/tmp/smithy-state.json` in the Pod and deserializes to the same `ContainerState`

### Requirement: Managed Pod discovery and cleanup

The Kubernetes runtime SHALL list, identify, and destroy managed task Pods by the `smithy.managed=true` label selector, mirroring the Docker runtime's `listManagedContainers` / `isManagedContainer` / `destroy` behavior so orphaned tasks can be reconciled on startup.

#### Scenario: Orphaned task Pods reconciled

- **WHEN** the orchestrator starts and queries managed containers
- **THEN** it receives the names of all Pods labeled `smithy.managed=true` in the namespace and can destroy them by deleting the Pod
