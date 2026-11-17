# Implementation Plan: AndroidX WebView with Document-Start Injection & Interception

## 1. Overview & Architecture

This approach leverages Android's built-in, hardware-accelerated Chromium engine via `androidx.webkit:webkit`. It exercises complete control over the browsing session by inserting hooks at the earliest possible lifecycle event (`addDocumentStartJavaScript`), intercepting all network requests (`shouldInterceptRequest`), and applying cosmetic DOM mutations before and during page render.

### Architectural Diagram
```
┌────────────────────────────────────────────────────────┐
│                Browser Application UI                  │
│    (Omnibox, Tab Switcher, Privacy Shield Toggle)      │
├────────────────────────────────────────────────────────┤
│                 AndroidX Webkit Layer                  │
│                                                        │
│  ┌───────────────────────┐  ┌───────────────────────┐  │
│  │ Document-Start Hooks  │  │ Network Interceptor   │  │
│  │ • Canvas Noise Shim   │  │ • EasyList matcher    │  │
│  │ • WebGL/GPU Masking   │  │ • Strips Sec-CH-UA    │  │
│  │ • AudioContext Shim   │  │ • Jsoup HTML cleaner  │  │
│  │ • Navigator Spoofing  │  │ • Zero MV3 rule limit │  │
│  └───────────────────────┘  └───────────────────────┘  │
├────────────────────────────────────────────────────────┤
│           android.webkit.WebView (Chromium)            │
│          (Underlying OS rendering & JS engine)         │
└────────────────────────────────────────────────────────┘
```

---

## 2. Technical Stack & Dependencies

- **Engine Core:** `androidx.webkit:webkit:1.12.0+`
- **HTML Stream Parser:** `org.jsoup:jsoup:1.18.1` (lightweight HTML mutation)
- **Adblock / Rule Matcher:** In-memory Bloom filter / Trie matcher (or `adblock-android`)

---

## 3. Core Implementation Phases

### Phase 1: Android Project Scaffolding & Modern WebView Setup
- [ ] Initialize Android project structure.
- [ ] Configure `AndroidManifest.xml` with standard `INTERNET` and `ACCESS_NETWORK_STATE` permissions.
- [ ] Implement `PrivacyWebView` wrapping `android.webkit.WebView` with hardened defaults:
  - Disable third-party cookies by default via `CookieManager`.
  - Disable Safe Browsing pings to Google servers:
    ```java
    if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
        WebSettingsCompat.setSafeBrowsingEnabled(settings, false);
    }
    ```
  - Disable geolocation, file access, and content access unless explicitly granted.

### Phase 2: Anti-Fingerprinting Suite via `addDocumentStartJavaScript`
- [ ] Implement `FingerprintDefense.js` injected via `WebViewCompat.addDocumentStartJavaScript(webView, script, Collections.singleton("*"))`:
  - **Canvas Randomization:** Override `toDataURL` and `getImageData` to introduce non-destructive micro-variations into color channels.
  - **WebGL Cloaking:** Intercept `WebGLRenderingContext.prototype.getParameter` to mask vendor strings (e.g. spoofing to generic `Mesa / ANGLE`).
  - **AudioContext Perturbation:** Hook `AnalyserNode.prototype.getFloatFrequencyData` and `AudioBuffer.prototype.copyFromChannel`.
  - **Navigator Hardening:** Override `navigator.hardwareConcurrency` (fixed to 4), `navigator.deviceMemory` (fixed to 8), and neutralize Battery and DeviceOrientation APIs.
- [ ] Establish two-way communication using `WebViewCompat.addWebMessageListener` to receive security alerts and blocked tracker counts from page scripts.

### Phase 3: Network Interception & Stream Sanitization (`shouldInterceptRequest`)
- [ ] Implement `PrivacyWebViewClient extends WebViewClient`:
  - Intercept subresource requests (scripts, images, stylesheets).
  - Match URLs against bundled EasyList / Peter Lowe blocklists loaded into an in-memory Trie/HashSet.
  - Return empty `WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]))` for blocked domains.
- [ ] Implement Main-Frame HTML Sanitization:
  - If `request.isForMainFrame()` and content mutation is enabled, fetch raw HTML stream.
  - Parse with `Jsoup`, strip inline tracking scripts, telemetry pixels, and known anti-adblock modals.
  - Return sanitized stream to WebView.

### Phase 4: Cosmetic Filtering & User Scripting
- [ ] Inject element-hiding CSS stylesheets dynamically per domain:
  - E.g. `##.advertisement, ##[id*="google_ads"] { display: none !important; }`.
- [ ] Implement an in-page `MutationObserver` script that removes dynamically inserted paywalls and cookie consent popups.

### Phase 5: Browser Shell & Verification
- [ ] Build minimalist browser interface:
  - Bottom omnibox / URL bar with search provider selector (DuckDuckGo, SearXNG).
  - Privacy shield button showing blocked tracker counts in real-time.
  - Tab management & private tab switcher.
- [ ] Verification on test sites:
  - Test on `browserleaks.com/canvas` (verify canvas hash changes per session).
  - Test on `amiunique.org` (verify entropy reduction).
  - Test on ad-heavy news sites to verify subresource blocking.

---

## 4. Pros & Cons

| Pros | Cons |
| :--- | :--- |
| **Instant compilation** on M1 Mac (< 15s). | Cannot install desktop `.crx` / `.xpi` extensions. |
| Tiny APK footprint. | Relies on OS-provided Chromium version. |
| 100% web compatibility with modern sites. | Extremely deep C++ engine changes cannot be done. |
| Zero Google Manifest V3 rule constraints. | |
