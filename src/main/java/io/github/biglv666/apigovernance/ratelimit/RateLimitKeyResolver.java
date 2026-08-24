package io.github.biglv666.apigovernance.ratelimit;

import io.github.biglv666.apigovernance.filter.FilterContext;

/**
 * 限流键解析器插件接口 —— 决定「以什么维度」进行限流。
 *
 * <p>这是限流「颗粒度」的扩展点。内置默认实现 {@link DefaultRateLimitKeyResolver}
 * 按<b>接口（方法）维度</b>限流（键 = 全限定类名#方法名）。通过注册自定义实现，
 * 可以切换到<b>用户维度</b>、<b>IP 维度</b>、<b>接口+用户维度</b>等任意颗粒度。
 *
 * <h3>用户颗粒度示例</h3>
 * <pre>
 * &#64;Bean
 * public RateLimitKeyResolver userRateLimitKeyResolver() {
 *     return context -&gt; {
 *         String userId = getCurrentUserId();          // 从 SecurityContext / Header / ThreadLocal 获取
 *         return context.getApiKey() + "#user:" + userId; // 接口 + 用户维度
 *     };
 * }
 * </pre>
 *
 * <p>说明：自定义实现注册为 {@code @Bean} 后自动覆盖默认实现（{@code @ConditionalOnMissingBean}）。
 * 返回的 key 会原样传给限流器（本机或 Redis），因此本地/Redis 均适用。
 *
 * @author API Governance Team
 * @since 1.0
 */
@FunctionalInterface
public interface RateLimitKeyResolver {

    /**
     * 根据请求上下文解析出本次限流使用的「限流键」。
     *
     * @param context 过滤器上下文（含 apiKey、方法、入参、扩展属性等）
     * @return 限流键；相同键共享同一限流配额
     */
    String resolve(FilterContext context);
}
