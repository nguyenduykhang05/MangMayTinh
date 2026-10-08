package com.networkalert.service.checker;

import com.networkalert.model.CheckResult;
import com.networkalert.model.PerfData;
import com.networkalert.model.ServerTarget;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.PortUnreachableException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * Kiểm tra kết nối tầng Vận chuyển qua giao thức UDP chuẩn Nagios (ERRATA §E9.1)
 */
public class UdpChecker implements IProtocolChecker {

    @Override
    public CheckResult check(ServerTarget target) {
        long startTime = System.nanoTime();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(target.getTimeoutMs());

            InetAddress address = InetAddress.getByName(target.getHost());
            byte[] sendData = ("PING_UDP_" + System.nanoTime()).getBytes(StandardCharsets.UTF_8);

            DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, address, target.getPort());
            socket.send(sendPacket);

            byte[] receiveBuffer = new byte[1024];
            DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);

            try {
                socket.receive(receivePacket);
                long endTime = System.nanoTime();
                long latencyMs = (endTime - startTime) / 1_000_000;
                if (latencyMs == 0) latencyMs = 1;

                List<PerfData> perf = Collections.singletonList(
                        PerfData.of("rta", latencyMs, "ms", (double) target.getWarningThresholdMs(), (double) target.getTimeoutMs(), 0.0, null)
                );
                return CheckResult.ok(latencyMs, "UDP Echo phản hồi thành công (" + receivePacket.getLength() + " bytes)", "udp", perf);
            } catch (SocketTimeoutException te) {
                return CheckResult.crit(target.getTimeoutMs(), 2, "UDP Timeout (Không nhận được gói tin phản hồi sau " + target.getTimeoutMs() + "ms)", "udp");
            }
        } catch (PortUnreachableException pue) {
            return CheckResult.crit(0, 2, "ICMP Port Unreachable (Cổng UDP " + target.getPort() + " đóng)", "udp");
        } catch (UnknownHostException e) {
            return CheckResult.unknown(0, 3, "Không xác định được máy chủ UDP: " + target.getHost(), "udp");
        } catch (IOException e) {
            return CheckResult.crit(0, 2, "Lỗi UDP Datagram: " + e.getMessage(), "udp");
        }
    }
}
