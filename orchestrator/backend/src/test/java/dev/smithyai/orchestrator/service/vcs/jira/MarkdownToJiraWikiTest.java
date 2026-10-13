package dev.smithyai.orchestrator.service.vcs.jira;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Comments are written once, in markdown, and must read well on Jira too.
 *
 * <p>The cases are the bodies the built-in definitions actually post and the
 * things a model puts in a summary, plus the other direction: a body that is
 * already wiki markup must survive untouched, because a deployment-specific
 * definition may well write one.
 */
class MarkdownToJiraWikiTest {

    @Test
    void theDevelopmentPlanCommentRendersAsHeadingLinkAndList() {
        String markdown = """
            Development plan: [.smithy/plans/ECD-9.md](https://git.example.com/acme/app/-/blob/smithy/ECD-9/.smithy/plans/ECD-9.md)

            ### Open Questions
            - Should the export include archived clients?
            - Which queue carries the event?""";

        String expected = """
            Development plan: [.smithy/plans/ECD-9.md|https://git.example.com/acme/app/-/blob/smithy/ECD-9/.smithy/plans/ECD-9.md]

            h3. Open Questions
            * Should the export include archived clients?
            * Which queue carries the event?""";

        assertEquals(expected, MarkdownToJiraWiki.convert(markdown));
    }

    @Test
    void theFanOutCommentKeepsItsLinksAndBoldLead() {
        String markdown = """
            Plan approved. I created 2 child issue(s):

            - acme/api: [Add export endpoint](https://git.example.com/acme/api/issues/4)
            - acme/web: [Export button](https://git.example.com/acme/web/issues/9)

            **What's needed from you:** review each plan there.""";

        String expected = """
            Plan approved. I created 2 child issue(s):

            * acme/api: [Add export endpoint|https://git.example.com/acme/api/issues/4]
            * acme/web: [Export button|https://git.example.com/acme/web/issues/9]

            *What's needed from you:* review each plan there.""";

        assertEquals(expected, MarkdownToJiraWiki.convert(markdown));
    }

    @Test
    void thePlanTemplateWithInlineCodeAndRuleConverts() {
        String markdown = """
            Here's my plan for this story:

            **Repos**
            - `gerimedica/gm-financials`: Add the WLZ export job
              - nested detail

            ---
            Want to change something? [Open the session](https://smithy.example.com/session/gm.coordinator.ECD-1) and chat with me there, then hit **Hand back to workflow**. Add the `plan-approved` label and I'll get going.""";

        String expected = """
            Here's my plan for this story:

            *Repos*
            * {{gerimedica/gm-financials}}: Add the WLZ export job
            ** nested detail

            ----
            Want to change something? [Open the session|https://smithy.example.com/session/gm.coordinator.ECD-1] and chat with me there, then hit *Hand back to workflow*. Add the {{plan-approved}} label and I'll get going.""";

        assertEquals(expected, MarkdownToJiraWiki.convert(markdown));
    }

    @Test
    void fencedCodeIsVerbatimAndLabelledByLanguage() {
        String markdown = """
            Run it:

            ```bash
            # not a heading
            echo **not bold** [not](a-link)
            ```
            done""";

        String expected = """
            Run it:

            {code:bash}
            # not a heading
            echo **not bold** [not](a-link)
            {code}
            done""";

        assertEquals(expected, MarkdownToJiraWiki.convert(markdown));
    }

    @Test
    void wikiMarkupComesBackUnchanged() {
        String wiki = """
            h2. Already wiki
            *bold* and _italic_ and {{mono}} and [a link|https://example.com]
            * item
            ** nested
            ||h1||h2||
            |a|b|
            {code:java}
            int x = 1;
            {code}
            ----
            bq. a quote""";

        assertEquals(wiki, MarkdownToJiraWiki.convert(wiki));
    }

    @Test
    void singleAsterisksNumberedListsQuotesTablesAndStrikethrough() {
        String markdown = """
            1. first *emphasis stays*
            2. second ~~gone~~
            > one quoted line

            | Repo | Task |
            |------|------|
            | api | export |
            | web | button |

            > first
            > second""";

        String expected = """
            # first *emphasis stays*
            # second -gone-
            bq. one quoted line

            ||Repo||Task||
            |api|export|
            |web|button|

            {quote}
            first
            second
            {quote}""";

        assertEquals(expected, MarkdownToJiraWiki.convert(markdown));
    }

    @Test
    void nullAndBlankAreHarmless() {
        assertEquals("", MarkdownToJiraWiki.convert(null));
        assertEquals("  ", MarkdownToJiraWiki.convert("  "));
    }
}
