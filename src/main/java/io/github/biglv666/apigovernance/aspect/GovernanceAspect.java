package io.github.biglv666.apigovernance.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import io.github.biglv666.apigovernance.annotation.NoLog;
import io.github.biglv666.apigovernance.annotation.Skip;
import io.github.biglv666.apigovernance.concurrency.ConcurrencyLimiter;
import io.github.biglv666.apigovernance.concurrency.ConcurrencyPermit;
import io.github.biglv666.apigovernance.concurrency.ConcurrencyPolicy;
import io.github.biglv666.apigovernance.concurrency.ConcurrencyPolicyResolver;
import io.github.biglv666.apigovernance.config.ApiGovernanceProperties;
import io.github.biglv666.apigovernance.exception.GovernanceException;
import io.github.biglv666.apigovernance.filter.FilterChain;
import io.github.biglv666.apigovernance.filter.FilterContext;
import io.github.biglv666.apigovernance.ratelimit.RateLimitPolicy;
import io.github.biglv666.apigovernance.ratelimit.RateLimitPolicyResolver;
import io.github.biglv666.apigovernance.ratelimit.rules.DynamicRateRule;
import io.github.biglv666.apigovernance.ratelimit.rules.RateRuleStore;
import io.github.biglv666.apigovernance.web.HttpRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
     * 动态限流规则存储（0.7.0 新增，可为 null：动态规则关闭或用户替换切面时）。
     * 热路径只读不可变快照，无规则时开销为一次判空。
     */
    private final RateRuleStore rateRuleStore;

    /**
     * 并发限制器（1.0.0 新增，可为 null：未启用并发治理或测试手工构造）。
     * 热路径仅一次判空；未标注 {@code @ConcurrencyLimit} 且无动态规则时不产生任何开销。
     */
    private final ConcurrencyLimiter concurrencyLimiter;

    /** SpEL 表达式解析器（限流键）。 */
    private final ExpressionParser rateLimitKeyParser = new SpelExpressionParser();

    /** 参数名发现器：优先编译期 -parameters，退回字节码调试信息。 */
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    /** 限流键表达式缓存（key = Method）：编译结果与参数名按方法缓存，避免每请求重复解析。 */
    private final Map<Method, RateLimitKeyExpression> rateLimitKeyCache = new ConcurrentHashMap<>();

    /**
     * 重载映射方法名缓存（key = 目标类）：类内存在同名请求映射方法时，apiKey 需要追加
     * 参数类型后缀消歧，否则两个重载端点会共享同一限流键与同一份指标。
     */
    private final Map<Class<?>, Set<String>> overloadedMethodNamesCache = new ConcurrentHashMap<>();

    /**
     * 构造切面（兼容 0.6.0 签名，等价于不启用动态规则）。
     *
     * @param filterChain 过滤器链
     * @param properties  全局配置
     */
    public GovernanceAspect(FilterChain filterChain, ApiGovernanceProperties properties) {
        this(filterChain, properties, null, null);
    }

    /**
     * 构造切面。
     *
     * @param filterChain   过滤器链
     * @param properties    全局配置
     * @param rateRuleStore 动态限流规则存储（null 表示不启用动态规则覆盖）
     */
    public GovernanceAspect(FilterChain filterChain, ApiGovernanceProperties properties,
                            RateRuleStore rateRuleStore) {
        this(filterChain, properties, rateRuleStore, null);
    }

    /**
     * 构造切面（1.0.0 起主构造器，含并发限制器）。
     *
     * @param filterChain        过滤器链
     * @param properties         全局配置
     * @param rateRuleStore      动态限流规则存储（null 表示不启用动态规则覆盖）
     * @param concurrencyLimiter 并发限制器（null 表示不启用并发限制）
     */
    public GovernanceAspect(FilterChain filterChain, ApiGovernanceProperties properties,
                            RateRuleStore rateRuleStore, ConcurrencyLimiter concurrencyLimiter) {
        this.filterChain = filterChain;
        this.properties = properties;
        this.rateRuleStore = rateRuleStore;
        this.concurrencyLimiter = concurrencyLimiter;
    }

    /**
     * 切点：所有 {@code @RestController} / {@code @Controller} 类的方法。
     *
     * <p>显式排除内置管理控制器 {@code GovernanceManagementController}：它已标注 {@link Skip}
     * 永不被治理，排除后宿主应用不再为它生成无意义的 CGLIB 代理（省启动开销，
     * 也规避 CGLIB 弱引用类缓存在多上下文场景下的已知重入/回收缺陷）。
     */
    @Pointcut("(within(@org.springframework.web.bind.annotation.RestController *) "
              + "|| within(@org.springframework.stereotype.Controller *)) "
              + "&& !within(io.github.biglv666.apigovernance.management.GovernanceManagementController)")
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
        // 3. 包范围过滤：exclude 命中直接放行；include 非空时仅治理命中包
        if (isOutOfScope(targetClass)) {
            return joinPoint.proceed();
        }
        // 4. 非请求映射方法（控制器内的公开辅助方法）不参与治理
        if (!isMappedMethod(method)) {
            return joinPoint.proceed();
        }

        // 5. 构建上下文并解析配置
        String apiKey = buildApiKey(targetClass, method);
        FilterContext context = new FilterContext(joinPoint, apiKey, method, targetClass,
                joinPoint.getArgs());
        injectHttpRequest(context);
        resolveRateLimit(targetClass, method, context);
        resolveConcurrency(targetClass, method, context);
        resolveLogEnabled(targetClass, method, context);

        Object result = null;
        Throwable error = null;
        boolean rejected = false;
        ConcurrencyPermit permit = null;
        try {
            // 6. 前置过滤器链：任一 false 即短路
            if (!filterChain.executePreFilters(context)) {
                rejected = true;
                // 上下文中收集的响应头（如 RateLimit-* / Retry-After）随异常带给处理器写回响应
                throw new GovernanceException(
                        context.getRejectStatus(),
                        "REJECTED",
                        context.getRejectReason() != null ? context.getRejectReason() : "请求被拒绝",
                        context.getResponseHeaders());
            }
            // 7. 并发准入：拿不到名额立即拒绝（不排队、不占线程）；
            //    permit 在 finally 中释放，异常/超时路径同样归还
            if (context.isConcurrencyLimitEnabled() && concurrencyLimiter != null) {
                boolean limiterFailed = false;
                try {
                    permit = concurrencyLimiter.tryAcquire(context.getApiKey(),
                            context.getConcurrencyLimit());
                } catch (RuntimeException e) {
                    // 并发限制器自身故障：fail-open，避免治理组件异常阻断业务
                    limiterFailed = true;
                    log.error("并发限制器执行故障，放行请求 - API: {}, 错误: {}",
                            context.getApiKey(), e.getMessage(), e);
                }
                if (!limiterFailed && permit == null) {
                    rejected = true;
                    ApiGovernanceProperties.Concurrency config = properties.getConcurrencyLimit();
                    context.setRejectStatus(config.getRejectStatus());
                    context.setRejectReason(config.getMessage());
                    context.addResponseHeader("Retry-After", "1");
                    throw new GovernanceException(config.getRejectStatus(), "CONCURRENCY_LIMIT",
                            config.getMessage(), context.getResponseHeaders());
                }
            }
            // 8. 执行业务逻辑（只调用一次）
            return result = joinPoint.proceed();
        } catch (Throwable t) {
            error = t;
            throw t;
        } finally {
            // 9. 归还并发名额（先释放，避免后置过滤器耗时占用配额）
            if (permit != null) {
                permit.release();
            }
            // 10. 后置过滤器链：无论成功/失败/拒绝都执行
            context.setRejected(rejected);
            context.setResult(result);
            context.setError(error);
            filterChain.executePostFilters(context);
        }
    }

    /**
     * 判断目标类是否在治理范围之外。
     *
     * <p>规则：{@code exclude-packages} 命中即排除（优先）；{@code include-packages}
     * 非空时仅命中前缀的类被治理，其余视为范围外。两个列表均为空时治理全部（0.3.0 行为）。
     */
    private boolean isOutOfScope(Class<?> targetClass) {
        String className = targetClass.getName();
        for (String pkg : properties.getExcludePackages()) {
            if (matchesPackage(className, pkg)) {
                return true;
            }
        }
        List<String> include = properties.getIncludePackages();
        if (include.isEmpty()) {
            return false;
        }
        for (String pkg : include) {
            if (matchesPackage(className, pkg)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 类名是否命中包前缀（整包语义：{@code com.x} 命中 {@code com.x} 与 {@code com.x.y}，
     * 不命中 {@code com.xy}）。
     */
    private boolean matchesPackage(String className, String pkg) {
        if (pkg == null || pkg.isBlank()) {
            return false;
        }
        String trimmed = pkg.trim();
        return className.equals(trimmed) || className.startsWith(trimmed + ".");
    }

    /**
     * 把当前 HTTP 请求的真实信息（URI、方法、客户端 IP）注入上下文。
     *
     * <p>切点只命中 Controller，正常都是 Servlet 环境；这里仍兜底捕获 {@link Throwable}：
     * 无 Servlet 类路径的宿主加载注入器会抛 {@code NoClassDefFoundError}，
     * 此时保留注解推导的元数据，绝不影响业务请求。
     */
    private void injectHttpRequest(FilterContext context) {
        try {
            HttpRequestContext.applyTo(context);
        } catch (Throwable ignored) {
            // 非 Servlet 环境：path/httpMethod 保持注解推导值（由 MetadataCollectorFilter 填充）
        }
    }

    /**
     * 构建唯一 API 标识（全限定类名#方法名）。
     *
     * <p>类内存在<b>同名</b>请求映射方法（重载端点）时，方法名不足以唯一标识——
     * 两个重载端点会共享同一限流键（配额互相消耗）与同一份指标。此时追加参数类型
     * 简名后缀消歧：{@code com.x.UserController#get(Long)}。无重载的常规类保持
     * {@code com.x.UserController#get} 的既有格式，管理接口的 key 契约不变。
     */
    private String buildApiKey(Class<?> targetClass, Method method) {
        String base = targetClass.getName() + "#" + method.getName();
        Set<String> overloadedNames = overloadedMethodNamesCache.computeIfAbsent(targetClass,
                this::resolveOverloadedMappedMethodNames);
        if (!overloadedNames.contains(method.getName())) {
            return base;
        }
        Class<?>[] parameterTypes = method.getParameterTypes();
        if (parameterTypes.length == 0) {
            return base + "()";
        }
        StringBuilder suffix = new StringBuilder("(");
        for (int i = 0; i < parameterTypes.length; i++) {
            if (i > 0) {
                suffix.append(',');
            }
            suffix.append(parameterTypes[i].getSimpleName());
        }
        return base + suffix.append(')');
    }

    /**
     * 找出目标类中「被多个请求映射方法共用」的方法名集合（重载判定，按类缓存一次）。
     */
    private Set<String> resolveOverloadedMappedMethodNames(Class<?> targetClass) {
        Map<String, Integer> nameCounts = new HashMap<>();
        for (Method method : targetClass.getMethods()) {
            if (isMappedMethod(method)) {
                nameCounts.merge(method.getName(), 1, Integer::sum);
            }
        }
        Set<String> overloaded = new HashSet<>();
        for (Map.Entry<String, Integer> entry : nameCounts.entrySet()) {
            if (entry.getValue() > 1) {
                overloaded.add(entry.getKey());
            }
        }
        return overloaded;
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
     * 解析限流配置并写入上下文（0.8.0 起委托 {@link RateLimitPolicyResolver}）。
     *
     * <p>优先级：动态规则 &gt; 方法 {@link RateLimit} &gt; 类 {@link RateLimit} &gt;
     * 全局默认配置。注解中 {@code limit < 0} 表示回退到下一级默认值。
     *
     * <p>SpEL 参数键是否解析沿用 0.7.0 语义：取决于<b>动态规则之前</b>是否已启用限流，
     * 动态规则只覆盖 {@code limit}/{@code window}，不改变参数维度键。
     */
    private void resolveRateLimit(Class<?> targetClass, Method method, FilterContext context) {
        RateLimitPolicy policy = RateLimitPolicyResolver.resolve(targetClass, method,
                context.getApiKey(), properties, rateRuleStore);
        // 复用同一次规则查找：并发策略不再重复访问存储
        context.setDynamicRule(policy.getDynamicRule());

        // 注解 / 全局默认（动态规则之前）的结果：limit >= 0 表示启用限流
        if (policy.isAnnotationEnabled()) {
            context.setRateLimitEnabled(true);
            context.setRateLimit(policy.getAnnotationLimit());
            context.setWindow(policy.getAnnotationWindow());
            // 解析参数维度限流键后缀（@RateLimit.key SpEL），失败时自动回退接口级限流
            applyRateLimitKey(method, policy.getKeyExpression(), context);
            if (log.isDebugEnabled()) {
                log.debug("启用限流 - API: {}, limit: {}/{}(秒), keySuffix: {}",
                        context.getApiKey(), policy.getAnnotationLimit(),
                        policy.getAnnotationWindow(), context.getRateLimitKeySuffix());
            }
        }

        // 动态规则覆盖：规则须能给「原本不限流」的接口加限流，也能放开已限流接口
        DynamicRateRule rule = policy.getDynamicRule();
        if (rule == null) {
            return;
        }
        if (rule.getLimit() < 0) {
            context.setRateLimitEnabled(false);
            context.setRateLimit(-1);
            context.setWindow(rule.getWindow());
        } else {
            context.setRateLimitEnabled(true);
            context.setRateLimit(rule.getLimit());
            context.setWindow(rule.getWindow());
        }
        if (log.isDebugEnabled()) {
            log.debug("动态规则覆盖限流 - API: {}, rule: {}, limit: {}/{}(秒)",
                    context.getApiKey(), rule.getPattern(), rule.getLimit(), rule.getWindow());
        }
    }

    /**
     * 解析并发上限并写入上下文（1.0.0 新增）。
     *
     * <p>优先级：动态规则 {@code maxConcurrent} &gt; 方法 {@code @ConcurrencyLimit}
     * &gt; 类 {@code @ConcurrencyLimit} &gt; 全局默认。复用
     * {@link FilterContext#getDynamicRule()}，不重复访问规则存储。
     */
    private void resolveConcurrency(Class<?> targetClass, Method method, FilterContext context) {
        ConcurrencyPolicy policy = ConcurrencyPolicyResolver.resolve(targetClass, method,
                properties, context.getDynamicRule());
        context.setConcurrencyLimitEnabled(policy.isEnabled());
        context.setConcurrencyLimit(policy.getPermits());
        if (policy.isEnabled() && log.isDebugEnabled()) {
            log.debug("启用并发限制 - API: {}, permits: {}, source: {}",
                    context.getApiKey(), policy.getPermits(), policy.getSource().getCode());
        }
    }

    /**
     * 解析 {@code @RateLimit(key = "...")} SpEL 表达式并写入上下文后缀。
     *
     * <p>表达式按 Method 缓存编译结果；求值使用受限的 {@link SimpleEvaluationContext}
     * （只读变量绑定，不允许类型引用 / 构造器 / Bean 引用），可用变量为方法参数名与
     * {@code #apiKey}。解析或求值任何一步失败都回退为接口级限流（不写后缀），仅记 warn 日志。
     *
     * @param method       被拦截方法
     * @param keyExpression 注解声明的 SpEL 表达式（空表示不使用参数维度限流）
     * @param context      过滤器上下文
     */
    private void applyRateLimitKey(Method method, String keyExpression, FilterContext context) {
        if (keyExpression == null || keyExpression.isBlank()) {
            return;
        }
        try {
            RateLimitKeyExpression compiled = rateLimitKeyCache.computeIfAbsent(method,
                    m -> compileRateLimitKey(m, keyExpression));
            if (compiled.expression == null) {
                // 编译失败的表达式：已在编译时记过 warn，直接回退接口级限流
                return;
            }
            SimpleEvaluationContext evalContext = SimpleEvaluationContext.forReadOnlyDataBinding().build();
            Object[] args = context.getArgs();
            String[] paramNames = compiled.paramNames;
            if (paramNames != null) {
                for (int i = 0; i < paramNames.length && i < args.length; i++) {
                    evalContext.setVariable(paramNames[i], args[i]);
                }
            } else if (log.isDebugEnabled()) {
                log.debug("无法发现方法参数名（编译时未启用 -parameters），SpEL 仅可用 #apiKey - method: {}",
                        method.getName());
            }
            evalContext.setVariable("apiKey", context.getApiKey());
            Object value = compiled.expression.getValue(evalContext);
            if (value != null) {
                String suffix = String.valueOf(value).trim();
                if (!suffix.isEmpty()) {
                    context.setRateLimitKeySuffix(suffix);
                }
            }
        } catch (Exception e) {
            log.warn("限流键 SpEL 求值失败，回退接口级限流 - API: {}, key: '{}', 错误: {}",
                    context.getApiKey(), keyExpression, e.getMessage());
        }
    }

    /**
     * 编译限流键表达式并尝试发现参数名（仅首次调用，结果按 Method 缓存）。
     * 编译失败时返回 expression 为 null 的占位对象，避免每请求重复解析。
     */
    private RateLimitKeyExpression compileRateLimitKey(Method method, String keyExpression) {
        try {
            Expression expression = rateLimitKeyParser.parseExpression(keyExpression);
            String[] paramNames = parameterNameDiscoverer.getParameterNames(method);
            return new RateLimitKeyExpression(expression, paramNames);
        } catch (Exception e) {
            log.warn("限流键 SpEL 解析失败，忽略参数维度限流 - method: {}, key: '{}', 错误: {}",
                    method.getName(), keyExpression, e.getMessage());
            return new RateLimitKeyExpression(null, null);
        }
    }

    /**
     * 限流键表达式的按方法缓存条目：编译后的表达式与参数名。
     * {@code expression == null} 表示编译失败（占位，永久回退接口级限流）。
     */
    private static final class RateLimitKeyExpression {

        /** 编译后的 SpEL 表达式；null 表示编译失败。 */
        final Expression expression;

        /** 方法参数名（可能为 null：编译期未启用 -parameters 且无调试信息）。 */
        final String[] paramNames;

        RateLimitKeyExpression(Expression expression, String[] paramNames) {
            this.expression = expression;
            this.paramNames = paramNames;
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
