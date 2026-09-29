package com.thangvovan.lofiserver;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

@ConfigurationProperties(prefix = "lofi")
public class LofiProperties {
    // Path to ffmpeg
    private String ffmpeg;

    // Opus bitrate in kbps
    private int defaultBitrate;

    // Allowed bitrates in kbps
    private Set<Integer> allowedBitrates;

    // Matroska cluster length
    private int clusterMillis;

    // Time to live manifest URLs
    private long resolveTtlSeconds;

    // How long a station keeps running with nobody listening
    private long idleGraceSeconds;

    // Bounded so a listener cannot grow without limit
    private int subscriberQueueSize;

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
