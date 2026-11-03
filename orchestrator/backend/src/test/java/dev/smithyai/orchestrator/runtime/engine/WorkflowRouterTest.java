package dev.smithyai.orchestrator.runtime.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.smithyai.orchestrator.config.DefaultsConfig;
import dev.smithyai.orchestrator.config.OrchestratorConfig;
import dev.smithyai.orchestrator.model.CommentData;
import dev.smithyai.orchestrator.model.PrContext;
import dev.smithyai.orchestrator.model.RepoInfo;
import dev.smithyai.orchestrator.model.events.WorkflowEvent;
import dev.smithyai.orchestrator.runtime.definition.WorkflowDefinitionParser;
import java.util.List;
import java.util.Map;
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
    private static final String COORDINATOR = AUTHOR.replace("name: author", "name: coordinator").replace(
        "actor: smithy",
        "actor: coordinator"
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
            new WorkflowEvent.PrConversationComment(prc, "smithy-bot", "done", 1, "d1", List.of("smithy")),
            definitions
        );
        assertEquals(
            List.of("reviewer"),
            byTheAuthorAgent.stream().map(WorkflowRouter.Decision::workflowName).toList()
        );

        var byAHuman = router.route(
            new WorkflowEvent.PrConversationComment(prc, "b.human", "why?", 2, "d1", List.of()),
            definitions
        );
        assertEquals(
            List.of("author", "reviewer"),
            byAHuman.stream().map(WorkflowRouter.Decision::workflowName).toList()
        );
    }

    @Test
    void aCommentByAnAccountSeveralActorsShareIsRoutedToNoneOfThem() {
        var parser = new WorkflowDefinitionParser();
        var definitions = List.of(
            parser.parse("author.yml", AUTHOR),
            parser.parse("coordinator.yml", COORDINATOR),
            parser.parse("reviewer.yml", REVIEWER)
        );
        var decisions = new WorkflowRouter(new ExpressionRenderer()).route(
            new WorkflowEvent.PrConversationComment(
                prContext(),
                "shared-bot",
                "done",
                1,
                "d1",
                List.of("smithy", "coordinator")
            ),
            definitions
        );
        assertEquals(List.of("reviewer"), decisions.stream().map(WorkflowRouter.Decision::workflowName).toList());
    }

    @Test
    void aReviewCommentIsNotRoutedToTheActorThatWroteIt() {
        var parser = new WorkflowDefinitionParser();
        var onReviewComments = AUTHOR.replace("event: pr.commented", "event: pr.review_commented");
        var definitions = List.of(
            parser.parse("author.yml", onReviewComments),
            parser.parse(
                "reviewer.yml",
                onReviewComments.replace("name: author", "name: reviewer").replace("actor: smithy", "actor: architect")
            )
        );
        var router = new WorkflowRouter(new ExpressionRenderer());
        var comments = List.of(new CommentData("architect-bot", "please fix", "src/App.java", 12));

        var byTheReviewer = router.route(
            new WorkflowEvent.PrReviewComment(prContext(), comments, 1, "d1", List.of("architect")),
            definitions
        );
        assertEquals(List.of("author"), byTheReviewer.stream().map(WorkflowRouter.Decision::workflowName).toList());

        var byAHuman = router.route(
            new WorkflowEvent.PrReviewComment(prContext(), comments, 2, "d1", List.of()),
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
            new WorkflowEvent.PrConversationComment(prContext(), "smithy-bot", "done", 1, "d1", List.of("smithy")),
            List.of(noActor)
        );
        assertTrue(decisions.isEmpty());
    }

    @Test
    void aDefinitionWithoutAnActorIsTheDeploymentsDefaultActor() {
        var config = new OrchestratorConfig(
            OrchestratorConfig.API_VERSION,
            OrchestratorConfig.KIND,
            null,
            null,
            null,
            null,
            Map.of(),
            new DefaultsConfig(null, null, "architect"),
            null,
            Map.of(),
            null,
            null,
            null
        );
        var noActor = new WorkflowDefinitionParser().parse("plain.yml", AUTHOR.replace("vars: {actor: smithy}\n", ""));
        var router = new WorkflowRouter(new ExpressionRenderer(), config);

        var byTheDefaultActor = router.route(
            new WorkflowEvent.PrConversationComment(
                prContext(),
                "architect-bot",
                "done",
                1,
                "d1",
                List.of("architect")
            ),
            List.of(noActor)
        );
        var bySmithy = router.route(
            new WorkflowEvent.PrConversationComment(prContext(), "smithy-bot", "done", 1, "d1", List.of("smithy")),
            List.of(noActor)
        );

        assertTrue(byTheDefaultActor.isEmpty());
        assertEquals(List.of("author"), bySmithy.stream().map(WorkflowRouter.Decision::workflowName).toList());
    }
}
