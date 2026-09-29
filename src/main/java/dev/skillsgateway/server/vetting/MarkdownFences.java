package dev.skillsgateway.server.vetting;

import io.github.reqstool.annotations.Requirements;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The fenced code blocks of a Markdown file (GW_VETTING_0054, GW_VETTING_0056): what an
 * instruction asks to be run. Prose and inline code spans are mentions, and are not returned.
 */
final class MarkdownFences {

    /** A block's content, and the file line its first content line is on. */
    record Block(int firstLine, String text) {}

    private static final Pattern OPEN = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");

    private MarkdownFences() {}

    /** Every fenced block, in order; one left open runs to the end of the file, as CommonMark reads it. */
    @Requirements({"GW_VETTING_0054", "GW_VETTING_0056"})
    static List<Block> of(String markdown) {
        String[] lines = markdown.split("\\r?\\n", -1);
        List<Block> blocks = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            Matcher open = OPEN.matcher(lines[i]);
            if (!open.matches()
                    || (open.group(1).charAt(0) == '`' && open.group(2).contains("`"))) {
                i++;
                continue;
            }
            Pattern close = Pattern.compile(
                    "^ {0,3}" + Pattern.quote(String.valueOf(open.group(1).charAt(0))) + "{"
                            + open.group(1).length() + ",}\\s*$");
            int first = i + 1;
            StringBuilder text = new StringBuilder();
            i++;
            while (i < lines.length && !close.matcher(lines[i]).matches()) {
                text.append(lines[i]).append('\n');
                i++;
            }
            blocks.add(new Block(first + 1, text.toString()));
            i++;
        }
        return blocks;
    }
}
