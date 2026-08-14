package org.example.apigovernancespringbootstarter.filter;

/**
 * 后置过滤器接口 —— 在业务方法执行<b>之后</b>运行。
 *
 * <p>无论业务方法正常返回还是抛出异常，后置过滤器都会在 {@code finally} 中执行
 * （前置过滤器短路导致未进入业务方法时除外）。
 *
 * <h3>数据来源</h3>
 * <p>后置过滤器从 {@link FilterContext} 中读取结果与异常：
 * <ul>
 *   <li>{@link FilterContext#getResult()}：业务返回值（成功时）</li>
 *   <li>{@link FilterContext#getError()}：业务异常（失败时）</li>
 *   <li>{@link FilterContext#getElapsedTime()}：执行耗时（毫秒）</li>
 * </ul>
 *
 * <h3>实现示例</h3>
 * <pre>
 * &#64;Component
 * &#64;Order(500)
 * public class AuditFilter implements PostFilter {
 *     public void doFilter(FilterContext ctx) {
 *         long ms = ctx.getElapsedTime();
 *         boolean ok = ctx.getError() == null;
 *         // ... 审计记录
 *     }
 * }
 * </pre>
 *
 * @author API Governance Team
 * @since 1.0
 */
public interface PostFilter extends Filter {

    /**
     * 执行后置过滤。
     *
     * @param context 过滤器上下文（含结果、异常、耗时）
     */
    void doFilter(FilterContext context);
}
