package io.github.biglv666.apigovernance.management;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.biglv666.apigovernance.async.internal.AsyncHandlerRegistry;
import io.github.biglv666.apigovernance.async.internal.DefaultAsyncExecutorProvider;
import io.github.biglv666.apigovernance.config.ApiGovernanceProperties;
import io.github.biglv666.apigovernance.filter.FilterChain;
import io.github.biglv666.apigovernance.metrics.MetricsRegistry;
import io.github.biglv666.apigovernance.ratelimit.RateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理接口控制器测试 —— 覆盖全部端点与敏感配置掩码。
 *
 * <p>重点回归 {@code /config} 不再回显明文令牌（0.2.0 的敏感信息泄露点）。
 *
 * @author API Governance Team
 * @since 0.3.0
 */
class GovernanceManagementControllerTest {

    private static final String MASK = "******";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MetricsRegistry metricsRegistry;

    private RecordingRateLimiter rateLimiter;

    private MockMvc mockMvc;

    /** 记录调用参数的假限流器，验证管理接口对限流器的委托。 */
    static final class RecordingRateLimiter implements RateLimiter {
        long currentCount = 3;
        String lastResetKey;
        int resetAllCalls;

        @Override
        public boolean tryAcquire(String key, int limit, int windowSeconds) {
            return true;
        }

        @Override
        public String getName() {
            return "recording";
        }

        @Override
        public long getCurrentCount(String key) {
            return currentCount;
        }

        @Override
        public void reset(String key) {
            lastResetKey = key;
        }

        @Override
        public void resetAll() {
            resetAllCalls++;
        }
    }

    @BeforeEach
    void setUp() {
        ApiGovernanceProperties properties = new ApiGovernanceProperties();
        properties.getManagement().setAuthToken("super-secret-token");
        properties.getAlert().getWebhook().setEnabled(true);
        properties.getAlert().getWebhook().setSecretToken("webhook-secret");
        properties.getAlert().getWebhook().setSignSecret("sign-secret");
        properties.getAlert().getWebhook().setPlatform("dingtalk");
        properties.getAlert().getWebhook().setUrl("https://example.com/hook");

        metricsRegistry = new MetricsRegistry(100, 100, 300_000L);
        rateLimiter = new RecordingRateLimiter();
        FilterChain filterChain = new FilterChain(List.of(), List.of());
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new GovernanceManagementController(properties, rateLimiter, filterChain,
                                metricsRegistry, null, null))
                .build();
    }

    @Test
    void configMasksSensitiveTokens() throws Exception {
        mockMvc.perform(get("/api-governance/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.management.authToken").value(MASK))
                .andExpect(jsonPath("$.alert.webhook.secretToken").value(MASK))
                .andExpect(jsonPath("$.alert.webhook.signSecret").value(MASK))
                // 非敏感字段原样输出
                .andExpect(jsonPath("$.alert.webhook.platform").value("dingtalk"))
                .andExpect(jsonPath("$.alert.webhook.url").value("https://example.com/hook"))
                .andExpect(jsonPath("$.rateLimit.type").value("local"));
    }

    @Test
    void statusExposesGovernanceOverview() throws Exception {
        mockMvc.perform(get("/api-governance/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.rateLimiter").value("recording"))
                .andExpect(jsonPath("$.trackedApis").value(0));
    }

    @Test
    void filtersEndpointDescribesChains() throws Exception {
        String body = mockMvc.perform(get("/api-governance/filters"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(body);
        assertThat(node.get("pre").isArray()).isTrue();
        assertThat(node.get("post").isArray()).isTrue();
    }

    @Test
    void rateLimiterCountDelegatesToLimiter() throws Exception {
        mockMvc.perform(get("/api-governance/rate-limiter/count").param("key", "com.x.A#get"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.rateLimiter").value("recording"));
    }

    @Test
    void rateLimiterResetDelegatesToLimiter() throws Exception {
        mockMvc.perform(post("/api-governance/rate-limiter/reset").param("key", "com.x.A#get"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        assertThat(rateLimiter.lastResetKey).isEqualTo("com.x.A#get");
    }

    @Test
    void rateLimiterResetAllDelegatesToLimiter() throws Exception {
        mockMvc.perform(post("/api-governance/rate-limiter/reset-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        assertThat(rateLimiter.resetAllCalls).isEqualTo(1);
    }

    @Test
    void metricsEndpointsRoundTrip() throws Exception {
        // total 由「请求进入」事件计数，success/slow 由「请求完成」事件计数（与真实管道一致）
        metricsRegistry.recordStart("com.x.A#get");
        metricsRegistry.recordResult("com.x.A#get", 1500, true, true, "GET", "/api/a", null);

        mockMvc.perform(get("/api-governance/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].apiKey").value("com.x.A#get"))
                .andExpect(jsonPath("$[0].total").value(1))
                .andExpect(jsonPath("$[0].success").value(1));

        mockMvc.perform(get("/api-governance/metrics/detail").param("key", "com.x.A#get"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.recentRecords").isArray());

        mockMvc.perform(get("/api-governance/metrics/slow").param("key", "com.x.A#get"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true));

        mockMvc.perform(get("/api-governance/metrics/slow/all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalApis").value(1));

        mockMvc.perform(delete("/api-governance/metrics/single").param("key", "com.x.A#get"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(delete("/api-governance/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(metricsRegistry.size()).isZero();
    }

    @Test
    void metricsDetailReturnsNotFoundForUnknownKey() throws Exception {
        mockMvc.perform(get("/api-governance/metrics/detail").param("key", "nope"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(false));
    }

    @Test
    void asyncEndpointsWhenPluginDisabled() throws Exception {
        // 异步插件关闭时（注册表为 null）：返回 enabled=false 而非报错
        mockMvc.perform(get("/api-governance/async/handlers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.count").value(0));

        mockMvc.perform(get("/api-governance/async/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void asyncEndpointsExposeRegistryAndPool() throws Exception {
        AnnotationConfigApplicationContext fixture = new AnnotationConfigApplicationContext(AsyncFixtureConfig.class);
        try {
            AsyncHandlerRegistry registry = new AsyncHandlerRegistry(fixture.getBeanFactory(), false);
            registry.afterSingletonsInstantiated();
            DefaultAsyncExecutorProvider provider =
                    new DefaultAsyncExecutorProvider(new ApiGovernanceProperties().getAsync());
            try {
                MockMvc asyncMockMvc = MockMvcBuilders.standaloneSetup(
                                new GovernanceManagementController(new ApiGovernanceProperties(), rateLimiter,
                                        new FilterChain(List.of(), List.of()), metricsRegistry, registry, provider))
                        .build();

                asyncMockMvc.perform(get("/api-governance/async/handlers"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.enabled").value(true))
                        .andExpect(jsonPath("$.count").value(1))
                        .andExpect(jsonPath("$.handlers[0].action").value("user.login"))
                        .andExpect(jsonPath("$.handlers[0].phase").value("AFTER_SUCCESS"))
                        .andExpect(jsonPath("$.handlers[0].beanName").value("asyncFixtureBean"));

                asyncMockMvc.perform(get("/api-governance/async/status"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.enabled").value(true))
                        .andExpect(jsonPath("$.registeredHandlers").value(1))
                        .andExpect(jsonPath("$.executorType").value("thread-pool"))
                        .andExpect(jsonPath("$.corePoolSize").value(2))
                        .andExpect(jsonPath("$.queueCapacity").value(1000));
            } finally {
                provider.destroy();
            }
        } finally {
            fixture.close();
        }
    }

    /** 异步注册表测试固件：一个 Bean 同时声明 action 与匹配的 handler。 */
    static class AsyncFixtureConfig {

        @org.springframework.context.annotation.Bean
        AsyncFixtureBean asyncFixtureBean() {
            return new AsyncFixtureBean();
        }
    }

    static class AsyncFixtureBean {

        @io.github.biglv666.apigovernance.async.annotation.AsyncAction("user.login")
        public void doLogin() {
        }

        @io.github.biglv666.apigovernance.async.annotation.AsyncHandler(
                value = "user.login", phase = io.github.biglv666.apigovernance.async.event.AsyncPhase.AFTER_SUCCESS)
        public void onLogin() {
        }
    }

    @Test
    void metricsPaginationIsDeterministic() throws Exception {
        for (int i = 1; i <= 3; i++) {
            metricsRegistry.recordStart("com.x.Api" + i + "#x");
        }
        // 0.4.0 起支持 page/size 分页
        mockMvc.perform(get("/api-governance/metrics").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items.length()").value(2));

        mockMvc.perform(get("/api-governance/metrics").param("page", "2").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));

        // 越界页返回空列表而非报错
        mockMvc.perform(get("/api-governance/metrics").param("page", "99").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));

        // 不传参数时保持 0.3.0 行为：全量数组
        mockMvc.perform(get("/api-governance/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void mutationsCanBeDisabledByProperty() throws Exception {
        ApiGovernanceProperties disabled = new ApiGovernanceProperties();
        disabled.getManagement().setMutationsEnabled(false);
        MetricsRegistry freshRegistry = new MetricsRegistry(100, 100, 300_000L);
        freshRegistry.recordStart("com.x.A#get");
        MockMvc noMutationMockMvc = MockMvcBuilders.standaloneSetup(
                        new GovernanceManagementController(disabled, rateLimiter,
                                new FilterChain(List.of(), List.of()), freshRegistry, null, null))
                .build();

        noMutationMockMvc.perform(post("/api-governance/rate-limiter/reset").param("key", "k"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(
                        "管理接口写操作已禁用 (api.governance.management.mutations-enabled=false)"));
        noMutationMockMvc.perform(post("/api-governance/rate-limiter/reset-all"))
                .andExpect(jsonPath("$.success").value(false));
        noMutationMockMvc.perform(delete("/api-governance/metrics"))
                .andExpect(jsonPath("$.success").value(false));
        noMutationMockMvc.perform(delete("/api-governance/metrics/single").param("key", "k"))
                .andExpect(jsonPath("$.success").value(false));

        // 写操作被禁用后，指标原样保留，只读端点不受影响
        assertThat(freshRegistry.size()).isEqualTo(1);
        noMutationMockMvc.perform(get("/api-governance/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }
}
