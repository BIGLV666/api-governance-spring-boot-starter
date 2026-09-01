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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 治理范围配置端到端测试：{@code exclude-packages} 命中的包完全放行（不进过滤器链、不产生指标），
 * 未命中的包照常治理。
 *
 * @author API Governance Team
 * @since 0.4.0
 */
@SpringBootTest(properties = {
        "api.governance.management.enabled=false",
        "api.governance.metrics.micrometer-enabled=false",
        "api.governance.exclude-packages=io.github.biglv666.apigovernance.aspect.excluded"
})
@AutoConfigureMockMvc
class GovernanceScopeIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void excludedPackageBypassesGovernance() throws Exception {
        CapturingGovernanceFilter.last = null;
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/gov-excluded/ping"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("pong"));
        }
        // 被排除包的请求从未进入过滤器链
        FilterContext last = CapturingGovernanceFilter.last;
        assertThat(last == null || !"/gov-excluded/ping".equals(last.getRequestUri())).isTrue();
    }

    @Test
    void includedPackageStillGoverned() throws Exception {
        CapturingGovernanceFilter.last = null;
        mockMvc.perform(get("/gov-test/ip")).andExpect(status().isOk());
        // 未被排除的包照常进入过滤器链
        assertThat(CapturingGovernanceFilter.last).isNotNull();
        assertThat(CapturingGovernanceFilter.last.getRequestUri()).isEqualTo("/gov-test/ip");
    }
}
