package io.github.biglv666.apigovernance.filter.impl;

import io.github.biglv666.apigovernance.config.ApiGovernanceProperties;
import io.github.biglv666.apigovernance.filter.FilterContext;
import io.github.biglv666.apigovernance.filter.PostFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * 日志记录过滤器（后置，order = 500）。
 *
 * <p>负责输出「响应情况」日志：成功/失败/被拒绝、耗时、异常信息等。默认对所有被拦截请求开启，
 * 可通过 {@code @NoLog} 注解或 {@code api.governance.log.enabled=false} 关闭。
 * 前置过滤器短路拒绝的请求单独以 warn 级别记录状态码与拒绝原因，不与业务失败混淆。
 *
 * <p>入参与响应体的输出默认关闭（避免敏感信息与超大日志），可分别通过
 * {@code api.governance.log.log-request-params} 与 {@code api.governance.log.log-response} 开启。
 *
 * @author API Governance Team
 * @since 1.0
 */
public class LoggingFilter implements PostFilter {

    private static final Logger log = LoggerFactory.getLogger(LoggingFilter.class);

    /** 入参/响应体日志的最大长度，防止超大对象撑爆日志。 */
    private static final int MAX_DUMP_LENGTH = 512;

    private final ApiGovernanceProperties properties;

    public LoggingFilter(ApiGovernanceProperties properties) {
        this.properties = properties;
    }

    @Override
    public void doFilter(FilterContext context) {
        if (!context.isLogEnabled()) {
            return;
        }

        long elapsed = context.getElapsedTime();
        String apiKey = context.getApiKey();
        String httpMethod = context.getHttpMethod();
        String path = context.getPath();

        if (context.isRejected()) {
            // 前置短路拒绝：独立于业务失败，warn 级别并输出真实拒绝原因与状态码
            // （此时 error 为框架 GovernanceException，message 是常量 "REJECTED"，不含原因）
            log.warn("[API] {} {} - {} - 已拒绝 - 状态: {} - 原因: {} - 耗时: {}ms",
                    httpMethod, path, apiKey, context.getRejectStatus(),
                    context.getRejectReason(), elapsed);
            return;
        }

        if (context.getError() == null) {
            log.info("[API] {} {} - {} - 成功 - 耗时: {}ms",
                    httpMethod, path, apiKey, elapsed);
            if (properties.getLog().isLogRequestParams()) {
                log.info("[API] {} 入参: {}", apiKey, truncate(Arrays.toString(context.getArgs())));
            }
            if (properties.getLog().isLogResponse()) {
                log.info("[API] {} 响应: {}", apiKey, truncate(String.valueOf(context.getResult())));
            }
        } else {
            log.error("[API] {} {} - {} - 失败 - 耗时: {}ms - 错误: {}",
                    httpMethod, path, apiKey, elapsed, context.getError().getMessage());
        }
    }

    /**
     * 截断过长的字符串，避免日志膨胀。
     */
    private String truncate(String text) {
        if (text == null) {
            return "null";
        }
        if (text.length() <= MAX_DUMP_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_DUMP_LENGTH) + "...(截断)";
    }

    @Override
    public int getOrder() {
        return 500;
    }
}
