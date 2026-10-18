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
    private String projectId;
    private String sceneId;
    private String shotId;
    private String startImageResultId;
    private Long seed;
    private Integer steps;
    private String workflowVersion;
    private String referenceMode;
    private String resultUrl;
    private String error;
    private boolean audioEnabled;
    private String audioText;
    private String audioVoice;
    private String audioId;
    private Double audioDurationSeconds;
    private com.fasterxml.jackson.databind.JsonNode audioDialogues;
    private String parentId;
    private com.fasterxml.jackson.databind.JsonNode dramaRequest;
    private java.util.List<MediaJob> dramaShots;
    private Integer currentShotIndex;
    private Double dynamicDurationSeconds;

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
    public String getProjectId() { return projectId; }
    public void setProjectId(String value) { this.projectId = value; }
    public String getSceneId() { return sceneId; }
    public void setSceneId(String value) { this.sceneId = value; }
    public String getShotId() { return shotId; }
    public void setShotId(String value) { this.shotId = value; }
    public String getStartImageResultId() { return startImageResultId; }
    public void setStartImageResultId(String value) { this.startImageResultId = value; }
    public Long getSeed() { return seed; }
    public void setSeed(Long value) { this.seed = value; }
    public Integer getSteps() { return steps; }
    public void setSteps(Integer value) { this.steps = value; }
    public String getWorkflowVersion() { return workflowVersion; }
    public void setWorkflowVersion(String value) { this.workflowVersion = value; }
    public String getReferenceMode() { return referenceMode; }
    public void setReferenceMode(String value) { this.referenceMode = value; }
    public String getResultUrl() { return resultUrl; }
    public void setResultUrl(String value) { this.resultUrl = value; }
    public String getError() { return error; }
    public void setError(String value) { this.error = value; }
    public boolean isAudioEnabled() { return audioEnabled; }
    public void setAudioEnabled(boolean value) { this.audioEnabled = value; }
    public String getAudioText() { return audioText; }
    public void setAudioText(String value) { this.audioText = value; }
    public String getAudioVoice() { return audioVoice; }
    public void setAudioVoice(String value) { this.audioVoice = value; }
    public String getAudioId() { return audioId; }
    public void setAudioId(String value) { this.audioId = value; }
    public Double getAudioDurationSeconds() { return audioDurationSeconds; }
    public void setAudioDurationSeconds(Double value) { this.audioDurationSeconds = value; }
    public com.fasterxml.jackson.databind.JsonNode getAudioDialogues() { return audioDialogues; }
    public void setAudioDialogues(com.fasterxml.jackson.databind.JsonNode value) { this.audioDialogues = value; }
    public String getParentId() { return parentId; }
    public void setParentId(String value) { parentId = value; }
    public com.fasterxml.jackson.databind.JsonNode getDramaRequest() { return dramaRequest; }
    public void setDramaRequest(com.fasterxml.jackson.databind.JsonNode value) { dramaRequest = value; }
    public java.util.List<MediaJob> getDramaShots() { return dramaShots; }
    public void setDramaShots(java.util.List<MediaJob> value) { dramaShots = value; }
    public Integer getCurrentShotIndex() { return currentShotIndex; }
    public void setCurrentShotIndex(Integer value) { currentShotIndex = value; }
    public Double getDynamicDurationSeconds() { return dynamicDurationSeconds; }
    public void setDynamicDurationSeconds(Double value) { dynamicDurationSeconds = value; }
}
