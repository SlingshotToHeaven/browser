package com.privacy.browser.adblock.cloud;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure Java Diff & Patch Engine for AdBlock states.
 * Supports:
 *   - Set diffs for line-based rule lists (hosts.txt, cosmetic_rules.txt)
 *   - Line-by-line additions and deletions for JavaScript scriptlets (.js)
 *   - Three applying modes:
 *     1. MAKE_SAME: Overwrites local files to match the uploader's state exactly.
 *     2. ADD_ONLY: Merges additions without removing any existing local rules/code.
 *     3. REMOVE_ONLY: Only applies the removals from the commit (ideal for fixing false positives).
 */
public class AdBlockDiffEngine {

    public enum ApplyMode {
        MAKE_SAME,
        ADD_ONLY,
        REMOVE_ONLY
    }

    /**
     * Computes a structured diff representation between original base content and modified content.
     */
    @NonNull
    public static JSONObject computeDiff(@NonNull String relativePath,
                                         @NonNull String baseContent,
                                         @NonNull String modifiedContent) {
        JSONObject diffObj = new JSONObject();
        try {
            boolean isListFile = relativePath.endsWith(".txt");
            diffObj.put("file", relativePath);
            diffObj.put("type", isListFile ? "set_diff" : "line_patch");

            if (isListFile) {
                Set<String> baseLines = parseLinesToSet(baseContent);
                Set<String> modLines = parseLinesToSet(modifiedContent);

                JSONArray addedArr = new JSONArray();
                for (String line : modLines) {
                    if (!baseLines.contains(line)) {
                        addedArr.put(line);
                    }
                }

                JSONArray removedArr = new JSONArray();
                for (String line : baseLines) {
                    if (!modLines.contains(line)) {
                        removedArr.put(line);
                    }
                }

                diffObj.put("added", addedArr);
                diffObj.put("removed", removedArr);
            } else {
                // Line-level diff for scriptlets (.js)
                List<String> baseLines = splitLines(baseContent);
                List<String> modLines = splitLines(modifiedContent);

                JSONArray addedLines = new JSONArray();
                JSONArray removedLines = new JSONArray();

                Set<String> baseSet = new LinkedHashSet<>(baseLines);
                Set<String> modSet = new LinkedHashSet<>(modLines);

                for (String line : modLines) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !baseSet.contains(line)) {
                        addedLines.put(line);
                    }
                }

                for (String line : baseLines) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !modSet.contains(line)) {
                        removedLines.put(line);
                    }
                }

                diffObj.put("added_lines", addedLines);
                diffObj.put("removed_lines", removedLines);
                // Also store full snapshot for clean MAKE_SAME mode
                diffObj.put("full_snapshot", modifiedContent);
            }
        } catch (Exception e) {
            // Fallback safe representation
        }
        return diffObj;
    }

    /**
     * Applies a diff onto the current local content according to the requested ApplyMode.
     */
    @NonNull
    public static String applyDiff(@NonNull String currentContent,
                                   @NonNull JSONObject diffObj,
                                   @NonNull ApplyMode mode) {
        try {
            String type = diffObj.optString("type", "set_diff");
            if ("set_diff".equals(type)) {
                return applySetDiff(currentContent, diffObj, mode);
            } else {
                return applyCodeDiff(currentContent, diffObj, mode);
            }
        } catch (Exception e) {
            return currentContent;
        }
    }

    private static String applySetDiff(@NonNull String currentContent,
                                       @NonNull JSONObject diffObj,
                                       @NonNull ApplyMode mode) {
        JSONArray addedJson = diffObj.optJSONArray("added");
        JSONArray removedJson = diffObj.optJSONArray("removed");

        List<String> currentLines = splitLines(currentContent);
        Set<String> currentSet = new LinkedHashSet<>(currentLines);

        Set<String> toAdd = new LinkedHashSet<>();
        if (addedJson != null && (mode == ApplyMode.MAKE_SAME || mode == ApplyMode.ADD_ONLY)) {
            for (int i = 0; i < addedJson.length(); i++) {
                String line = addedJson.optString(i, "").trim();
                if (!line.isEmpty()) toAdd.add(line);
            }
        }

        Set<String> toRemove = new LinkedHashSet<>();
        if (removedJson != null && (mode == ApplyMode.MAKE_SAME || mode == ApplyMode.REMOVE_ONLY)) {
            for (int i = 0; i < removedJson.length(); i++) {
                String line = removedJson.optString(i, "").trim();
                if (!line.isEmpty()) toRemove.add(line);
            }
        }

        StringBuilder result = new StringBuilder();
        for (String line : currentLines) {
            String trimmed = line.trim();
            if (toRemove.contains(trimmed)) {
                continue; // Omit removed lines
            }
            if (result.length() > 0) result.append("\n");
            result.append(line);
            toAdd.remove(trimmed); // Already present
        }

        // Append remaining additions at the bottom
        for (String addLine : toAdd) {
            if (result.length() > 0) result.append("\n");
            result.append(addLine);
        }

        return result.toString();
    }

    private static String applyCodeDiff(@NonNull String currentContent,
                                        @NonNull JSONObject diffObj,
                                        @NonNull ApplyMode mode) {
        if (mode == ApplyMode.MAKE_SAME && diffObj.has("full_snapshot")) {
            return diffObj.optString("full_snapshot", currentContent);
        }

        JSONArray addedJson = diffObj.optJSONArray("added_lines");
        JSONArray removedJson = diffObj.optJSONArray("removed_lines");

        List<String> currentLines = splitLines(currentContent);

        // 1. Remove lines if in REMOVE_ONLY mode
        if (mode == ApplyMode.REMOVE_ONLY && removedJson != null) {
            Set<String> toRemove = new LinkedHashSet<>();
            for (int i = 0; i < removedJson.length(); i++) {
                String l = removedJson.optString(i, "").trim();
                if (!l.isEmpty()) toRemove.add(l);
            }
            StringBuilder sb = new StringBuilder();
            for (String line : currentLines) {
                if (toRemove.contains(line.trim())) {
                    continue; // Skip line to be removed
                }
                if (sb.length() > 0) sb.append("\n");
                sb.append(line);
            }
            return sb.toString();
        }

        // 2. Add lines if in ADD_ONLY mode
        if (mode == ApplyMode.ADD_ONLY && addedJson != null) {
            Set<String> existingTrimmed = new LinkedHashSet<>();
            for (String line : currentLines) {
                existingTrimmed.add(line.trim());
            }

            StringBuilder additions = new StringBuilder();
            for (int i = 0; i < addedJson.length(); i++) {
                String newLine = addedJson.optString(i, "");
                if (!newLine.trim().isEmpty() && !existingTrimmed.contains(newLine.trim())) {
                    if (additions.length() > 0) additions.append("\n");
                    additions.append(newLine);
                }
            }

            if (additions.length() == 0) {
                return currentContent;
            }

            // Append new code safely before closing IIFE tags or at bottom
            String trimmed = currentContent.trim();
            if (trimmed.endsWith("})();") || trimmed.endsWith("})();\n")) {
                int lastIdx = currentContent.lastIndexOf("})();");
                if (lastIdx != -1) {
                    return currentContent.substring(0, lastIdx) +
                        "\n  // Community Cloud State Additions\n  " +
                        additions.toString().replace("\n", "\n  ") +
                        "\n})();";
                }
            }
            return currentContent + "\n\n// Community Cloud State Additions\n" + additions.toString();
        }

        return currentContent;
    }

    private static Set<String> parseLinesToSet(String content) {
        Set<String> set = new LinkedHashSet<>();
        if (content == null) return set;
        String[] lines = content.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith("!")) {
                set.add(trimmed);
            }
        }
        return set;
    }

    private static List<String> splitLines(String content) {
        List<String> list = new ArrayList<>();
        if (content == null) return list;
        String[] lines = content.split("\n", -1);
        for (String line : lines) {
            list.add(line);
        }
        return list;
    }
}
