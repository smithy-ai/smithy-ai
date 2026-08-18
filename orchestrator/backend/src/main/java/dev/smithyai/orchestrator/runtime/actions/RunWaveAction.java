package dev.smithyai.orchestrator.runtime.actions;

import dev.smithyai.orchestrator.runtime.store.Run;
import dev.smithyai.orchestrator.runtime.store.RunStatus;
import dev.smithyai.orchestrator.runtime.store.RunStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Release the children whose dependencies are done.
 *
 * <p>Work fanned out across repositories is rarely independent: the service has
 * to ship before the client that calls it. Each child records which siblings it
 * waits on when it is spawned, and this reports which ones have become
 * releasable since the last time it was asked.
 *
 * <p>Real logic, so it is an action rather than expression syntax — the whole
 * point of keeping the definition language small is that anything needing a
 * loop and a set intersection gets a typed home instead.
 *
 * <p>A dependency that was never created cannot block forever: a plan that
 * named a repository the catalog does not have would otherwise strand every
 * child behind it.
 */
@Slf4j
@Component
public class RunWaveAction implements WorkflowAction {

    /** Set on a child when it is spawned; read here to schedule it. */
    public static final String INDEX_VAR = "index";

    public static final String DEPENDS_ON_VAR = "dependsOn";
    static final String RELEASED_VAR = "released";

    private final RunStore store;

    public RunWaveAction(RunStore store) {
        this.store = store;
    }

    @Override
    public String type() {
        return "run.wave";
    }

    @Override
    public boolean idempotent() {
        // Asking twice reports nothing the second time: a released child is
        // marked as such, so a replayed transition does not assign it again.
        return true;
    }

    @Override
    public Map<String, Object> execute(ActionContext context, Map<String, Object> input) {
        var children = store.findChildren(context.run().id());
        var completed = new java.util.HashSet<Integer>();
        var known = new java.util.HashSet<Integer>();
        for (var child : children) {
            indexOf(child).ifPresent(index -> {
                known.add(index);
                if (child.status() == RunStatus.COMPLETED) completed.add(index);
            });
        }

        var released = new ArrayList<Map<String, Object>>();
        var blocked = new ArrayList<Map<String, Object>>();
        for (var child : children) {
            if (isReleased(child) || child.status().isTerminal()) continue;
            if (ready(child, completed, known)) {
                store.mergeVars(child.id(), Map.of(RELEASED_VAR, true));
                released.add(view(child));
            } else {
                blocked.add(view(child));
            }
        }

        // Every child actually delivered — not merely stopped. A cancelled or
        // failed child means the feature is not done, however quiet it has gone.
        boolean complete =
            !children.isEmpty() && children.stream().allMatch(child -> child.status() == RunStatus.COMPLETED);
        long abandoned = children
            .stream()
            .filter(child -> child.status().isTerminal() && child.status() != RunStatus.COMPLETED)
            .count();
        if (!released.isEmpty()) {
            log.info("Run {} released {} child run(s)", context.run().id(), released.size());
        }
        return Map.of(
            "released",
            released,
            "blocked",
            blocked,
            "complete",
            complete,
            "total",
            children.size(),
            "finished",
            children
                .stream()
                .filter(child -> child.status().isTerminal())
                .count(),
            "abandoned",
            abandoned,
            "pending",
            children
                .stream()
                .filter(child -> !child.status().isTerminal())
                .count()
        );
    }

    /** All dependencies either finished, or never existed to begin with. */
    private boolean ready(Run child, java.util.Set<Integer> completed, java.util.Set<Integer> known) {
        for (int dependency : dependenciesOf(child)) {
            if (known.contains(dependency) && !completed.contains(dependency)) return false;
        }
        return true;
    }

    private static Map<String, Object> view(Run child) {
        var view = new LinkedHashMap<String, Object>(child.vars());
        view.put("runId", child.id());
        view.put("workflow", child.workflowName());
        view.put("status", child.status().value());
        return view;
    }

    private static boolean isReleased(Run child) {
        return Boolean.TRUE.equals(child.vars().get(RELEASED_VAR));
    }

    private static java.util.Optional<Integer> indexOf(Run child) {
        return child.vars().get(INDEX_VAR) instanceof Number number
            ? java.util.Optional.of(number.intValue())
            : java.util.Optional.empty();
    }

    private static List<Integer> dependenciesOf(Run child) {
        if (!(child.vars().get(DEPENDS_ON_VAR) instanceof List<?> declared)) return List.of();
        var dependencies = new ArrayList<Integer>();
        for (Object entry : declared) {
            if (entry instanceof Number number) dependencies.add(number.intValue());
            else try {
                dependencies.add(Integer.parseInt(String.valueOf(entry).strip()));
            } catch (NumberFormatException e) {
                log.warn("Child run {} declares an unreadable dependency '{}'", child.id(), entry);
            }
        }
        return dependencies;
    }
}
