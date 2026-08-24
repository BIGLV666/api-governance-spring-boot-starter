package io.github.biglv666.apigovernance.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 不记日志注解 —— 关闭某个「类」或「方法」的日志输出。
 *
 * <p>默认情况下所有被拦截请求的日志都是开启的（见 {@code api.governance.log.enabled}），
 * 本注解用于「局部关闭」日志输出，例如保护包含敏感信息的接口。
 *
 * <p>注意：{@code @NoLog} 仅关闭「日志输出」，<b>不影响</b>限流与内存指标统计。
 * 若希望完全跳过治理（不限流、不统计、不记日志），请改用 {@link Skip}。
 *
 * <h3>使用示例</h3>
 * <pre>
 * &#64;PostMapping("/login")
 * &#64;NoLog   // 登录接口可能包含密码等敏感信息，不输出日志
 * public Token login(&#64;RequestBody LoginRequest req) { ... }
 * </pre>
 *
 * @author API Governance Team
 * @since 1.0
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface NoLog {
}
