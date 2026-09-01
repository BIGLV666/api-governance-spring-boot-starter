package io.github.biglv666.apigovernance.async.internal;

import io.github.biglv666.apigovernance.async.AsyncEventBuilder;
import io.github.biglv666.apigovernance.async.AsyncInvocation;
import io.github.biglv666.apigovernance.async.spi.AsyncEventEnricher;
import io.github.biglv666.apigovernance.web.HttpRequestContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 内置 HTTP 上下文 enricher —— 在调用线程上把当前请求的元数据快照进事件
 * {@code data}（键：{@code requestUri} / {@code httpMethod} / {@code clientIp}），
 * 异步 Handler 无需再自行解 {@code RequestContextHolder}。
 *
 * <h3>安全边界</h3>
 * <p>只快照三个只读元数据，<b>绝不</b>捕获请求头、请求参数与请求体；
 * {@code clientIp} 可被请求头伪造，仅用于展示。非请求线程（无 Servlet 请求属性）
 * 或无 Servlet 类路径时不写入任何数据（enricher 异常由 {@code AsyncEventFactory} 隔离）。
 *
 * <p>通过 {@code api.governance.async.web-context-enrichment=false} 可关闭。
 *
 * @author API Governance Team
 * @since 0.5.0
 */
public class WebContextEnricher implements AsyncEventEnricher {

    /** 事件 data 中的真实请求 URI 键。 */
    public static final String KEY_REQUEST_URI = "requestUri";

    /** 事件 data 中的 HTTP 方法键。 */
    public static final String KEY_HTTP_METHOD = "httpMethod";

    /** 事件 data 中的客户端 IP 键。 */
    public static final String KEY_CLIENT_IP = "clientIp";

    @Override
    public void enrich(AsyncEventBuilder builder, AsyncInvocation invocation) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes servletAttributes)) {
            return;
        }
        HttpServletRequest request = servletAttributes.getRequest();
        builder.put(KEY_REQUEST_URI, request.getRequestURI());
        builder.put(KEY_HTTP_METHOD, request.getMethod());
        builder.put(KEY_CLIENT_IP, HttpRequestContext.resolveClientIp(request));
    }
}
