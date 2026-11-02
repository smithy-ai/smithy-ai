package dev.smithyai.orchestrator.runtime.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.smithyai.orchestrator.model.PrContext;
import dev.smithyai.orchestrator.model.RepoInfo;
import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import dev.smithyai.orchestrator.runtime.definition.WorkflowDefinitionParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkflowRouterTest {

    private static final String AUTHOR = """
        apiVersion: smithy.ai/v1alpha1
        kind: Workflow
        metadata: {name: author}
        vars: {actor: smithy}
        routing:
          - event: pr.commented
            action: dispatch
            key: "{{ repo.fullName }}!{{ event.prNumber }}"
        state: {initial: new, terminal: done, new: {on: {}}, done: {on: {}}}
        """;
    private static final String REVIEWER = AUTHOR.replace("name: author", "name: reviewer").replace(
        "actor: smithy",
        "actor: architect"
    );

    private static PrContext prContext() {
        return new PrContext(
            new RepoInfo("acme", "app", "https://git.invalid/acme/app"),
            41,
            "A change",
            "",
            false,
            "feature/x",
            "main"
        );
    }

    @Test
    void aCommentIsNotRoutedToTheActorThatWroteIt() {
        var parser = new WorkflowDefinitionParser();
        var definitions = List.of(parser.parse("author.yml", AUTHOR), parser.parse("reviewer.yml", REVIEWER));
        var router = new WorkflowRouter(new ExpressionRenderer());
        var prc = prContext();

        var byTheAuthorAgent = router.route(
            new WorkflowEvent.PrConversationComment(prc, "smithy-bot", "done", 1, "d1", "smithy"),
            definitions
        );
        assertEquals(
            List.of("reviewer"),
            byTheAuthorAgent.stream().map(WorkflowRouter.Decision::workflowName).toList()
        );

        var byAHuman = router.route(
            new WorkflowEvent.PrConversationComment(prc, "b.human", "why?", 2, "d1", ""),
            definitions
        );
        assertEquals(
            List.of("author", "reviewer"),
            byAHuman.stream().map(WorkflowRouter.Decision::workflowName).toList()
        );
    }

    @Test
    void aDefinitionWithoutAnActorIsTheDefaultActor() {
        var noActor = new WorkflowDefinitionParser().parse("plain.yml", AUTHOR.replace("vars: {actor: smithy}\n", ""));
        var decisions = new WorkflowRouter(new ExpressionRenderer()).route(
            new WorkflowEvent.PrConversationComment(prContext(), "smithy-bot", "done", 1, "d1", "smithy"),
            List.of(noActor)
        );
        assertTrue(decisions.isEmpty());
    }
}
