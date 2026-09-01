package io.github.biglv666.apigovernance.aspect;

import io.github.biglv666.apigovernance.filter.FilterContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 治理切面端到端测试 —— 走真实 Spring MVC + AOP 代理，验证
 * 「切面 → 过滤器链 → 拒绝异常 → 全局处理器」整条链路与 HTTP 上下文注入。
 *
 * @author API Governance Team
 * @since 0.3.0
 */
@SpringBootTest(properties = {
        // 测试容器不暴露管理接口，减少干扰
        "api.governance.management.enabled=false",
        "api.governance.metrics.micrometer-enabled=false"
})
@AutoConfigureMockMvc
class GovernanceAspectIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rateLimitedEndpointReturns429AfterThreshold() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get("/gov-test/limited"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("ok"));
        }
        // 第 3 次超限：前置链短路，返回 yml 默认状态码 429 与提示语
        mockMvc.perform(get("/gov-test/limited"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void skippedEndpointBypassesGovernance() throws Exception {
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(get("/gov-test/skip"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("skipped"));
        }
    }

    @Test
    void errorEndpointStillRunsPostFilters() throws Exception {
        mockMvc.perform(get("/gov-test/boom"))
                .andExpect(status().isBadRequest());
        // 业务异常不阻断后置链：上下文应记录异常
        FilterContext last = CapturingGovernanceFilter.last;
        assertThat(last).isNotNull();
        assertThat(last.getApiKey()).endsWith("GovernanceTestController#boom");
        assertThat(last.getError()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void filterContextCarriesRealHttpRequestInfo() throws Exception {
        CapturingGovernanceFilter.last = null;
        mockMvc.perform(get("/gov-test/ip")
                        .header("X-Forwarded-For", "203.0.113.9, 70.41.3.18"))
                .andExpect(status().isOk());
        FilterContext last = CapturingGovernanceFilter.last;
        assertThat(last).isNotNull();
        // 真实请求 URI 优先于注解推导的路径模式
        assertThat(last.getRequestUri()).isEqualTo("/gov-test/ip");
        assertThat(last.getPath()).isEqualTo("/gov-test/ip");
        assertThat(last.getHttpMethod()).isEqualTo("GET");
        // X-Forwarded-For 第一个地址为最初客户端
        assertThat(last.getClientIp()).isEqualTo("203.0.113.9");
    }
}
