package com.privacy.imagesearch.model;

import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ImageSearchEngine {

    public enum Category {
        ALL,
        GENERAL,
        ANIME,
        STOCK,
        PRODUCT,
        TRADEMARKS
    }

    private final String id;
    private final String displayName;
    private final String urlTemplate;
    private final Category category;
    private final String description;

    public ImageSearchEngine(@NonNull String id,
                             @NonNull String displayName,
                             @NonNull String urlTemplate,
                             @NonNull Category category,
                             @Nullable String description) {
        this.id = id;
        this.displayName = displayName;
        this.urlTemplate = urlTemplate;
        this.category = category;
        this.description = description != null ? description : "";
    }

    @NonNull
    public String getId() {
        return id;
    }

    @NonNull
    public String getDisplayName() {
        return displayName;
    }

    @NonNull
    public Category getCategory() {
        return category;
    }

    @NonNull
    public String getDescription() {
        return description;
    }

    public boolean acceptsImageUrl() { return urlTemplate.contains("{imgUrl}"); }

    @NonNull
    public String buildSearchUrl(@NonNull String imageUrl) {
        String encoded = Uri.encode(imageUrl);
        return urlTemplate.replace("{imgUrl}", encoded);
    }

    private static final List<ImageSearchEngine> ALL_ENGINES;

    static {
        List<ImageSearchEngine> list = new ArrayList<>();

        // --- General Reverse Image Search ---
        list.add(new ImageSearchEngine("googleLens", "Google Lens",
                "https://lens.google.com/upload?url={imgUrl}",
                Category.GENERAL, "Visual match, landmarks, objects, OCR text"));

        list.add(new ImageSearchEngine("bing", "Bing Visual Search",
                "https://www.bing.com/images/search?q=imgurl:{imgUrl}&view=detailv2&iss=sbi",
                Category.GENERAL, "Comprehensive web reverse search and similar images"));

        list.add(new ImageSearchEngine("yandex", "Yandex Images",
                "https://yandex.com/images/search?url={imgUrl}&rpt=imageview",
                Category.GENERAL, "High accuracy face and duplicate image identification"));

        list.add(new ImageSearchEngine("tineye", "TinEye",
                "https://www.tineye.com/search/?url={imgUrl}",
                Category.GENERAL, "Source image tracking, modifications and high-res matches"));

        list.add(new ImageSearchEngine("baidu", "Baidu",
                "https://graph.baidu.com/details?isfromsimilarity=1&image={imgUrl}",
                Category.GENERAL, "Asian web discovery and Chinese source identification"));

        list.add(new ImageSearchEngine("sogou", "Sogou",
                "https://pic.sogou.com/ris?query={imgUrl}&flag=1&drag=0",
                Category.GENERAL, "Regional reverse image lookup across Chinese web"));

        list.add(new ImageSearchEngine("lenso", "Lenso.ai",
                "https://lenso.ai/en/search-by-url?url={imgUrl}&utm_source=sbi",
                Category.GENERAL, "AI-driven reverse search for people, places, and duplicates"));

        list.add(new ImageSearchEngine("kagi", "Kagi Images",
                "https://kagi.com/images?q={imgUrl}",
                Category.GENERAL, "Privacy-focused ad-free image discovery"));

        list.add(new ImageSearchEngine("qihoo", "Qihoo 360",
                "https://st.so.com/stu?imgurl={imgUrl}",
                Category.GENERAL, "Major Chinese visual search engine"));

        list.add(new ImageSearchEngine("pimeyes", "PimEyes",
                "https://pimeyes.com/en",
                Category.GENERAL, "Face search and online presence audit"));

        // --- Anime & Illustration Search ---
        list.add(new ImageSearchEngine("saucenao", "SauceNAO",
                "https://saucenao.com/search.php?url={imgUrl}",
                Category.ANIME, "Anime artwork, Pixiv, Twitter, and manga author indexing"));

        list.add(new ImageSearchEngine("iqdb", "IQDB",
                "https://iqdb.org/?url={imgUrl}",
                Category.ANIME, "Multi-service anime booru indexer (Danbooru, Gelbooru, Konachan)"));

        list.add(new ImageSearchEngine("ascii2d", "Ascii2d",
                "https://ascii2d.net/search/url/{imgUrl}",
                Category.ANIME, "Japanese illustration tracker with 2-color and feature matching"));

        list.add(new ImageSearchEngine("whatanime", "trace.moe",
                "https://trace.moe/?url={imgUrl}",
                Category.ANIME, "Anime scene search: finds exact show, episode, and timestamp"));

        list.add(new ImageSearchEngine("repostSleuth", "RepostSleuth",
                "https://repostsleuth.com/search?url={imgUrl}",
                Category.ANIME, "Reddit meme and illustration duplicate detection"));

        // --- Stock Photography & Creative Works ---
        list.add(new ImageSearchEngine("unsplash", "Unsplash",
                "https://unsplash.com/s/photos/{imgUrl}",
                Category.STOCK, "High-resolution curated creative commons photography"));

        list.add(new ImageSearchEngine("freepik", "Freepik",
                "https://www.freepik.com/search?format=search&query={imgUrl}",
                Category.STOCK, "Vectors, stock photos, and PSD mockups"));

        list.add(new ImageSearchEngine("shutterstock", "Shutterstock",
                "https://www.shutterstock.com/search?image_url={imgUrl}",
                Category.STOCK, "Commercial photography and vector graphics licensing"));

        list.add(new ImageSearchEngine("adobestock", "Adobe Stock",
                "https://stock.adobe.com/search?k={imgUrl}",
                Category.STOCK, "Royalty-free photos, illustrations, and vector assets"));

        list.add(new ImageSearchEngine("alamy", "Alamy",
                "https://www.alamy.com/search.html?qt={imgUrl}",
                Category.STOCK, "Global stock library for editorial and news photography"));

        list.add(new ImageSearchEngine("getty", "Getty Images",
                "https://www.gettyimages.com/photos/{imgUrl}",
                Category.STOCK, "Editorial and creative visual content archive"));

        list.add(new ImageSearchEngine("depositphotos", "Depositphotos",
                "https://depositphotos.com/search/{imgUrl}.html",
                Category.STOCK, "High quality royalty-free stock imagery"));

        list.add(new ImageSearchEngine("dreamstime", "Dreamstime",
                "https://www.dreamstime.com/search.php?srh_field={imgUrl}",
                Category.STOCK, "Digital stock photography and illustrations"));

        list.add(new ImageSearchEngine("lexica", "Lexica",
                "https://lexica.art/?q={imgUrl}",
                Category.STOCK, "AI generative art search engine and prompt database"));

        list.add(new ImageSearchEngine("icons8", "Icons8",
                "https://icons8.com/photos/search?q={imgUrl}",
                Category.STOCK, "Design icons, illustrations, and transparent PNGs"));

        // --- Shopping & Products ---
        list.add(new ImageSearchEngine("taobao", "Taobao",
                "https://s.taobao.com/search?q={imgUrl}",
                Category.PRODUCT, "Product matching and wholesale manufacturing lookup"));

        list.add(new ImageSearchEngine("alibabaChina", "1688 (Alibaba China)",
                "https://s.1688.com/youyuan/index.htm?tab=imageSearch&imageUrl={imgUrl}",
                Category.PRODUCT, "B2B wholesale product and supplier directory"));

        list.add(new ImageSearchEngine("shein", "SHEIN",
                "https://m.shein.com/presearch?search_words={imgUrl}",
                Category.PRODUCT, "Fashion and apparel reverse visual lookup"));

        list.add(new ImageSearchEngine("wildberries", "Wildberries",
                "https://www.wildberries.ru/catalog/0/search.aspx?search={imgUrl}",
                Category.PRODUCT, "Major Eastern European online marketplace"));

        list.add(new ImageSearchEngine("lykdat", "Lykdat",
                "https://lykdat.com/search?url={imgUrl}",
                Category.PRODUCT, "Fashion clothes and style finder across multiple stores"));

        list.add(new ImageSearchEngine("ikea", "IKEA Visual Search",
                "https://www.ikea.com/search/?q={imgUrl}",
                Category.PRODUCT, "Furniture and home decor catalog search"));

        // --- Trademarks & Intellectual Property ---
        list.add(new ImageSearchEngine("esearch", "eSearch Plus (EUIPO)",
                "https://euipo.europa.eu/eSearch/",
                Category.TRADEMARKS, "European Union trademarks and designs visual search"));

        list.add(new ImageSearchEngine("tmview", "TMview",
                "https://www.tmdn.org/tmview/",
                Category.TRADEMARKS, "Trade mark search tool covering 70+ participating offices"));

        list.add(new ImageSearchEngine("branddb", "Global Brand Database (WIPO)",
                "https://branddb.wipo.int/en/similarlogo",
                Category.TRADEMARKS, "Worldwide trademark applications and registrations visual check"));

        list.add(new ImageSearchEngine("madridMonitor", "Madrid Monitor (WIPO)",
                "https://www3.wipo.int/madrid/monitor/en/",
                Category.TRADEMARKS, "International registration of marks tracking"));

        list.add(new ImageSearchEngine("auTrademark", "IP Australia Trademarks",
                "https://search.ipaustralia.gov.au/trademarks/search/advanced",
                Category.TRADEMARKS, "Australian official trademarks register lookup"));

        ALL_ENGINES = Collections.unmodifiableList(list);
    }

    @NonNull
    public static List<ImageSearchEngine> getAllEngines() {
        return ALL_ENGINES;
    }

    @NonNull
    public static List<ImageSearchEngine> getEnginesByCategory(@Nullable Category category) {
        if (category == null || category == Category.ALL) {
            return ALL_ENGINES;
        }
        List<ImageSearchEngine> filtered = new ArrayList<>();
        for (ImageSearchEngine e : ALL_ENGINES) {
            if (e.getCategory() == category) {
                filtered.add(e);
            }
        }
        return filtered;
    }

    @Nullable
    public static ImageSearchEngine getById(@Nullable String id) {
        if (id == null) return null;
        for (ImageSearchEngine engine : ALL_ENGINES) {
            if (engine.getId().equalsIgnoreCase(id)) {
                return engine;
            }
        }
        return null;
    }
}
