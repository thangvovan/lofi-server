package lofi;

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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Turns a video id into the live HLS manifest URL. */
@Service
public class YoutubeResolver {

    private static final Logger log = LoggerFactory.getLogger(YoutubeResolver.class);

    /**
     * Bump this if resolving starts answering FAILED_PRECONDITION.
     *
     * That error is the whole story of this class. An earlier attempt read it as
     * "YouTube wants a PO token" and concluded a browser could never get a stream;
     * in fact the client version was simply stale. With a current one, ANDROID
     * answers OK for an anonymous request - no cookies, no visitor id, no token -
     * which was verified against every station in the playlist.
     */
    static final String CLIENT_VERSION = "21.02.35";
    static final String USER_AGENT =
            "com.google.android.youtube/" + CLIENT_VERSION + " (Linux; U; Android 11) gzip";

    private static final URI PLAYER =
            URI.create("https://www.youtube.com/youtubei/v1/player?prettyPrint=false");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final LofiProperties props;

    private record Cached(String url, long at) {}

    YoutubeResolver(LofiProperties props) {
        this.props = props;
    }

    public String resolveHls(String videoId) throws IOException, InterruptedException {
        Cached hit = cache.get(videoId);
        if (hit != null && System.currentTimeMillis() - hit.at() < props.getResolveTtlSeconds() * 1000) {
            return hit.url();
        }

        String body = """
                {"context":{"client":{"clientName":"ANDROID","clientVersion":"%s",\
                "androidSdkVersion":30,"userAgent":"%s","osName":"Android",\
                "osVersion":"11","hl":"en","gl":"US"}},"videoId":"%s",\
                "contentCheckOk":true,"racyCheckOk":true}"""
                .formatted(CLIENT_VERSION, USER_AGENT, videoId);

        HttpRequest req = HttpRequest.newBuilder(PLAYER)
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("User-Agent", USER_AGENT)
                .header("X-Youtube-Client-Name", "3")
                .header("X-Youtube-Client-Version", CLIENT_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode root = json.readTree(res.body());

        JsonNode hls = root.path("streamingData").path("hlsManifestUrl");
        if (hls.isMissingNode() || hls.asText().isBlank()) {
            String status = root.path("playabilityStatus").path("status").asText("unknown");
            // LOGIN_REQUIRED here nearly always means the host's IP sits in a range
            // YouTube treats as a datacenter, not that anything is misconfigured.
            throw new IOException("no hlsManifestUrl (http " + res.statusCode()
                    + ", playabilityStatus=" + status + ")");
        }

        String url = hls.asText();
        cache.put(videoId, new Cached(url, System.currentTimeMillis()));
        log.info("resolved {}", videoId);
        return url;
    }

    /**
     * Forgets a URL once the stream started from it has died.
     *
     * Whatever killed it, the URL is suspect now. Left in the cache it would be
     * handed to every retry until the TTL ran out, so a manifest that stopped
     * working would keep starting an ffmpeg that exits at once.
     */
    void evict(String videoId) {
        cache.remove(videoId);
    }
}
