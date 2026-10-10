package com.networkalert.simulator.scenario;

import com.networkalert.simulator.MockHttpServer;
import com.networkalert.simulator.MockTcpServer;
import com.networkalert.simulator.MockUdpServer;
import com.networkalert.util.LoggerUtil;
import com.networkalert.util.NamedThreadFactory;

import java.io.File;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Động cơ thực thi kịch bản diễn tiến tự động theo ERRATA §E14.1.
 * Hỗ trợ đồng hồ Clock thực (khi chạy ứng dụng/demo) hoặc Clock giả định (khi chạy test E2E nhanh không cần chờ).
 */
public class ScenarioRunner {
    private final MockTcpServer tcpServer;
    private final MockUdpServer udpServer;
    private final MockHttpServer httpServer;
    private final ScenarioModel scenario;
    private final Clock clock;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private int currentStepIndex = -1;
    private long startTimeEpochSec = 0;
    private ScheduledExecutorService scheduler;

    private BiConsumer<Integer, ScenarioStep> stepListener;
    private Consumer<String> statusListener;
    private Runnable completionListener;

    public ScenarioRunner(MockTcpServer tcp, MockUdpServer udp, MockHttpServer http) {
        this(tcp, udp, http, ScenarioModel.loadFromFileOrDefault(new File("scenarios/demo.json")), Clock.systemUTC());
    }

    public ScenarioRunner(MockTcpServer tcp, MockUdpServer udp, MockHttpServer http, ScenarioModel scenario, Clock clock) {
        this.tcpServer = tcp;
        this.udpServer = udp;
        this.httpServer = http;
        this.scenario = scenario != null ? scenario : ScenarioModel.createDefault();
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public synchronized boolean start() {
        if (running.get()) return true;

        this.currentStepIndex = -1;
        this.startTimeEpochSec = clock.instant().getEpochSecond();
        this.running.set(true);

        LoggerUtil.info("🎬 [ScenarioRunner] Bắt đầu chạy kịch bản: '" + scenario.getName() + "' (" + scenario.getSteps().size() + " bước)...");
        advanceTo(0);

        scheduler = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("NCAS-ScenarioWorker", true));
        scheduler.scheduleAtFixedRate(this::tick, 1, 1, TimeUnit.SECONDS);
        return true;
    }

    private void tick() {
        if (!running.get()) return;
        long elapsedSec = clock.instant().getEpochSecond() - startTimeEpochSec;
        advanceTo(elapsedSec);
    }

    /**
     * Chuyển mốc thời gian diễn tiến kịch bản tới `elapsedSeconds`.
     * Cho phép kiểm thử tự động tức thời với TestClock mà không phải sleep luồng.
     */
    public synchronized void advanceTo(long elapsedSeconds) {
        List<ScenarioStep> steps = scenario.getSteps();
        if (steps == null || steps.isEmpty()) return;

        for (int i = 0; i < steps.size(); i++) {
            ScenarioStep step = steps.get(i);
            if (step.getTimeOffsetSeconds() <= elapsedSeconds && i > currentStepIndex) {
                executeStep(step);
                currentStepIndex = i;
                LoggerUtil.info(String.format("🎯 [ScenarioRunner] [Bước %d/%d] (+%ds) %s - %s",
                        i + 1, steps.size(), step.getTimeOffsetSeconds(), step.getName(), step.getDescription()));

                if (stepListener != null) {
                    stepListener.accept(i, step);
                }
                if (statusListener != null) {
                    statusListener.accept(String.format("Bước %d/%d (+%ds): %s", i + 1, steps.size(), step.getTimeOffsetSeconds(), step.getName()));
                }
            }
        }

        // Kiểm tra xem đã kết thúc toàn bộ bước kịch bản chưa
        if (currentStepIndex >= steps.size() - 1) {
            long lastStepTime = steps.get(steps.size() - 1).getTimeOffsetSeconds();
            if (elapsedSeconds >= lastStepTime) {
                if (running.get() && completionListener != null) {
                    completionListener.run();
                }
            }
        }
    }

    /**
     * Áp dụng trạng thái của từng bước kịch bản lên các Mock Server
     */
    public void executeStep(ScenarioStep step) {
        if (step == null) return;

        // 1. TCP Server
        if (tcpServer != null) {
            if (step.getTcpRunning() != null) {
                if (step.getTcpRunning() && !tcpServer.isRunning()) {
                    tcpServer.start();
                } else if (!step.getTcpRunning() && tcpServer.isRunning()) {
                    tcpServer.stop();
                }
            }
            if (step.getTcpDelayMs() != null) {
                tcpServer.setArtificialDelayMs(step.getTcpDelayMs());
            }
        }

        // 2. HTTP Server
        if (httpServer != null) {
            if (step.getHttpRunning() != null) {
                if (step.getHttpRunning() && !httpServer.isRunning()) {
                    httpServer.start();
                } else if (!step.getHttpRunning() && httpServer.isRunning()) {
                    httpServer.stop();
                }
            }
            if (step.getHttpStatusCode() != null) {
                httpServer.setResponseCode(step.getHttpStatusCode());
            }
            if (step.getHttpDelayMs() != null) {
                httpServer.setArtificialDelayMs(step.getHttpDelayMs());
            }
        }

        // 3. UDP Server
        if (udpServer != null) {
            if (step.getUdpRunning() != null) {
                if (step.getUdpRunning() && !udpServer.isRunning()) {
                    udpServer.start();
                } else if (!step.getUdpRunning() && udpServer.isRunning()) {
                    udpServer.stop();
                }
            }
        }
    }

    public synchronized void stop() {
        running.set(false);
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
        LoggerUtil.info("⏹ [ScenarioRunner] Đã dừng kịch bản demo.");
    }

    public boolean isRunning() { return running.get(); }
    public int getCurrentStepIndex() { return currentStepIndex; }
    public ScenarioModel getScenario() { return scenario; }

    public void setStepListener(BiConsumer<Integer, ScenarioStep> stepListener) {
        this.stepListener = stepListener;
    }

    public void setStatusListener(Consumer<String> statusListener) {
        this.statusListener = statusListener;
    }

    public void setCompletionListener(Runnable completionListener) {
        this.completionListener = completionListener;
    }

    /**
     * Chạy kịch bản trực tiếp từ dòng lệnh CLI (Headless Mode)
     */
    public static void runHeadlessCli(String[] args) {
        File file = new File(args.length > 0 ? args[0] : "scenarios/demo.json");
        ScenarioModel model = ScenarioModel.loadFromFileOrDefault(file);

        MockTcpServer tcp = new MockTcpServer(9000);
        MockUdpServer udp = new MockUdpServer(9001);
        MockHttpServer http = new MockHttpServer(9002);

        LoggerUtil.info("================================================================================");
        LoggerUtil.info("   NCAS 2.0 CLI SCENARIO RUNNER - " + model.getName());
        LoggerUtil.info("   Mô tả: " + model.getDescription());
        LoggerUtil.info("   Số bước: " + model.getSteps().size());
        LoggerUtil.info("================================================================================");

        ScenarioRunner runner = new ScenarioRunner(tcp, udp, http, model, Clock.systemUTC());
        runner.setCompletionListener(() -> {
            LoggerUtil.info("🎉 [NCAS Scenario] Kịch bản demo đã hoàn thành xuất sắc toàn bộ các bước!");
            runner.stop();
            tcp.stop();
            udp.stop();
            http.stop();
            System.exit(0);
        });

        runner.start();

        Runtime.getRuntime().addShutdownHook(new NamedThreadFactory("NCAS-ScenarioShutdown").newThread(() -> {
            runner.stop();
            tcp.stop();
            udp.stop();
            http.stop();
        }));
    }
}
