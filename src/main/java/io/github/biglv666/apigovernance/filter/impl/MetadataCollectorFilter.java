package io.github.biglv666.apigovernance.filter.impl;

import io.github.biglv666.apigovernance.filter.FilterContext;
import io.github.biglv666.apigovernance.filter.PreFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.lang.reflect.Method;

/**
 * 信息采集过滤器（前置，order = 1）。
 *
 * <p>作为管道第一个过滤器，负责采集 API 的基础元数据（HTTP 方法、请求路径），
 * 供后续的限流、统计、日志过滤器使用。
 *
 * <p>通过 {@code @RequestMapping}（其组合注解 {@code @GetMapping} 等会被元注解解析命中）
 * 同时提取类级别前缀与方法的路径，拼接成完整路径。
 *
 * @author API Governance Team
 * @since 1.0
 */
public class MetadataCollectorFilter implements PreFilter {

    private static final Logger log = LoggerFactory.getLogger(MetadataCollectorFilter.class);

    @Override
    public boolean doFilter(FilterContext context) {
        try {
            extractHttpInfo(context);
            if (log.isDebugEnabled()) {
                log.debug("采集元数据 - API: {}, method: {}, path: {}",
                        context.getApiKey(), context.getHttpMethod(), context.getPath());
            }
        } catch (Exception e) {
            // 元数据采集失败不应阻断业务，降级为 UNKNOWN
            log.debug("元数据采集失败 - API: {}", context.getApiKey(), e);
        }
        return true;
    }

    /**
     * 提取 HTTP 方法与路径。
     *
     * <p>真实请求信息由 {@code GovernanceAspect} 在构建上下文时优先注入
     * （真实 URI / 真实 HTTP 方法 / 客户端 IP）；本过滤器只在字段<b>缺失</b>时
     * 回退为 {@code @RequestMapping} 注解推导值（非 Servlet 环境的兜底），绝不覆盖真实值。
     */
    private void extractHttpInfo(FilterContext context) {
        Method method = context.getMethod();
        Class<?> targetClass = context.getTargetClass();

        // 类级别路径前缀（@RequestMapping("/api")）
        String classPrefix = "";
        RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(targetClass, RequestMapping.class);
        if (classMapping != null) {
            classPrefix = firstPath(classMapping.path());
        }

        // 方法级别映射（@RequestMapping/@GetMapping/... 均通过元注解解析命中）
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
        if (mapping != null) {
            RequestMethod[] methods = mapping.method();
            if (context.getHttpMethod() == null) {
                context.setHttpMethod(methods.length > 0 ? methods[0].name() : "UNKNOWN");
            }
            if (context.getPath() == null) {
                context.setPath(combine(classPrefix, firstPath(mapping.path())));
            }
        } else {
            if (context.getHttpMethod() == null) {
                context.setHttpMethod("UNKNOWN");
            }
            if (context.getPath() == null) {
                context.setPath(classPrefix);
            }
        }
    }

    /**
     * 取路径数组的第一个非空值。
     */
    private String firstPath(String[] paths) {
        for (String p : paths) {
            if (p != null && !p.isEmpty()) {
                return p;
            }
        }
        return "";
    }

    /**
     * 拼接类路径前缀与方法路径。
     */
    private String combine(String prefix, String methodPath) {
        if (methodPath == null || methodPath.isEmpty()) {
            return prefix;
        }
        if (prefix == null || prefix.isEmpty()) {
            return methodPath;
        }
        if (prefix.endsWith("/") && methodPath.startsWith("/")) {
            return prefix + methodPath.substring(1);
        }
        if (!prefix.endsWith("/") && !methodPath.startsWith("/")) {
            return prefix + "/" + methodPath;
        }
        return prefix + methodPath;
    }

    @Override
    public int getOrder() {
        return 1;
    }
}
