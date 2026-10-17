package com.tenx.ai.media.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tenx.media")
public class MediaProperties {

    private List<String> apiKeys = new ArrayList<String>();
    private String gatewayBaseUrl = "http://macstudio.tentest.cn:8088/v1";
    private String gatewayApiKey = "local-dev-key";
    private String publicBaseUrl = "http://windows.tentest.cn:8092";
    private String frameCacheRoot = "data/frame-cache";
    private String resultsRoot = "data/results";
    private long maxFrameBytes = 52428800;
    private String ffmpegPath = "ffmpeg";
    private long ffmpegTimeoutMillis = 600000;
    private long gatewayTimeoutMillis = 1800000;
    private int maxConcurrentVideoJobs = 1;
    private String cosyvoiceBaseUrl = "http://localhost:4030";
    private String cosyvoiceApiKey = "";
    private long cosyvoiceTimeoutMillis = 3600000;
    private String audioRoot = "data/audio";

    public List<String> getApiKeys() { return apiKeys; }
    public void setApiKeys(List<String> value) { this.apiKeys = value; }
    public String getGatewayBaseUrl() { return gatewayBaseUrl; }
    public void setGatewayBaseUrl(String value) { this.gatewayBaseUrl = value; }
    public String getGatewayApiKey() { return gatewayApiKey; }
    public void setGatewayApiKey(String value) { this.gatewayApiKey = value; }
    public String getPublicBaseUrl() { return publicBaseUrl; }
    public void setPublicBaseUrl(String value) { this.publicBaseUrl = value; }
    public String getFrameCacheRoot() { return frameCacheRoot; }
    public void setFrameCacheRoot(String value) { this.frameCacheRoot = value; }
    public String getResultsRoot() { return resultsRoot; }
    public void setResultsRoot(String value) { this.resultsRoot = value; }
    public long getMaxFrameBytes() { return maxFrameBytes; }
    public void setMaxFrameBytes(long value) { this.maxFrameBytes = value; }
    public String getFfmpegPath() { return ffmpegPath; }
    public void setFfmpegPath(String value) { this.ffmpegPath = value; }
    public long getFfmpegTimeoutMillis() { return ffmpegTimeoutMillis; }
    public void setFfmpegTimeoutMillis(long value) { this.ffmpegTimeoutMillis = value; }
    public long getGatewayTimeoutMillis() { return gatewayTimeoutMillis; }
    public void setGatewayTimeoutMillis(long value) { this.gatewayTimeoutMillis = value; }
    public int getMaxConcurrentVideoJobs() { return maxConcurrentVideoJobs; }
    public void setMaxConcurrentVideoJobs(int value) { this.maxConcurrentVideoJobs = value; }
    public String getCosyvoiceBaseUrl() { return cosyvoiceBaseUrl; }
    public void setCosyvoiceBaseUrl(String value) { this.cosyvoiceBaseUrl = value; }
    public String getCosyvoiceApiKey() { return cosyvoiceApiKey; }
    public void setCosyvoiceApiKey(String value) { this.cosyvoiceApiKey = value; }
    public long getCosyvoiceTimeoutMillis() { return cosyvoiceTimeoutMillis; }
    public void setCosyvoiceTimeoutMillis(long value) { this.cosyvoiceTimeoutMillis = value; }
    public String getAudioRoot() { return audioRoot; }
    public void setAudioRoot(String value) { this.audioRoot = value; }
}
