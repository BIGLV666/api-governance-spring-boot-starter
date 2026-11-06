package io.github.biglv666.apigovernance.ratelimit;

import io.github.biglv666.apigovernance.ratelimit.rules.DynamicRateRule;

/**
 * 单个 API 的限流策略解析结果（0.8.0 新增）。
 *
 * <p>把「动态规则 &gt; 方法注解 &gt; 类注解 &gt; 全局默认」的优先级解析从治理切面中
 * 抽离出来，切面与 {@code GET /rate-limiter/rules/effective} 管理接口共用同一份逻辑，
 * 避免"解释结果"与"实际执行"漂移。
 *
 * @author API Governance Team
 * @since 0.8.0
 */
public final class RateLimitPolicy {

    /** 生效值来源。 */
    public enum Source {
        /** 命中动态限流规则。 */
        DYNAMIC_RULE("dynamic-rule"),
        /** 方法级 {@code @RateLimit}。 */
        METHOD_ANNOTATION("method-annotation"),
        /** 类级 {@code @RateLimit}。 */
        CLASS_ANNOTATION("class-annotation"),
        /** 全局默认配置。 */
        GLOBAL_DEFAULT("global-default"),
        /** 未启用限流。 */
        NONE("none");

        private final String code;

        Source(String code) {
            this.code = code;
        }

        /** 管理接口展示用的稳定编码。 */
        public String getCode() {
            return code;
        }
    }

    private final int annotationLimit;
    private final int annotationWindow;
    private final boolean annotationEnabled;
    private final Source annotationSource;
    private final int effectiveLimit;
    private final int effectiveWindow;
    private final boolean enabled;
    private final Source effectiveSource;
    private final DynamicRateRule dynamicRule;
    private final String keyExpression;

    /**
     * 构造解析结果。
     *
     * @param annotationLimit    注解/全局默认解析后的限流阈值（动态规则之前）
     * @param annotationWindow   注解/全局默认解析后的窗口（动态规则之前）
     * @param annotationEnabled  注解/全局默认是否启用限流（动态规则之前）
     * @param annotationSource   注解/全局默认的值来源
     * @param effectiveLimit     最终生效阈值
     * @param effectiveWindow    最终生效窗口
     * @param enabled            最终是否启用限流
     * @param effectiveSource    最终生效值来源
     * @param dynamicRule        命中的动态规则（可为 null）
     * @param keyExpression      {@code @RateLimit(key = ...)} 原始表达式（可为空串）
     */
    public RateLimitPolicy(int annotationLimit, int annotationWindow,
                           boolean annotationEnabled, Source annotationSource,
                           int effectiveLimit, int effectiveWindow,
                           boolean enabled, Source effectiveSource,
                           DynamicRateRule dynamicRule, String keyExpression) {
        this.annotationLimit = annotationLimit;
        this.annotationWindow = annotationWindow;
        this.annotationEnabled = annotationEnabled;
        this.annotationSource = annotationSource;
        this.effectiveLimit = effectiveLimit;
        this.effectiveWindow = effectiveWindow;
        this.enabled = enabled;
        this.effectiveSource = effectiveSource;
        this.dynamicRule = dynamicRule;
        this.keyExpression = keyExpression == null ? "" : keyExpression;
    }

    /** 动态规则生效前的限流阈值（用于判断是否需要解析 SpEL 键）。 */
    public int getAnnotationLimit() {
        return annotationLimit;
    }

    /** 动态规则生效前的窗口。 */
    public int getAnnotationWindow() {
        return annotationWindow;
    }

    /** 动态规则生效前是否启用限流（沿用 0.7.0 的键解析语义）。 */
    public boolean isAnnotationEnabled() {
        return annotationEnabled;
    }

    /** 动态规则生效前的值来源。 */
    public Source getAnnotationSource() {
        return annotationSource;
    }

    /** 最终生效的限流阈值（-1 表示不限流）。 */
    public int getLimit() {
        return effectiveLimit;
    }

    /** 最终生效的窗口（秒）。 */
    public int getWindow() {
        return effectiveWindow;
    }

    /** 最终是否启用限流。 */
    public boolean isEnabled() {
        return enabled;
    }

    /** 最终生效值来源。 */
    public Source getSource() {
        return effectiveSource;
    }

    /** 命中的动态规则；未命中为 null。 */
    public DynamicRateRule getDynamicRule() {
        return dynamicRule;
    }

    /** {@code @RateLimit(key = ...)} 的原始 SpEL 表达式（未求值，可为空串）。 */
    public String getKeyExpression() {
        return keyExpression;
    }
}
