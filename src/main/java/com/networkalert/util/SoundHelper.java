package com.networkalert.util;

import java.awt.Toolkit;

/**
 * Tiện ích phát âm thanh cảnh báo khi có sự cố mạng
 */
public class SoundHelper {

    public static void playAlertBeep() {
        new Thread(() -> {
            try {
                // Tiếng beep cảnh báo sự cố (2 tiếng liên tiếp)
                Toolkit.getDefaultToolkit().beep();
                Thread.sleep(150);
                Toolkit.getDefaultToolkit().beep();
            } catch (Exception ignored) {
            }
        }, "SoundAlertThread").start();
    }

    public static void playRecoveryBeep() {
        new Thread(() -> {
            try {
                // Tiếng beep nhẹ báo hiệu khôi phục kết nối (1 tiếng)
                Toolkit.getDefaultToolkit().beep();
            } catch (Exception ignored) {
            }
        }, "SoundRecoveryThread").start();
    }

    public static void playWarningBeep() {
        new Thread(() -> {
            try {
                // Tiếng beep cảnh báo độ trễ cao
                Toolkit.getDefaultToolkit().beep();
            } catch (Exception ignored) {
            }
        }, "SoundWarningThread").start();
    }
}
