package dev.smithyai.orchestrator.service.vcs.jira;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * <p>Deliberately conservative and line-oriented, covering what prompts and
 * definitions actually produce. Text that is already wiki markup must come
 * back unchanged, so a single {@code *word*} — already wiki bold, and used by
 * both — is left alone, and a {@code [text|url]} link does not match the
 * markdown link pattern. Anything this does not recognise passes through.
 */
public final class MarkdownToJiraWiki {

    private static final Pattern FENCE = Pattern.compile("^\\s*```\\s*([\\w+-]*)\\s*$");
    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern RULE = Pattern.compile("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$");
    private static final Pattern QUOTE = Pattern.compile("^\\s{0,3}>\\s?(.*)$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)([-+*])\\s+(.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^(\\s*)\\d+[.)]\\s+(.*)$");
    private static final Pattern TABLE_ROW = Pattern.compile("^\\s*\\|.*\\|\\s*$");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile(
        "^\\s*\\|?(\\s*:?-{3,}:?\\s*\\|)+\\s*(:?-{3,}:?\\s*)?\\|?\\s*$"
    );

    private static final Pattern CODE_SPAN = Pattern.compile("`([^`]+)`");
    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)");
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern BOLD_UNDERSCORE = Pattern.compile("(?<![\\w])__(.+?)__(?![\\w])");
    private static final Pattern STRIKE = Pattern.compile("~~(.+?)~~");

    private MarkdownToJiraWiki() {}

    public static String convert(String markdown) {
        if (markdown == null) return "";
        if (markdown.isBlank()) return markdown;

        String[] lines = markdown.split("\n", -1);
        var out = new ArrayList<String>(lines.length);
        var quote = new ArrayList<String>();
        boolean inFence = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            var fence = FENCE.matcher(line);
            if (fence.matches()) {
                flushQuote(quote, out);
                if (inFence) {
                    out.add("{code}");
                } else {
                    String lang = fence.group(1);
                    out.add(lang.isBlank() ? "{code}" : "{code:" + lang + "}");
                }
                inFence = !inFence;
                continue;
            }
            if (inFence) {
                // Verbatim: a code block full of ** and # is still just code.
                out.add(line);
                continue;
            }

            var quoted = QUOTE.matcher(line);
            if (quoted.matches()) {
                quote.add(inline(quoted.group(1)));
                continue;
            }
            flushQuote(quote, out);

            if (isTableStart(lines, i)) {
                i = table(lines, i, out);
                continue;
            }

            var heading = HEADING.matcher(line);
            if (heading.matches()) {
                out.add("h" + heading.group(1).length() + ". " + inline(heading.group(2)));
                continue;
            }
            if (RULE.matcher(line).matches()) {
                out.add("----");
                continue;
            }

            var bullet = BULLET.matcher(line);
            if (bullet.matches()) {
                int depth = depth(bullet.group(1));
                // A top-level "* item" is already wiki markup; only the dash
                // and plus forms and indented items need rewriting.
                if (depth == 1 && "*".equals(bullet.group(2))) {
                    out.add("* " + inline(bullet.group(3)));
                } else {
                    out.add("*".repeat(depth) + " " + inline(bullet.group(3)));
                }
                continue;
            }
            var numbered = NUMBERED.matcher(line);
            if (numbered.matches()) {
                out.add("#".repeat(depth(numbered.group(1))) + " " + inline(numbered.group(2)));
                continue;
            }

            out.add(inline(line));
        }
        flushQuote(quote, out);
        if (inFence) out.add("{code}");
        return String.join("\n", out);
    }

    /** Two spaces (or a tab) of indentation per nesting level, as markdown lists are usually written. */
    private static int depth(String indent) {
        int width = 0;
        for (char c : indent.toCharArray()) width += c == '\t' ? 2 : 1;
        return 1 + width / 2;
    }

    private static void flushQuote(List<String> quote, List<String> out) {
        if (quote.isEmpty()) return;
        if (quote.size() == 1) {
            out.add("bq. " + quote.getFirst());
        } else {
            out.add("{quote}");
            out.addAll(quote);
            out.add("{quote}");
        }
        quote.clear();
    }

    private static boolean isTableStart(String[] lines, int i) {
        return (
            i + 1 < lines.length &&
            TABLE_ROW.matcher(lines[i]).matches() &&
            TABLE_SEPARATOR.matcher(lines[i + 1]).matches()
        );
    }

    /** Header row, separator, body rows; returns the index of the last line consumed. */
    private static int table(String[] lines, int start, List<String> out) {
        out.add("||" + String.join("||", cells(lines[start])) + "||");
        int i = start + 2;
        while (i < lines.length && TABLE_ROW.matcher(lines[i]).matches()) {
            out.add("|" + String.join("|", cells(lines[i])) + "|");
            i++;
        }
        return i - 1;
    }

    private static List<String> cells(String row) {
        String trimmed = row.strip();
        trimmed = trimmed.substring(1, trimmed.endsWith("|") ? trimmed.length() - 1 : trimmed.length());
        var cells = new ArrayList<String>();
        for (String cell : trimmed.split("\\|", -1)) {
            String text = inline(cell.strip());
            // Jira drops a cell that renders empty, which shifts every cell after it.
            cells.add(text.isEmpty() ? " " : text);
        }
        return cells;
    }

    /** Inline markup on one line of prose, leaving the inside of code spans alone. */
    static String inline(String text) {
        if (text.isEmpty()) return text;
        var result = new StringBuilder();
        var code = CODE_SPAN.matcher(text);
        int last = 0;
        while (code.find()) {
            result.append(prose(text.substring(last, code.start())));
            result.append("{{").append(code.group(1)).append("}}");
            last = code.end();
        }
        result.append(prose(text.substring(last)));
        return result.toString();
    }

    private static String prose(String text) {
        if (text.isEmpty()) return text;
        String converted = IMAGE.matcher(text).replaceAll(m -> "!" + Matcher.quoteReplacement(m.group(1)) + "!");
        converted = LINK.matcher(converted).replaceAll(
            m -> "[" + Matcher.quoteReplacement(m.group(1)) + "|" + Matcher.quoteReplacement(m.group(2)) + "]"
        );
        converted = BOLD.matcher(converted).replaceAll(m -> "*" + Matcher.quoteReplacement(m.group(1)) + "*");
        converted = BOLD_UNDERSCORE.matcher(converted).replaceAll(
            m -> "*" + Matcher.quoteReplacement(m.group(1)) + "*"
        );
        converted = STRIKE.matcher(converted).replaceAll(m -> "-" + Matcher.quoteReplacement(m.group(1)) + "-");
        return converted;
    }
}
