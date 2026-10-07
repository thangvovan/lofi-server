package com.thangvovan.lofiserver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Station names from Lofi Girl's playlist.
 */
@Service
public class StationNames {
    private static final Logger log = LoggerFactory.getLogger(StationNames.class);
    private static final URI BROWSE = URI.create("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false");
    private static final String PLAYLIST_ID = "PL6NdkXsPL07Il2hEQGcLI4dg_LTg7xA2L";
    private static final String FLOOR = "2.20260910.01.00";
    private static final String FLAGSHIP = "Lofi Radio";
    private static final Pattern DESCRIPTION_STARTS = Pattern.compile("[^\\p{L}\\p{N}\\s/&'’,.\\-]", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern WORD = Pattern.compile("\\S+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern SPACES = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern VIDEO_ID = Pattern.compile("[\\w-]{11}");
    private static final long REFRESH_MS = 30 * 60 * 1000;
    private static final long RETRY_MS = 60 * 1000;

    private record Entry(String videoId, String rawTitle) {}

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicBoolean loading = new AtomicBoolean();
    private volatile Map<String, String> names = Map.of();
    private volatile long loadedAt;
    private volatile long triedAt;

    public String name(String videoId) {
        long now = System.currentTimeMillis();
        String name = names.get(videoId);
        boolean stale = now - loadedAt > REFRESH_MS || name == null;
        if (stale && now - triedAt > RETRY_MS) refresh();
        return name != null ? name : videoId;
    }

    public void refresh() {
        if (!loading.compareAndSet(false, true)) return;
        triedAt = System.currentTimeMillis();
        CompletableFuture.runAsync(() -> {
            try {
                names = load();
                loadedAt = System.currentTimeMillis();
                log.info("Loaded {} station names", names.size());
            } catch (Exception e) {
                log.warn("Cannot read station names");
            } finally {
                loading.set(false);
            }
        });
    }

    private Map<String, String> load() throws Exception {
        String body = """
            { \
                "browseId": "VL%s", \
                "context": { "client": { "clientName": "WEB", "clientVersion": "%s", "hl": "en", "gl": "US" } } \
            } \
        """.formatted(PLAYLIST_ID, clientVersion(LocalDate.now(ZoneOffset.UTC)));

        HttpRequest req = HttpRequest.newBuilder(BROWSE)
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IllegalStateException("Failed to fetch station names");

        List<Entry> entries = collect(json.readTree(res.body()), new ArrayList<>());
        if (entries.isEmpty()) throw new IllegalStateException("Playlist returned no stations");

        List<String> titles = nameAll(entries);
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i < entries.size(); i++) out.put(entries.get(i).videoId(), titles.get(i));
        return Map.copyOf(out);
    }

    static String clientVersion(LocalDate today) {
        String dated = "2." + today.format(DateTimeFormatter.BASIC_ISO_DATE) + ".00.00";
        return dated.compareTo(FLOOR) > 0 ? dated : FLOOR;
    }

    static String titleCase(String s) {
        Matcher m = WORD.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String w = m.group();
            m.appendReplacement(out, Matcher.quoteReplacement(w.substring(0, 1).toUpperCase(Locale.ROOT) + w.substring(1)));
        }
        m.appendTail(out);
        return out.toString();
    }

    // The last words of the name
    static String shorten(String raw, int words) {
        String name = DESCRIPTION_STARTS.split(raw, 2)[0];
        List<String> parts = Arrays.stream(SPACES.split(name.strip())).filter(p -> !p.isEmpty()).toList();
        return titleCase(String.join(" ", parts.subList(Math.max(0, parts.size() - words), parts.size())));
    }

    static List<String> nameAll(List<Entry> entries) {
        Set<String> taken = new HashSet<>();
        List<String> out = new ArrayList<>();

        for (int i = 0; i < entries.size(); i++) {
            String raw = entries.get(i).rawTitle();
            String name = nameOne(raw, i, taken);
            if (name != null) {
                out.add(name);
            }
        }
        return out;
    }

    private static String nameOne(String raw, int i, Set<String> taken) {
        if (i == 0 && raw.toLowerCase(Locale.ROOT).contains("lofi") && raw.toLowerCase(Locale.ROOT).contains("radio")) {
            taken.add(FLAGSHIP);
            return FLAGSHIP;
        }

        for (int words = 3; words <= 8; words++) {
            String title = shorten(raw, words);
            if (taken.add(title)) return title;
            if (title.equals(shorten(raw, words + 1))) break;
        }

        return null;
    }

    private static List<Entry> collect(JsonNode node, List<Entry> out) {
        if (node.isArray()) {
            for (JsonNode n : node) collect(n, out);
            return out;
        }
        if (!node.isObject()) return out;

        JsonNode lockup = node.get("lockupViewModel");
        if (lockup != null && VIDEO_ID.matcher(lockup.path("contentId").asText()).matches()) {
            String id = lockup.path("contentId").asText();
            String type = lockup.path("contentType").asText("");
            boolean isVideo = type.isEmpty() || type.equals("LOCKUP_CONTENT_TYPE_VIDEO");
            String title = lockup.path("metadata").path("lockupMetadataViewModel").path("title").path("content").asText("");
            if (isVideo && !title.isEmpty() && out.stream().noneMatch(e -> e.videoId().equals(id))) {
                out.add(new Entry(id, title));
            }
        }
        for (Iterator<JsonNode> it = node.elements(); it.hasNext(); ) collect(it.next(), out);
        return out;
    }
}
