package dev.smithyai.orchestrator.runtime;

import static org.junit.jupiter.api.Assertions.*;

import dev.smithyai.orchestrator.model.IssueContext;
import dev.smithyai.orchestrator.model.RepoInfo;
import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import dev.smithyai.orchestrator.runtime.actions.ActionContext;
import dev.smithyai.orchestrator.runtime.definition.WorkflowDefinitionException;
import dev.smithyai.orchestrator.runtime.definition.WorkflowDefinitionParser;
import dev.smithyai.orchestrator.runtime.definition.WorkflowRoutingAction;
import dev.smithyai.orchestrator.runtime.engine.ExpressionRenderer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DefinitionAndExpressionTest {

    private static final RepoInfo REPO = new RepoInfo("acme", "app", "https://git.invalid/acme/app", "forgejo");

    private final WorkflowDefinitionParser parser = new WorkflowDefinitionParser();
    private final ExpressionRenderer renderer = new ExpressionRenderer();

    private static final String SMITHY_YAML = """
        apiVersion: smithy.ai/v1alpha1
        kind: Workflow
        metadata:
          name: smithy-development
        vars:
          branchPattern: "smithy/{{ event.issueRef }}"
        routing:
          - event: issue.assigned
            when: "{{ true }}"
            action: create
            key: "smithy.{{ repo.owner }}.{{ repo.name }}.{{ event.issueRef }}"
          - event: [issue.commented, issue.plan_approved]
            action: dispatch
            key: "smithy.{{ repo.owner }}.{{ repo.name }}.{{ event.issueRef }}"
        state:
          initial: refine
          terminal: done
          refine:
            on:
              issue.assigned:
                to: build
                steps:
                  - uses: issue.comment
                    id: announce
                    with:
                      owner: "{{ repo.owner }}"
                      repo: "{{ repo.name }}"
                      issue: "{{ event.issueRef }}"
                      body: "Working on {{ event.issueTitle }}"
          build:
            on: {}
          done:
            on: {}
        """;

    private static ActionContext contextFor(WorkflowEvent event, Map<String, Object> vars) {
        return new ActionContext(null, event, Map.of(), vars);
    }

    private static WorkflowEvent.IssueAssigned issueAssigned() {
        return new WorkflowEvent.IssueAssigned(
            new IssueContext(REPO, "7", "Add a thing", "body", "main"),
            "https://git.invalid/acme/app"
        );
    }

    @Test
    void parsesAWorkflowWithRoutingPredicatesAndVars() {
        var definition = parser.parse("smithy.yml", SMITHY_YAML);

        assertEquals("smithy-development", definition.metadata().name());
        assertEquals("smithy/{{ event.issueRef }}", definition.vars().get("branchPattern"));

        var create = definition.routing().getFirst();
        assertTrue(create.matchesName("issue.assigned"));
        assertEquals(WorkflowRoutingAction.create, create.action());
        assertEquals("{{ true }}", create.when(), "routing carries a predicate, not just a name");

        var dispatch = definition.routing().get(1);
        assertTrue(dispatch.matchesName("issue.plan_approved"));
        assertFalse(dispatch.matchesName("pr.merged"));
    }

    @Test
    void readsStatesTransitionsAndSteps() {
        var definition = parser.parse("smithy.yml", SMITHY_YAML);
        var state = definition.state();

        assertEquals("refine", state.getInitial());
        assertEquals("done", state.getTerminal());

        var transition = state.getStages().get("refine").on().get("issue.assigned");
        assertEquals("build", transition.to());
        assertEquals(1, transition.steps().size());

        var step = transition.steps().getFirst();
        assertEquals("issue.comment", step.uses());
        assertEquals("announce", step.id());
        assertEquals("{{ repo.owner }}", step.with().get("owner"));
    }

    @Test
    void rejectsADefinitionWhoseTransitionTargetDoesNotExist() {
        String broken = SMITHY_YAML.replace("to: build", "to: nowhere");

        var error = assertThrows(WorkflowDefinitionException.class, () -> parser.parse("broken.yml", broken));
        assertTrue(error.getMessage().contains("nowhere"), "the error names the missing state: " + error.getMessage());
    }

    @Test
    void rendersStepInputsAgainstTheEventAndRepo() {
        var definition = parser.parse("smithy.yml", SMITHY_YAML);
        var step = definition.state().getStages().get("refine").on().get("issue.assigned").steps().getFirst();

        var rendered = renderer.renderInputs(step.with(), contextFor(issueAssigned(), Map.of()));

        assertEquals("acme", rendered.get("owner"));
        assertEquals("app", rendered.get("repo"));
        assertEquals("7", rendered.get("issue"));
        assertEquals("Working on Add a thing", rendered.get("body"));
    }

    @Test
    void rendersTheRoutingKeyThatIdentifiesARun() {
        var definition = parser.parse("smithy.yml", SMITHY_YAML);
        var rule = definition.routing().getFirst();

        // The same container key the Java factory computes today.
        assertEquals("smithy.acme.app.7", renderer.render(rule.key(), contextFor(issueAssigned(), Map.of())));
    }

    @Test
    void conditionsAreDeliberatelyNarrow() {
        var context = contextFor(issueAssigned(), Map.of("enabled", true));

        assertTrue(renderer.isTruthy(null, context), "no condition means run");
        assertTrue(renderer.isTruthy("{{ vars.enabled }}", context));
        assertFalse(renderer.isTruthy("{{ vars.missing }}", context));
        assertFalse(renderer.isTruthy("maybe", context));
    }

    @Test
    void issueContentIsSubstitutedAsTextNotEvaluatedAsATemplate() {
        // Jira renders {{text}} as monospace, so story bodies routinely carry
        // it. A substituted value is user content: it must come through
        // verbatim, not be parsed as an expression of ours.
        var event = new WorkflowEvent.IssueAssigned(
            new IssueContext(REPO, "7", "A feature", "Go to {{Add to Wait List > Step 2}} and press save", "main"),
            "https://git.invalid/acme/app"
        );
        var context = contextFor(event, Map.of());

        assertEquals(
            "Go to {{Add to Wait List > Step 2}} and press save",
            renderer.render("{{ event.issueBody }}", context)
        );
        assertEquals(
            "Working on: Go to {{Add to Wait List > Step 2}} and press save",
            renderer.render("Working on: {{ event.issueBody }}", context)
        );
    }

    @Test
    void rendersNestedStructuresRecursively() {
        var inputs = Map.<String, Object>of(
            "context",
            Map.of("issue", "{{ event.issueRef }}"),
            "labels",
            List.of("{{ repo.name }}", "static")
        );

        var rendered = renderer.renderInputs(inputs, contextFor(issueAssigned(), Map.of()));

        assertEquals(Map.of("issue", "7"), rendered.get("context"));
        assertEquals(List.of("app", "static"), rendered.get("labels"));
    }

    @Test
    void aVariableCanFallBackToTheEventsOwnConnector() {
        // How a coordinator says "the catalog is on the system the story came
        // through, unless I name another one".
        var context = contextFor(issueAssigned(), Map.of("childConnector", ""));
        assertEquals(
            "forgejo",
            renderer.render("{{ vars.childConnector if vars.childConnector else event.source.id }}", context)
        );

        var named = contextFor(issueAssigned(), Map.of("childConnector", "gitlab"));
        assertEquals(
            "gitlab",
            renderer.render("{{ vars.childConnector if vars.childConnector else event.source.id }}", named)
        );
    }

    @Test
    void aDefinitionCanReadAndFilterOnTheConnectorAnEventCameThrough() {
        var renderer = new ExpressionRenderer();
        var jira = new WorkflowEvent.IssueAssigned(
            new IssueContext(
                new dev.smithyai.orchestrator.model.RepoInfo("PROJ", "PROJ", null, "jira"),
                "PROJ-42",
                "A feature",
                "",
                null
            ),
            null
        );
        var context = new ActionContext(null, jira, java.util.Map.of(), java.util.Map.of());

        assertEquals("jira", renderer.render("{{ event.source.id }}", context));
        assertEquals("jira", renderer.render("{{ event.source.provider }}", context));
        assertEquals("jira", renderer.render("{{ repo.source }}", context));
        // Which is what a routing rule filters on when two systems produce the
        // same event name.
        assertTrue(renderer.isTruthy("{{ event.source.id == 'jira' }}", context));
        assertFalse(renderer.isTruthy("{{ event.source.id == 'gitlab' }}", context));
    }

    @Test
    void aSignalCarriesTheOriginOfWhatTriggeredIt() {
        var renderer = new ExpressionRenderer();
        var signal = new WorkflowEvent.Signal(
            new dev.smithyai.orchestrator.model.RepoInfo("acme", "api", null, "gitlab"),
            "child-done",
            java.util.Map.of("child", "run-1")
        );
        var context = new ActionContext(null, signal, java.util.Map.of(), java.util.Map.of());

        // Filtering a signal must work like filtering anything else.
        assertEquals("gitlab", renderer.render("{{ event.source.id }}", context));
        assertTrue(renderer.isTruthy("{{ event.source.id == 'gitlab' }}", context));
        assertEquals("run-1", renderer.render("{{ event.child }}", context), "payload still reads as fields");
    }
}
