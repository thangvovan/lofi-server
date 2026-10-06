package com.thangvovan.lofiserver.service;

import com.thangvovan.lofiserver.LofiProperties;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 
 * Turns a video id into the live HLS manifest URL.
 */
@Service
public class YoutubeResolver {
    private static final Logger log = LoggerFactory.getLogger(YoutubeResolver.class);
    private static final URI PLAYER = URI.create("https://www.youtube.com/youtubei/v1/player?prettyPrint=false");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final LofiProperties props;

    private record Cached(String url, long at) {}

    YoutubeResolver(LofiProperties props) {
        this.props = props;
    }

    static String clientVersion(LocalDate today) {
        LocalDate target = today.minusWeeks(8);
        int major = target.get(IsoFields.WEEK_BASED_YEAR) - 2005;
        int week = target.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        return "%d.%02d.00".formatted(major, week);
    }

    private static String userAgent(String version) {
        return "com.google.android.youtube/" + version + " (Linux; U; Android 11) gzip";
    }

    public String resolveHls(String videoId) throws IOException, InterruptedException {
        Cached hit = cache.get(videoId);
        if (hit != null && System.currentTimeMillis() - hit.at() < props.getResolveTtlSeconds() * 1000) {
            return hit.url();
        }

        String version = clientVersion(LocalDate.now());
        String userAgent = userAgent(version);

        String body = """
            { \
                "context": { \
                    "client": { \
                        "clientName": "ANDROID", \
                        "clientVersion": "%s", \
                        "androidSdkVersion": 30, \
                        "userAgent": "%s", \
                        "osName": "Android", \
                        "osVersion": "11", \
                        "hl": "en", \
                        "gl": "US" \
                    } \
                }, \
                "videoId": "%s", \
                "contentCheckOk": true, \
                "racyCheckOk": true \
            } \
        """.formatted(version, userAgent, videoId);

        HttpRequest req = HttpRequest.newBuilder(PLAYER)
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .header("User-Agent", userAgent)
            .header("X-Youtube-Client-Name", "3")
            .header("X-Youtube-Client-Version", version)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IOException("Player API returned error");
        }
        JsonNode root = json.readTree(res.body());

        JsonNode hls = root.path("streamingData").path("hlsManifestUrl");
        if (hls.isMissingNode() || hls.asText().isBlank()) throw new IOException("No Hls Manifest Url");

        String url = hls.asText();
        cache.put(videoId, new Cached(url, System.currentTimeMillis()));
        log.info("Resolved {}", videoId);
        return url;
    }

    // Forgets a URL
    void evict(String videoId) {
        cache.remove(videoId);
    }
}
