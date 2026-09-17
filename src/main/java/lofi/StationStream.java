package lofi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * One ffmpeg transcode, fanned out to every listener on the same station.
 *
 * One process per station rather than per listener, for two reasons. On a small
 * host the CPU limit binds long before bandwidth does. And it doubles as the
 * latency fix: joining a station that is already running costs one cluster
 * (~1s), against the ~3.8s a cold start takes - 0.65s to resolve, then about
 * 3.1s before ffmpeg emits anything.
 */
final class StationStream {

    private static final Logger log = LoggerFactory.getLogger(StationStream.class);

    /**
     * Matroska Cluster element id.
     *
     * Scanning for it separates the init segment from the body: everything before
     * the first one is the EBML header and Tracks that every listener must
     * receive, and each later one is a point a new listener can be spliced in at.
     *
     * This is a byte scan, not a parse. A four-byte id can in principle occur
     * inside frame data by chance; at roughly a kilobyte per cluster that is rare
     * enough to live with, and the failure mode is one glitched cluster rather
     * than a broken stream.
     */
    private static final byte[] CLUSTER_ID = {0x1f, 0x43, (byte) 0xb6, 0x75};

    /** Sentinel placed on every subscriber queue when the stream finishes. */
    static final byte[] END = new byte[0];

    /**
     * How long ffmpeg may go without writing before it counts as dead. A cold
     * start takes about 3.8s, so this is far past any healthy pause.
     */
    private static final long STALL_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final String videoId;
    private final int bitrate;
    private final int queueSize;
    private final Set<BlockingQueue<byte[]>> subscribers = ConcurrentHashMap.newKeySet();
    private final CountDownLatch headerReady = new CountDownLatch(1);

    private volatile byte[] header;
    private volatile Process process;
    private volatile long emptySince = System.nanoTime();
    private volatile long lastOutput = System.nanoTime();

    StationStream(String videoId, int bitrate, int queueSize) {
        this.videoId = videoId;
        this.bitrate = bitrate;
        this.queueSize = queueSize;
    }

    void start(String hlsUrl, LofiProperties props) throws IOException {
        List<String> cmd = List.of(
                props.getFfmpeg(), "-hide_banner", "-loglevel", "error",
                // A live playlist drops a connection now and then; take it back up.
                "-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "5",
                "-i", hlsUrl,
                "-vn", "-sn",
                "-c:a", "libopus", "-b:a", bitrate + "k", "-ar", "48000", "-ac", "2",
                // -live 1 stops the muxer wanting to seek back to write cues, which
                // is impossible down a pipe and stalls the stream outright.
                "-f", "webm", "-live", "1",
                "-cluster_time_limit", String.valueOf(props.getClusterMillis()),
                "pipe:1");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        process = pb.start();
        lastOutput = System.nanoTime();

        Thread.ofVirtual().name("pump-" + videoId).start(this::pump);
        log.info("started {} at {}k", videoId, bitrate);
    }

    /**
     * Splits ffmpeg's output at cluster boundaries and hands each block out.
     *
     * The buffer is a plain array compacted in place rather than a stream, so a
     * boundary scan never has to copy the whole pending region first.
     */
    private void pump() {
        byte[] pending = new byte[64 * 1024];
        int len = 0;
        byte[] chunk = new byte[8192];

        try (InputStream in = process.getInputStream()) {
            int n;
            while ((n = in.read(chunk)) > 0) {
                if (len + n > pending.length) {
                    pending = Arrays.copyOf(pending, Math.max(pending.length * 2, len + n));
                }
                System.arraycopy(chunk, 0, pending, len, n);
                len += n;
                lastOutput = System.nanoTime();

                // Search from offset 1 so a boundary sitting at the start of the
                // buffer is not rediscovered on every pass.
                int idx;
                while ((idx = indexOf(pending, len, CLUSTER_ID, 1)) >= 0) {
                    byte[] block = Arrays.copyOfRange(pending, 0, idx);
                    System.arraycopy(pending, idx, pending, 0, len - idx);
                    len -= idx;

                    if (header == null) {
                        header = block;             // everything before cluster one
                        headerReady.countDown();
                    } else {
                        broadcast(block);
                    }
                }
            }
        } catch (IOException e) {
            log.debug("pump for {} ended: {}", videoId, e.getMessage());
        } finally {
            headerReady.countDown();                // never leave a joiner waiting
            broadcast(END);
            log.info("ended {}", videoId);
        }
    }

    private static int indexOf(byte[] haystack, int length, byte[] needle, int from) {
        outer:
        for (int i = from; i <= length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private void broadcast(byte[] block) {
        for (BlockingQueue<byte[]> q : subscribers) {
            if (!q.offer(block)) {
                // A listener that cannot keep up is dropped rather than allowed to
                // hold the whole station back.
                subscribers.remove(q);
                log.info("dropped a slow listener on {}", videoId);
            }
        }
    }

    BlockingQueue<byte[]> subscribe() {
        BlockingQueue<byte[]> q = new ArrayBlockingQueue<>(queueSize);
        subscribers.add(q);
        return q;
    }

    void unsubscribe(BlockingQueue<byte[]> q) {
        subscribers.remove(q);
        if (subscribers.isEmpty()) emptySince = System.nanoTime();
    }

    /** Blocks until the init segment exists; null if it never arrives. */
    byte[] awaitHeader(long timeoutSeconds) throws InterruptedException {
        return headerReady.await(timeoutSeconds, TimeUnit.SECONDS) ? header : null;
    }

    /**
     * Running and still producing.
     *
     * A running process is not enough. When ffmpeg loses its segments it can sit
     * with its connections half-closed, never writing and never exiting: measured
     * overnight, one wrote nothing for two and a half hours. Reported as alive, it
     * was handed to every new listener, who got a header and then silence.
     */
    boolean alive() {
        Process p = process;
        return p != null && p.isAlive()
                && System.nanoTime() - lastOutput < STALL_NANOS;
    }

    int listeners() {
        return subscribers.size();
    }

    boolean idleLongerThan(long seconds) {
        return subscribers.isEmpty()
                && System.nanoTime() - emptySince > seconds * 1_000_000_000L;
    }

    void stop() {
        Process p = process;
        if (p != null) p.destroyForcibly();
    }
}
