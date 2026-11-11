package io.github.biglv666.apigovernance.exception;

import io.github.biglv666.apigovernance.config.ApiGovernanceProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 治理异常处理器测试：默认响应结构与可选 RFC 9457 problem+json。
 *
 * @author API Governance Team
 * @since 1.0.0
 */
class GovernanceExceptionHandlerTest {

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static void bind(MockHttpServletRequest request) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static ApiGovernanceProperties problemDetailsProperties(boolean enabled) {
        ApiGovernanceProperties properties = new ApiGovernanceProperties();
        properties.getProblemDetails().setEnabled(enabled);
        return properties;
    }

    @Test
    void defaultResponseKeepsLegacyStructure() {
        GovernanceExceptionHandler handler = new GovernanceExceptionHandler(
                problemDetailsProperties(false));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
        bind(request);

        ResponseEntity<Map<String, Object>> response = handler.handleGovernanceException(
                new GovernanceException(429, "REJECTED", "slow down"));

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getHeaders().getContentType())
                .isNotEqualTo(MediaType.parseMediaType("application/problem+json"));
        assertThat(response.getBody())
                .containsEntry("success", false)
                .containsEntry("code", "REJECTED")
                .containsEntry("message", "slow down")
                .containsEntry("status", 429);
    }

    @Test
    void problemDetailsResponseFollowsRfc9457() {
        GovernanceExceptionHandler handler = new GovernanceExceptionHandler(
                problemDetailsProperties(true));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
        bind(request);

        ResponseEntity<Map<String, Object>> response = handler.handleGovernanceException(
                new GovernanceException(429, "REJECTED", "slow down",
                        Map.of("Retry-After", "1")));

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.parseMediaType("application/problem+json"));
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(response.getBody())
                .containsEntry("type", "about:blank")
                .containsEntry("title", "Too Many Requests")
                .containsEntry("status", 429)
                .containsEntry("detail", "slow down")
                .containsEntry("instance", "/api/orders")
                .containsEntry("code", "REJECTED");
    }

    @Test
    void defaultConstructorKeepsLegacyBehavior() {
        GovernanceExceptionHandler handler = new GovernanceExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
        bind(request);

        ResponseEntity<Map<String, Object>> response = handler.handleGovernanceException(
                new GovernanceException(429, "REJECTED", "slow down"));

        assertThat(response.getBody()).containsKey("success");
    }
}
