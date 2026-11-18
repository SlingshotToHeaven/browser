package com.privacy.browser.adblock.cloud;

import com.privacy.browser.adblock.rules.FilterSnapshot;
import com.privacy.browser.adblock.rules.FilterSnapshot.Part;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Plan from the exact incoming snapshot and exact local snapshot shown in the preview. */
public final class AdBlockDiffEngine {
    public enum ApplyMode { MAKE_SAME, MERGE }
    private static final int SAMPLES = 5;
    private AdBlockDiffEngine() {}

    public static final class Change {
        public final Part part;
        public final int added, removed, resultRules, resultBytes;
        public final List<String> samples;

        private Change(Part part, Set<String> before, Set<String> after, FilterSnapshot result) {
            this.part = part;
            List<String> examples = new ArrayList<>();
            added = count(after, before, "+ ", examples);
            removed = count(before, after, "− ", examples);
            resultRules = result.ruleCount(part);
            resultBytes = result.bytes(part);
            samples = Collections.unmodifiableList(examples);
        }

        private static int count(Set<String> lines, Set<String> against, String marker, List<String> examples) {
            int total = 0, here = 0;
            for (String line : lines) if (!against.contains(line)) {
                total++;
                if (here++ < SAMPLES) examples.add(marker + (line.length() > 150 ? line.substring(0, 150) + "…" : line));
            }
            return total;
        }
    }

    public static final class Plan {
        public final FilterSnapshot expected, result;
        public final List<Change> changes;
        public final ApplyMode mode;

        private Plan(FilterSnapshot expected, FilterSnapshot result, List<Change> changes, ApplyMode mode) {
            this.expected = expected;
            this.result = changes.isEmpty() ? expected : result;
            this.changes = Collections.unmodifiableList(changes);
            this.mode = mode;
        }
    }

    public static Plan preview(FilterSnapshot local, FilterSnapshot incoming, ApplyMode mode) {
        incoming.requireSupported();
        EnumMap<Part, String> merged = new EnumMap<>(Part.class);
        EnumMap<Part, Set<String>> localRules = new EnumMap<>(Part.class);
        for (Part part : Part.values()) {
            Set<String> before = lines(local.text(part));
            localRules.put(part, before);
            if (mode != ApplyMode.MERGE) continue;
            String base = local.text(part);
            StringBuilder output = new StringBuilder(base);
            for (String rule : lines(incoming.text(part))) if (!before.contains(rule)) {
                if (output.length() > 0 && output.charAt(output.length() - 1) != '\n') output.append('\n');
                output.append(rule).append('\n');
            }
            merged.put(part, output.toString());
        }
        // Merge intentionally preserves local comments/order, and never removes rules. If a
        // legacy line cannot be parsed, require correction or deliberate exact replacement.
        FilterSnapshot result = mode == ApplyMode.MAKE_SAME ? incoming : FilterSnapshot.parse(merged, true);
        List<Change> changes = new ArrayList<>();
        for (Part part : Part.values()) {
            if (!local.text(part).equals(result.text(part))) {
                changes.add(new Change(part, localRules.get(part), lines(result.text(part)), result));
            }
        }
        return new Plan(local, result, changes, mode);
    }

    private static Set<String> lines(String content) {
        Set<String> lines = new LinkedHashSet<>();
        for (String raw : FilterSnapshot.lines(content)) {
            String line = raw.trim();
            if (!FilterSnapshot.isComment(line)) lines.add(line);
        }
        return lines;
    }
}
