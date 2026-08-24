package io.github.biglv666.apigovernance.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 限流注解 —— 在「类」或「方法」上声明限流阈值。
 *
 * <p>本 Starter 默认拦截所有 Controller 请求，无需任何注解即可生效（受全局默认配置约束）。
 * 此注解仅用于「覆盖」某个类或方法的限流阈值，因此注解数量被刻意压缩到最少。
 *
 * <h3>使用示例</h3>
 * <pre>
 * &#64;RestController
 * &#64;RateLimit(limit = 100)          // 类级别：该 Controller 下所有方法默认 100 次/窗口
 * public class UserController {
 *
 *     &#64;GetMapping("/users")
 *     public List&lt;User&gt; list() { ... }      // 继承类级别：100 次/窗口
 *
 *     &#64;PostMapping("/users")
 *     &#64;RateLimit(limit = 5, window = 60)    // 方法级别覆盖：60 秒内最多 5 次
 *     public User create(&#64;RequestBody User u) { ... }
 * }
 * </pre>
 *
 * <h3>优先级规则</h3>
 * <p>方法级别 &gt; 类级别 &gt; 全局默认配置（yml / Bean）。
 *
 * <h3>取值约定</h3>
 * <ul>
 *   <li>{@code limit = -1}：使用全局默认限流阈值</li>
 *   <li>{@code limit = 0}：禁止访问（直接拒绝）</li>
 *   <li>{@code limit &gt; 0}：窗口内允许的最大请求数</li>
 *   <li>{@code window = -1}：使用全局默认时间窗口（秒）</li>
 * </ul>
 *
 * @author API Governance Team
 * @since 1.0
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface RateLimit {

    /**
     * 限流阈值：时间窗口内允许的最大请求次数。
     *
     * <p>{@code -1} 表示沿用全局默认配置（见 {@code api.governance.rate-limit.default-limit}）。
     *
     * @return 窗口内最大请求数
     */
    int limit() default -1;

    /**
     * 时间窗口大小（单位：秒）。
     *
     * <p>{@code -1} 表示沿用全局默认配置（见 {@code api.governance.rate-limit.default-window}）。
     *
     * @return 时间窗口（秒）
     */
    int window() default -1;
}
