package com.privacy.browser.adblock;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    private final Context context;
    private final File rootDir;
    private final File networkDir;
    private final File cosmeticDir;
    private final File scriptletsDir;
    private final File userScriptsDir;

    private AdBlockFileManager(@NonNull Context context) {
        this.context = context;
        this.rootDir = new File(context.getFilesDir(), DIR_ADBLOCK);
        this.networkDir = new File(rootDir, DIR_NETWORK);
        this.cosmeticDir = new File(rootDir, DIR_COSMETIC);
        this.scriptletsDir = new File(rootDir, DIR_SCRIPTLETS);
        this.userScriptsDir = new File(rootDir, DIR_USER_SCRIPTS);
        ensureDirectories();
        seedDefaultFilesIfMissing();
    }

    private void ensureDirectories() {
        if (!networkDir.exists()) networkDir.mkdirs();
        if (!cosmeticDir.exists()) cosmeticDir.mkdirs();
        if (!scriptletsDir.exists()) scriptletsDir.mkdirs();
        if (!userScriptsDir.exists()) userScriptsDir.mkdirs();
    }

    private void seedDefaultFilesIfMissing() {
        // 1. Network hosts
        File hostsFile = getFile(Category.NETWORK, FILE_HOSTS);
        android.content.SharedPreferences sp = context.getSharedPreferences("adblock_file_manager_prefs", Context.MODE_PRIVATE);
        int hostsVersion = sp.getInt("pref_hosts_file_version", 1);
        if (!hostsFile.exists() || hostsFile.length() < 500000 || hostsVersion < 2) {
            copyAssetToFile(ASSET_HOSTS, hostsFile);
            sp.edit().putInt("pref_hosts_file_version", 2).apply();
        }
        File userNetFile = getFile(Category.NETWORK, FILE_USER_NETWORK);
        if (!userNetFile.exists()) {
            writeStringToFile(userNetFile, "! Custom User Network Filter Rules (||domain^ or @@||domain^)\n");
        }

        // 2. Cosmetic rules
        File cosmeticFile = getFile(Category.COSMETIC, FILE_COSMETIC_RULES);
        if (!cosmeticFile.exists() || cosmeticFile.length() == 0) {
            copyAssetToFile(ASSET_COSMETIC, cosmeticFile);
        }
        File userCosmeticFile = getFile(Category.COSMETIC, FILE_USER_COSMETIC);
        if (!userCosmeticFile.exists()) {
            // Check legacy user_filters.txt for backward compatibility
            File legacyFile = new File(context.getFilesDir(), "user_filters.txt");
            if (legacyFile.exists() && legacyFile.length() > 0) {
                copyFile(legacyFile, userCosmeticFile);
            } else {
                writeStringToFile(userCosmeticFile, "! Custom User Cosmetic Rules (##selector or domain##selector)\n");
            }
        }

        // 3. Scriptlets
        File antiAbFile = getFile(Category.SCRIPTLETS, FILE_SCRIPTLET_ANTI_ADBLOCK);
        if (!antiAbFile.exists() || antiAbFile.length() == 0) {
            copyAssetToFile(ASSET_SCRIPTLET_ANTI_ADBLOCK, antiAbFile);
        } else {
            String content = readFileContent(Category.SCRIPTLETS, FILE_SCRIPTLET_ANTI_ADBLOCK);
            if (!content.contains("isAdBlockActive")) {
                copyAssetToFile(ASSET_SCRIPTLET_ANTI_ADBLOCK, antiAbFile);
            }
        }

        File cosmeticPrunerFile = getFile(Category.SCRIPTLETS, FILE_SCRIPTLET_COSMETIC_PRUNER);
        if (!cosmeticPrunerFile.exists() || cosmeticPrunerFile.length() == 0) {
            copyAssetToFile(ASSET_SCRIPTLET_COSMETIC_PRUNER, cosmeticPrunerFile);
        } else {
            String content = readFileContent(Category.SCRIPTLETS, FILE_SCRIPTLET_COSMETIC_PRUNER);
            if (!content.contains("isCosmeticEnabled")) {
                copyAssetToFile(ASSET_SCRIPTLET_COSMETIC_PRUNER, cosmeticPrunerFile);
            }
        }

        File popupFile = getFile(Category.SCRIPTLETS, FILE_SCRIPTLET_POPUP_DEFUSER);
        if (!popupFile.exists() || popupFile.length() == 0) {
            copyAssetToFile(ASSET_SCRIPTLET_POPUP_DEFUSER, popupFile);
        } else {
            String content = readFileContent(Category.SCRIPTLETS, FILE_SCRIPTLET_POPUP_DEFUSER);
            if (!content.contains("isAdBlockActive")) {
                copyAssetToFile(ASSET_SCRIPTLET_POPUP_DEFUSER, popupFile);
            }
        }

        File ytFile = getFile(Category.SCRIPTLETS, FILE_SCRIPTLET_YOUTUBE_DEFUSER);
        if (!ytFile.exists() || ytFile.length() == 0) {
            copyAssetToFile(ASSET_SCRIPTLET_YOUTUBE_DEFUSER, ytFile);
        } else {
            String content = readFileContent(Category.SCRIPTLETS, FILE_SCRIPTLET_YOUTUBE_DEFUSER);
            if (!content.contains("__privacy_ubo_yt_defuser_v2")) {
                copyAssetToFile(ASSET_SCRIPTLET_YOUTUBE_DEFUSER, ytFile);
            }
        }
    }

    public enum Category {
        NETWORK,
        COSMETIC,
        SCRIPTLETS,
        USER_SCRIPTS
    }

    @NonNull
    public File getFile(@NonNull Category category, @NonNull String fileName) {
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
        File[] files = userScriptsDir.listFiles((dir, name) -> name != null && name.toLowerCase(java.util.Locale.US).endsWith(".js"));
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
        File file = getFile(category, fileName);
        File tempFile = new File(file.getParentFile(), fileName + ".tmp");
        try {
            try (FileOutputStream fos = new FileOutputStream(tempFile);
                 OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                writer.write(content);
                writer.flush();
            }
            if (file.exists()) {
                file.delete();
            }
            return tempFile.renameTo(file);
        } catch (Exception e) {
            Log.e(TAG, "Error writing file: " + fileName, e);
            if (tempFile.exists()) tempFile.delete();
            return false;
        }
    }

    /**
     * Appends a single line to the file.
     */
    public synchronized boolean appendLineToFile(@NonNull Category category, @NonNull String fileName, @NonNull String line) {
        File file = getFile(category, fileName);
        try (FileOutputStream fos = new FileOutputStream(file, true);
             OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            writer.write("\n" + line.trim());
            writer.flush();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error appending to file: " + fileName, e);
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
        StringBuilder sb = new StringBuilder();
        try (InputStream is = context.getAssets().open(assetPath);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (!first) sb.append("\n");
                sb.append(line);
                first = false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading bundled asset: " + assetPath, e);
        }
        return sb.toString();
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
        try (InputStream is = context.getAssets().open(assetPath);
             FileOutputStream fos = new FileOutputStream(destFile)) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = is.read(buf)) != -1) {
                fos.write(buf, 0, read);
            }
            fos.flush();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed copying asset " + assetPath + " to " + destFile.getAbsolutePath(), e);
            return false;
        }
    }

    private boolean copyFile(@NonNull File src, @NonNull File dest) {
        try (FileInputStream fis = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = fis.read(buf)) != -1) {
                fos.write(buf, 0, read);
            }
            fos.flush();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed copying file " + src.getAbsolutePath() + " to " + dest.getAbsolutePath(), e);
            return false;
        }
    }

    private boolean writeStringToFile(@NonNull File file, @NonNull String content) {
        try (FileOutputStream fos = new FileOutputStream(file);
             OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            writer.write(content);
            writer.flush();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed writing string to " + file.getAbsolutePath(), e);
            return false;
        }
    }
}
