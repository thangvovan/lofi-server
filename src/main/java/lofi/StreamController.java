package lofi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** The whole HTTP surface: three endpoints. */
@RestController
@CrossOrigin(origins = "*")   // the wallpaper runs from file://, whose origin is "null"
public class StreamController {

    private static final Logger log = LoggerFactory.getLogger(StreamController.class);
    private static final Pattern VIDEO_ID = Pattern.compile("[\\w-]{11}");

    private final StationRegistry registry;
    private final YoutubeResolver resolver;
    private final LofiProperties props;

    StreamController(StationRegistry registry, YoutubeResolver resolver, LofiProperties props) {
        this.registry = registry;
        this.resolver = resolver;
        this.props = props;
    }

    /** Also the wallpaper's keep-alive target, so it must stay cheap. */
    @GetMapping("/api/health")
    public Map<String, Object> health() {
        return Map.of("ok", true,
                "ffmpeg", props.getFfmpeg(),
                "stations", registry.snapshot());
    }

    /**
     * Resolving on its own, for diagnosing a deployment.
     *
     * This is the call that fails first when a host's IP is in a range YouTube
     * treats as a datacenter, and it costs no bandwidth worth counting - so it is
     * the right first thing to run against a new server.
     */
    @GetMapping("/api/resolve")
    public ResponseEntity<Map<String, Object>> resolve(@RequestParam("id") String id) {
        if (!VIDEO_ID.matcher(id).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "bad video id"));
        }
        try {
            return ResponseEntity.ok(Map.of("ok", true, "url", resolver.resolveHls(id)));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("ok", false, "error", String.valueOf(e.getMessage())));
        }
    }

    @GetMapping("/stream")
    public ResponseEntity<StreamingResponseBody> stream(
            @RequestParam("id") String id,
            @RequestParam(name = "q", required = false) Integer q) {

        if (!VIDEO_ID.matcher(id).matches()) {
            return ResponseEntity.badRequest().build();
        }
        int bitrate = (q != null && props.getAllowedBitrates().contains(q))
                ? q : props.getDefaultBitrate();

        final StationStream station;
        try {
            station = registry.acquire(id, bitrate);
        } catch (Exception e) {
            log.warn("cannot start {}: {}", id, e.getMessage());
            return ResponseEntity.status(502).build();
        }

        StreamingResponseBody body = out -> {
            // Subscribe before waiting on the header, so no cluster produced in
            // between is missed.
            BlockingQueue<byte[]> queue = station.subscribe();
            try {
                byte[] header = station.awaitHeader(30);
                if (header == null) return;
                out.write(header);
                out.flush();

                while (true) {
                    // A timeout, not take(). A listener dropped for being slow is
                    // never sent END, and a station can go quiet without ending;
                    // either way take() parked this thread for good. Overnight that
                    // left 63 of them holding sockets the page had long closed.
                    byte[] block = queue.poll(30, TimeUnit.SECONDS);
                    if (block == null || block == StationStream.END) return;
                    out.write(block);
                    out.flush();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // Normal: the page changed station, navigated away, or was closed.
                log.debug("listener on {} left: {}", id, e.getMessage());
            } finally {
                station.unsubscribe(queue);
            }
        };

        // No Content-Length and deliberately no Accept-Ranges. The media element
        // then treats this as live and never seeks, so it never asks for a byte
        // range that cannot be answered.
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("audio/webm"));
        headers.setCacheControl("no-store");
        return new ResponseEntity<>(body, headers, 200);
    }
}
