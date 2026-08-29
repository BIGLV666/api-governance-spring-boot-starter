package io.github.biglv666.apigovernance.management;

import io.github.biglv666.apigovernance.config.ApiGovernanceProperties;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理接口鉴权过滤器单元测试：验证令牌校验、未启用放行与安全响应。
 *
 * @author API Governance Team
 * @since 0.2.0
 */
class GovernanceManagementAuthFilterTest {

    private ApiGovernanceProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ApiGovernanceProperties();
    }

    @Test
    void validTokenPassesThrough() throws ServletException, IOException {
        properties.getManagement().setAuthToken("secret-token");
        GovernanceManagementAuthFilter filter = new GovernanceManagementAuthFilter(properties);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api-governance/status");
        request.addHeader("X-Governance-Token", "secret-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        // 请求已到达下游
        assertTrue(chain.getRequest() != null);
        assertEquals(200, response.getStatus());
    }

    @Test
    void missingOrWrongTokenReturns401() throws ServletException, IOException {
        properties.getManagement().setAuthToken("secret-token");
        GovernanceManagementAuthFilter filter = new GovernanceManagementAuthFilter(properties);

        // 未携带令牌
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api-governance/status");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertTrue(chain.getRequest() == null, "被拒绝的请求不应到达下游");
        // 401 响应不泄露细节
        assertTrue(response.getContentAsString().contains("UNAUTHORIZED"));
    }

    @Test
    void customHeaderNameIsHonored() throws ServletException, IOException {
        properties.getManagement().setAuthToken("secret-token");
        properties.getManagement().setAuthHeader("My-Auth");
        GovernanceManagementAuthFilter filter = new GovernanceManagementAuthFilter(properties);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api-governance/metrics");
        request.addHeader("My-Auth", "secret-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);
        assertEquals(200, response.getStatus());
    }
}
