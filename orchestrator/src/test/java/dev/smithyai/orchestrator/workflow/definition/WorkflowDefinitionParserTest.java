package dev.smithyai.orchestrator.workflow.definition;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WorkflowDefinitionParserTest {

    private final WorkflowDefinitionParser parser = new WorkflowDefinitionParser();

    @Test
    void parsesWorkflowDefinition() {
        var definition = parser.parse("test.yml", validWorkflow("example"));

        assertEquals("example", definition.metadata().name());
        assertEquals("refine", definition.state().getInitial());
        assertEquals("done", definition.state().getTerminal());
        assertTrue(definition.state().getStages().containsKey("refine"));
        assertEquals(2, definition.routing().get(1).event().size());
        assertEquals(
            "agent.run",
            definition.state().getStages().get("refine").on().get("issue.comment").steps().getFirst().uses()
        );
    }

    @Test
    void rejectsUnknownTransitionTarget() {
        var exception = assertThrows(WorkflowDefinitionException.class, () ->
            parser.parse(
                "bad.yml",
                """
                apiVersion: smithy.ai/v1alpha1
                kind: Workflow
                metadata:
                  name: bad
                routing: []
                state:
                  initial: refine
                  refine:
                    on:
                      issue.assigned:
                        to: missing
                        steps:
                          - uses: agent.run
                """
            )
        );

        assertTrue(
            exception
                .errors()
                .stream()
                .anyMatch(error -> error.contains("unknown stage missing"))
        );
    }

    @Test
    void rejectsMissingStepUses() {
        var exception = assertThrows(WorkflowDefinitionException.class, () ->
            parser.parse(
                "bad.yml",
                """
                apiVersion: smithy.ai/v1alpha1
                kind: Workflow
                metadata:
                  name: bad
                routing: []
                state:
                  initial: refine
                  refine:
                    on:
                      issue.assigned:
                        steps:
                          - id: missing-action
                """
            )
        );

        assertTrue(
            exception
                .errors()
                .stream()
                .anyMatch(error -> error.contains(".uses is required"))
        );
    }

    static String validWorkflow(String name) {
        return """
        apiVersion: smithy.ai/v1alpha1
        kind: Workflow
        metadata:
          name: %s
        defaults:
          tools:
            refine: [Read, Write]
        routing:
          - event: issue.assigned
            action: create
            key: "{{ repo.owner }}/{{ repo.name }}"
          - event: [issue.comment, ci.failure]
            action: dispatch
            key: "{{ repo.owner }}/{{ repo.name }}"
        state:
          initial: refine
          terminal: done
          refine:
            on:
              issue.assigned:
                to: build
                steps:
                  - uses: container.init
                    id: init
                    with:
                      branch: "{{ smithy.branch(issue.number, issue.title) }}"
              issue.comment:
                steps:
                  - uses: agent.run
                    if: "{{ true }}"
          build:
            on: {}
          done:
            on: {}
        actions:
          smithy.resume:
            steps:
              - uses: agent.run
        """.formatted(name);
    }
}
