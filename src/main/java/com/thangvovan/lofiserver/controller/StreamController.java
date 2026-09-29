package com.thangvovan.lofiserver.controller;

import com.thangvovan.lofiserver.LofiProperties;
import com.thangvovan.lofiserver.service.StationRegistry;
import com.thangvovan.lofiserver.worker.StationStream;

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

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** 
 * The whole HTTP surface.
 */
@RestController
@CrossOrigin(origins = "*")
public class StreamController {
    private static final Logger log = LoggerFactory.getLogger(StreamController.class);
    private static final Pattern VIDEO_ID = Pattern.compile("[\\w-]{11}");

    private final StationRegistry registry;
    private final LofiProperties props;

    StreamController(StationRegistry registry, LofiProperties props) {
        this.registry = registry;
        this.props = props;
    }

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        return Map.of(
            "ok", true,
            "stations", registry.snapshot()
        );
    }

    @GetMapping("/stream")
    public ResponseEntity<StreamingResponseBody> stream(@RequestParam("id") String id, @RequestParam(name = "q", required = false) Integer q) {
        if (!VIDEO_ID.matcher(id).matches()) {
            return ResponseEntity.badRequest().build();
        }

        int bitrate = (q != null && props.getAllowedBitrates().contains(q)) ? q : props.getDefaultBitrate();

        final StationStream station;
        try {
            station = registry.acquire(id, bitrate);
        } catch (Exception e) {
            log.warn("Cannot start {}: {}", id, e.getMessage());
            return ResponseEntity.status(502).build();
        }

        StreamingResponseBody body = out -> {
            // Subscribe before waiting on the header
            BlockingQueue<byte[]> queue = station.subscribe();
            try {
                byte[] header = station.awaitHeader(30);
                if (header == null) return;
                out.write(header);
                out.flush();

                while (true) {
                    byte[] block = queue.poll(30, TimeUnit.SECONDS);
                    if (block == null || block == StationStream.END) return;
                    out.write(block);
                    out.flush();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.debug("Listener on {} left: {}", id, e.getMessage());
            } finally {
                station.unsubscribe(queue);
            }
        };

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("audio/webm"));
        headers.setCacheControl("no-store");
        return new ResponseEntity<>(body, headers, 200);
    }
}
