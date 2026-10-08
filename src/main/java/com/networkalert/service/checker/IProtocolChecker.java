package com.networkalert.service.checker;

import com.networkalert.model.CheckResult;
import com.networkalert.model.ServerTarget;

/**
 * Giao diện chuẩn cho tất cả các giao thức kiểm tra mạng
 */
public interface IProtocolChecker {
    /**
     * Thực hiện kiểm tra kết nối tới ServerTarget
     * @param target Mục tiêu cần kiểm tra
     * @return CheckResult Kết quả đo lường độ trễ và trạng thái
     */
    CheckResult check(ServerTarget target);
}
