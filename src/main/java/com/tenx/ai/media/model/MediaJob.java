package com.tenx.ai.media.model;

public class MediaJob {

    private String id;
    private String type;
    private JobStatus status;
    private long createdAt;
    private long updatedAt;
    private String model;
    private String gatewayResultId;
    private String framesRelativePath;
    private Integer frameCount;
    private Integer fps;
    private String resultUrl;
    private String error;

    public MediaJob() {
    }

    public static MediaJob video(String id, String model) {
        long now = System.currentTimeMillis();
        MediaJob job = new MediaJob();
        job.id = id;
        job.type = "video";
        job.status = JobStatus.QUEUED;
        job.createdAt = now;
        job.updatedAt = now;
        job.model = model;
        return job;
    }

    public void transition(JobStatus value) {
        this.status = value;
        this.updatedAt = System.currentTimeMillis();
    }

    public String getId() { return id; }
    public void setId(String value) { this.id = value; }
    public String getType() { return type; }
    public void setType(String value) { this.type = value; }
    public JobStatus getStatus() { return status; }
    public void setStatus(JobStatus value) { this.status = value; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long value) { this.createdAt = value; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long value) { this.updatedAt = value; }
    public String getModel() { return model; }
    public void setModel(String value) { this.model = value; }
    public String getGatewayResultId() { return gatewayResultId; }
    public void setGatewayResultId(String value) { this.gatewayResultId = value; }
    public String getFramesRelativePath() { return framesRelativePath; }
    public void setFramesRelativePath(String value) { this.framesRelativePath = value; }
    public Integer getFrameCount() { return frameCount; }
    public void setFrameCount(Integer value) { this.frameCount = value; }
    public Integer getFps() { return fps; }
    public void setFps(Integer value) { this.fps = value; }
    public String getResultUrl() { return resultUrl; }
    public void setResultUrl(String value) { this.resultUrl = value; }
    public String getError() { return error; }
    public void setError(String value) { this.error = value; }
}
