package io.github.biglv666.apigovernance.ratelimit;

import io.github.biglv666.apigovernance.annotation.RateLimit;
import io.github.biglv666.apigovernance.config.ApiGovernanceProperties;
import io.github.biglv666.apigovernance.ratelimit.rules.DynamicRateRule;
import io.github.biglv666.apigovernance.ratelimit.rules.RateRuleStore;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 限流策略解析器（0.8.0 新增）。
 *
 * <p>单一事实来源：治理切面与 {@code GET /rate-limiter/rules/effective} 都调用本类，
 * 保证「解释出来的策略」与「实际执行的策略」完全一致。
 *
 * <p>解析顺序严格沿用 0.7.0：
 * <ol>
 *   <li>方法 {@code @RateLimit} &gt; 类 {@code @RateLimit} &gt; 全局默认；</li>
 *   <li>注解中 {@code limit < 0} 回退全局 {@code default-limit}，
 *       {@code window <= 0} 回退全局 {@code default-window}（再兜底 1 秒）；</li>
 *   <li>动态规则最后覆盖 {@code limit}/{@code window}（{@code limit < 0} 表示放开）；</li>
 *   <li>SpEL 参数键是否解析，取决于<b>动态规则之前</b>是否已启用限流（保持 0.7.0 语义）。</li>
 * </ol>
 *
 * @author API Governance Team
 * @since 0.8.0
 */
public final class RateLimitPolicyResolver {

    private RateLimitPolicyResolver() {
    }

    /**
     * 解析指定 Controller 方法的限流策略。
     *
     * @param targetClass 目标 Controller 类（可为 null：仅解析全局默认与动态规则）
     * @param method      目标方法（可为 null：仅解析类级注解）
     * @param apiKey      API 标识（用于动态规则匹配）
     * @param properties  全局配置
     * @param store       动态规则存储（可为 null）
     * @return 解析结果
     */
    public static RateLimitPolicy resolve(Class<?> targetClass, Method method, String apiKey,
                                          ApiGovernanceProperties properties,
                                          RateRuleStore store) {
        RateLimit methodAnn = method == null ? null
                : AnnotatedElementUtils.findMergedAnnotation(method, RateLimit.class);
        RateLimit classAnn = targetClass == null ? null
                : AnnotatedElementUtils.findMergedAnnotation(targetClass, RateLimit.class);

        int rawLimit;
        int rawWindow;
        RateLimitPolicy.Source annotationSource;
        if (methodAnn != null) {
            rawLimit = methodAnn.limit();
            rawWindow = methodAnn.window();
            annotationSource = RateLimitPolicy.Source.METHOD_ANNOTATION;
        } else if (classAnn != null) {
            rawLimit = classAnn.limit();
            rawWindow = classAnn.window();
            annotationSource = RateLimitPolicy.Source.CLASS_ANNOTATION;
        } else {
            rawLimit = -1;
            rawWindow = -1;
            annotationSource = RateLimitPolicy.Source.NONE;
        }

        int globalLimit = properties.getRateLimit().getDefaultLimit();
        int globalWindow = properties.getRateLimit().getDefaultWindow();

        int annotationLimit = rawLimit < 0 ? globalLimit : rawLimit;
        int annotationWindow = rawWindow <= 0 ? globalWindow : rawWindow;
        if (annotationWindow <= 0) {
            annotationWindow = 1;
        }
        boolean annotationEnabled = annotationLimit >= 0;
        RateLimitPolicy.Source resolvedAnnotationSource = annotationEnabled
                ? (rawLimit >= 0 ? annotationSource : RateLimitPolicy.Source.GLOBAL_DEFAULT)
                : RateLimitPolicy.Source.NONE;

        int effectiveLimit = annotationLimit;
        int effectiveWindow = annotationWindow;
        boolean enabled = annotationEnabled;
        RateLimitPolicy.Source effectiveSource = resolvedAnnotationSource;

        DynamicRateRule rule = store == null || apiKey == null
                ? null : store.findMatch(apiKey).orElse(null);
        if (rule != null) {
            if (rule.getLimit() < 0) {
                enabled = false;
                effectiveLimit = -1;
            } else {
                enabled = true;
                effectiveLimit = rule.getLimit();
            }
            effectiveWindow = rule.getWindow();
            effectiveSource = RateLimitPolicy.Source.DYNAMIC_RULE;
        }

        String keyExpression = methodAnn != null ? methodAnn.key()
                : (classAnn != null ? classAnn.key() : "");
        return new RateLimitPolicy(annotationLimit, annotationWindow, annotationEnabled,
                resolvedAnnotationSource, effectiveLimit, effectiveWindow, enabled,
                effectiveSource, rule, keyExpression);
    }

    /**
     * 按 API 标识反查 Controller 类/方法并解析策略（管理接口解释端点使用）。
     *
     * <p>类名用 {@code ClassUtils.forName} 加载（不触发静态初始化）；找不到类/方法时
     * 退化为「全局默认 + 动态规则」解释，并在结果中标记未解析，绝不抛异常。
     *
     * @param apiKey     API 标识（{@code 全限定类名#方法名}，方法名可带参数简名）
     * @param properties 全局配置
     * @param store      动态规则存储（可为 null）
     * @return 解释结果
     */
    public static ApiKeyResolution resolveForApiKey(String apiKey,
                                                    ApiGovernanceProperties properties,
                                                    RateRuleStore store) {
        if (apiKey == null || apiKey.isBlank()) {
            return new ApiKeyResolution(resolve(null, null, "", properties, store),
                    false, false, false, "apiKey 不能为空", null, null);
        }
        String trimmed = apiKey.trim();
        int hash = trimmed.lastIndexOf('#');
        if (hash <= 0 || hash == trimmed.length() - 1) {
            return new ApiKeyResolution(resolve(null, null, trimmed, properties, store),
                    false, false, false,
                    "apiKey 格式应为 全限定类名#方法名，收到: " + trimmed, null, null);
        }
        String className = trimmed.substring(0, hash);
        String methodPart = trimmed.substring(hash + 1);

        Class<?> targetClass;
        try {
            targetClass = ClassUtils.forName(className,
                    RateLimitPolicyResolver.class.getClassLoader());
        } catch (Exception | LinkageError e) {
            return new ApiKeyResolution(resolve(null, null, trimmed, properties, store),
                    false, false, false, "无法加载类 " + className + ": " + e.getMessage(),
                    null, null);
        }

        MethodLookup lookup = findMappedMethod(targetClass, methodPart);
        RateLimitPolicy policy = resolve(targetClass, lookup.method(), trimmed, properties, store);
        String message = lookup.ambiguous()
                ? "类内存在多个同名映射方法，已按参数列表选择: " + lookup.method()
                : (lookup.method() == null ? "未找到映射方法 " + methodPart + "，仅按类级注解解释" : null);
        return new ApiKeyResolution(policy, true, lookup.method() != null,
                lookup.ambiguous(), message, targetClass, lookup.method());
    }

    private static MethodLookup findMappedMethod(Class<?> targetClass, String methodPart) {
        boolean withParams = methodPart.endsWith(")") && methodPart.contains("(");
        String methodName = withParams
                ? methodPart.substring(0, methodPart.indexOf('('))
                : methodPart;
        String[] expectedParams = withParams
                ? splitParams(methodPart.substring(methodPart.indexOf('(') + 1,
                        methodPart.length() - 1))
                : null;

        List<Method> candidates = new ArrayList<>();
        for (Method method : targetClass.getMethods()) {
            if (method.isBridge() || !method.getName().equals(methodName)
                    || AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class) == null) {
                continue;
            }
            if (expectedParams == null || matchesParams(method, expectedParams)) {
                candidates.add(method);
            }
        }
        if (candidates.isEmpty()) {
            return new MethodLookup(null, false);
        }
        candidates.sort(Comparator.comparingInt((Method m) -> m.getParameterCount())
                .thenComparing(Method::toString));
        return new MethodLookup(candidates.get(0), candidates.size() > 1);
    }

    private static boolean matchesParams(Method method, String[] expectedParams) {
        Class<?>[] actual = method.getParameterTypes();
        if (actual.length != expectedParams.length) {
            return false;
        }
        for (int i = 0; i < actual.length; i++) {
            if (!actual[i].getSimpleName().equals(expectedParams[i])) {
                return false;
            }
        }
        return true;
    }

    private static String[] splitParams(String raw) {
        if (raw.isBlank()) {
            return new String[0];
        }
        String[] parts = raw.split(",");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
    }

    /** 方法查找结果。 */
    private record MethodLookup(Method method, boolean ambiguous) {
    }

    /**
     * 按 API 标识解释的结果。
     *
     * @param policy           解析出的策略
     * @param classResolved    是否成功加载 Controller 类
     * @param methodResolved   是否成功定位映射方法
     * @param ambiguous        是否因方法重载而存在歧义（已取确定性首选）
     * @param message          补充说明（无法解析时给出原因）
     * @param targetClass      反查到的 Controller 类（无法解析时为 null，1.0.0 新增）
     * @param method           反查到的映射方法（无法解析时为 null，1.0.0 新增）
     */
    public record ApiKeyResolution(RateLimitPolicy policy, boolean classResolved,
                                   boolean methodResolved, boolean ambiguous, String message,
                                   Class<?> targetClass, Method method) {
    }
}
