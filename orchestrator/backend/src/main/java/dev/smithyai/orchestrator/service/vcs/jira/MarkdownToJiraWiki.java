package dev.smithyai.orchestrator.service.vcs.jira;

import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.jira.converter.JiraConverterExtension;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import java.util.List;

/**
 * Markdown, as every workflow writes it, to the wiki markup Jira's v2 comment
 * API renders.
 *
 * <p>Jira is the one provider that does not render markdown: a comment posted
 * through the v2 API is a wiki-markup string, so {@code **bold**},
 * {@code [text](url)} and {@code ### heading} arrived on stories as raw
 * punctuation. GitLab, Forgejo and GitHub render markdown natively, which is
 * why this lives at the Jira boundary and nowhere else.
 *
 * <p>The conversion is flexmark's Jira converter: a real CommonMark parser
 * with GFM tables and strikethrough, rendering to wiki markup. The input is
 * read as markdown and nothing else: wiki constructs markdown has no meaning
 * for ({@code {{mono}}}, {@code h2.}, {@code [text|url]}) pass through as
 * text, but {@code *word*} is markdown emphasis and comes out as Jira italic,
 * and a single line break inside a paragraph is a soft break and joins.
 * Workflows write markdown; that is the whole contract.
 */
public final class MarkdownToJiraWiki {

    private static final MutableDataSet OPTIONS = new MutableDataSet().set(
        Parser.EXTENSIONS,
        List.of(JiraConverterExtension.create(), TablesExtension.create(), StrikethroughExtension.create())
    );
    private static final Parser PARSER = Parser.builder(OPTIONS).build();
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder(OPTIONS).build();

    private MarkdownToJiraWiki() {}

    public static String convert(String markdown) {
        if (markdown == null) return "";
        if (markdown.isBlank()) return markdown;
        return RENDERER.render(PARSER.parse(markdown)).stripTrailing();
    }
}
