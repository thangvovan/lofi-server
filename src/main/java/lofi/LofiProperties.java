package lofi;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

/** Everything tunable, so nothing operational is buried in the code. */
@ConfigurationProperties(prefix = "lofi")
public class LofiProperties {

    /** Path to ffmpeg. Left as a bare name so PATH is used unless overridden. */
    private String ffmpeg = "ffmpeg";

    /**
     * Opus bitrate in kbps when the request does not ask for one.
     *
     * Measured at steady state on a warm station, which is what matters for a
     * wallpaper that plays continuously: 32k costs 16.1 MB/hour, 48k costs 19.4,
     * 96k about 37. Add roughly 4 MB/hour of outbound HTTP requests on top -
     * ffmpeg issues about 2,340 of them an hour, each carrying a googlevideo URL
     * around 1,241 characters long.
     */
    private int defaultBitrate = 32;

    private Set<Integer> allowedBitrates = Set.of(32, 48, 64, 96);

    /**
     * Matroska cluster length. This is also the worst-case delay before a new
     * listener can be spliced into a station that is already running, because a
     * cluster boundary is the only place a stream can be joined.
     */
    private int clusterMillis = 1000;

    /** Live manifest URLs outlive a page reload, so a reconnect need not re-resolve. */
    private long resolveTtlSeconds = 30 * 60;

    /**
     * How long a station keeps running with nobody listening.
     *
     * Not just politeness: starting a station makes ffmpeg pull the whole HLS
     * window at once, which costs about twice the steady rate for the first
     * twenty seconds. Riding out a brief reconnect is cheaper than paying that
     * burst again.
     */
    private long idleGraceSeconds = 15;

    /** Bounded so a listener that stops reading cannot grow a queue without limit. */
    private int subscriberQueueSize = 32;

    public String getFfmpeg() { return ffmpeg; }
    public void setFfmpeg(String ffmpeg) { this.ffmpeg = ffmpeg; }

    public int getDefaultBitrate() { return defaultBitrate; }
    public void setDefaultBitrate(int defaultBitrate) { this.defaultBitrate = defaultBitrate; }

    public Set<Integer> getAllowedBitrates() { return allowedBitrates; }
    public void setAllowedBitrates(Set<Integer> allowedBitrates) { this.allowedBitrates = allowedBitrates; }

    public int getClusterMillis() { return clusterMillis; }
    public void setClusterMillis(int clusterMillis) { this.clusterMillis = clusterMillis; }

    public long getResolveTtlSeconds() { return resolveTtlSeconds; }
    public void setResolveTtlSeconds(long resolveTtlSeconds) { this.resolveTtlSeconds = resolveTtlSeconds; }

    public long getIdleGraceSeconds() { return idleGraceSeconds; }
    public void setIdleGraceSeconds(long idleGraceSeconds) { this.idleGraceSeconds = idleGraceSeconds; }

    public int getSubscriberQueueSize() { return subscriberQueueSize; }
    public void setSubscriberQueueSize(int subscriberQueueSize) { this.subscriberQueueSize = subscriberQueueSize; }
}
