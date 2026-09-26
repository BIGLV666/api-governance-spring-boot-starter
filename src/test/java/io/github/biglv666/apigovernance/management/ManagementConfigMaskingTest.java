package io.github.biglv666.apigovernance.management;

import io.github.biglv666.apigovernance.config.ApiGovernanceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /config} 回显安全测试：webhook URL 的 query 参数（机器人 access_token）必须掩码。
 *
 * @author API Governance Team
 * @since 0.5.1
 */
class ManagementConfigMaskingTest {

    private static final String MASK = "******";

    @Test
    void webhookUrlQueryIsMaskedInConfigEndpoint() throws Exception {
        ApiGovernanceProperties properties = new ApiGovernanceProperties();
        properties.getAlert().getWebhook().setUrl(
                "https://oapi.dingtalk.com/robot/send?access_token=super-secret&tag=dev");

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new GovernanceManagementController(
                        properties, null, new io.github.biglv666.apigovernance.filter.FilterChain(
                        java.util.List.of(), java.util.List.of()),
                        new io.github.biglv666.apigovernance.metrics.MetricsRegistry(10, 10, 60_000L),
                        null, null))
                .build();

        mockMvc.perform(get("/api-governance/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alert.webhook.url").value(
                        "https://oapi.dingtalk.com/robot/send?" + MASK));
    }

    @Test
    void webhookUrlWithoutQueryIsReturnedAsIs() throws Exception {
        ApiGovernanceProperties properties = new ApiGovernanceProperties();
        properties.getAlert().getWebhook().setUrl("https://hooks.example.com/alert");

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new GovernanceManagementController(
                        properties, null, new io.github.biglv666.apigovernance.filter.FilterChain(
                        java.util.List.of(), java.util.List.of()),
                        new io.github.biglv666.apigovernance.metrics.MetricsRegistry(10, 10, 60_000L),
                        null, null))
                .build();

        mockMvc.perform(get("/api-governance/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alert.webhook.url").value("https://hooks.example.com/alert"));
    }
}
