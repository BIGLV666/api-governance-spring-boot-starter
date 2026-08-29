package io.github.biglv666.apigovernance.ratelimit;

import io.github.biglv666.apigovernance.filter.FilterContext;

/**
 * 限流拒绝处理器插件 —— 决定「请求被限流后如何响应」。
 *
 * <p>默认实现按 {@code api.governance.rate-limit.status-code} / {@code message}
 * 写入拒绝状态码与原因，随后由 {@code GovernanceExceptionHandler} 渲染为统一 JSON 响应。
 * 注册自定义 Bean 即可覆盖默认行为，实现诸如：
 * <ul>
 *   <li>动态状态码（如按租户返回不同码）；</li>
 *   <li>向 {@code FilterContext} 写入扩展属性（attributes），供下游过滤器或异常处理器使用；</li>
 *   <li>直接向 {@code ServletResponse} 写出完全自定义的响应体（通过
 *       {@code RequestContextHolder} 获取 response），并返回后仍走统一短路逻辑。</li>
 * </ul>
 *
 * <h3>契约</h3>
 * <ul>
 *   <li>处理器返回后，治理切面<b>必定</b>抛出 {@code GovernanceException} 短路请求，
 *       业务方法不会执行；</li>
 *   <li>拒绝指标（含 {@code MetricsEventListener.onReject} 事件）由过滤器在调用处理器之后
 *       统一记录，处理器无需（也不应）重复记录；</li>
 *   <li>处理器抛出的异常会被过滤器捕获并按原拒绝逻辑兜底，不影响管道稳定性。</li>
 * </ul>
 *
 * <h3>使用方式</h3>
 * <pre>
 * &#64;Bean
 * public RateLimitRejectHandler myRejectHandler() {
 *     return (context, rateLimitKey) -> {
 *         context.setRejectStatus(429);
 *         context.setRejectReason("每秒最多 " + context.getRateLimit() + " 次");
 *         context.setAttribute("retryAfterSeconds", context.getWindow());
 *     };
 * }
 * </pre>
 *
 * @author API Governance Team
 * @since 0.2.0
 */
@FunctionalInterface
public interface RateLimitRejectHandler {

    /**
     * 处理一次限流拒绝。
     *
     * @param context      过滤器上下文（可修改拒绝状态码、原因、扩展属性）
     * @param rateLimitKey 本次请求的限流键（由 {@link RateLimitKeyResolver} 解析）
     */
    void handleReject(FilterContext context, String rateLimitKey);
}
