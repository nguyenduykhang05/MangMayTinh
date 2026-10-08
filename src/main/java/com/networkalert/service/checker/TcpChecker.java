package com.networkalert.service.checker;

import com.networkalert.model.CheckResult;
import com.networkalert.model.PerfData;
import com.networkalert.model.ServerTarget;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;

/**
 * Kiểm tra kết nối tầng Vận chuyển qua giao thức TCP (TCP 3-way Handshake) chuẩn Nagios (ERRATA §E9.1)
 */
public class TcpChecker implements IProtocolChecker {

    @Override
    public CheckResult check(ServerTarget target) {
        long startTime = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.setTcpNoDelay(true);
            socket.setReuseAddress(true);

            InetSocketAddress address = new InetSocketAddress(target.getHost(), target.getPort());
            socket.connect(address, target.getTimeoutMs());

            long endTime = System.nanoTime();
            long latencyMs = (endTime - startTime) / 1_000_000;
            if (latencyMs == 0) latencyMs = 1;

            List<PerfData> perf = Collections.singletonList(
                    PerfData.of("rta", latencyMs, "ms", (double) target.getWarningThresholdMs(), (double) target.getTimeoutMs(), 0.0, null)
            );
            return CheckResult.ok(latencyMs, "TCP Handshake thành công cổng " + target.getPort(), "tcp", perf);
        } catch (SocketTimeoutException e) {
            return CheckResult.crit(target.getTimeoutMs(), 2, "TCP Timeout sau " + target.getTimeoutMs() + "ms", "tcp");
        } catch (ConnectException e) {
            return CheckResult.crit(0, 2, "Connection Refused (Cổng " + target.getPort() + " đóng)", "tcp");
        } catch (UnknownHostException e) {
            return CheckResult.unknown(0, 3, "Không phân giải được tên miền: " + target.getHost(), "tcp");
        } catch (NoRouteToHostException e) {
            return CheckResult.crit(0, 2, "Không có tuyến đường mạng (No route to host)", "tcp");
        } catch (IOException e) {
            return CheckResult.crit(0, 2, "Lỗi TCP I/O: " + e.getMessage(), "tcp");
        }
    }
}
