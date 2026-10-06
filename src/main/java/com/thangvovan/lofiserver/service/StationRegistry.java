package com.thangvovan.lofiserver.service;

import com.thangvovan.lofiserver.LofiProperties;
import com.thangvovan.lofiserver.worker.StationStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** 
 * Keeps at most one {@link StationStream} per station and retires idle ones.
 */
@Service
public class StationRegistry {
    private static final Logger log = LoggerFactory.getLogger(StationRegistry.class);

    // Futures rather than streams
    private final Map<String, CompletableFuture<StationStream>> streams = new ConcurrentHashMap<>();

    private final YoutubeResolver resolver;
    private final LofiProperties props;

    StationRegistry(YoutubeResolver resolver, LofiProperties props) {
        this.resolver = resolver;
        this.props = props;
    }

    public StationStream acquire(String videoId, int bitrate) throws IOException {
        String key = videoId + "@" + bitrate;

        // At most one retry
        for (int tries = 0; tries < 2; tries++) {
            CompletableFuture<StationStream> pending = streams.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> open(videoId, bitrate)));
            try {
                StationStream stream = pending.join();
                if (stream.alive()) return stream;
                stream.stop(); // reap() never sees it once it leaves the map
                streams.remove(key, pending);
                resolver.evict(videoId); // the URL is suspect now
            } catch (CompletionException e) {
                streams.remove(key, pending);
                throw new IOException("Station " + key + " will not stay up");
            }
        }
        throw new IOException("Station " + key + " will not stay up");
    }

    private StationStream open(String videoId, int bitrate) {
        try {
            String hls = resolver.resolveHls(videoId);
            StationStream stream = new StationStream(videoId, bitrate, props.getSubscriberQueueSize());
            stream.start(hls, props);
            return stream;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException(e);
        } catch (Exception e) {
            throw new CompletionException(e);
        }
    }

    // Shuts down stations nobody has listened
    @Scheduled(fixedDelay = 5000)
    void reap() {
        streams.entrySet().removeIf(entry -> {
            CompletableFuture<StationStream> pending = entry.getValue();
            if (!pending.isDone()) return false; // still starting up
            if (pending.isCompletedExceptionally()) return true;

            StationStream stream = pending.join();
            boolean died = !stream.alive();
            boolean finished = died || stream.idleLongerThan(props.getIdleGraceSeconds());
            if (finished) {
                stream.stop();
                if (died) {
                    String key = entry.getKey();
                    resolver.evict(key.substring(0, key.indexOf('@')));
                }
                log.info("Released {}", entry.getKey());
            }
            return finished;
        });
    }

    public Map<String, Integer> snapshot() {
        return streams.entrySet().stream()
            .filter(e -> e.getValue().isDone() && !e.getValue().isCompletedExceptionally())
            .filter(e -> e.getValue().join().alive())
            .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue().join().listeners()));
    }
}
