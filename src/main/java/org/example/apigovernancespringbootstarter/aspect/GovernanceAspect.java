package org.example.apigovernancespringbootstarter.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.example.apigovernancespringbootstarter.annotation.NoLog;
import org.example.apigovernancespringbootstarter.annotation.RateLimit;
import org.example.apigovernancespringbootstarter.annotation.Skip;
import org.example.apigovernancespringbootstarter.config.ApiGovernanceProperties;
import org.example.apigovernancespringbootstarter.exception.GovernanceException;
import org.example.apigovernancespringbootstarter.filter.FilterChain;
import org.example.apigovernancespringbootstarter.filter.FilterContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;

/**
 * Controller 治理主切面 —— 整条 Controller 过滤器管道只有这一个
 * {@code @Around} 通知。
 *
 * <h3>切点</h3>
 * <p>默认拦截所有 {@code @RestController} / {@code @Controller} 类中的请求映射方法，
 * <b>无需任何自定义注解即可生效</b>。注解 {@link RateLimit} / {@link Skip} / {@link NoLog}
 * 仅用于「覆盖」默认行为，因此注解数量被压缩到最少。
 *
 * <h3>执行流程</h3>
 * <pre>
 *   请求进入
 *     │
 *     ├─ 有 @Skip？ ──是──▶ 直接 proceed()（完全放行）
 *     ├─ 全局未启用？ ──是──▶ 直接 proceed()
 *     ├─ 非请求映射方法？ ──是──▶ 直接 proceed()
 *     │
 *     ▼ 构建上下文 + 解析限流/日志配置
 *   前置过滤器链（信息采集 → 流量统计 → 限流判断 → 自定义...）
 *     │  任一返回 false → 短路，抛拒绝异常
 *     ▼
 *   pjp.proceed()（只调用一次，执行业务逻辑）
 *     │
 *     ▼
 *   后置过滤器链（记录耗时/更新统计 → 日志记录 → 自定义...）  ← finally 保证执行
 * </pre>
 *
 * @author API Governance Team
 * @since 1.0
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class GovernanceAspect {

    private static final Logger log = LoggerFactory.getLogger(GovernanceAspect.class);

    /** 过滤器链管理器。 */
    private final FilterChain filterChain;

    /** 全局配置。 */
    private final ApiGovernanceProperties properties;

    /**
     * 构造切面。
     *
     * @param filterChain 过滤器链
     * @param properties  全局配置
     */
    public GovernanceAspect(FilterChain filterChain, ApiGovernanceProperties properties) {
        this.filterChain = filterChain;
        this.properties = properties;
    }

    /**
     * 切点：所有 {@code @RestController} / {@code @Controller} 类的方法。
     */
    @Pointcut("within(@org.springframework.web.bind.annotation.RestController *) || " +
              "within(@org.springframework.stereotype.Controller *)")
    public void controllerPointcut() {
        // 仅作为切点声明，无逻辑
    }

    /**
     * 环绕通知：执行治理管道。
     *
     * @param joinPoint AOP 连接点
     * @return 业务方法返回值
     * @throws Throwable 业务异常或治理拒绝异常
     */
    @Around("controllerPointcut()")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Class<?> targetClass = AopUtils.getTargetClass(joinPoint.getTarget());

        // 1. 放行注解：类或方法标注 @Skip 时，完全跳过治理
        if (isSkipped(targetClass, method)) {
            return joinPoint.proceed();
        }
        // 2. 全局开关关闭
        if (!properties.isEnabled()) {
            return joinPoint.proceed();
        }
        // 3. 非请求映射方法（控制器内的公开辅助方法）不参与治理
        if (!isMappedMethod(method)) {
            return joinPoint.proceed();
        }

        // 4. 构建上下文并解析配置
        String apiKey = targetClass.getName() + "#" + method.getName();
        FilterContext context = new FilterContext(joinPoint, apiKey, method, targetClass,
                joinPoint.getArgs());
        resolveRateLimit(targetClass, method, context);
        resolveLogEnabled(targetClass, method, context);

        Object result = null;
        Throwable error = null;
        boolean rejected = false;
        try {
            // 5. 前置过滤器链：任一 false 即短路
            if (!filterChain.executePreFilters(context)) {
                rejected = true;
                throw new GovernanceException(
                        context.getRejectStatus(),
                        "REJECTED",
                        context.getRejectReason() != null ? context.getRejectReason() : "请求被拒绝");
            }
            // 6. 执行业务逻辑（只调用一次）
            return result = joinPoint.proceed();
        } catch (Throwable t) {
            error = t;
            throw t;
        } finally {
            // 7. 后置过滤器链：无论成功/失败/拒绝都执行
            context.setRejected(rejected);
            context.setResult(result);
            context.setError(error);
            filterChain.executePostFilters(context);
        }
    }

    /**
     * 判断类或方法是否标注了 {@link Skip}（放行）。
     */
    private boolean isSkipped(Class<?> targetClass, Method method) {
        return AnnotatedElementUtils.findMergedAnnotation(targetClass, Skip.class) != null
                || AnnotatedElementUtils.findMergedAnnotation(method, Skip.class) != null;
    }

    /**
     * 判断方法是否为请求映射方法（含 {@code @GetMapping} 等组合注解）。
     */
    private boolean isMappedMethod(Method method) {
        return AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class) != null;
    }

    /**
     * 解析限流配置并写入上下文。
     *
     * <p>优先级：方法 {@link RateLimit} &gt; 类 {@link RateLimit} &gt; 全局默认配置。
     * 注解中 {@code -1} 表示回退到下一级默认值。
     */
    private void resolveRateLimit(Class<?> targetClass, Method method, FilterContext context) {
        RateLimit methodAnn = AnnotatedElementUtils.findMergedAnnotation(method, RateLimit.class);
        RateLimit classAnn = AnnotatedElementUtils.findMergedAnnotation(targetClass, RateLimit.class);

        int limit;
        int window;
        if (methodAnn != null) {
            limit = methodAnn.limit();
            window = methodAnn.window();
        } else if (classAnn != null) {
            limit = classAnn.limit();
            window = classAnn.window();
        } else {
            limit = -1;
            window = -1;
        }

        // -1 回退全局默认
        if (limit < 0) {
            limit = properties.getRateLimit().getDefaultLimit();
        }
        if (window <= 0) {
            window = properties.getRateLimit().getDefaultWindow();
        }
        if (window <= 0) {
            window = 1;
        }

        // limit >= 0 表示启用限流（0 = 禁止访问，>0 = 正常限流）
        if (limit >= 0) {
            context.setRateLimitEnabled(true);
            context.setRateLimit(limit);
            context.setWindow(window);
            if (log.isDebugEnabled()) {
                log.debug("启用限流 - API: {}, limit: {}/{}(秒)", context.getApiKey(), limit, window);
            }
        }
    }

    /**
     * 解析日志开关：全局开关 && 未标注 {@link NoLog}。
     */
    private void resolveLogEnabled(Class<?> targetClass, Method method, FilterContext context) {
        boolean globalEnabled = properties.getLog().isEnabled();
        boolean noLog = AnnotatedElementUtils.findMergedAnnotation(targetClass, NoLog.class) != null
                || AnnotatedElementUtils.findMergedAnnotation(method, NoLog.class) != null;
        context.setLogEnabled(globalEnabled && !noLog);
    }
}
