package io.github.biglv666.apigovernance.web;

import io.github.biglv666.apigovernance.filter.FilterContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * HTTP 请求上下文注入器 —— 把当前请求的真实信息（URI、HTTP 方法、客户端 IP）
 * 写入 {@link FilterContext}，供限流、统计、日志与自定义过滤器使用。
 *
 * <h3>注入时机与优先级</h3>
 * <p>由 {@code GovernanceAspect} 在构建上下文后立即调用（早于所有过滤器）。
 * 注入成功后 {@code path} 为真实请求 URI；{@code MetadataCollectorFilter} 仅在
 * 字段缺失时回退为注解推导值 —— 即<b>真实请求信息永远优先于注解推导信息</b>。
 *
 * <h3>非 Servlet 环境</h3>
 * <p>非请求线程（异步任务、MQ 消费）或无 Servlet 类路径时直接返回，不做任何注入，
 * 上下文保持注解推导的元数据，绝不抛出异常影响治理管道。
 *
 * @author API Governance Team
 * @since 0.3.0
 */
public final class HttpRequestContext {

    private HttpRequestContext() {
    }

    /**
     * 从 {@link RequestContextHolder} 读取当前请求并写入上下文（幂等，可重复调用）。
     *
     * @param context 过滤器上下文
     */
    public static void applyTo(FilterContext context) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes servletAttributes)) {
            return;
        }
        HttpServletRequest request = servletAttributes.getRequest();
        String requestUri = request.getRequestURI();
        context.setRequestUri(requestUri);
        context.setClientIp(resolveClientIp(request));
        context.setHttpMethod(request.getMethod());
        context.setPath(requestUri);
    }

    /**
     * 解析客户端 IP：{@code X-Forwarded-For}（取第一个，即最初客户端）→
     * {@code X-Real-IP} → {@code remoteAddr}。
     *
     * <p>注意：两个请求头可被客户端伪造，仅用于统计与告警展示，不作为安全决策依据。
     *
     * @param request 当前 HTTP 请求
     * @return 客户端 IP（可能为 null：无代理头且 remoteAddr 不可用时）
     */
    public static String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // 多级代理时第一个地址为最初客户端
            int comma = forwarded.indexOf(',');
            String first = comma > 0 ? forwarded.substring(0, comma) : forwarded;
            String trimmed = first.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
