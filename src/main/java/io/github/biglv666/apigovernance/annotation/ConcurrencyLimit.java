package io.github.biglv666.apigovernance.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 并发上限注解 —— 限制同一接口<b>同时在途</b>的请求数量（bulkhead）。
 *
 * <p>限流控制的是「单位时间请求数」，挡不住慢下游把线程池占满：一个接口限流
 * 100 次/秒，当响应从 10ms 变成 2s 时，仍可能有 100 个请求同时占用线程。
 * 本注解限制的是并发数，超出的请求会被<b>立即拒绝</b>（不排队、不占线程）。
 *
 * <h3>使用示例</h3>
 * <pre>
 * &#64;RestController
 * public class OrderController {
 *
 *     &#64;PostMapping("/orders")
 *     &#64;ConcurrencyLimit(permits = 10)   // 最多 10 个请求同时在途
 *     public Order create(&#64;RequestBody Order order) { ... }
 * }
 * </pre>
 *
 * <h3>取值约定</h3>
 * <ul>
 *   <li>{@code permits > 0}：该接口的并发上限；</li>
 *   <li>{@code permits = 0}：显式关闭并发限制（覆盖全局默认）；</li>
 *   <li>{@code permits = -1}（默认）：沿用全局 {@code concurrency-limit.default-permits}，
 *       未配置全局默认时不限制。</li>
 * </ul>
 *
 * <p>优先级：动态规则 {@code maxConcurrent} &gt; 方法注解 &gt; 类注解 &gt; 全局默认。
 * 动态规则 {@code maxConcurrent = -1} 可临时关闭某接口的并发限制。
 *
 * @author API Governance Team
 * @since 1.0.0
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface ConcurrencyLimit {

    /**
     * 并发上限（同时在途请求数）。
     *
     * @return 并发上限；0 = 关闭，-1 = 沿用全局默认
     */
    int permits() default -1;
}
