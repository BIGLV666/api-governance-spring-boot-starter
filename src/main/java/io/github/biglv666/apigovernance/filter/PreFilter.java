package io.github.biglv666.apigovernance.filter;

/**
 * 前置过滤器接口 —— 在业务方法执行<b>之前</b>运行。
 *
 * <h3>短路机制</h3>
 * <p>任何前置过滤器返回 {@code false} 都会：
 * <ol>
 *   <li>立即停止后续前置过滤器；</li>
 *   <li>跳过业务方法（{@code pjp.proceed()} 不会被调用）；</li>
 *   <li>由切面抛出拒绝异常，返回标准拒绝响应。</li>
 * </ol>
 * 拒绝时请通过 {@link FilterContext#setRejectReason(String)} 设置原因。
 *
 * <h3>实现示例</h3>
 * <pre>
 * &#64;Component
 * &#64;Order(300)
 * public class ParamCheckFilter implements PreFilter {
 *     public boolean doFilter(FilterContext ctx) {
 *         if (!valid(ctx.getArgs())) {
 *             ctx.setRejectStatus(400);
 *             ctx.setRejectReason("参数校验失败");
 *             return false; // 短路
 *         }
 *         return true;
 *     }
 * }
 * </pre>
 *
 * @author API Governance Team
 * @since 1.0
 */
public interface PreFilter extends Filter {

    /**
     * 执行前置过滤。
     *
     * @param context 过滤器上下文
     * @return true 继续执行；false 拒绝并短路
     */
    boolean doFilter(FilterContext context);
}
