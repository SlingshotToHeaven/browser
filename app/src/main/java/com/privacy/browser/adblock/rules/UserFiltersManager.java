package com.privacy.browser.adblock.rules;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Custom uBlock Origin filter rules engine storing and compiling user-defined rules.
 * Supports cosmetic hiding (domain##selector, ##globalSelector), network blocks (||host^),
 * and network whitelists (@@||host^).
 */
public class UserFiltersManager {

    private static final String TAG = "UserFiltersManager";
    private static final String FILTERS_FILE_NAME = "user_filters.txt";
    private static final String BUILTIN_COSMETIC_FILE = "filters/cosmetic_rules.txt";

    private static volatile UserFiltersManager instance;

    public static UserFiltersManager getInstance(@NonNull Context context) {
        if (instance == null) {
            synchronized (UserFiltersManager.class) {
                if (instance == null) {
                    instance = new UserFiltersManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private final Context context;
    private final List<String> rawRules = new ArrayList<>();
    private final Map<String, List<String>> domainCosmeticMap = new HashMap<>();
    private final List<String> globalCosmeticRules = new ArrayList<>();
    private final Set<String> blockedHosts = new HashSet<>();
    private final Set<String> whitelistedHosts = new HashSet<>();

    private UserFiltersManager(@NonNull Context context) {
        this.context = context;
        loadRules();
    }

    public synchronized void loadRules() {
        rawRules.clear();
        domainCosmeticMap.clear();
        globalCosmeticRules.clear();
        blockedHosts.clear();
        whitelistedHosts.clear();

        // 1. Load built-in high-impact EasyList cosmetic element hiding rules from file
        com.privacy.browser.adblock.AdBlockFileManager fileManager = com.privacy.browser.adblock.AdBlockFileManager.getInstance(context);
        File builtInFile = fileManager.getFile(com.privacy.browser.adblock.AdBlockFileManager.Category.COSMETIC, com.privacy.browser.adblock.AdBlockFileManager.FILE_COSMETIC_RULES);
        if (builtInFile.exists()) {
            try (FileInputStream fis = new FileInputStream(builtInFile);
                 BufferedReader assetReader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
                String assetLine;
                while ((assetLine = assetReader.readLine()) != null) {
                    parseRuleLine(assetLine);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed reading cosmetic rules file: " + e.getMessage());
            }
        } else {
            try (java.io.InputStream is = context.getAssets().open(BUILTIN_COSMETIC_FILE);
                 BufferedReader assetReader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String assetLine;
                while ((assetLine = assetReader.readLine()) != null) {
                    parseRuleLine(assetLine);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed reading built-in cosmetic filters: " + e.getMessage());
            }
        }

        // 2. Load user-defined filters
        File file = fileManager.getFile(com.privacy.browser.adblock.AdBlockFileManager.Category.COSMETIC, com.privacy.browser.adblock.AdBlockFileManager.FILE_USER_COSMETIC);
        if (!file.exists()) {
            file = new File(context.getFilesDir(), FILTERS_FILE_NAME);
            if (!file.exists()) {
                createDefaultFiltersFile(file);
            }
        }

        try (FileInputStream fis = new FileInputStream(file);
             BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                rawRules.add(line);
                parseRuleLine(line);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed reading user filters", e);
        }
    }

    private void parseRuleLine(String raw) {
        if (TextUtils.isEmpty(raw)) return;
        String line = raw.trim();
        if (line.startsWith("!") || line.startsWith("# ")) return;

        // Whitelist network: @@||host^
        if (line.startsWith("@@||")) {
            String host = extractHostFromRule(line.substring(4));
            if (!host.isEmpty()) whitelistedHosts.add(host.toLowerCase(Locale.US));
            return;
        }

        // Cosmetic filter: [domains]##selector
        int dHash = line.indexOf("##");
        if (dHash != -1) {
            String domainsPart = line.substring(0, dHash).trim();
            String selectorPart = line.substring(dHash + 2).trim();
            if (selectorPart.isEmpty()) return;

            if (domainsPart.isEmpty()) {
                globalCosmeticRules.add(selectorPart);
            } else {
                String[] domains = domainsPart.split(",");
                for (String d : domains) {
                    String cleanD = d.trim().toLowerCase(Locale.US);
                    if (!cleanD.isEmpty()) {
                        List<String> list = domainCosmeticMap.computeIfAbsent(cleanD, k -> new ArrayList<>());
                        list.add(selectorPart);
                    }
                }
            }
            return;
        }

        // Network block: ||host^
        if (line.startsWith("||")) {
            String host = extractHostFromRule(line.substring(2));
            if (!host.isEmpty()) blockedHosts.add(host.toLowerCase(Locale.US));
        }
    }

    private String extractHostFromRule(String input) {
        String clean = input;
        int caret = clean.indexOf('^');
        if (caret != -1) clean = clean.substring(0, caret);
        int slash = clean.indexOf('/');
        if (slash != -1) clean = clean.substring(0, slash);
        return clean.trim();
    }

    public synchronized void addRule(String rule) {
        if (TextUtils.isEmpty(rule)) return;
        String trimmed = rule.trim();
        if (!rawRules.contains(trimmed)) {
            rawRules.add(trimmed);
            parseRuleLine(trimmed);
            saveRules();
        }
    }

    public synchronized void removeRule(String rule) {
        if (rawRules.remove(rule)) {
            saveRules();
            loadRules();
        }
    }

    public synchronized void setAllRules(List<String> newRules) {
        rawRules.clear();
        rawRules.addAll(newRules);
        saveRules();
        loadRules();
    }

    public synchronized List<String> getRawRules() {
        return Collections.unmodifiableList(new ArrayList<>(rawRules));
    }

    public synchronized int getRuleCount() {
        int count = 0;
        for (String r : rawRules) {
            String t = r.trim();
            if (!t.isEmpty() && !t.startsWith("!") && !t.startsWith("# ")) {
                count++;
            }
        }
        return count;
    }

    public synchronized String getCosmeticCssForDomain(@Nullable String domain) {
        StringBuilder css = new StringBuilder();
        List<String> selectors = new ArrayList<>(globalCosmeticRules);

        if (!TextUtils.isEmpty(domain)) {
            String d = domain.toLowerCase(Locale.US);
            if (domainCosmeticMap.containsKey(d)) {
                selectors.addAll(domainCosmeticMap.get(d));
            }
            // Also check subdomains
            int dot = d.indexOf('.');
            while (dot != -1 && dot < d.length() - 1) {
                String parent = d.substring(dot + 1);
                if (domainCosmeticMap.containsKey(parent)) {
                    selectors.addAll(domainCosmeticMap.get(parent));
                }
                dot = d.indexOf('.', dot + 1);
            }
        }

        if (selectors.isEmpty()) return "";

        for (int i = 0; i < selectors.size(); i++) {
            if (i > 0) css.append(", ");
            css.append(selectors.get(i));
        }
        css.append(" { display: none !important; visibility: hidden !important; height: 0 !important; max-height: 0 !important; min-height: 0 !important; margin: 0 !important; padding: 0 !important; border: none !important; pointer-events: none !important; }");
        return css.toString();
    }

    public synchronized boolean shouldBlockHost(String host) {
        if (TextUtils.isEmpty(host)) return false;
        String h = host.toLowerCase(Locale.US);
        if (whitelistedHosts.contains(h)) return false;
        if (blockedHosts.contains(h)) return true;

        int dot = h.indexOf('.');
        while (dot != -1 && dot < h.length() - 1) {
            String parent = h.substring(dot + 1);
            if (blockedHosts.contains(parent)) return true;
            dot = h.indexOf('.', dot + 1);
        }
        return false;
    }

    public synchronized boolean isHostWhitelisted(String host) {
        if (TextUtils.isEmpty(host)) return false;
        return whitelistedHosts.contains(host.toLowerCase(Locale.US));
    }

    private synchronized void saveRules() {
        com.privacy.browser.adblock.AdBlockFileManager fileManager = com.privacy.browser.adblock.AdBlockFileManager.getInstance(context);
        File file = fileManager.getFile(com.privacy.browser.adblock.AdBlockFileManager.Category.COSMETIC, com.privacy.browser.adblock.AdBlockFileManager.FILE_USER_COSMETIC);
        try (FileOutputStream fos = new FileOutputStream(file);
             OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            for (String r : rawRules) {
                writer.write(r);
                writer.write("\n");
            }
            writer.flush();
        } catch (Exception e) {
            Log.e(TAG, "Failed writing user filters", e);
        }
        // Also update legacy file to keep both in sync
        File legacyFile = new File(context.getFilesDir(), FILTERS_FILE_NAME);
        try (FileOutputStream fos = new FileOutputStream(legacyFile);
             OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            for (String r : rawRules) {
                writer.write(r);
                writer.write("\n");
            }
            writer.flush();
        } catch (Exception e) {}
    }

    private void createDefaultFiltersFile(File file) {
        try (FileOutputStream fos = new FileOutputStream(file);
             OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            writer.write("! *** User-defined filters (uBlock Origin format) ***\n");
            writer.write("! Examples:\n");
            writer.write("!   example.com##.ad-banner\n");
            writer.write("!   example.com###sponsored-post\n");
            writer.write("!   ##.global-annoying-popup\n");
            writer.write("!   ||tracking-domain.com^\n");
            writer.write("!   @@||whitelisted-site.com^\n\n");
            writer.flush();
        } catch (Exception e) {
            Log.e(TAG, "Failed initializing default user filters file", e);
        }
    }
}
