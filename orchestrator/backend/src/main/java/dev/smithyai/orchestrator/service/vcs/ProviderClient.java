package dev.smithyai.orchestrator.service.vcs;

import dev.smithyai.orchestrator.runtime.actions.Capability;
import java.util.Set;

/**
 * A provider that declares what it can do.
 *
 * <p>The optional operations on {@link VcsClient} and {@link IssueTrackerClient}
 * are {@code default} methods that throw, so a workflow needing one used to fail
 * at the moment the step ran — mid-flight, on someone's pull request. Declaring
 * support lets a definition be checked at startup instead.
 *
 * <p>The default is empty rather than a guessed baseline: a provider that has
 * not been audited should fail loudly at load, not quietly claim it can do
 * things nobody checked.
 */
public interface ProviderClient {
    default Set<Capability> capabilities() {
        return Set.of();
    }
}
