package io.github.biglv666.apigovernance.concurrency;

import io.github.biglv666.apigovernance.annotation.ConcurrencyLimit;
import io.github.biglv666.apigovernance.config.ApiGovernanceProperties;
import io.github.biglv666.apigovernance.ratelimit.rules.DynamicRateRule;
import io.github.biglv666.apigovernance.ratelimit.rules.RateRuleStore;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.lang.reflect.Method;

/**
 * 并发策略解析器（1.0.0 新增）。
 *
 * <p>切面与 {@code GET /rate-limiter/rules/effective} 共用本解析器，保证
 * 「解释出来的并发上限」与「实际执行的并发上限」一致。
 *
 * <p>解析顺序：动态规则 {@code maxConcurrent} &gt; 方法注解 &gt; 类注解 &gt; 全局默认。
 * 动态规则取值语义：{@code >0} 覆盖；{@code <0} 关闭；{@code 0} 不干预。
 *
 * @author API Governance Team
 * @since 1.0.0
 */
public final class ConcurrencyPolicyResolver {

    private ConcurrencyPolicyResolver() {
    }

    /**
     * 解析指定 Controller 方法的并发策略。
     *
     * @param targetClass 目标 Controller 类（可为 null）
     * @param method      目标方法（可为 null：仅解析类级注解）
     * @param apiKey      API 标识（用于动态规则匹配）
     * @param properties  全局配置
     * @param store       动态规则存储（可为 null）
     * @return 解析结果
     */
    public static ConcurrencyPolicy resolve(Class<?> targetClass, Method method, String apiKey,
                                            ApiGovernanceProperties properties,
                                            RateRuleStore store) {
        DynamicRateRule rule = store == null || apiKey == null
                ? null : store.findMatch(apiKey).orElse(null);
        return resolve(targetClass, method, properties, rule);
    }

    /**
     * 解析并发策略（复用调用方已查到的动态规则，避免请求热路径重复访问存储）。
     *
     * @param targetClass 目标 Controller 类（可为 null）
     * @param method      目标方法（可为 null）
     * @param properties  全局配置
     * @param rule        已命中的动态规则（可为 null）
     * @return 解析结果
     */
    public static ConcurrencyPolicy resolve(Class<?> targetClass, Method method,
                                            ApiGovernanceProperties properties,
                                            DynamicRateRule rule) {
        ApiGovernanceProperties.Concurrency config = properties.getConcurrencyLimit();
        if (!config.isEnabled()) {
            return new ConcurrencyPolicy(-1, -1, false, ConcurrencyPolicy.Source.NONE, null);
        }

        ConcurrencyLimit methodAnn = method == null ? null
                : AnnotatedElementUtils.findMergedAnnotation(method, ConcurrencyLimit.class);
        ConcurrencyLimit classAnn = targetClass == null ? null
                : AnnotatedElementUtils.findMergedAnnotation(targetClass, ConcurrencyLimit.class);

        int raw = methodAnn != null ? methodAnn.permits()
                : (classAnn != null ? classAnn.permits() : -1);
        ConcurrencyPolicy.Source annotationSource = methodAnn != null
                ? ConcurrencyPolicy.Source.METHOD_ANNOTATION
                : (classAnn != null ? ConcurrencyPolicy.Source.CLASS_ANNOTATION
                : ConcurrencyPolicy.Source.NONE);

        int annotationPermits;
        ConcurrencyPolicy.Source source;
        if (raw > 0) {
            annotationPermits = raw;
            source = annotationSource;
        } else if (raw == 0) {
            // 显式关闭：覆盖全局默认
            annotationPermits = 0;
            source = ConcurrencyPolicy.Source.NONE;
        } else {
            annotationPermits = config.getDefaultPermits();
            source = annotationPermits > 0
                    ? ConcurrencyPolicy.Source.GLOBAL_DEFAULT : ConcurrencyPolicy.Source.NONE;
        }

        int effective = annotationPermits;
        if (rule != null && rule.getMaxConcurrent() != 0) {
            effective = rule.getMaxConcurrent() > 0 ? rule.getMaxConcurrent() : -1;
            source = ConcurrencyPolicy.Source.DYNAMIC_RULE;
        }

        return new ConcurrencyPolicy(annotationPermits, effective, effective > 0, source, rule);
    }
}
