package dev.smithyai.orchestrator.service.design;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One design referenced from a ticket: which Figma file, and which frame inside
 * it if the link named one.
 *
 * <p>A design reaches a ticket as a link rather than a file, and the agent runs
 * in a container that cannot follow it. Reading the link is therefore the first
 * half of getting the design in front of the agent; {@link FigmaClient} renders
 * what the link points at, and {@link FigmaDesignHelper} carries the result in.
 *
 * @param fileKey the file the URL addresses
 * @param nodeId  the frame, in the API's {@code 1:23} form, or empty when the
 *                link pointed at the file as a whole
 * @param url     the link as it appeared, so a prompt can name its source
 */
public record FigmaLink(String fileKey, String nodeId, String url) {
    /**
     * Figma addresses the same file under several product names — {@code /file}
     * is the historical one, {@code /design} what the editor writes today, and
     * the rest are the other surfaces that share the URL shape. The trailing
     * character class stops the match at the punctuation that wraps a link in
     * Markdown {@code [text](url)}, Jira {@code [text|url]} or a sentence.
     */
    private static final Pattern URL_PATTERN = Pattern.compile(
        "https?://(?:[\\w-]+\\.)?figma\\.com/(?:file|design|proto|board|slides|deck)/([0-9A-Za-z]{8,})[^\\s\"'<>\\]|)]*",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern NODE_ID_PARAM = Pattern.compile("[?&]node[-_]?id=([^&#]+)", Pattern.CASE_INSENSITIVE);

    /**
     * Every distinct design linked from the given texts, in the order they were
     * first seen.
     *
     * <p>Distinct means file plus frame: the same file linked twice at two
     * frames is two designs, and the same frame linked from the description and
     * again from a comment is one.
     */
    public static List<FigmaLink> scan(List<String> texts) {
        // Keyed on file and frame rather than the link itself: the editor
        // stamps a session id into every URL it copies, so the same frame
        // shared twice arrives as two strings that mean one design.
        var seen = new LinkedHashMap<String, FigmaLink>();
        for (String text : texts) {
            if (text == null || text.isBlank()) continue;
            var matcher = URL_PATTERN.matcher(text);
            while (matcher.find()) {
                String url = trimTrailingPunctuation(matcher.group());
                var link = new FigmaLink(matcher.group(1), nodeIdOf(url), url);
                seen.putIfAbsent(link.fileKey() + "#" + link.nodeId(), link);
            }
        }
        return List.copyOf(seen.values());
    }

    /** A file-scoped link asks for the whole file rather than one frame. */
    public boolean isWholeFile() {
        return nodeId.isEmpty();
    }

    /** Node ids appear in paths and filenames, where a colon is awkward. */
    public String nodeSlug() {
        return nodeId.replace(':', '-').replace(';', '_');
    }

    /**
     * The {@code node-id} query parameter, translated to the form the API takes.
     *
     * <p>A URL carries the id as {@code 1-23} (what the editor copies today) or
     * as an escaped {@code 1%3A23} (what it used to); the API answers only to
     * {@code 1:23}.
     */
    private static String nodeIdOf(String url) {
        var matcher = NODE_ID_PARAM.matcher(url);
        if (!matcher.find()) return "";
        String raw = URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8).strip();
        return raw.replace('-', ':');
    }

    /**
     * A link at the end of a sentence keeps the sentence's punctuation, and a
     * link inside a Markdown image keeps its closing bracket.
     */
    private static String trimTrailingPunctuation(String url) {
        int end = url.length();
        while (end > 0 && ".,;:!?".indexOf(url.charAt(end - 1)) >= 0) end--;
        return url.substring(0, end);
    }
}
