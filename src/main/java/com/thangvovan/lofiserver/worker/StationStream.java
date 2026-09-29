package com.thangvovan.lofiserver.worker;

import com.thangvovan.lofiserver.LofiProperties;

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
 */
public class StationStream {
    private static final Logger log = LoggerFactory.getLogger(StationStream.class);

    // Matroska Cluster element id
    private static final byte[] CLUSTER_ID = {0x1f, 0x43, (byte) 0xb6, 0x75};

    // Sentinel placed on every subscriber queue when the stream finishes
    public static final byte[] END = new byte[0];

    // How long ffmpeg may go without writing before it counts as dead
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

    public StationStream(String videoId, int bitrate, int queueSize) {
        this.videoId = videoId;
        this.bitrate = bitrate;
        this.queueSize = queueSize;
    }

    public void start(String hlsUrl, LofiProperties props) throws IOException {
        List<String> cmd = List.of(
            props.getFfmpeg(), 
            "-hide_banner",
            "-loglevel", "error",
            "-reconnect", "1",
            "-reconnect_streamed", "1",
            "-reconnect_delay_max", "5",
            "-i", hlsUrl,
            "-vn",
            "-sn",
            "-c:a", "libopus",
            "-b:a", bitrate + "k",
            "-ar", "48000",
            "-ac", "2",
            "-f", "webm",
            "-live", "1",
            "-cluster_time_limit", String.valueOf(props.getClusterMillis()),
            "pipe:1"
        );

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        process = pb.start();
        lastOutput = System.nanoTime();

        Thread.ofPlatform().daemon().name("pump-" + videoId).start(this::pump);
        log.info("Started {} at {}k", videoId, bitrate);
    }

    // Splits ffmpeg's output at cluster boundaries and hands each block out to every subscriber
    private void pump() {
        byte[] pending = new byte[64 * 1024];
        byte[] chunk = new byte[8192];

        int len = 0;
        try (InputStream in = process.getInputStream()) {
            int n;
            while ((n = in.read(chunk)) > 0) {
                if (len + n > pending.length) {
                    pending = Arrays.copyOf(pending, Math.max(pending.length * 2, len + n));
                }

                System.arraycopy(chunk, 0, pending, len, n);
                len += n;
                lastOutput = System.nanoTime();

                // Search from offset 1
                int idx;
                while ((idx = indexOf(pending, len, CLUSTER_ID, 1)) >= 0) {
                    byte[] block = Arrays.copyOfRange(pending, 0, idx);
                    System.arraycopy(pending, idx, pending, 0, len - idx);
                    len -= idx;

                    if (header == null) {
                        header = block; // Everything before the first cluster
                        headerReady.countDown();
                    } else {
                        broadcast(block);
                    }
                }
            }
        } catch (IOException e) {
            log.debug("Pump for {} ended: {}", videoId, e.getMessage());
        } finally {
            headerReady.countDown(); // Never leave a joiner waiting
            broadcast(END);
            log.info("Ended {}", videoId);
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
                // A listener that cannot keep up is dropped
                subscribers.remove(q);
                log.info("Dropped a slow listener on {}", videoId);
            }
        }
    }

    public BlockingQueue<byte[]> subscribe() {
        BlockingQueue<byte[]> q = new ArrayBlockingQueue<>(queueSize);
        subscribers.add(q);
        return q;
    }

    public void unsubscribe(BlockingQueue<byte[]> q) {
        subscribers.remove(q);
        if (subscribers.isEmpty()) emptySince = System.nanoTime();
    }

    // Blocks until the init segment exists
    public byte[] awaitHeader(long timeoutSeconds) throws InterruptedException {
        return headerReady.await(timeoutSeconds, TimeUnit.SECONDS) ? header : null;
    }

    // Running and still producing
    public boolean alive() {
        Process p = process;
        return p != null && p.isAlive() && System.nanoTime() - lastOutput < STALL_NANOS;
    }

    public int listeners() {
        return subscribers.size();
    }

    public boolean idleLongerThan(long seconds) {
        return subscribers.isEmpty() && System.nanoTime() - emptySince > seconds * 1_000_000_000L;
    }

    public void stop() {
        Process p = process;
        if (p != null) p.destroyForcibly();
    }
}
