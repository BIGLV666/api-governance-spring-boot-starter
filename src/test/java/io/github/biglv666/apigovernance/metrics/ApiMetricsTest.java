package io.github.biglv666.apigovernance.metrics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 单 API 指标测试：验证平均耗时的统计口径。
 *
 * @author API Governance Team
 * @since 0.5.1
 */
class ApiMetricsTest {

    @Test
    void avgElapsedUsesExecutedRequestsAsDenominator() {
        // 回归：平均耗时曾以 totalRequests（含被拒绝请求）为分母，
        // 拒绝请求耗时为 0，会稀释平均值误导观测
        ApiMetrics metrics = new ApiMetrics("com.x.A#get", 10, 300_000L);
        metrics.recordStart();
        metrics.recordResult(100, true, false, "GET", "/a", null);
        metrics.recordStart();
        metrics.recordResult(200, false, false, "GET", "/a", "err");
        // 一次拒绝：计入 totalRequests 但不产生耗时
        metrics.recordStart();
        metrics.recordReject("GET", "/a", "too many");

        assertThat(metrics.getTotalRequests()).isEqualTo(3);
        assertThat(metrics.getRejectRequests()).isEqualTo(1);
        // 分母 = success(1) + fail(1) = 2，avg = (100+200)/2 = 150
        assertThat(metrics.getAvgElapsedMs()).isCloseTo(150.0, within(0.001));
    }

    @Test
    void avgElapsedIsZeroWhenOnlyRejectedRequests() {
        ApiMetrics metrics = new ApiMetrics("com.x.A#get", 10, 300_000L);
        metrics.recordStart();
        metrics.recordReject("GET", "/a", "too many");

        assertThat(metrics.getAvgElapsedMs()).isEqualTo(0.0);
    }
}
