package com.networkalert.server.ingest;

import com.networkalert.model.CheckResult;
import com.networkalert.model.ServerStatus;
import com.networkalert.model.ServerTarget;
import com.networkalert.model.TargetScope;
import com.networkalert.model.TargetStateMachine;
import com.networkalert.protocol.Frame;
import com.networkalert.protocol.MessageType;
import com.networkalert.protocol.ProtocolMetrics;
import com.networkalert.protocol.messages.AckMessage;
import com.networkalert.protocol.messages.DataGap;
import com.networkalert.protocol.messages.ReportBatchMessage;
import com.networkalert.protocol.messages.ReportResultMessage;
import com.networkalert.server.AgentSession;
import com.networkalert.server.StreamTracker;
import com.networkalert.server.TargetDistributor;
import com.networkalert.service.AlertService;
import com.networkalert.util.LoggerUtil;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Đường ống tiếp nhận, chuẩn hóa và xử lý kết quả đo kiểm (Result Ingestor Pipeline).
 * <p>
 * Tuân thủ nghiêm ngặt quy trình 8 bước theo ERRATA §E5.1 & TASKS.md T1.6:
 * <ol>
 *   <li>{@code validate}: Kiểm tra tính hợp lệ cấu trúc của bản ghi kết quả</li>
 *   <li>{@code authz}: Phân quyền Agent chỉ được nạp dữ liệu cho Target được giao (ERRATA §E2.6)</li>
 *   <li>{@code dedupe}: Khử trùng lặp theo Sequence Number trên Stream (ERRATA §E3.3)</li>
 *   <li>{@code chuẩn hóa thời gian}: Bù trừ lệch đồng hồ phân tán theo mốc NTP (ERRATA §E3.7)</li>
 *   <li>{@code phân loại}: Phân định Fresh vs Backfill dựa trên freshWindow (ERRATA §E5.2)</li>
 *   <li>{@code persist}: Cam kết lô dữ liệu và Watermark vào {@link ResultSink}</li>
 *   <li>{@code ACK}: Gửi ACK về cho Agent SAU KHI persist thành công</li>
 *   <li>{@code dispatch}: Đẩy vào Quorum, TargetStateMachine và AlertService (CHỈ DÀNH CHO FRESH)</li>
 * </ol>
 * </p>
 */
public class ResultIngestor {

    public static final int DEFAULT_MAX_QUEUE_DEPTH = 10000;
    public static final long CLOCK_SKEW_THRESHOLD_MS = 5000L;

    private final TargetDistributor targetDistributor;
    private final ResultSink resultSink;
    private final AlertService alertService;
    private final IngestMetrics metrics;
    private final Clock clock;
    private final int maxQueueDepth;

    private final AtomicLong activeQueueDepth = new AtomicLong(0);

    private final List<BiConsumer<ServerTarget, CheckResult>> checkResultListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Frame>> consoleBroadcasters = new CopyOnWriteArrayList<>();

    public ResultIngestor(TargetDistributor targetDistributor,
                          ResultSink resultSink,
                          AlertService alertService,
                          IngestMetrics metrics,
                          Clock clock) {
        this(targetDistributor, resultSink, alertService, metrics, clock, DEFAULT_MAX_QUEUE_DEPTH);
    }

    public ResultIngestor(TargetDistributor targetDistributor,
                          ResultSink resultSink,
                          AlertService alertService,
                          IngestMetrics metrics,
                          Clock clock,
                          int maxQueueDepth) {
        this.targetDistributor = targetDistributor;
        this.resultSink = resultSink != null ? resultSink : new ResultSink.DefaultResultSink(targetDistributor);
        this.alertService = alertService;
        this.metrics = metrics != null ? metrics : new IngestMetrics();
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.maxQueueDepth = maxQueueDepth > 0 ? maxQueueDepth : DEFAULT_MAX_QUEUE_DEPTH;
    }

    public void addCheckResultListener(BiConsumer<ServerTarget, CheckResult> listener) {
        if (listener != null) {
            checkResultListeners.add(listener);
        }
    }

    public void addConsoleBroadcaster(Consumer<Frame> broadcaster) {
        if (broadcaster != null) {
            consoleBroadcasters.add(broadcaster);
        }
    }

    public IngestMetrics getMetrics() {
        return metrics;
    }

    public ResultSink getResultSink() {
        return resultSink;
    }

    public int getMaxQueueDepth() {
        return maxQueueDepth;
    }

    public long getActiveQueueDepth() {
        return activeQueueDepth.get();
    }

    /**
     * Nạp một kết quả đo kiểm đơn lẻ từ thông điệp REPORT_RESULT (0x0005)
     */
    public void ingestSingle(AgentSession session, ReportResultMessage report, long serverRecvMs) {
        if (report == null) return;
        metrics.incrementResultsReceived();

        // Kiểm soát Backpressure (§E5.6, §E8.1)
        if (activeQueueDepth.get() + 1 > maxQueueDepth || com.networkalert.repository.DatabaseManager.getInstance().isWriteQueueFull()) {
            metrics.recordProtocolError("backpressure_drop");
            LoggerUtil.warn(String.format("⚠️ [Ingestor] Hàng đợi đầy hoặc CSDL quá tải (%d/%d)! Từ chối kết quả đơn từ Agent '%s' (không ACK).",
                    activeQueueDepth.get(), maxQueueDepth, session != null ? session.getAgentId() : "?"));
            return;
        }

        activeQueueDepth.incrementAndGet();
        metrics.setQueueDepth(activeQueueDepth.get());
        try {
            // Bước 1: Validate
            if (!validateReport(report)) {
                metrics.recordProtocolError("invalid_payload");
                return;
            }

            // Bước 2: Authz
            ServerTarget target = authorizeTarget(session, report.getTargetId());
            if (target == null) {
                return;
            }

            Long streamId = report.getStreamId();
            Long seq = report.getSeq();
            long initWm = (resultSink != null && session != null && streamId != null) ? resultSink.getWatermark(session.getAgentId(), streamId) : 0L;
            StreamTracker tracker = (session != null && streamId != null) ? session.getOrCreateStreamTracker(streamId, initWm) : null;

            // Bước 3: Dedupe
            if (tracker != null && seq != null) {
                StreamTracker.SeqStatus status = tracker.processSeq(seq);
                if (status == StreamTracker.SeqStatus.DUPLICATE) {
                    metrics.incrementDuplicates();
                    // Bản trùng: không nạp lại, nhưng vẫn gửi ACK kèm watermark hiện tại để peer xả spool (§E5.1)
                    sendAckIfApplicable(session, streamId, tracker.getWatermark(), serverRecvMs);
                    return;
                } else if (status == StreamTracker.SeqStatus.ACCEPTED_OUT_OF_ORDER) {
                    metrics.incrementOutOfOrder();
                }
            }

            // Bước 4: Chuẩn hóa thời gian (NTP, §E3.7)
            long checkedAtServer = normalizeTimestamp(session, report.getCheckedAt(), serverRecvMs);

            // Bước 5: Phân loại fresh/backfill (§E5.2)
            boolean backfilled = classifyBackfill(report, target, checkedAtServer, serverRecvMs);

            String agentId = session != null ? session.getAgentId() : "AGENT";
            long actualStreamId = streamId != null ? streamId : 0L;
            long actualSeq = seq != null ? seq : 0L;

            IngestedResult ingested = new IngestedResult(
                    agentId, actualStreamId, actualSeq, report.getTargetId(),
                    checkedAtServer, serverRecvMs, report.isSuccess(),
                    report.getLatencyMs(), report.getResponseCode(),
                    report.getMessage(), report.getMethod(),
                    backfilled, report
            );

            // Bước 6: Persist (lô, commit cùng watermark) (§E5.1, §E8.3)
            long watermarkToCommit = tracker != null ? tracker.getWatermark() : actualSeq;
            try {
                resultSink.persist(Collections.singletonList(ingested), agentId, actualStreamId, watermarkToCommit);
            } catch (IOException e) {
                metrics.recordProtocolError("sink_error");
                LoggerUtil.error(String.format("❌ [Ingestor] Lỗi ghi dữ liệu vào ResultSink: %s. KHÔNG gửi ACK (E5.6)!", e.getMessage()));
                return;
            }

            // Bước 7: ACK (chỉ gửi sau khi persist thành công)
            if (tracker != null) {
                sendAckIfApplicable(session, actualStreamId, watermarkToCommit, serverRecvMs);
            }

            // Bước 8: Dispatch (chỉ dành cho fresh results)
            if (!backfilled) {
                dispatchFreshResult(session, target, ingested);
            }

        } finally {
            activeQueueDepth.decrementAndGet();
            metrics.setQueueDepth(activeQueueDepth.get());
            metrics.recordIngestLatency(clock.millis() - serverRecvMs);
        }
    }

    /**
     * Nạp một lô kết quả đo kiểm từ thông điệp REPORT_BATCH (0x0010)
     */
    public void ingestBatch(AgentSession session, ReportBatchMessage batch, long serverRecvMs) {
        if (batch == null || session == null) return;
        List<ReportResultMessage> items = batch.getItems() != null ? batch.getItems() : Collections.emptyList();
        int count = items.size();
        metrics.incrementResultsReceived(count);

        // Kiểm soát Backpressure (§E5.6, §E8.1)
        if (activeQueueDepth.get() + count > maxQueueDepth || com.networkalert.repository.DatabaseManager.getInstance().isWriteQueueFull()) {
            metrics.recordProtocolError("backpressure_drop");
            LoggerUtil.warn(String.format("⚠️ [Ingestor] Hàng đợi đầy hoặc CSDL quá tải (%d + %d > %d)! Từ chối lô kết quả từ Agent '%s' (không ACK).",
                    activeQueueDepth.get(), count, maxQueueDepth, session.getAgentId()));
            return;
        }

        activeQueueDepth.addAndGet(count);
        metrics.setQueueDepth(activeQueueDepth.get());
        try {
            long streamId = batch.getStreamId();
            long initWm = (resultSink != null && session != null) ? resultSink.getWatermark(session.getAgentId(), streamId) : 0L;
            StreamTracker tracker = session.getOrCreateStreamTracker(streamId, initWm);

            // Xử lý các khoảng Gap đã khai báo để kéo tiến watermark (§E3.4)
            if (batch.getGaps() != null) {
                for (DataGap gap : batch.getGaps()) {
                    if (gap.getStreamId() == streamId) {
                        long lostCount = tracker.applyGap(gap.getFromSeq(), gap.getToSeq());
                        if (lostCount > 0) {
                            metrics.incrementLost(lostCount);
                        }
                    }
                }
            }

            List<IngestedResult> batchToPersist = new ArrayList<>();
            List<IngestedResult> freshToDispatch = new ArrayList<>();

            for (ReportResultMessage report : items) {
                // Bước 1: Validate
                if (!validateReport(report)) {
                    metrics.recordProtocolError("invalid_payload");
                    continue;
                }

                // Bước 2: Authz
                ServerTarget target = authorizeTarget(session, report.getTargetId());
                if (target == null) {
                    continue;
                }

                // Bước 3: Dedupe
                if (report.getSeq() != null) {
                    StreamTracker.SeqStatus status = tracker.processSeq(report.getSeq());
                    if (status == StreamTracker.SeqStatus.DUPLICATE) {
                        metrics.incrementDuplicates();
                        continue; // Bỏ qua bản trùng
                    } else if (status == StreamTracker.SeqStatus.ACCEPTED_OUT_OF_ORDER) {
                        metrics.incrementOutOfOrder();
                    }
                }

                // Bước 4: Chuẩn hóa thời gian (NTP, §E3.7)
                long checkedAtServer = normalizeTimestamp(session, report.getCheckedAt(), serverRecvMs);

                // Bước 5: Phân loại fresh/backfill (§E5.2)
                boolean backfilled = classifyBackfill(report, target, checkedAtServer, serverRecvMs);

                IngestedResult ingested = new IngestedResult(
                        session.getAgentId(), streamId, report.getSeq() != null ? report.getSeq() : 0L,
                        report.getTargetId(), checkedAtServer, serverRecvMs, report.isSuccess(),
                        report.getLatencyMs(), report.getResponseCode(), report.getMessage(),
                        report.getMethod(), backfilled, report
                );

                batchToPersist.add(ingested);
                if (!backfilled) {
                    freshToDispatch.add(ingested);
                }
            }

            // Bước 6: Persist (lô, commit cùng watermark) (§E5.1, §E8.3)
            long watermarkToCommit = tracker.getWatermark();
            try {
                resultSink.persist(batchToPersist, session.getAgentId(), streamId, watermarkToCommit);
            } catch (IOException e) {
                metrics.recordProtocolError("sink_error");
                LoggerUtil.error(String.format("❌ [Ingestor] Lỗi ghi lô dữ liệu vào ResultSink: %s. KHÔNG gửi ACK (E5.6)!", e.getMessage()));
                return;
            }

            // Bước 7: ACK (chỉ gửi sau khi persist thành công)
            sendAckIfApplicable(session, streamId, watermarkToCommit, serverRecvMs);

            // Bước 8: Dispatch (chỉ dành cho fresh results)
            for (IngestedResult fresh : freshToDispatch) {
                ServerTarget target = targetDistributor.getTarget(fresh.getTargetId());
                if (target != null) {
                    dispatchFreshResult(session, target, fresh);
                }
            }

        } finally {
            activeQueueDepth.addAndGet(-count);
            metrics.setQueueDepth(activeQueueDepth.get());
            metrics.recordIngestLatency(clock.millis() - serverRecvMs);
        }
    }

    private boolean validateReport(ReportResultMessage report) {
        if (report == null) return false;
        if (report.getTargetId() <= 0) return false;
        return report.getCheckedAt() > 0;
    }

    private ServerTarget authorizeTarget(AgentSession session, int targetId) {
        ServerTarget target = targetDistributor != null ? targetDistributor.getTarget(targetId) : null;
        if (target == null) {
            metrics.recordProtocolError("target_not_found");
            LoggerUtil.warn(String.format("⚠️ [Ingestor] Bỏ qua kết quả cho Target #%d không tồn tại trên Server!", targetId));
            return null;
        }

        // Phân quyền dữ liệu (§E2.6): Target phạm vi SINGLE/GROUP chỉ chấp nhận kết quả từ Agent được phân công
        if (session != null && target.getScope() != TargetScope.MULTI &&
                session.getAssignedTargetIds() != null && !session.getAssignedTargetIds().isEmpty() &&
                !session.getAssignedTargetIds().contains(targetId)) {
            metrics.incrementAuthFailures();
            ProtocolMetrics.recordAuthFailure(session.getAgentId());
            LoggerUtil.warn(String.format("🛡️ [Ingestor] Từ chối kết quả từ Agent '%s': Target #%d không thuộc danh sách phân công (E2.6)!",
                    session.getAgentId(), targetId));
            return null;
        }

        return target;
    }

    private long normalizeTimestamp(AgentSession session, long agentCheckedAt, long serverRecvMs) {
        if (session != null && session.getClockOffsetMs() != null) {
            long offset = session.getClockOffsetMs();
            if (Math.abs(offset) > CLOCK_SKEW_THRESHOLD_MS) {
                session.setClockSkewDetected(true);
            }
            long adjusted = agentCheckedAt + offset;
            return Math.min(adjusted, serverRecvMs);
        }
        return Math.min(agentCheckedAt, serverRecvMs);
    }

    private boolean classifyBackfill(ReportResultMessage report, ServerTarget target, long checkedAtServer, long serverRecvMs) {
        long age = Math.max(0, serverRecvMs - checkedAtServer);
        int intervalSec = (target != null && target.getIntervalSeconds() > 0) ? target.getIntervalSeconds() : 5;
        long freshWindow = 2L * intervalSec * 1000L + 5000L;

        boolean isBackfilled = Boolean.TRUE.equals(report.getBackfilled()) || (age > freshWindow);
        if (isBackfilled) {
            metrics.incrementBackfilled();
        }
        return isBackfilled;
    }

    private void sendAckIfApplicable(AgentSession session, long streamId, long watermark, long serverRecvMs) {
        if (session != null && session.isV11OrHigher()) {
            try {
                long serverSendMs = clock.millis();
                AckMessage ack = AckMessage.ofStream(streamId, watermark, serverRecvMs, serverSendMs);
                session.sendFrame(MessageType.ACK, ack);
            } catch (Exception e) {
                LoggerUtil.warn(String.format("[Ingestor] Lỗi gửi ACK stream %d cho Agent '%s': %s",
                        streamId, session.getAgentId(), e.getMessage()));
            }
        }
    }

    private void dispatchFreshResult(AgentSession session, ServerTarget target, IngestedResult ingested) {
        if (ingested.getOriginalReport() != null && ingested.getOriginalReport().getDiag() != null) {
            target.setLastDiagnosis(ingested.getOriginalReport().getDiag());
        }

        CheckResult result = ingested.getOriginalReport() != null
                ? ingested.getOriginalReport().toCheckResult()
                : new CheckResult(ingested.isSuccess(), ingested.getLatencyMs(), ingested.getResponseCode(),
                                  ingested.getMessage(), ingested.isSuccess() ? ServerStatus.ONLINE : ServerStatus.OFFLINE);

        long latencyThreshold = (alertService != null && alertService.getConfig() != null)
                ? alertService.getConfig().getLatencyThresholdMs()
                : 300L;

        String stationId = (session != null && session.getAgentId() != null) ? session.getAgentId() : "AGENT";
        Optional<TargetStateMachine.StateTransition> transitionOpt = target.recordStationResult(stationId, result, latencyThreshold);

        // Kích hoạt cảnh báo khi có sự cố chuyển trạng thái
        if (transitionOpt.isPresent() && alertService != null) {
            TargetStateMachine.StateTransition transition = transitionOpt.get();
            ServerStatus from = transition.getFromStatus();
            ServerStatus to = transition.getToStatus();

            if (to == ServerStatus.OFFLINE) {
                alertService.notifyOffline(target, result.getMessage());
            } else if (from == ServerStatus.OFFLINE && to == ServerStatus.ONLINE) {
                alertService.notifyRecovered(target, target.getCurrentLatency());
            } else if (from == ServerStatus.OFFLINE && to == ServerStatus.WARNING) {
                alertService.notifyHighLatency(target, target.getCurrentLatency());
            } else if (from != ServerStatus.WARNING && to == ServerStatus.WARNING) {
                alertService.notifyHighLatency(target, target.getCurrentLatency());
            } else if (from == ServerStatus.WARNING && to == ServerStatus.ONLINE) {
                alertService.notifyLatencyRecovered(target, target.getCurrentLatency());
            }
        }

        // Thông báo kết quả cho các Listener nội bộ (Desktop GUI, MonitorService)
        for (BiConsumer<ServerTarget, CheckResult> l : checkResultListeners) {
            try {
                l.accept(target, result);
            } catch (Exception ignored) {}
        }

        // Đẩy kết quả theo thời gian thực tới các Console đang theo dõi
        if (ingested.getOriginalReport() != null) {
            Frame frame = Frame.create(MessageType.REPORT_RESULT, ingested.getOriginalReport());
            for (Consumer<Frame> broadcaster : consoleBroadcasters) {
                try {
                    broadcaster.accept(frame);
                } catch (Exception ignored) {}
            }
        }
    }
}
