package io.github.biglv666.apigovernance.async;

import io.github.biglv666.apigovernance.async.event.AsyncPhase;
import io.github.biglv666.apigovernance.async.internal.WebContextEnricher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内置 HTTP 上下文 enricher 测试：快照 requestUri/httpMethod/clientIp 进事件 data，
 * 且绝不携带请求参数与请求头。
 *
 * @author API Governance Team
 * @since 0.5.0
 */
class WebContextEnricherTest {

    private final WebContextEnricher enricher = new WebContextEnricher();

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void snapshotsRequestMetadataIntoEventData() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/42");
        request.addHeader("X-Forwarded-For", "203.0.113.9, 70.41.3.18");
        request.setParameter("secret", "should-not-leak");
        request.addHeader("Authorization", "Bearer should-not-leak");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        Map<String, Object> data = buildData();

        assertThat(data)
                .containsEntry(WebContextEnricher.KEY_REQUEST_URI, "/api/users/42")
                .containsEntry(WebContextEnricher.KEY_HTTP_METHOD, "GET")
                .containsEntry(WebContextEnricher.KEY_CLIENT_IP, "203.0.113.9")
                .doesNotContainKey("secret")
                .doesNotContainKey("Authorization");
    }

    @Test
    void writesNothingOutsideWebRequest() throws Exception {
        // 无请求属性（MQ 消费、定时任务、测试直调）：不写入任何 data
        Map<String, Object> data = buildData();
        assertThat(data).isEmpty();
    }

    private Map<String, Object> buildData() throws Exception {
        AsyncEventBuilder builder = new AsyncEventBuilder(new AsyncInvocation(
                "id-1", "user.login", AsyncPhase.BEFORE, String.class,
                String.class.getMethod("toString"), new Object[0], null, null,
                Instant.now(), 0L));
        enricher.enrich(builder, null);
        return builder.build().data();
    }
}
