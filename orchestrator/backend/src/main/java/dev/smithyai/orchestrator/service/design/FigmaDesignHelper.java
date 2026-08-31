package dev.smithyai.orchestrator.service.design;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.smithyai.orchestrator.service.docker.ContainerSession;
import dev.smithyai.orchestrator.service.vcs.IssueTrackerClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Puts the designs a ticket links to in front of the agent.
 *
 * <p>The same problem attachments have, one step removed. A design carries
 * requirements the ticket text omits, and the agent cannot follow a link out of
 * its container — but a Figma design is never attached, it is linked, so there
 * is nothing to download until something has resolved the link. This renders
 * what the link points at and carries the image in, so the prompt can name a
 * path the way it already names an attachment.
 *
 * <p>Best-effort throughout: a design that will not render is a design the
 * agent works without, not a failed run.
 */
@Slf4j
public final class FigmaDesignHelper {

    public static final String DESIGNS_DIR = "/workspace/.smithy/tmp/designs";
    private static final String MANIFEST = "designs.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FigmaDesignHelper() {}

    /**
     * Render every design linked from the issue into the container.
     *
     * @return one map per rendered design — {@code path}, {@code url},
     *         {@code file} and {@code frame} — in the order the links appeared.
     *         Maps rather than a record because these are read from a Jinja
     *         template, which addresses properties by name.
     */
    public static List<Map<String, Object>> fetchAndInject(
        IssueTrackerClient client,
        FigmaClient figma,
        ContainerSession session,
        String owner,
        String repo,
        String issueRef,
        int maxDesigns
    ) {
        if (!figma.isActive()) return List.of();

        var links = FigmaLink.scan(issueTexts(client, owner, repo, issueRef));
        if (links.isEmpty()) return List.of();
        if (!session.ensureScratchDir(DESIGNS_DIR)) return List.of();

        // One request per file rather than per link: a ticket usually links
        // several frames of the same design file, and Figma renders a batch.
        var byFile = new LinkedHashMap<String, List<FigmaLink>>();
        links.forEach(link -> byFile.computeIfAbsent(link.fileKey(), key -> new ArrayList<>()).add(link));

        var designs = new ArrayList<Map<String, Object>>();
        for (var entry : byFile.entrySet()) {
            if (designs.size() >= maxDesigns) break;
            try {
                designs.addAll(
                    renderFile(figma, session, entry.getKey(), entry.getValue(), maxDesigns - designs.size())
                );
            } catch (Exception e) {
                log.warn("Failed to render Figma file {} linked from {}", entry.getKey(), issueRef, e);
            }
        }

        if (designs.isEmpty()) return List.of();
        writeManifest(session, designs);
        log.info(
            "Injected {} Figma design(s) into {} for issue {}",
            designs.size(),
            session.getContainerName(),
            issueRef
        );
        return List.copyOf(designs);
    }

    /**
     * Everywhere a link can hide: the description, the comments, and — on Jira —
     * the remote links, which is where the Figma app puts a design rather than
     * in any text a human wrote.
     */
    private static List<String> issueTexts(IssueTrackerClient client, String owner, String repo, String issueRef) {
        var texts = new ArrayList<String>();
        try {
            var issue = client.getIssue(owner, repo, issueRef);
            texts.add(issue.body());
        } catch (Exception e) {
            log.warn("Failed to read issue {} while looking for designs", issueRef, e);
        }
        try {
            client.getIssueComments(owner, repo, issueRef).forEach(comment -> texts.add(comment.body()));
        } catch (Exception e) {
            log.warn("Failed to read comments on {} while looking for designs", issueRef, e);
        }
        try {
            texts.addAll(client.getIssueLinks(owner, repo, issueRef));
        } catch (Exception e) {
            log.warn("Failed to read linked resources on {} while looking for designs", issueRef, e);
        }
        return texts;
    }

    private static List<Map<String, Object>> renderFile(
        FigmaClient figma,
        ContainerSession session,
        String fileKey,
        List<FigmaLink> links,
        int budget
    ) {
        // A link that named no frame stands for the page it opened on, so the
        // frames of that page are what gets rendered in its place.
        var nodeIds = new ArrayList<String>();
        var sourceUrls = new LinkedHashMap<String, String>();
        for (var link : links) {
            var ids = link.isWholeFile() ? figma.topLevelFrames(fileKey, budget) : List.of(link.nodeId());
            for (String id : ids) {
                if (nodeIds.size() >= budget) break;
                if (sourceUrls.putIfAbsent(id, link.url()) == null) nodeIds.add(id);
            }
        }
        if (nodeIds.isEmpty()) return List.of();

        var names = figma.names(fileKey, nodeIds);
        var urls = figma.renderUrls(fileKey, nodeIds);

        var rendered = new ArrayList<Map<String, Object>>();
        for (String nodeId : nodeIds) {
            String imageUrl = urls.get(nodeId);
            if (imageUrl == null) {
                log.warn("Figma returned no image for node {} of file {}", nodeId, fileKey);
                continue;
            }
            String frame = names.nodeOr(nodeId, nodeId);
            String filename = filenameFor(frame, nodeId, figma.format());
            try {
                session.copyToContainer(DESIGNS_DIR, figma.download(imageUrl), filename);
            } catch (Exception e) {
                log.warn("Failed to inject Figma design {} of file {}", frame, fileKey, e);
                continue;
            }
            var design = new LinkedHashMap<String, Object>();
            design.put("path", DESIGNS_DIR + "/" + filename);
            design.put("frame", frame);
            design.put("file", names.file());
            design.put("url", sourceUrls.getOrDefault(nodeId, ""));
            rendered.add(design);
        }
        return rendered;
    }

    /**
     * A filename the agent can reason about: the frame's own name, with the
     * node id kept so two frames called "Modal" stay two files.
     */
    public static String filenameFor(String frame, String nodeId, String format) {
        String slug = frame.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > 40) slug = slug.substring(0, 40).replaceAll("-+$", "");
        if (slug.isEmpty()) slug = "design";
        return "%s-%s.%s".formatted(slug, nodeId.replace(':', '-').replace(';', '_'), format);
    }

    /**
     * A sidecar listing what came in and where each frame came from, for the
     * turn that reads a file the prompt no longer mentions.
     */
    private static void writeManifest(ContainerSession session, List<Map<String, Object>> designs) {
        try {
            byte[] json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(designs);
            session.copyToContainer(DESIGNS_DIR, json, MANIFEST);
        } catch (Exception e) {
            log.warn("Failed to write the design manifest", e);
        }
    }
}
