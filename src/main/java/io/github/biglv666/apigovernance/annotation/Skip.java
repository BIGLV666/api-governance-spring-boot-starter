package io.github.biglv666.apigovernance.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 放行注解 —— 让某个「类」或「方法」完全跳过治理管道。
 *
 * <p>被标注的目标将<b>不</b>执行限流、不统计指标、不记录日志，等价于切面直接放行
 * （{@code joinPoint.proceed()}）。
 *
 * <h3>典型场景</h3>
 * <ul>
 *   <li>健康检查、探活等无需治理的内部端点</li>
 *   <li>已经通过其它方式（网关/安全组件）治理的接口</li>
 *   <li>临时紧急放行某个故障接口</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>
 * &#64;RestController
 * public class HealthController {
 *
 *     &#64;GetMapping("/health")
 *     &#64;Skip   // 该接口不参与任何治理
 *     public String health() { return "UP"; }
 * }
 * </pre>
 *
 * @author API Governance Team
 * @since 1.0
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface Skip {

    /**
     * 放行原因（可选），仅用于文档说明，不参与运行逻辑。
     *
     * @return 放行原因描述
     */
    String reason() default "";
}
