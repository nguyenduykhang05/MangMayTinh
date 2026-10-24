package com.networkalert.simulator.scenario;

import com.networkalert.simulator.MockHttpServer;
import com.networkalert.simulator.MockTcpServer;
import com.networkalert.simulator.MockUdpServer;
import com.networkalert.test.TestClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("T6.3: Kịch bản Demo Tự Động + ScenarioRunner (ERRATA §E14.1)")
public class ScenarioRunnerTest {

    private MockTcpServer tcpServer;
    private MockUdpServer udpServer;
    private MockHttpServer httpServer;
    private TestClock testClock;

    @BeforeEach
    void setUp() {
        tcpServer = new MockTcpServer(9800);
        udpServer = new MockUdpServer(9801);
        httpServer = new MockHttpServer(9802);
        testClock = TestClock.of("2026-10-24T10:00:00Z");
    }

    @AfterEach
    void tearDown() {
        if (tcpServer != null) tcpServer.stop();
        if (udpServer != null) udpServer.stop();
        if (httpServer != null) httpServer.stop();
    }

    @Test
    @DisplayName("1. Nạp kịch bản từ file scenarios/demo.json chuẩn theo ERRATA §E14.1")
    void testLoadScenarioFromDemoJsonFile() {
        File file = new File("scenarios/demo.json");
        assertTrue(file.exists(), "File scenarios/demo.json phải tồn tại trên đĩa");

        ScenarioModel model = ScenarioModel.loadFromFileOrDefault(file);
        assertNotNull(model);
        assertEquals(5, model.getSteps().size(), "Kịch bản chuẩn phải có 5 bước diễn tiến");

        ScenarioStep s0 = model.getSteps().get(0);
        assertEquals(0, s0.getTimeOffsetSeconds());
        assertEquals("Mọi thứ OK", s0.getName());
        assertTrue(s0.getTcpRunning());
        assertEquals(200, s0.getHttpStatusCode());

        ScenarioStep s1 = model.getSteps().get(1);
        assertEquals(30, s1.getTimeOffsetSeconds());
        assertEquals("TCP sập", s1.getName());
        assertFalse(s1.getTcpRunning());

        ScenarioStep s2 = model.getSteps().get(2);
        assertEquals(90, s2.getTimeOffsetSeconds());
        assertEquals("HTTP 500", s2.getName());
        assertEquals(500, s2.getHttpStatusCode());

        ScenarioStep s3 = model.getSteps().get(3);
        assertEquals(150, s3.getTimeOffsetSeconds());
        assertEquals("Thêm trễ 500ms", s3.getName());
        assertEquals(500, s3.getHttpDelayMs());

        ScenarioStep s4 = model.getSteps().get(4);
        assertEquals(210, s4.getTimeOffsetSeconds());
        assertEquals("Hồi phục hoàn toàn", s4.getName());
        assertTrue(s4.getTcpRunning());
        assertEquals(200, s4.getHttpStatusCode());
    }

    @Test
    @DisplayName("2. Thực thi kịch bản tất định từng bước theo TestClock (Không dùng Thread.sleep)")
    void testScenarioRunnerProgressiveExecutionWithTestClock() {
        File file = new File("scenarios/demo.json");
        ScenarioModel model = ScenarioModel.loadFromFileOrDefault(file);

        ScenarioRunner runner = new ScenarioRunner(tcpServer, udpServer, httpServer, model, testClock);

        List<String> executedSteps = new ArrayList<>();
        runner.setStepListener((idx, step) -> executedSteps.add(step.getName()));

        AtomicBoolean completed = new AtomicBoolean(false);
        runner.setCompletionListener(() -> completed.set(true));

        // t = 0: Khởi động mọi thứ OK
        runner.start();
        assertEquals(1, executedSteps.size());
        assertEquals("Mọi thứ OK", executedSteps.get(0));
        assertTrue(tcpServer.isRunning(), "TCP Server phải đang chạy ở t=0");
        assertTrue(udpServer.isRunning(), "UDP Server phải đang chạy ở t=0");
        assertTrue(httpServer.isRunning(), "HTTP Server phải đang chạy ở t=0");
        assertEquals(200, httpServer.getResponseCode());
        assertEquals(0, httpServer.getArtificialDelayMs());

        // Tua đồng hồ tới t = +15s (chưa tới bước 2)
        testClock.advanceSeconds(15);
        runner.advanceTo(15);
        assertEquals(1, executedSteps.size(), "Chưa có bước mới nào ở t=15s");
        assertTrue(tcpServer.isRunning());

        // Tua đồng hồ tới t = +30s: Bước 2 (TCP sập)
        testClock.advanceSeconds(15); // Tổng 30s
        runner.advanceTo(30);
        assertEquals(2, executedSteps.size());
        assertEquals("TCP sập", executedSteps.get(1));
        assertFalse(tcpServer.isRunning(), "TCP Server phải sập (stop) ở t=30s");
        assertEquals(200, httpServer.getResponseCode(), "HTTP Server vẫn 200");

        // Tua đồng hồ tới t = +90s: Bước 3 (HTTP 500)
        testClock.advanceSeconds(60); // Tổng 90s
        runner.advanceTo(90);
        assertEquals(3, executedSteps.size());
        assertEquals("HTTP 500", executedSteps.get(2));
        assertFalse(tcpServer.isRunning());
        assertEquals(500, httpServer.getResponseCode(), "HTTP Server phải trả về mã 500 ở t=90s");

        // Tua đồng hồ tới t = +150s: Bước 4 (Thêm trễ 500ms)
        testClock.advanceSeconds(60); // Tổng 150s
        runner.advanceTo(150);
        assertEquals(4, executedSteps.size());
        assertEquals("Thêm trễ 500ms", executedSteps.get(3));
        assertEquals(500, httpServer.getArtificialDelayMs(), "HTTP Server phải có độ trễ 500ms ở t=150s");

        // Tua đồng hồ tới t = +210s: Bước 5 (Hồi phục hoàn toàn)
        testClock.advanceSeconds(60); // Tổng 210s
        runner.advanceTo(210);
        assertEquals(5, executedSteps.size());
        assertEquals("Hồi phục hoàn toàn", executedSteps.get(4));
        assertTrue(tcpServer.isRunning(), "TCP Server phải được khôi phục bật lại ở t=210s");
        assertEquals(200, httpServer.getResponseCode(), "HTTP Server phải về lại status 200");
        assertEquals(0, httpServer.getArtificialDelayMs(), "Độ trễ HTTP phải về 0ms");
        assertTrue(completed.get(), "Completion callback phải được kích hoạt sau khi chạy hết các bước");

        runner.stop();
    }

    @Test
    @DisplayName("3. Tự động dự phòng kịch bản mặc định khi file không tồn tại")
    void testScenarioDefaultFallbackWhenFileNotFound() {
        File nonExistent = new File("scenarios/non_existent_file_12345.json");
        ScenarioModel model = ScenarioModel.loadFromFileOrDefault(nonExistent);

        assertNotNull(model);
        assertEquals(5, model.getSteps().size());
        assertEquals(0, model.getSteps().get(0).getTimeOffsetSeconds());
        assertEquals(210, model.getSteps().get(4).getTimeOffsetSeconds());
    }

    @Test
    @DisplayName("4. Dừng kịch bản (stop) an toàn và không gây lỗi")
    void testScenarioRunnerStop() {
        ScenarioRunner runner = new ScenarioRunner(tcpServer, udpServer, httpServer);
        runner.start();
        assertTrue(runner.isRunning());

        runner.stop();
        assertFalse(runner.isRunning());
    }
}
