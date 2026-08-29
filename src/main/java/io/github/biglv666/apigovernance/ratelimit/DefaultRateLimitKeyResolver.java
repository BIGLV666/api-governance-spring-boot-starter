package io.github.biglv666.apigovernance.ratelimit;

import io.github.biglv666.apigovernance.filter.FilterContext;

/**
 * 默认限流键解析器 —— 按「接口（方法）维度」限流，并支持注解声明的参数维度后缀。
 *
 * <p>限流键 = 全限定类名#方法名（即 {@link FilterContext#getApiKey()}），
 * 同一 Controller 方法的所有请求共享同一份限流配额，无论由哪个用户发起。
 *
 * <p>当切面已通过 {@code @RateLimit(key = "...")} 的 SpEL 表达式解析出参数维度后缀
 * （{@link FilterContext#getRateLimitKeySuffix()}）时，键升级为
 * {@code 全限定类名#方法名:后缀}，不同参数值各自拥有独立配额。
 *
 * <p>如需更复杂的颗粒度（请求头、安全上下文等），实现 {@link RateLimitKeyResolver}
 * 并注册为 Bean 覆盖本实现即可；自定义实现可按需决定是否使用后缀。
 *
 * @author API Governance Team
 * @since 0.2.0
 */
public class DefaultRateLimitKeyResolver implements RateLimitKeyResolver {

    @Override
    public String resolve(FilterContext context) {
        String suffix = context.getRateLimitKeySuffix();
        return suffix == null ? context.getApiKey() : context.getApiKey() + ":" + suffix;
    }
}
