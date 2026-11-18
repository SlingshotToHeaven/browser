package com.privacy.browser.adblock;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.privacy.browser.adblock.rules.FilterIndexCache;
import com.privacy.browser.adblock.rules.FilterSnapshot;
import com.privacy.browser.adblock.rules.FilterSnapshot.Part;
import com.privacy.browser.adblock.rules.FilterStore;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Manages modular, editable, and hot-reloadable ad block configuration files,
 * filter lists, and JavaScript scriptlets stored in the app's internal storage.
 * Automatically seeds default assets on first launch and provides safe
 * restore-to-default capabilities.
 */
public class AdBlockFileManager {

    private static final String TAG = "AdBlockFileManager";

    public static final String DIR_ADBLOCK = "adblock";
    public static final String DIR_NETWORK = "network";
    public static final String DIR_COSMETIC = "cosmetic";
    public static final String DIR_SCRIPTLETS = "scriptlets";
    public static final String DIR_USER_SCRIPTS = "user_scripts";

    // File definitions
    public static final String FILE_HOSTS = "hosts.txt";
    public static final String FILE_USER_NETWORK = "user_network.txt";
    public static final String FILE_COSMETIC_RULES = "cosmetic_rules.txt";
    public static final String FILE_USER_COSMETIC = "user_cosmetic.txt";
    public static final String FILE_SCRIPTLET_ANTI_ADBLOCK = "anti_adblock.js";
    public static final String FILE_SCRIPTLET_COSMETIC_PRUNER = "cosmetic_pruner.js";
    public static final String FILE_SCRIPTLET_POPUP_DEFUSER = "popup_defuser.js";
    public static final String FILE_SCRIPTLET_YOUTUBE_DEFUSER = "youtube_defuser.js";

    // Asset paths
    private static final String ASSET_HOSTS = "filters/hosts.txt";
    private static final String ASSET_COSMETIC = "filters/cosmetic_rules.txt";
    private static final String ASSET_SCRIPTLET_ANTI_ADBLOCK = "filters/scriptlets/anti_adblock.js";
    private static final String ASSET_SCRIPTLET_COSMETIC_PRUNER = "filters/scriptlets/cosmetic_pruner.js";
    private static final String ASSET_SCRIPTLET_POPUP_DEFUSER = "filters/scriptlets/popup_defuser.js";
    private static final String ASSET_SCRIPTLET_YOUTUBE_DEFUSER = "filters/scriptlets/youtube_defuser.js";
    private static final String ASSET_DOM_WORK = "filters/scriptlets/bounded_dom_work.js";
    private String domWork; // accessed only while loading/caching the assembled scriptlets

    private static volatile AdBlockFileManager instance;

    public static AdBlockFileManager getInstance(@NonNull Context context) {
        if (instance == null) {
            synchronized (AdBlockFileManager.class) {
                if (instance == null) {
                    instance = new AdBlockFileManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    /** Recovery text can be inspected, but incomplete current rules must not start browsing. */
    static synchronized void prepareForBrowsing(Context context) {
        if (instance != null && instance.ruleWarning() == null) return;
        AdBlockFileManager prepared = new AdBlockFileManager(context.getApplicationContext());
        if (prepared.ruleWarning() != null) throw new IllegalStateException(prepared.ruleWarning());
        instance = prepared;
    }

    private final Context context;
    private final File rootDir;
    private final File networkDir;
    private final File cosmeticDir;
    private final File scriptletsDir;
    private final File userScriptsDir;
    private final FilterStore rules;
    private final FilterIndexCache readyCache;
    private static final Executor INDEX_WRITER = Executors.newSingleThreadExecutor(task -> new Thread(task, "browser-rule-index"));
    private String legacyWarning;

    private AdBlockFileManager(@NonNull Context context) {
        this.context = context;
        this.rootDir = new File(context.getFilesDir(), DIR_ADBLOCK);
        this.networkDir = new File(rootDir, DIR_NETWORK);
        this.cosmeticDir = new File(rootDir, DIR_COSMETIC);
        this.scriptletsDir = new File(rootDir, DIR_SCRIPTLETS);
        this.userScriptsDir = new File(rootDir, DIR_USER_SCRIPTS);
        ensureDirectories();
        seedScriptsIfMissing();
        readyCache = new FilterIndexCache(new File(rootDir, "rules-index-v1.bin").toPath(), INDEX_WRITER);
        try {
            rules = new FilterStore(new File(rootDir, "rules-v2.bin").toPath(), this::readLegacyRules, readyCache);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read filter configuration", e);
        }
    }

    private void ensureDirectories() {
        if (!networkDir.exists()) networkDir.mkdirs();
        if (!cosmeticDir.exists()) cosmeticDir.mkdirs();
        if (!scriptletsDir.exists()) scriptletsDir.mkdirs();
        if (!userScriptsDir.exists()) userScriptsDir.mkdirs();
    }

    private void seedScriptsIfMissing() {
        File antiAbFile = getFile(Category.SCRIPTLETS, FILE_SCRIPTLET_ANTI_ADBLOCK);
        if (!antiAbFile.exists() || antiAbFile.length() == 0) {
            copyAssetToFile(ASSET_SCRIPTLET_ANTI_ADBLOCK, antiAbFile);
        } else {
            String content = readFileContent(Category.SCRIPTLETS, FILE_SCRIPTLET_ANTI_ADBLOCK);
            if (!content.contains("isAdBlockActive")) {
                copyAssetToFile(ASSET_SCRIPTLET_ANTI_ADBLOCK, antiAbFile);
            }
        }

        seedOptimizedScriptlet(FILE_SCRIPTLET_COSMETIC_PRUNER, ASSET_SCRIPTLET_COSMETIC_PRUNER);
        seedOptimizedScriptlet(FILE_SCRIPTLET_POPUP_DEFUSER, ASSET_SCRIPTLET_POPUP_DEFUSER);

        File ytFile = getFile(Category.SCRIPTLETS, FILE_SCRIPTLET_YOUTUBE_DEFUSER);
        if (!ytFile.exists() || ytFile.length() == 0) {
            copyAssetToFile(ASSET_SCRIPTLET_YOUTUBE_DEFUSER, ytFile);
        } else if (ytFile.length() <= YouTubeAdDefuser.MAX_SCRIPT_BYTES) {
            String content = readFileContent(Category.SCRIPTLETS, FILE_SCRIPTLET_YOUTUBE_DEFUSER);
            if (YouTubeAdDefuser.isLegacyDefault(content)) {
                copyAssetToFile(ASSET_SCRIPTLET_YOUTUBE_DEFUSER, ytFile);
            }
        }
    }

    private void seedOptimizedScriptlet(String name, String asset) {
        File file = getFile(Category.SCRIPTLETS, name);
        try {
            if (Files.size(file.toPath()) == 0 || previousStock(file)) copyAssetToFile(asset, file);
        } catch (NoSuchFileException missing) {
            copyAssetToFile(asset, file);
        } catch (IOException unreadable) {
            Log.w(TAG, "Preserved uninspected filter scriptlet " + name);
        }
    }

    private static boolean previousStock(File file) {
        // Compare exact seeded bytes (including the final LF), not the editor's normalized text.
        switch (file.getName()) {
            case FILE_SCRIPTLET_COSMETIC_PRUNER:
                return matchesStock(file, 6265, "196355e4662a381f348621cacb92530b5c32344ae6040e4d1816f08a0fd8eacb");
            case FILE_SCRIPTLET_POPUP_DEFUSER:
                return matchesStock(file, 4416, "dd2a84e62f8a40bb4e3671562f0a8a7f14774b148ec53b4612ddbcffdc3192e7");
            default:
                return false;
        }
    }

    static boolean matchesStock(File file, int size, String digest) {
        if (file.length() != size) return false;
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file.toPath()))) {
            byte[] bytes = new byte[size];
            in.readFully(bytes);
            if (in.read() != -1) return false;
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            for (int i = 0; i < hash.length; i++) {
                if ((hash[i] & 0xff) != Integer.parseInt(digest.substring(2 * i, 2 * i + 2), 16)) return false;
            }
            return true;
        } catch (IOException ignored) {
            return false; // unknown/unreadable is not evidence that we can replace an existing file
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    String optimizedScriptlet(String name) {
        File file = getFile(Category.SCRIPTLETS, name);
        // Use the new packaged body even if replacing a known old stock file failed.
        String js = previousStock(file) ? "" : readFileContent(Category.SCRIPTLETS, name);
        if (js.trim().isEmpty()) js = readBundledAssetContent(Category.SCRIPTLETS, name);
        if (js.trim().isEmpty()) throw new IllegalStateException("Missing filter scriptlet: " + name);
        return "(function(__privacyObserveElements){(function(){\n" + js + "\n})();})(\n" + readDomWork() + "\n);";
    }

    private synchronized String readDomWork() {
        if (domWork == null) {
            try (InputStream in = context.getAssets().open(ASSET_DOM_WORK)) {
                String source = FilterSnapshot.readText(in, 32 * 1024);
                if (source.trim().isEmpty()) throw new IOException("Empty DOM work helper");
                domWork = source;
            } catch (IOException e) {
                throw new IllegalStateException("Could not read filter scriptlet support", e);
            }
        }
        return domWork;
    }

    public enum Category {
        NETWORK,
        COSMETIC,
        SCRIPTLETS,
        USER_SCRIPTS
    }

    private static Part part(Category category, String name) {
        if (category == Category.NETWORK) {
            if (FILE_HOSTS.equals(name)) return Part.HOSTS;
            if (FILE_USER_NETWORK.equals(name)) return Part.NETWORK;
        } else if (category == Category.COSMETIC) {
            if (FILE_COSMETIC_RULES.equals(name)) return Part.COSMETIC;
            if (FILE_USER_COSMETIC.equals(name)) return Part.USER_FILTERS;
        }
        return null;
    }

    private FilterSnapshot readLegacyRules() throws IOException {
        EnumMap<Part, String> legacy = new EnumMap<>(Part.class);
        for (Part part : Part.values()) {
            String text = null;
            try {
                text = readOptionalRules(getFile(ruleCategory(part), part.fileName), part);
                if (text == null && part == Part.USER_FILTERS) {
                    text = readOptionalRules(new File(context.getFilesDir(), "user_filters.txt"), part);
                }
            } catch (IOException | IllegalArgumentException unreadable) { warnLegacy(part); }
            legacy.put(part, text == null ? bundledRules(part) : text);
        }
        try {
            return readyCache.loadOrBuild(legacy);
        } catch (IllegalArgumentException invalidSection) {
            // Isolate the damaged section only on recovery, rather than parsing each normal legacy list twice.
            for (Part part : Part.values()) {
                try { FilterSnapshot.checkSection(part, legacy.get(part)); }
                catch (IllegalArgumentException invalid) {
                    warnLegacy(part);
                    legacy.put(part, bundledRules(part));
                }
            }
            return readyCache.loadOrBuild(legacy);
        }
    }

    private static String readOptionalRules(File file, Part part) throws IOException {
        try (InputStream in = Files.newInputStream(file.toPath())) {
            return FilterSnapshot.readText(in, part.byteLimit);
        } catch (NoSuchFileException missing) { return null; }
    }

    private static Category ruleCategory(Part part) {
        return part == Part.HOSTS || part == Part.NETWORK ? Category.NETWORK : Category.COSMETIC;
    }

    private String bundledRules(Part part) throws IOException {
        String asset = getAssetPath(ruleCategory(part), part.fileName);
        if (asset == null) return readBundledAssetContent(ruleCategory(part), part.fileName);
        try (InputStream in = context.getAssets().open(asset)) {
            String text = FilterSnapshot.readText(in, part.byteLimit);
            if (part == Part.HOSTS && text.isEmpty()) throw new IOException("Bundled hosts are empty");
            return text;
        }
    }

    private void warnLegacy(Part part) {
        legacyWarning = "A legacy rule file was unreadable or exceeded limits. Its bytes were preserved; rules are read-only.";
        Log.w(TAG, "Could not load legacy rule section " + part.key);
    }

    public FilterSnapshot ruleSnapshot() { return rules.current(); }

    @Nullable public String ruleWarning() { return legacyWarning != null ? legacyWarning : rules.recoveryWarning(); }

    private void requireWritableRules() throws IOException {
        String warning = ruleWarning();
        if (warning != null) throw new IOException(warning);
    }

    public void applyRuleSnapshot(FilterSnapshot expected, FilterSnapshot next) throws IOException {
        requireWritableRules();
        rules.commit(expected, next);
        if (expected != next) WebScriptPolicy.changed();
    }

    public void editRulePart(Part part, String content) throws IOException {
        requireWritableRules();
        FilterSnapshot old = rules.current();
        rules.edit(part, content);
        if (old != rules.current()) WebScriptPolicy.changed();
    }

    @NonNull
    public File getFile(@NonNull Category category, @NonNull String fileName) {
        if (category == Category.USER_SCRIPTS ? !fileName.matches("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,95}\\.[jJ][sS]")
                : category == Category.SCRIPTLETS ? getAssetPath(category, fileName) == null : part(category, fileName) == null) {
            throw new IllegalArgumentException("Unknown filter destination.");
        }
        switch (category) {
            case NETWORK:
                return new File(networkDir, fileName);
            case COSMETIC:
                return new File(cosmeticDir, fileName);
            case USER_SCRIPTS:
                return new File(userScriptsDir, fileName);
            case SCRIPTLETS:
            default:
                return new File(scriptletsDir, fileName);
        }
    }

    @NonNull
    public List<File> getUserScriptFiles() {
        if (!userScriptsDir.exists()) return Collections.emptyList();
        File[] files = userScriptsDir.listFiles((dir, name) -> name != null && name.matches("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,95}\\.[jJ][sS]"));
        if (files == null || files.length == 0) return Collections.emptyList();
        List<File> list = new ArrayList<>(java.util.Arrays.asList(files));
        Collections.sort(list, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return list;
    }

    @NonNull
    public String readUserScript(@NonNull String fileName) {
        return readFileContent(Category.USER_SCRIPTS, fileName);
    }

    public boolean writeUserScript(@NonNull String fileName, @NonNull String content) {
        return writeFileContent(Category.USER_SCRIPTS, fileName, content);
    }

    public boolean deleteUserScript(@NonNull String fileName) {
        File f = getFile(Category.USER_SCRIPTS, fileName);
        return f.exists() && f.delete();
    }

    public boolean createUserScript(@NonNull String fileName, @NonNull String runAt) {
        return createUserScript(fileName, runAt, "*", null);
    }

    public boolean createUserScript(@NonNull String fileName, @NonNull String runAt, @NonNull String match, @Nullable String customCode) {
        String cleanName = fileName.trim();
        if (!cleanName.toLowerCase(java.util.Locale.US).endsWith(".js")) {
            cleanName += ".js";
        }
        File file = getFile(Category.USER_SCRIPTS, cleanName);
        if (file.exists()) {
            return false;
        }
        String cleanMatch = (match == null || match.trim().isEmpty()) ? "*" : match.trim();
        String starterCode;
        if (customCode != null && !customCode.trim().isEmpty()) {
            starterCode = "// ==UserScript==\n" +
                    "// @name         " + cleanName.replace(".js", "") + "\n" +
                    "// @run-at       " + runAt + "\n" +
                    "// @match        " + cleanMatch + "\n" +
                    "// ==/UserScript==\n\n" +
                    customCode.trim() + "\n";
        } else {
            starterCode = "// ==UserScript==\n" +
                    "// @name         " + cleanName.replace(".js", "") + "\n" +
                    "// @run-at       " + runAt + "\n" +
                    "// @match        " + cleanMatch + "\n" +
                    "// ==/UserScript==\n\n" +
                    "(function() {\n" +
                    "    'use strict';\n" +
                    "    // Custom adblock scriptlet executed at " + runAt + "\n" +
                    "})();\n";
        }
        return writeFileContent(Category.USER_SCRIPTS, cleanName, starterCode);
    }

    /**
     * Reads a file completely into a UTF-8 String.
     */
    @NonNull
    public String readFileContent(@NonNull Category category, @NonNull String fileName) {
        Part part = part(category, fileName);
        if (part != null) return rules.current().text(part);
        File file = getFile(category, fileName);
        if (!file.exists()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (FileInputStream fis = new FileInputStream(file);
             BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (!first) sb.append("\n");
                sb.append(line);
                first = false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading file: " + fileName, e);
        }
        return sb.toString();
    }

    /**
     * Reads up to maxLines lines of a file into a UTF-8 String preview.
     */
    @NonNull
    public String readFilePreview(@NonNull Category category, @NonNull String fileName, int maxLines) {
        Part part = part(category, fileName);
        if (part != null) {
            String text = rules.current().text(part);
            int end = 0;
            for (int i = 0; i < maxLines && end < text.length(); i++) {
                int next = text.indexOf('\n', end);
                end = next == -1 ? text.length() : next + 1;
            }
            return text.substring(0, end);
        }
        File file = getFile(category, fileName);
        if (!file.exists()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (FileInputStream fis = new FileInputStream(file);
             BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
            String line;
            int count = 0;
            boolean first = true;
            while ((line = reader.readLine()) != null && count < maxLines) {
                if (!first) sb.append("\n");
                sb.append(line);
                first = false;
                count++;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading preview for file: " + fileName, e);
        }
        return sb.toString();
    }

    /**
     * Writes content into the target file safely using UTF-8 encoding.
     */
    public boolean writeFileContent(@NonNull Category category, @NonNull String fileName, @NonNull String content) {
        Part part = part(category, fileName);
        if (part == null) return writeStringToFile(getFile(category, fileName), content);
        try {
            editRulePart(part, content);
            return true;
        } catch (IOException | IllegalArgumentException e) {
            Log.w(TAG, "Rejected edit to " + part.key, e);
            return false;
        }
    }

    /**
     * Reads the pristine bundled asset template into a UTF-8 String.
     */
    @NonNull
    public String readBundledAssetContent(@NonNull Category category, @NonNull String fileName) {
        String assetPath = getAssetPath(category, fileName);
        if (assetPath == null) {
            if (category == Category.USER_SCRIPTS) return "";
            return (category == Category.NETWORK)
                ? "! Custom User Network Filter Rules (||domain^ or @@||domain^)\n"
                : "! Custom User Cosmetic Rules (##selector or domain##selector)\n";
        }
        try (InputStream is = context.getAssets().open(assetPath)) {
            Part part = part(category, fileName);
            return FilterSnapshot.readText(is, part == null ? 256 * 1024 : part.byteLimit);
        } catch (IOException e) {
            Log.e(TAG, "Error reading bundled asset: " + assetPath, e);
            return "";
        }
    }

    /**
     * Checks if the current on-disk file content deviates from the bundled version.
     */
    public boolean isFileDeviatedFromBundled(@NonNull Category category, @NonNull String fileName) {
        String disk = readFileContent(category, fileName);
        String bundled = readBundledAssetContent(category, fileName);
        return !disk.equals(bundled);
    }

    /**
     * Restores a target file to its clean factory-default state from bundled APK assets.
     */
    public boolean restoreToDefault(@NonNull Category category, @NonNull String fileName) {
        if (part(category, fileName) != null) {
            return writeFileContent(category, fileName, readBundledAssetContent(category, fileName));
        }
        File file = getFile(category, fileName);
        String assetPath = getAssetPath(category, fileName);
        if (assetPath != null) {
            return copyAssetToFile(assetPath, file);
        } else {
            // Empty template file
            String initialComment = (category == Category.NETWORK)
                ? "! Custom User Network Filter Rules (||domain^ or @@||domain^)\n"
                : "! Custom User Cosmetic Rules (##selector or domain##selector)\n";
            return writeStringToFile(file, initialComment);
        }
    }

    @Nullable
    private String getAssetPath(@NonNull Category category, @NonNull String fileName) {
        if (category == Category.NETWORK && FILE_HOSTS.equals(fileName)) {
            return ASSET_HOSTS;
        }
        if (category == Category.COSMETIC && FILE_COSMETIC_RULES.equals(fileName)) {
            return ASSET_COSMETIC;
        }
        if (category == Category.SCRIPTLETS) {
            if (FILE_SCRIPTLET_ANTI_ADBLOCK.equals(fileName)) return ASSET_SCRIPTLET_ANTI_ADBLOCK;
            if (FILE_SCRIPTLET_COSMETIC_PRUNER.equals(fileName)) return ASSET_SCRIPTLET_COSMETIC_PRUNER;
            if (FILE_SCRIPTLET_POPUP_DEFUSER.equals(fileName)) return ASSET_SCRIPTLET_POPUP_DEFUSER;
            if (FILE_SCRIPTLET_YOUTUBE_DEFUSER.equals(fileName)) return ASSET_SCRIPTLET_YOUTUBE_DEFUSER;
        }
        return null;
    }

    private boolean copyAssetToFile(@NonNull String assetPath, @NonNull File destFile) {
        return writeAtomically(destFile, out -> {
            try (InputStream in = context.getAssets().open(assetPath)) { copy(in, out); }
        });
    }

    private boolean writeStringToFile(@NonNull File file, @NonNull String content) {
        return writeAtomically(file, out -> out.write(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static void copy(InputStream in, FileOutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
    }

    private interface RuleWriter { void write(FileOutputStream out) throws IOException; }

    /** Single file replacement in app storage. Never unlink the old file before replacing it. */
    private boolean writeAtomically(File dest, RuleWriter writer) {
        File temporary = null;
        try {
            temporary = File.createTempFile(".rule-", ".tmp", dest.getParentFile());
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                writer.write(out);
                out.getFD().sync();
            }
            Files.move(temporary.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Could not replace " + dest.getName(), e);
            return false;
        } finally {
            if (temporary != null) temporary.delete();
        }
    }
}
