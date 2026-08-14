package org.example.apigovernancespringbootstarter.ratelimit;

import org.example.apigovernancespringbootstarter.filter.FilterContext;

/**
 * 默认限流键解析器 —— 按「接口（方法）维度」限流。
 *
 * <p>限流键 = 全限定类名#方法名（即 {@link FilterContext#getApiKey()}），
 * 同一 Controller 方法的所有请求共享同一份限流配额，无论由哪个用户发起。
 *
 * <p>如需其它颗粒度，实现 {@link RateLimitKeyResolver} 并注册为 Bean 覆盖本实现即可。
 *
 * @author API Governance Team
 * @since 1.0
 */
public class DefaultRateLimitKeyResolver implements RateLimitKeyResolver {

    @Override
    public String resolve(FilterContext context) {
        return context.getApiKey();
    }
}
