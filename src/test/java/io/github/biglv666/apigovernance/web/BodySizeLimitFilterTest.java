package io.github.biglv666.apigovernance.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 请求体大小限制过滤器测试：Content-Length 快速拒绝、分块传输实际计数、放行路径。
 *
 * @author API Governance Team
 * @since 1.0.0
 */
class BodySizeLimitFilterTest {

    private static byte[] body(int bytes) {
        return "x".repeat(bytes).getBytes(StandardCharsets.UTF_8);
    }

    private static FilterChain readAll(AtomicBoolean invoked) {
        return (request, response) -> {
            invoked.set(true);
            request.getInputStream().readAllBytes();
        };
    }

    @Test
    void rejectsDeclaredContentLengthAboveLimitWithoutReadingBody() throws Exception {
        BodySizeLimitFilter filter = new BodySizeLimitFilter(10, 413, "too large");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/upload");
        request.setContent(body(11));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(request, response, readAll(invoked));

        assertThat(invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString()).contains("BODY_TOO_LARGE").contains("too large");
    }

    @Test
    void passesWhenDeclaredContentLengthWithinLimit() throws Exception {
        BodySizeLimitFilter filter = new BodySizeLimitFilter(10, 413, "too large");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/upload");
        request.setContent(body(10));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(request, response, readAll(invoked));

        assertThat(invoked).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsChunkedBodyWhenActualBytesExceedLimit() throws Exception {
        BodySizeLimitFilter filter = new BodySizeLimitFilter(10, 413, "too large");
        MockHttpServletRequest original = new MockHttpServletRequest("POST", "/upload");
        original.setContent(body(50));
        HttpServletRequest chunked = new HttpServletRequestWrapper(original) {
            @Override
            public long getContentLengthLong() {
                return -1L;
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(chunked, response, readAll(invoked));

        assertThat(invoked).isTrue();
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("BODY_TOO_LARGE");
    }

    @Test
    void rejectsUnknownLengthFormBodyBeforeContainerParsesIt() throws Exception {
        BodySizeLimitFilter filter = new BodySizeLimitFilter(10, 413, "too large");
        MockHttpServletRequest original = new MockHttpServletRequest("POST", "/form");
        original.setContentType("application/x-www-form-urlencoded");
        original.setContent("a=1&b=2&c=3&d=4&e=5".getBytes(StandardCharsets.UTF_8));
        original.addHeader("Transfer-Encoding", "chunked");
        HttpServletRequest chunked = new HttpServletRequestWrapper(original) {
            @Override
            public long getContentLengthLong() {
                return -1L;
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(chunked, response, readAll(invoked));

        assertThat(invoked).isFalse();
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("BODY_TOO_LARGE");
    }

    @Test
    void readerPathAlsoEnforcesLimit() throws Exception {
        BodySizeLimitFilter filter = new BodySizeLimitFilter(10, 413, "too large");
        MockHttpServletRequest original = new MockHttpServletRequest("POST", "/json");
        original.setContent(body(50));
        HttpServletRequest chunked = new HttpServletRequestWrapper(original) {
            @Override
            public long getContentLengthLong() {
                return -1L;
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();
        FilterChain readViaReader = (request, resp) -> {
            invoked.set(true);
            request.getReader().readLine();
        };

        filter.doFilter(chunked, response, readViaReader);

        assertThat(invoked).isTrue();
        assertThat(response.getStatus()).isEqualTo(413);
    }

    @Test
    void passesChunkedBodyWithinLimit() throws Exception {
        BodySizeLimitFilter filter = new BodySizeLimitFilter(10, 413, "too large");
        MockHttpServletRequest original = new MockHttpServletRequest("POST", "/upload");
        original.setContent(body(10));
        HttpServletRequest chunked = new HttpServletRequestWrapper(original) {
            @Override
            public long getContentLengthLong() {
                return -1L;
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(chunked, response, readAll(invoked));

        assertThat(invoked).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsWithCustomStatusAndEscapedMessage() throws Exception {
        BodySizeLimitFilter filter = new BodySizeLimitFilter(1, 400, "a\"b");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/upload");
        request.setContent(body(2));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, readAll(new AtomicBoolean()));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("a\\\"b");
    }
}
