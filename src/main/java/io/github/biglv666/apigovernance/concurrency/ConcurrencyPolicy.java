package io.github.biglv666.apigovernance.concurrency;

import io.github.biglv666.apigovernance.ratelimit.rules.DynamicRateRule;

/**
 * 单个 API 的并发策略解析结果（1.0.0 新增）。
 *
 * <p>优先级：动态规则 {@code maxConcurrent} &gt; 方法 {@code @ConcurrencyLimit}
 * &gt; 类 {@code @ConcurrencyLimit} &gt; 全局默认。
 *
 * @author API Governance Team
 * @since 1.0.0
 */
public final class ConcurrencyPolicy {

    /** 生效值来源。 */
    public enum Source {
        /** 命中动态限流规则。 */
        DYNAMIC_RULE("dynamic-rule"),
        /** 方法级 {@code @ConcurrencyLimit}。 */
        METHOD_ANNOTATION("method-annotation"),
        /** 类级 {@code @ConcurrencyLimit}。 */
        CLASS_ANNOTATION("class-annotation"),
        /** 全局默认配置。 */
        GLOBAL_DEFAULT("global-default"),
        /** 未启用并发限制。 */
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

    private final int annotationPermits;

    private final int effectivePermits;

    private final boolean enabled;

    private final Source source;

    private final DynamicRateRule dynamicRule;

    /**
     * 构造解析结果。
     *
     * @param annotationPermits 动态规则之前的并发上限（-1/0 表示未启用）
     * @param effectivePermits  最终生效并发上限（-1 表示未启用）
     * @param enabled           最终是否启用
     * @param source            最终值来源
     * @param dynamicRule       命中的动态规则（可为 null）
     */
    public ConcurrencyPolicy(int annotationPermits, int effectivePermits, boolean enabled,
                             Source source, DynamicRateRule dynamicRule) {
        this.annotationPermits = annotationPermits;
        this.effectivePermits = effectivePermits;
        this.enabled = enabled;
        this.source = source;
        this.dynamicRule = dynamicRule;
    }

    /** 动态规则之前的并发上限（-1/0 表示未启用）。 */
    public int getAnnotationPermits() {
        return annotationPermits;
    }

    /** 最终生效的并发上限（-1 表示未启用）。 */
    public int getPermits() {
        return effectivePermits;
    }

    /** 最终是否启用并发限制。 */
    public boolean isEnabled() {
        return enabled;
    }

    /** 最终生效值来源。 */
    public Source getSource() {
        return source;
    }

    /** 命中的动态规则；未命中为 null。 */
    public DynamicRateRule getDynamicRule() {
        return dynamicRule;
    }
}
