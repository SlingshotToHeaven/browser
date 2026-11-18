package com.privacy.browser.ai;

import android.os.Build;
import android.text.Html;
import android.text.Spanned;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MarkdownRenderer {

    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("```(?:\\w+)?\\n?(.*?)```", Pattern.DOTALL);
    private static final Pattern INLINE_CODE_PATTERN = Pattern.compile("`([^`\\n]+?)`");
    private static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern ITALIC_PATTERN = Pattern.compile("(?<!\\*)\\*([^\\*\\n]+?)\\*(?!\\*)");
    private static final Pattern LINK_PATTERN = Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)");
    private static final Pattern HEADER_PATTERN = Pattern.compile("(?m)^#{1,6}\\s*(.+)$");
    private static final Pattern BULLET_PATTERN = Pattern.compile("(?m)^[\\*\\-]\\s+(.+)$");
    private static final Pattern NUMBERED_PATTERN = Pattern.compile("(?m)^(\\d+)\\.\\s+(.+)$");

    public static Spanned format(String markdown, boolean isDark) {
        if (markdown == null || markdown.isEmpty()) {
            return Html.fromHtml("");
        }

        // 1. Escape HTML entities
        String escaped = markdown
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");

        String codeColor = isDark ? "#38BDF8" : "#0284C7";

        // 2. Fenced code blocks
        Matcher cbMatcher = CODE_BLOCK_PATTERN.matcher(escaped);
        StringBuffer sb = new StringBuffer();
        while (cbMatcher.find()) {
            String block = cbMatcher.group(1);
            String replacement = "<br/><tt><font color=\"" + codeColor + "\">" + block.replace("\n", "<br/>") + "</font></tt><br/>";
            cbMatcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        cbMatcher.appendTail(sb);
        String text = sb.toString();

        // 3. Inline code
        Matcher icMatcher = INLINE_CODE_PATTERN.matcher(text);
        sb = new StringBuffer();
        while (icMatcher.find()) {
            String code = icMatcher.group(1);
            String replacement = "<tt><font color=\"" + codeColor + "\">" + code + "</font></tt>";
            icMatcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        icMatcher.appendTail(sb);
        text = sb.toString();

        // Provider text is readable, not an Android intent or a shortcut around review.
        text = LINK_PATTERN.matcher(text).replaceAll("$1 ($2)");

        // 5. Bold **text**
        text = BOLD_PATTERN.matcher(text).replaceAll("<b>$1</b>");

        // 6. Italic *text*
        text = ITALIC_PATTERN.matcher(text).replaceAll("<i>$1</i>");

        // 7. Headers #, ##, ###
        text = HEADER_PATTERN.matcher(text).replaceAll("<b>$1</b>");

        // 8. Bullet points and numbered lists
        text = BULLET_PATTERN.matcher(text).replaceAll("&bull;&nbsp;$1");
        text = NUMBERED_PATTERN.matcher(text).replaceAll("$1.&nbsp;$2");

        // 9. Newlines to break tags
        text = text.replace("\n", "<br/>");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY);
        } else {
            return Html.fromHtml(text);
        }
    }
}
