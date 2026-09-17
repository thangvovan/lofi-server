package lofi;

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

/** Keeps at most one {@link StationStream} per station and retires idle ones. */
@Service
public class StationRegistry {

    private static final Logger log = LoggerFactory.getLogger(StationRegistry.class);

    /**
     * Futures rather than streams, so that two listeners arriving together share
     * one transcode.
     *
     * Holding the stream directly does not work: creating one means resolving and
     * spawning ffmpeg, which is far too slow to do inside a map function, and
     * doing it outside leaves a window where both callers see an empty map and
     * both start a process. Checking "is the existing one alive?" does not close
     * that window either - a stream that has been constructed but not yet started
     * has no process, so it reports not alive and gets replaced. The observable
     * result was two ffmpeg processes for one station.
     *
     * computeIfAbsent runs the creation exactly once per key; everyone else waits
     * on the same future.
     */
    private final Map<String, CompletableFuture<StationStream>> streams = new ConcurrentHashMap<>();

    private final YoutubeResolver resolver;
    private final LofiProperties props;

    StationRegistry(YoutubeResolver resolver, LofiProperties props) {
        this.resolver = resolver;
        this.props = props;
    }

    StationStream acquire(String videoId, int bitrate) throws IOException {
        String key = videoId + "@" + bitrate;

        // At most one retry: the only way round the loop is finding a stream that
        // died between the lookup and the join, and a second death means the
        // station is genuinely broken rather than racing.
        for (int tries = 0; tries < 2; tries++) {
            CompletableFuture<StationStream> pending = streams.computeIfAbsent(key,
                    k -> CompletableFuture.supplyAsync(() -> open(videoId, bitrate)));
            try {
                StationStream stream = pending.join();
                if (stream.alive()) return stream;
                streams.remove(key, pending);
                resolver.evict(videoId);        // the URL it started from is suspect now
            } catch (CompletionException e) {
                streams.remove(key, pending);
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                throw new IOException(cause.getMessage(), cause);
            }
        }
        throw new IOException("station " + key + " will not stay up");
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

    /** Shuts down stations nobody has listened to for a while. */
    @Scheduled(fixedDelay = 5000)
    void reap() {
        streams.entrySet().removeIf(entry -> {
            CompletableFuture<StationStream> pending = entry.getValue();
            if (!pending.isDone()) return false;             // still starting up
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
                log.info("released {}", entry.getKey());
            }
            return finished;
        });
    }

    public Map<String, Integer> snapshot() {
        return streams.entrySet().stream()
                .filter(e -> e.getValue().isDone() && !e.getValue().isCompletedExceptionally())
                .filter(e -> e.getValue().join().alive())
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().join().listeners()));
    }
}
