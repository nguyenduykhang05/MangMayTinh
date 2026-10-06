package com.networkalert.model;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Đối tượng sự cố hệ thống (Problem) theo chuẩn Zabbix / Nagios.
 */
public class Problem {
    private int id;
    private int targetId;
    private String targetName;
    private String source = "Server"; // Server hoặc Agent ID
    private ProblemSeverity severity = ProblemSeverity.HIGH;
    private ProblemStatus status = ProblemStatus.ACTIVE;
    private String title;
    private String description;
    private LocalDateTime startTime;
    private LocalDateTime resolvedTime;
    private LocalDateTime ackTime;
    private String ackUser;
    private String ackComment;

    public Problem() {
        this(Clock.systemDefaultZone());
    }

    public Problem(Clock clock) {
        this.startTime = LocalDateTime.now(clock != null ? clock : Clock.systemDefaultZone());
    }

    public Problem(int id, int targetId, String targetName, String source, ProblemSeverity severity,
                   ProblemStatus status, String title, String description, LocalDateTime startTime) {
        this(id, targetId, targetName, source, severity, status, title, description, startTime, Clock.systemDefaultZone());
    }

    public Problem(int id, int targetId, String targetName, String source, ProblemSeverity severity,
                   ProblemStatus status, String title, String description, LocalDateTime startTime, Clock clock) {
        this.id = id;
        this.targetId = targetId;
        this.targetName = targetName;
        this.source = source != null ? source : "Server";
        this.severity = severity != null ? severity : ProblemSeverity.HIGH;
        this.status = status != null ? status : ProblemStatus.ACTIVE;
        this.title = title;
        this.description = description;
        this.startTime = startTime != null ? startTime : LocalDateTime.now(clock != null ? clock : Clock.systemDefaultZone());
    }

    public String getDurationFormatted(Clock clock) {
        if (startTime == null) return "—";
        LocalDateTime end = (resolvedTime != null) ? resolvedTime : LocalDateTime.now(clock != null ? clock : Clock.systemDefaultZone());
        Duration d = Duration.between(startTime, end);
        long mins = d.toMinutes();
        if (mins < 1) return d.getSeconds() + " giây";
        if (mins < 60) return mins + " phút " + (d.getSeconds() % 60) + "s";
        long hours = d.toHours();
        long remMins = mins % 60;
        if (hours < 24) return hours + " giờ " + remMins + "p";
        long days = d.toDays();
        long remHours = hours % 24;
        return days + " ngày " + remHours + "g";
    }

    public String getDurationFormatted() {
        return getDurationFormatted(Clock.systemDefaultZone());
    }

    public String getFormattedDuration() {
        return getDurationFormatted();
    }

    public void acknowledge(String user, String comment, Clock clock) {
        this.status = ProblemStatus.ACKNOWLEDGED;
        this.ackTime = LocalDateTime.now(clock != null ? clock : Clock.systemDefaultZone());
        this.ackUser = user != null ? user : "Admin";
        this.ackComment = comment != null ? comment : "";
    }

    public void acknowledge(String user, String comment) {
        acknowledge(user, comment, Clock.systemDefaultZone());
    }

    public void resolve(String resolutionNote, Clock clock) {
        this.status = ProblemStatus.RESOLVED;
        this.resolvedTime = LocalDateTime.now(clock != null ? clock : Clock.systemDefaultZone());
        if (resolutionNote != null && !resolutionNote.trim().isEmpty()) {
            this.description = (this.description != null ? this.description + " | " : "") + resolutionNote;
        }
    }

    public void resolve(String resolutionNote) {
        resolve(resolutionNote, Clock.systemDefaultZone());
    }

    // Getters and Setters
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public int getTargetId() { return targetId; }
    public void setTargetId(int targetId) { this.targetId = targetId; }

    public String getTargetName() { return targetName; }
    public void setTargetName(String targetName) { this.targetName = targetName; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public ProblemSeverity getSeverity() { return severity; }
    public void setSeverity(ProblemSeverity severity) { this.severity = severity; }

    public ProblemStatus getStatus() { return status; }
    public void setStatus(ProblemStatus status) { this.status = status; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }

    public LocalDateTime getResolvedTime() { return resolvedTime; }
    public void setResolvedTime(LocalDateTime resolvedTime) { this.resolvedTime = resolvedTime; }

    public LocalDateTime getAckTime() { return ackTime; }
    public void setAckTime(LocalDateTime ackTime) { this.ackTime = ackTime; }

    public String getAckUser() { return ackUser; }
    public void setAckUser(String ackUser) { this.ackUser = ackUser; }

    public String getAckComment() { return ackComment; }
    public void setAckComment(String ackComment) { this.ackComment = ackComment; }

    private String diagnosis;
    public String getDiagnosis() { return diagnosis; }
    public void setDiagnosis(String diagnosis) { this.diagnosis = diagnosis; }
}
