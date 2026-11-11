package io.github.biglv666.apigovernance.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 请求体大小限制过滤器（1.0.0 新增，默认关闭）。
 *
 * <p>在请求进入 Controller / 反序列化<b>之前</b>拒绝超大请求体，避免大 JSON / 文件
 * 占用内存与线程：
 * <ul>
 *   <li>{@code Content-Length} 已知且超限：直接返回 413，不读取请求体；</li>
 *   <li>分块传输（{@code Transfer-Encoding: chunked}，长度未知）：包装输入流，
 *       实际读取字节数超限时中断并返回 413。</li>
 * </ul>
 *
 * <p>默认关闭（{@code api.governance.body-size-limit.enabled=false}），开启后对所有
 * 请求生效。响应体保持与治理拒绝一致的精简 JSON 结构。
 *
 * @author API Governance Team
 * @since 1.0.0
 */
public class BodySizeLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(BodySizeLimitFilter.class);

    private final long maxBytes;

    private final int rejectStatus;

    private final String message;

    /**
     * 构造请求体大小限制过滤器。
     *
     * @param maxBytes     允许的最大字节数
     * @param rejectStatus 超限状态码（默认 413）
     * @param message      超限提示语
     */
    public BodySizeLimitFilter(long maxBytes, int rejectStatus, String message) {
        this.maxBytes = Math.max(1L, maxBytes);
        this.rejectStatus = rejectStatus;
        this.message = message == null || message.isBlank() ? "请求体超过大小限制" : message;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > maxBytes) {
            log.warn("拒绝超大请求体 - uri: {}, contentLength: {}, maxBytes: {}",
                    request.getRequestURI(), declaredLength, maxBytes);
            writeReject(response, message);
            return;
        }
        boolean chunked = isChunked(request);
        boolean lengthUnknown = chunked || declaredLength < 0L;
        if (lengthUnknown && isFormBody(request)) {
            // 表单/multipart 由容器直接解析原始 body（getParameter/getParts），
            // 包装输入流无法约束，长度未知时只能拒绝，避免大小限制被静默绕过
            log.warn("拒绝长度未知的表单/多部分请求 - uri: {}, contentLength: {}, chunked: {}",
                    request.getRequestURI(), declaredLength, chunked);
            writeReject(response, "长度未知的表单请求不支持大小限制，请携带 Content-Length");
            return;
        }
        if (!lengthUnknown) {
            // Content-Length 已声明且未超限：Servlet 容器保证实际读取不会超过该值
            filterChain.doFilter(request, response);
            return;
        }
        try {
            filterChain.doFilter(new LimitedRequest(request), response);
        } catch (RuntimeException | ServletException e) {
            if (!isBodyTooLarge(e)) {
                throw e;
            }
            if (response.isCommitted()) {
                throw e;
            }
            response.reset();
            log.warn("拒绝超大请求体 - uri: {}, maxBytes: {}", request.getRequestURI(), maxBytes);
            writeReject(response, message);
        }
    }

    private static boolean isChunked(HttpServletRequest request) {
        String transferEncoding = request.getHeader("Transfer-Encoding");
        return transferEncoding != null
                && transferEncoding.toLowerCase(java.util.Locale.ROOT).contains("chunked");
    }

    private static boolean isFormBody(HttpServletRequest request) {
        String contentType = request.getContentType();
        if (contentType == null) {
            return false;
        }
        String lower = contentType.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("application/x-www-form-urlencoded")
                || lower.startsWith("multipart/");
    }

    private static boolean isBodyTooLarge(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (current instanceof BodyTooLargeException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void writeReject(HttpServletResponse response, String rejectMessage)
            throws IOException {
        response.setStatus(rejectStatus);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"success\":false,\"code\":\"BODY_TOO_LARGE\",\"message\":\""
                + jsonEscape(rejectMessage) + "\",\"status\":" + rejectStatus + "}");
    }

    private static String jsonEscape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /**
     * 长度未知时包装请求，按实际读取字节数计数。
     */
    private final class LimitedRequest extends HttpServletRequestWrapper {

        /** 复用同一个计数流，避免多次 getInputStream 重置计数。 */
        private LimitedInputStream limitedStream;

        LimitedRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (limitedStream == null) {
                limitedStream = new LimitedInputStream(super.getInputStream());
            }
            return limitedStream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null
                    ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    /**
     * 计数输入流：累计读取超过上限时抛出 {@link BodyTooLargeException}。
     */
    private final class LimitedInputStream extends ServletInputStream {

        private final ServletInputStream delegate;

        private long count;

        LimitedInputStream(ServletInputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value != -1) {
                add(1);
            }
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int read = delegate.read(b, off, len);
            if (read > 0) {
                add(read);
            }
            return read;
        }

        private void add(int bytes) {
            count += bytes;
            if (count > maxBytes) {
                throw new BodyTooLargeException(
                        "请求体超过大小限制 " + maxBytes + " 字节");
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    /**
     * 请求体超限信号（运行时异常，穿透框架包装后由过滤器捕获）。
     */
    static final class BodyTooLargeException extends RuntimeException {

        BodyTooLargeException(String message) {
            super(message);
        }
    }
}
