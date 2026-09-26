package io.github.biglv666.apigovernance.filter.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.biglv666.apigovernance.config.ApiGovernanceProperties;
import io.github.biglv666.apigovernance.filter.FilterContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 日志过滤器测试：验证被拒绝请求的日志口径（warn 级别 + 真实拒绝原因）。
 *
 * <p>回归：拒绝请求曾与业务失败共用 error 日志，且打印的是框架异常的常量 message
 * "REJECTED"，真实拒绝原因丢失。
 *
 * @author API Governance Team
 * @since 0.5.1
 */
class LoggingFilterTest {

    private ListAppender<ILoggingEvent> appender;

    private Logger logger;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(LoggingFilter.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    @Test
    void rejectedRequestLogsWarnWithStatusAndReason() {
        FilterContext context = new FilterContext(null, "com.x.A#get", null, Object.class, new Object[0]);
        context.setRejected(true);
        context.setRejectStatus(429);
        context.setRejectReason("请求过于频繁，请稍后重试");

        new LoggingFilter(new ApiGovernanceProperties()).doFilter(context);

        List<ILoggingEvent> events = appender.list;
        assertThat(events).hasSize(1);
        ILoggingEvent event = events.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage())
                .contains("已拒绝")
                .contains("429")
                .contains("请求过于频繁，请稍后重试")
                .doesNotContain("REJECTED");
    }

    @Test
    void businessFailureStillLogsError() {
        FilterContext context = new FilterContext(null, "com.x.A#get", null, Object.class, new Object[0]);
        context.setError(new IllegalStateException("业务异常"));

        new LoggingFilter(new ApiGovernanceProperties()).doFilter(context);

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.ERROR);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("业务异常");
    }
}
