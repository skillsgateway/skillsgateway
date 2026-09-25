package dev.skillsgateway.server.vetting;

import io.github.reqstool.annotations.Requirements;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The runtime-fetch rules of the {@code executable-surface} vetter (GW_VETTING_0048): where a
 * hook command, or a file a hook launches, downloads code and executes it, or runs a package
 * runner that fetches its package at run time.
 *
 * <p>Pure over text. Lines are joined across shell continuations, stripped of quoting inserted
 * into a command name ({@code c''url}, {@code "cu"rl}, a backslash before a letter), and — in a file — skipped
 * when they are comments, before any rule sees them.
 */
final class RuntimeFetch {

    static final String FETCH_EXEC = "runtime-fetch-exec";
    static final String PACKAGE_RUN = "runtime-package-run";

    /** One rule firing at one line of the scanned text. */
    record Match(String rule, int line, String message) {}

    private RuntimeFetch() {}

    private static final String DL =
            "(?:\\bcurl(?:\\.exe)?\\b|\\bwget\\b|(?i:\\b(?:iwr|irm|invoke-webrequest|invoke-restmethod)\\b))";

    private static final String INTERP = "(?:sudo\\s+(?:-\\S+\\s+)*)?(?:[\\w./-]*/)?"
            + "(?:sh|bash|zsh|dash|ksh|python[0-9.]*|node|deno|bun|perl|ruby|php"
            + "|(?i:iex|invoke-expression|pwsh|powershell))(?:\\.exe)?\\b";

    /** A download piped into an interpreter, on one logical line; {@code ||} is not a pipe. */
    private static final Pattern PIPE = Pattern.compile(DL + "[^\\n]*?(?<!\\|)\\|(?!\\|)\\s*" + INTERP);

    /** An interpreter fed a download by command or process substitution. */
    private static final Pattern SUBSTITUTION = Pattern.compile("(?:" + INTERP
            + "\\s+(?:-\\S+\\s+)*|(?:^|[\\s;&|(])(?:eval|source|\\.)\\s+)[\"']?(?:\\$\\(|`|<\\()\\s*" + DL);

    /** PowerShell's download-then-evaluate in one expression. */
    private static final Pattern POWERSHELL_EVAL = Pattern.compile(
            "(?i:\\b(?:iex|invoke-expression)\\b[^\\n]*\\b(?:iwr|irm|invoke-webrequest|invoke-restmethod|downloadstring)\\b)");

    /** A script language evaluating what it just fetched. */
    private static final Pattern INLINE_EVAL =
            Pattern.compile("\\b(?:exec|eval)\\s*\\([^\\n]*\\b(?:urlopen|urlretrieve|requests\\.get)\\b");

    /** A download written to a file rather than to standard output. */
    private static final Pattern DOWNLOAD_TO_FILE = Pattern.compile("\\bcurl(?:\\.exe)?\\b[^\\n|]*?"
            + "(?:\\s-[A-Za-z]*[oO][A-Za-z]*(?=[\\s\"'$]|$)|\\s--output\\b|\\s--remote-name\\b"
            + "|\\s>(?![&>])\\s*(?!/dev/null)\\S)"
            + "|\\bwget\\b(?=\\s+(?:-|[\"'$]|\\w+://))(?![^\\n]*(?:O\\s*-(?:[\\s\"'|]|$)|--output-document[= ]?-|--spider))"
            + "|(?i:\\b(?:iwr|invoke-webrequest)\\b[^\\n]*-outfile\\b)"
            + "|(?i:\\bcertutil(?:\\.exe)?\\b[^\\n]*-urlcache)"
            + "|(?i:\\bbitsadmin(?:\\.exe)?\\b[^\\n]*/transfer)"
            + "|\\burlretrieve\\s*\\(");

    /** A download to a file run by an interpreter on the same line. */
    private static final Pattern DOWNLOAD_THEN_RUN =
            Pattern.compile("(?:" + DOWNLOAD_TO_FILE.pattern() + ")[^\\n]*(?:&&|;)\\s*" + INTERP + "\\s");

    /** Something made executable; numeric modes are checked for an execute bit separately. */
    private static final Pattern MAKE_EXECUTABLE =
            Pattern.compile("\\bchmod\\s+(?:-\\S+\\s+)*(?<mode>[ugoa]*[+=][rwxXst]*x[rwxXst]*|[0-7]{3,4})\\b"
                    + "|\\bos\\.chmod\\s*\\(|\\bfs\\.chmod(?:Sync)?\\s*\\(|(?i:\\bstart-process\\b)");

    private static final String TARGET = "[\\s=]*(?<target>\"[^\"]*\"|'[^']*'|[^\\s;|&]+)";

    /**
     * The output file a download names. curl's {@code -o} is a file and its {@code -O} a remote
     * name, where wget's {@code -O} is the file, so the two tools have their own flags.
     */
    private static final Pattern CURL_TARGET =
            Pattern.compile("(?:\\s-[A-Za-z]*o(?=[\\s\"'$])|\\s--output\\b)" + TARGET);

    private static final Pattern WGET_TARGET = Pattern.compile("(?:\\s-[A-Za-z]*O|\\s--output-document\\b)" + TARGET);
    private static final Pattern OTHER_TARGET = Pattern.compile("(?:(?i:\\s-outfile\\b)|\\s>(?![&>]))" + TARGET);
    private static final Pattern WGET = Pattern.compile("\\bwget\\b");

    /** The first operand after a chmod mode. */
    private static final Pattern OPERAND = Pattern.compile("\"[^\"]*\"|'[^']*'|[^\\s;|&)]+");

    /** A package runner in command position: it fetches the package from a registry when run. */
    private static final Pattern PACKAGE_RUNNER =
            Pattern.compile("(?:^|[;&|(]|\\$\\(|\\b(?:then|do|else|exec)\\b)\\s*(?:sudo\\s+)?"
                    + "(?<runner>(?:npx|bunx|pnpx|uvx)\\b(?![^\\n]*--(?:no-install|offline))"
                    + "|(?:pnpm|yarn)\\s+dlx\\b|npm\\s+exec\\b|pipx\\s+run\\b|uv\\s+tool\\s+run\\b)");

    /**
     * A package install from a registry: in command position, as {@code -m pip install}, or as the
     * argument list a script hands to a subprocess ({@code "pip", "install"}).
     */
    private static final Pattern PACKAGE_INSTALL =
            Pattern.compile("(?:(?:^|[;&|(]|\\$\\(|\\b(?:then|do|else|exec)\\b)\\s*(?:sudo\\s+)?"
                    + "(?:pip[0-9.]*\\s+install|uv\\s+pip\\s+install|npm\\s+(?:install|i|ci)|(?:yarn|pnpm)\\s+(?:add|install)"
                    + "|gem\\s+install|cargo\\s+install|go\\s+install)\\b"
                    + "|\\s-m\\s+pip\\s+install\\b|[\"']pip[\"']\\s*,\\s*[\"']install[\"'])");

    /** Probing whether a tool exists names it without running it. */
    private static final Pattern PROBE = Pattern.compile("\\b(?:command\\s+-v|which|type\\s+-p|hash)\\s+\\S+");

    private static final Pattern WORD_QUOTE = Pattern.compile("(?<=\\w)[\"'](?=\\w)");
    private static final Pattern ESCAPE = Pattern.compile("\\\\(?=[A-Za-z])");
    private static final Pattern COMMENT = Pattern.compile("^\\s*(?:#|//|/\\*|\\*|::|(?i:rem)\\b)");

    /**
     * Every rule that fires in {@code text}, one match per rule and line.
     *
     * @param file {@code true} for a launched file, whose comment lines are skipped; a hook command
     *     is a command, where {@code #} is not a comment marker at the start
     */
    @Requirements({"GW_VETTING_0048"})
    static List<Match> scan(String text, boolean file) {
        Map<String, Match> matches = new LinkedHashMap<>();
        List<Download> downloads = new ArrayList<>();
        Executable executable = new Executable();
        for (LogicalLine line : logicalLines(text)) {
            if (file && COMMENT.matcher(line.text()).find()) {
                continue;
            }
            String normal = normalize(line.text());
            if (PIPE.matcher(normal).find()) {
                add(matches, FETCH_EXEC, line.number(), "downloads code and pipes it into an interpreter");
            }
            if (SUBSTITUTION.matcher(normal).find()
                    || POWERSHELL_EVAL.matcher(normal).find()
                    || INLINE_EVAL.matcher(normal).find()) {
                add(matches, FETCH_EXEC, line.number(), "downloads code and evaluates it in place");
            }
            if (DOWNLOAD_THEN_RUN.matcher(normal).find()) {
                add(matches, FETCH_EXEC, line.number(), "downloads a file and runs it with an interpreter");
            }
            if (DOWNLOAD_TO_FILE.matcher(normal).find()) {
                downloads.add(new Download(line.number(), target(normal)));
            }
            executable.scan(normal);
            if (PACKAGE_INSTALL.matcher(normal).find()) {
                add(matches, PACKAGE_RUN, line.number(), "installs packages from a registry at run time");
            }
            Matcher runner = PACKAGE_RUNNER.matcher(normal);
            if (runner.find()) {
                add(
                        matches,
                        PACKAGE_RUN,
                        line.number(),
                        "runs '%s', which fetches the package's code from a registry at run time"
                                .formatted(runner.group("runner").split("\\s+")[0]));
            }
        }
        for (Download download : downloads) {
            if (executable.covers(download.target())) {
                add(
                        matches,
                        FETCH_EXEC,
                        download.line(),
                        "downloads to a file, and this file also makes that file executable");
            }
        }
        return List.copyOf(matches.values());
    }

    /** A download to a file: its line, and the file it writes when the command names one. */
    private record Download(int line, String target) {}

    /**
     * What a file makes executable. A download is flagged when its target is among them — so a
     * checksum sidecar fetched beside a binary is not — and whenever either side cannot be named,
     * because an unnamed target is not evidence that nothing downloaded is run.
     */
    private static final class Executable {

        private final java.util.Set<String> targets = new java.util.HashSet<>();
        private boolean unnamed;

        void scan(String line) {
            Matcher matcher = MAKE_EXECUTABLE.matcher(line);
            while (matcher.find()) {
                String mode = matcher.group("mode");
                if (mode == null) {
                    unnamed = true;
                } else if (executableMode(mode)) {
                    String rest = line.substring(matcher.end()).trim();
                    Matcher operand = OPERAND.matcher(rest);
                    // A chmod with no operand changes nothing: it is prose, such as a help message.
                    if (operand.lookingAt()) {
                        targets.add(unquote(operand.group()));
                    }
                }
            }
        }

        boolean covers(String download) {
            return download == null ? unnamed || !targets.isEmpty() : unnamed || targets.contains(download);
        }
    }

    private static boolean executableMode(String mode) {
        if (!mode.chars().allMatch(Character::isDigit)) {
            return true;
        }
        // A numeric mode makes something executable when any of its permission digits is odd.
        return mode.substring(mode.length() - 3).chars().anyMatch(digit -> (digit - '0') % 2 == 1);
    }

    /** The file a download writes, when the command names it: {@code -o}, {@code -O}, {@code -OutFile}, {@code >}. */
    private static String target(String line) {
        Matcher named = (WGET.matcher(line).find() ? WGET_TARGET : CURL_TARGET).matcher(line);
        if (named.find()) {
            return unquote(named.group("target"));
        }
        named = OTHER_TARGET.matcher(line);
        return named.find() ? unquote(named.group("target")) : null;
    }

    private static String unquote(String token) {
        String bare = token.trim();
        if (bare.length() >= 2
                && (bare.charAt(0) == '"' || bare.charAt(0) == '\'')
                && bare.charAt(bare.length() - 1) == bare.charAt(0)) {
            return bare.substring(1, bare.length() - 1);
        }
        return bare;
    }

    /** The first match per rule and line wins: one line is one thing for a reviewer to judge. */
    private static void add(Map<String, Match> matches, String rule, int line, String message) {
        matches.putIfAbsent(rule + ":" + line, new Match(rule, line, message));
    }

    /** Quoting and escaping inserted into a command name removed, and tool probes blanked. */
    static String normalize(String line) {
        String normal = line.replace("''", "").replace("\"\"", "");
        normal = WORD_QUOTE.matcher(normal).replaceAll("");
        normal = ESCAPE.matcher(normal).replaceAll("");
        return PROBE.matcher(normal).replaceAll(" ");
    }

    /** A line as a shell reads it: physical lines ending in a backslash joined to the next. */
    record LogicalLine(int number, String text) {}

    static List<LogicalLine> logicalLines(String text) {
        List<LogicalLine> lines = new ArrayList<>();
        String[] physical = text.split("\\r?\\n", -1);
        StringBuilder current = null;
        int start = 0;
        for (int i = 0; i < physical.length; i++) {
            String line = physical[i];
            if (current == null) {
                current = new StringBuilder();
                start = i + 1;
            }
            if (line.endsWith("\\")) {
                current.append(line, 0, line.length() - 1).append(' ');
                continue;
            }
            current.append(line);
            lines.add(new LogicalLine(start, current.toString()));
            current = null;
        }
        if (current != null) {
            lines.add(new LogicalLine(start, current.toString()));
        }
        return lines;
    }
}
