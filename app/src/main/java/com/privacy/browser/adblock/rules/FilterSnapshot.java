package com.privacy.browser.adblock.rules;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** All four rule files and their parsed indexes. A snapshot never contains executable modules. */
public final class FilterSnapshot {
    public enum Part {
        HOSTS("hosts", "hosts.txt", 8 * 1024 * 1024, 450_000),
        NETWORK("network", "user_network.txt", 512 * 1024, 12_000),
        COSMETIC("cosmetic", "cosmetic_rules.txt", 512 * 1024, 12_000),
        USER_FILTERS("user_filters", "user_cosmetic.txt", 512 * 1024, 12_000);

        public final String key;
        public final String fileName;
        public final int byteLimit;
        final int ruleLimit;

        Part(String key, String fileName, int byteLimit, int ruleLimit) {
            this.key = key;
            this.fileName = fileName;
            this.byteLimit = byteLimit;
            this.ruleLimit = ruleLimit;
        }
    }

    public enum Network { NONE, BLOCK, EXCEPTION }
    private static final int MAX_RULE_CHARS = 4096;
    private static final String CSS = " {display:none!important;visibility:hidden!important;height:0!important;"
            + "max-height:0!important;min-height:0!important;margin:0!important;padding:0!important;"
            + "border:none!important;pointer-events:none!important;}\n";
    private static final Pattern HOST = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*");
    private static final Pattern PATH = Pattern.compile("/[A-Za-z0-9/._~%!$&'()+,;=:@?\\-]*");
    private final EnumMap<Part, Rules> parts;

    private FilterSnapshot(EnumMap<Part, Rules> parts) { this.parts = parts; }

    public static FilterSnapshot parse(Map<Part, String> text, boolean strict) {
        if (text.size() != Part.values().length) throw new IllegalArgumentException("Expected all four rule sections.");
        EnumMap<Part, Rules> parsed = new EnumMap<>(Part.class);
        for (Part part : Part.values()) parsed.put(part, new Rules(part, text.get(part), strict));
        return new FilterSnapshot(parsed);
    }

    /** Derived, checksummed local cache only. Imported rules always go through parse(). */
    void writeReady(DataOutputStream out) throws IOException {
        for (Part part : Part.values()) {
            Rules rules = parts.get(part);
            out.writeInt(rules.active);
            out.writeInt(rules.unsupported);
            if (part == Part.HOSTS) {
                out.writeInt(rules.hosts.size());
                for (String host : rules.hosts) out.writeUTF(host);
            } else {
                writePaths(out, rules.blocks);
                writePaths(out, rules.exceptions);
                out.writeInt(rules.cosmetics.size());
                for (Map.Entry<String, List<String>> item : rules.cosmetics.entrySet()) {
                    out.writeUTF(item.getKey());
                    out.writeInt(item.getValue().size());
                    for (String selector : item.getValue()) out.writeUTF(selector);
                }
            }
        }
    }

    static FilterSnapshot readReady(Map<Part, String> texts, Map<Part, Integer> lengths, DataInputStream in) throws IOException {
        EnumMap<Part, Rules> parsed = new EnumMap<>(Part.class);
        for (Part part : Part.values()) {
            int active = count(in, part.ruleLimit);
            int unsupported = count(in, part.ruleLimit - active);
            Rules rules = new Rules(texts.get(part), lengths.get(part), active, unsupported);
            if (part == Part.HOSTS) {
                int hosts = count(in, active);
                for (int i = 0; i < hosts; i++) {
                    if (!rules.hosts.add(readString(in, 253))) throw new IOException("Duplicate indexed host");
                }
            } else {
                int network = part == Part.COSMETIC ? 0 : active;
                network = readPaths(in, rules.blocks, network);
                readPaths(in, rules.exceptions, network);
                // A line may repeat the same domain. Bound fan-out by source bytes, not line count.
                int hosts = count(in, part == Part.NETWORK || active == 0 ? 0 : rules.bytes);
                for (int i = 0; i < hosts; i++) {
                    String host = readString(in, 253);
                    int size = count(in, rules.bytes);
                    List<String> selectors = new ArrayList<>();
                    for (int j = 0; j < size; j++) {
                        String selector = readString(in, MAX_RULE_CHARS);
                        if (!validSelectorList(selector)) throw new IOException("Invalid cached selector");
                        selectors.add(selector);
                    }
                    if (rules.cosmetics.put(host, selectors) != null) throw new IOException("Duplicate indexed selector bucket");
                }
            }
            parsed.put(part, rules);
        }
        return new FilterSnapshot(parsed);
    }

    private static void writePaths(DataOutputStream out, Map<String, List<PathRule>> index) throws IOException {
        out.writeInt(index.size());
        for (Map.Entry<String, List<PathRule>> item : index.entrySet()) {
            out.writeUTF(item.getKey());
            out.writeInt(item.getValue().size());
            for (PathRule rule : item.getValue()) {
                out.writeUTF(rule.prefix);
                out.writeBoolean(rule.separator);
            }
        }
    }

    private static int readPaths(DataInputStream in, Map<String, List<PathRule>> index, int remaining) throws IOException {
        int hosts = count(in, remaining);
        for (int i = 0; i < hosts; i++) {
            String host = readString(in, 253);
            int size = count(in, remaining);
            if (size == 0) throw new IOException("Empty indexed path bucket");
            remaining -= size;
            List<PathRule> paths = new ArrayList<>();
            for (int j = 0; j < size; j++) paths.add(new PathRule(readString(in, MAX_RULE_CHARS), in.readBoolean()));
            if (index.put(host, paths) != null) throw new IOException("Duplicate indexed path bucket");
        }
        return remaining;
    }

    private static int count(DataInputStream in, int limit) throws IOException {
        int value = in.readInt();
        if (value < 0 || value > limit) throw new IOException("Out of bounds rule index");
        return value;
    }

    private static String readString(DataInputStream in, int maxChars) throws IOException {
        String value = in.readUTF();
        if (value.length() > maxChars) throw new IOException("Invalid indexed rule length");
        return value;
    }

    public static void checkSection(Part part, String text) { new Rules(part, text, false); }

    public static void checkRule(Part part, String rule) {
        if (rule == null || rule.indexOf('\n') >= 0 || rule.indexOf('\r') >= 0
                || new Rules(part, rule, true).active != 1) {
            throw new IllegalArgumentException("Enter one supported rule line.");
        }
    }

    /** A local edit validates the changed section and retains unedited legacy bytes verbatim. */
    public FilterSnapshot replace(Part part, String text) {
        if (text(part).equals(text) && unsupported(part) == 0) return this;
        EnumMap<Part, Rules> next = new EnumMap<>(parts);
        next.put(part, new Rules(part, text, true));
        return new FilterSnapshot(next);
    }

    public String text(Part part) { return parts.get(part).text; }
    public int bytes(Part part) { return parts.get(part).bytes; }
    public int unsupported(Part part) { return parts.get(part).unsupported; }
    public int ruleCount(Part part) { return parts.get(part).active; }
    public int hostCount() { return parts.get(Part.HOSTS).hosts.size(); }

    public void requireSupported() {
        for (Part part : Part.values()) if (unsupported(part) != 0) {
            throw new IllegalArgumentException(part.key + " contains unsupported legacy rules. Review and correct them first.");
        }
    }

    public Network network(String host, String encodedPath, String encodedQuery) {
        if (host == null || host.isEmpty()) return Network.NONE;
        String suffix = normalizeHost(host);
        String path = null;
        boolean block = false;
        Rules user = parts.get(Part.USER_FILTERS), network = parts.get(Part.NETWORK);
        Set<String> hosts = parts.get(Part.HOSTS).hosts;
        do {
            List<PathRule> userAllow = user.exceptions.get(suffix), regularAllow = network.exceptions.get(suffix);
            List<PathRule> userBlock = block ? null : user.blocks.get(suffix), regularBlock = block ? null : network.blocks.get(suffix);
            // Most requests never hit a path bucket. Do not copy their encoded query just to walk hosts.
            if (path == null && (userAllow != null || regularAllow != null || userBlock != null || regularBlock != null)) {
                path = encodedPath == null || encodedPath.isEmpty() ? "/" : encodedPath;
                if (encodedQuery != null) path += "?" + encodedQuery;
            }
            if (matches(userAllow, path) || matches(regularAllow, path)) return Network.EXCEPTION;
            block = block || hosts.contains(suffix) || matches(userBlock, path) || matches(regularBlock, path);
            suffix = parent(suffix);
        } while (suffix != null);
        return block ? Network.BLOCK : Network.NONE;
    }

    public String cosmeticCss(String host) {
        Set<String> selectors = new LinkedHashSet<>();
        for (Part part : new Part[]{Part.COSMETIC, Part.USER_FILTERS}) {
            Map<String, List<String>> byHost = parts.get(part).cosmetics;
            add(selectors, byHost.get(""));
            String suffix = host == null || host.isEmpty() ? null : normalizeHost(host);
            while (suffix != null) {
                add(selectors, byHost.get(suffix));
                suffix = parent(suffix);
            }
        }
        StringBuilder css = new StringBuilder();
        // A selector unsupported by this provider cannot invalidate other rules' blocks.
        for (String selector : selectors) css.append(selector).append(CSS);
        return css.toString();
    }

    private static void add(Set<String> selectors, List<String> values) { if (values != null) selectors.addAll(values); }
    private static String parent(String host) {
        int dot = host.indexOf('.');
        return dot < 0 ? null : host.substring(dot + 1);
    }
    private static String normalizeHost(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.endsWith(".") ? lower.substring(0, lower.length() - 1) : lower;
    }
    private static boolean validHost(String host) { return host.length() <= 253 && HOST.matcher(host).matches(); }

    private static boolean matches(List<PathRule> rules, String path) {
        if (rules != null) for (PathRule rule : rules) if (rule.matches(path)) return true;
        return false;
    }

    private static final class PathRule {
        final String prefix;
        final boolean separator;
        PathRule(String prefix, boolean separator) { this.prefix = prefix; this.separator = separator; }
        boolean matches(String path) {
            if (!path.startsWith(prefix)) return false;
            if (!separator || path.length() == prefix.length()) return true;
            char next = path.charAt(prefix.length());
            return !(next >= '0' && next <= '9' || next >= 'a' && next <= 'z' || next >= 'A' && next <= 'Z'
                    || next == '_' || next == '-' || next == '.' || next == '%');
        }
    }

    public static boolean isComment(String line) {
        return line.isEmpty() || line.startsWith("!") || line.equals("#")
                || line.startsWith("# ") || line.startsWith("#\t");
    }

    public static String[] lines(String text) { return text.split("\r\n|\n|\r", -1); }

    private static final class Rules {
        final String text;
        final int bytes;
        final Set<String> hosts = new HashSet<>();
        final Map<String, List<PathRule>> blocks = new HashMap<>(), exceptions = new HashMap<>();
        final Map<String, List<String>> cosmetics = new HashMap<>();
        final int active, unsupported;

        private Rules(String text, int bytes, int active, int unsupported) {
            this.text = text;
            this.bytes = bytes;
            this.active = active;
            this.unsupported = unsupported;
        }

        Rules(Part part, String text, boolean strict) {
            this.text = text;
            bytes = utf8(text, part.byteLimit).length;
            int applied = 0, bad = 0, lineIndex = 0;
            for (String raw : lines(text)) {
                lineIndex++;
                String line = raw.trim();
                if (isComment(line)) continue;
                if (applied + bad >= part.ruleLimit) throw new IllegalArgumentException("Too many rules in " + part.key + ".");
                if (line.length() <= MAX_RULE_CHARS && parse(part, line)) applied++;
                else if (strict) throw new IllegalArgumentException("Unsupported rule at " + part.key + " line " + lineIndex + ".");
                else bad++;
            }
            active = applied;
            unsupported = bad;
        }

        private boolean parse(Part part, String line) {
            if (part == Part.HOSTS) {
                int comment = line.indexOf('#');
                if (comment > 0 && (line.charAt(comment - 1) == ' ' || line.charAt(comment - 1) == '\t')) line = line.substring(0, comment).trim();
                if (line.startsWith("0.0.0.0 ") || line.startsWith("0.0.0.0\t")) line = line.substring(8).trim();
                else if (line.startsWith("127.0.0.1 ") || line.startsWith("127.0.0.1\t")) line = line.substring(10).trim();
                String host = normalizeHost(line);
                if (!validHost(host) || !host.contains(".")) return false;
                hosts.add(host);
                return true;
            }
            if (line.startsWith("||") || line.startsWith("@@||")) {
                return part != Part.COSMETIC && networkRule(line);
            }
            if (part == Part.NETWORK) return false;
            int split = line.indexOf("##");
            if (split < 0) return false;
            String selector = line.substring(split + 2).trim();
            if (!validSelectorList(selector)) return false;
            String[] domains = line.substring(0, split).trim().split(",", -1);
            List<String> keys = new ArrayList<>();
            for (String domain : domains) {
                String key = normalizeHost(domain.trim());
                if (key.isEmpty() ? domains.length != 1 || !domain.isEmpty() : !validHost(key)) return false;
                keys.add(key);
            }
            for (String key : keys) cosmetics.computeIfAbsent(key, ignored -> new ArrayList<>()).add(selector);
            return true;
        }

        private boolean networkRule(String line) {
            boolean allow = line.startsWith("@@");
            String target = line.substring(allow ? 4 : 2);
            boolean terminal = target.endsWith("^");
            if (terminal) target = target.substring(0, target.length() - 1);
            int slash = target.indexOf('/');
            if (slash < 0 && !terminal) return false;
            String domain = normalizeHost(slash < 0 ? target : target.substring(0, slash));
            String prefix = slash < 0 ? "" : target.substring(slash);
            // Dollar is the ABP options marker; use its URL encoding in a literal path.
            if (!validHost(domain) || (slash >= 0 && (!PATH.matcher(prefix).matches() || prefix.indexOf('$') >= 0))) return false;
            (allow ? exceptions : blocks).computeIfAbsent(domain, ignored -> new ArrayList<>())
                    .add(new PathRule(prefix, slash >= 0 && terminal));
            return true;
        }
    }

    /** A picker rule stays tied to the selected host and uses the same grammar as saved rules. */
    public static String pickerSelector(String rule, String host) {
        if (rule == null || host == null || !validHost(host) || rule.length() > MAX_RULE_CHARS
                || !rule.startsWith(host + "##") || rule.indexOf('\r') >= 0 || rule.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Enter a selector scoped to " + host + ".");
        }
        String selector = rule.substring(host.length() + 2).trim();
        if (!validSelectorList(selector)) throw new IllegalArgumentException("Enter a supported CSS selector list.");
        return selector;
    }

    /** Only selector lists, never rule blocks, @-rules, scriptlet or procedural directives. */
    private static boolean validSelectorList(String selector) {
        String lower = selector.toLowerCase(Locale.ROOT);
        if (selector.isEmpty() || selector.startsWith("+js(") || selector.startsWith("^") || lower.contains(":has-text(")
                || lower.contains(":matches-") || lower.contains(":xpath(") || lower.contains(":remove(")
                || lower.contains(":style(") || lower.contains(":-abp-") || lower.contains(":upward(")
                || lower.contains(":watch-attr(") || lower.contains(":min-text-length(") || selector.contains("/*")) return false;
        char quote = 0;
        char[] opens = new char[32];
        int depth = 0;
        boolean piece = false;
        for (int i = 0; i < selector.length(); i++) {
            char ch = selector.charAt(i);
            if (Character.isISOControl(ch) || ch == '{' || ch == '}') return false;
            if (ch == '\\') {
                if (++i == selector.length() || Character.isISOControl(selector.charAt(i))) return false;
                piece = true;
                continue;
            }
            if (quote != 0) {
                if (ch == quote) quote = 0;
                continue;
            }
            if (ch == ';' || ch == '@') return false;
            if (ch == '\'' || ch == '"') quote = ch;
            else if (ch == '(' || ch == '[') {
                if (depth == opens.length) return false;
                opens[depth++] = ch;
            } else if (ch == ')' || ch == ']') {
                if (depth == 0 || opens[--depth] != (ch == ')' ? '(' : '[')) return false;
            } else if (depth == 0 && ch == ',') {
                if (!piece) return false;
                piece = false;
                continue;
            }
            if (!Character.isWhitespace(ch)) piece = true;
        }
        return quote == 0 && depth == 0 && piece;
    }

    static byte[] utf8(String text, int max) {
        if (text == null || text.length() > max) throw new IllegalArgumentException("Rule section exceeds its size limit.");
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) throw new IllegalArgumentException("Invalid Unicode in rules.");
            } else if (Character.isLowSurrogate(ch) || ch == 0) throw new IllegalArgumentException("Invalid text in rules.");
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > max) throw new IllegalArgumentException("Rule section exceeds its size limit.");
        return bytes;
    }

    public static String readText(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = in.read(buffer)) != -1) {
            if (count > max - out.size()) throw new IOException("Response or rule file exceeds its size limit.");
            out.write(buffer, 0, count);
        }
        return decode(out.toByteArray());
    }

    static String decode(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }
}
