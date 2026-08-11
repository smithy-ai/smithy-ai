package dev.smithyai.orchestrator.runtime.definition;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Decides what an event does to a workflow's runs.
 *
 * @param event event names this rule matches, e.g. {@code issue.assigned}
 * @param when  an optional predicate over the event context. Matching on the
 *              name alone gave no way to say "this workflow handles issues in
 *              <em>this</em> repository", so two definitions listening on the
 *              same event both fired with no arbitration.
 * @param key   template resolving the run this event belongs to
 * @param by    resolve the run through a correlation instead of a key template:
 *              {@code pr}, {@code issue}, {@code branch} or {@code container}.
 *              A pull-request event carries no issue reference, and deriving one
 *              by parsing the branch name is what put a flow's naming convention
 *              inside the provider adapters. The run recorded what it opened;
 *              this looks it up.
 */
public record WorkflowRoutingRule(
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> event,
    @JsonProperty("when") String when,
    WorkflowRoutingAction action,
    String key,
    String by
) {
    public List<String> event() {
        return event != null ? event : List.of();
    }

    public boolean matchesName(String eventName) {
        return event().contains(eventName);
    }
}
