package dev.smithyai.orchestrator.runtime.actions;

import dev.smithyai.orchestrator.service.vcs.VcsClient;
import dev.smithyai.orchestrator.service.vcs.dto.WebhookInfo;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * Says, where people will talk to the bot, when nothing they say there can
 * reach it.
 *
 * <p>The orchestrator only ever hears about a repository through the webhook
 * that repository has registered for it. A workflow that opens a pull request
 * in a repository without one — a coordinator fanning a story out across a
 * catalog, say — has delivered work into a place it is deaf to: every review
 * comment, every "@bot fix this", every pipeline result lands in silence, and
 * the people commenting have no way to tell that from the bot ignoring them.
 *
 * <p>Observed live: 132 of 138 repositories in one deployment's catalog had no
 * webhook, and every comment on a bot-authored merge request in those
 * repositories went unanswered until someone investigated by hand. This
 * checks, at the moment a pull request is opened, whether the repository can
 * report back, and hands the caller a notice to post there when it cannot.
 *
 * <p>The hook that counts is the one whose URL path ends in
 * {@code /webhooks/<connector>} — the path the webhook controller serves for
 * that connector — so the check needs nothing new configured. A hook may sit
 * behind a reverse proxy prefix, so the path is matched by suffix.
 *
 * <p>Listing hooks needs maintainer rights the bot may not have, and a provider
 * may not expose them at all. Not knowing is not the same as knowing there is
 * none, so both cases stay quiet.
 */
@Slf4j
public final class WebhookAudit {

    private WebhookAudit() {}

    /**
     * @param connectorId the connector the repository is reached through; the
     *                    webhook must deliver to that connector's path
     * @return a notice to post on the pull request when the repository cannot
     *         report comments and pull-request events for this connector;
     *         empty when it can, or when that cannot be determined
     */
    public static Optional<String> notice(VcsClient vcs, String connectorId, String owner, String repo) {
        List<WebhookInfo> hooks;
        try {
            hooks = vcs.listWebhooks(owner, repo);
        } catch (UnsupportedOperationException e) {
            return Optional.empty();
        } catch (RuntimeException e) {
            log.debug("Cannot list webhooks of {}/{}: {}", owner, repo, e.getMessage());
            return Optional.empty();
        }
        if (hooks == null) return Optional.empty();

        String path = webhookPath(connectorId);
        var mine = hooks
            .stream()
            .filter(WebhookInfo::active)
            .filter(hook -> deliversTo(hook.url(), path))
            .toList();
        if (mine.isEmpty()) {
            return Optional.of(
                (
                    "Heads-up: nothing said here will reach me. This project has no webhook delivering to " +
                    "`%s` on the orchestrator, so comments, reviews and pipeline results posted here never " +
                    "arrive. A project maintainer can add one, with comment and merge/pull request events " +
                    "enabled and the configured webhook secret; until then, reach me on the issue or from " +
                    "the dashboard."
                ).formatted(path)
            );
        }

        boolean comments = mine.stream().anyMatch(hook -> hook.sends(WebhookInfo.COMMENTS));
        boolean pullRequests = mine.stream().anyMatch(hook -> hook.sends(WebhookInfo.PULL_REQUESTS));
        if (comments && pullRequests) return Optional.empty();

        String lacks;
        String consequence;
        if (!comments && !pullRequests) {
            lacks = "comment or merge/pull request events";
            consequence = "I will not see comments or reviews posted here, nor notice when this is merged or closed";
        } else if (!comments) {
            lacks = "comment events";
            consequence = "I will not see comments or reviews posted here";
        } else {
            lacks = "merge/pull request events";
            consequence = "I will not notice when this is merged or closed";
        }
        return Optional.of(
            (
                "Heads-up: this project's webhook to `%s` does not send %s, so %s. A project maintainer can " +
                "enable them on the webhook."
            ).formatted(path, lacks, consequence)
        );
    }

    /** The path the webhook controller serves for a connector. */
    public static String webhookPath(String connectorId) {
        return "/webhooks/" + connectorId;
    }

    static boolean deliversTo(String url, String path) {
        if (url == null || url.isBlank()) return false;
        String hookPath;
        try {
            hookPath = URI.create(url.strip()).getPath();
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (hookPath == null) return false;
        while (hookPath.endsWith("/")) hookPath = hookPath.substring(0, hookPath.length() - 1);
        // Suffix, not equality: the orchestrator may sit behind a proxy prefix.
        // The leading slash in `path` keeps `/webhooks/gitlab` from matching
        // `/webhooks/other-gitlab`.
        return hookPath.endsWith(path);
    }
}
