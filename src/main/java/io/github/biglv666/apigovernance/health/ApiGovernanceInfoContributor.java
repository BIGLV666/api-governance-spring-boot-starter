package io.github.biglv666.apigovernance.health;

import io.github.biglv666.apigovernance.config.ApiGovernanceProperties;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * API 治理 Actuator Info 贡献器（0.8.0 新增）。
 *
 * <p>在 {@code /actuator/info} 暴露治理组件版本与关键模式，便于线上确认实际生效的
 * 限流 / 告警 / 追踪配置（yml 拼写错误、环境变量未注入时一眼可见）。
 *
 * @author API Governance Team
 * @since 0.8.0
 */
public class ApiGovernanceInfoContributor implements InfoContributor {

    private final ApiGovernanceProperties properties;

    /**
     * 构造 Info 贡献器。
     *
     * @param properties 全局配置
     */
    public ApiGovernanceInfoContributor(ApiGovernanceProperties properties) {
        this.properties = properties;
    }

    @Override
    public void contribute(Info.Builder builder) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("version", resolveVersion());
        info.put("enabled", properties.isEnabled());

        Map<String, Object> rateLimit = new LinkedHashMap<>();
        rateLimit.put("type", properties.getRateLimit().getType());
        rateLimit.put("algorithm", properties.getRateLimit().getAlgorithm());
        rateLimit.put("defaultLimit", properties.getRateLimit().getDefaultLimit());
        rateLimit.put("defaultWindow", properties.getRateLimit().getDefaultWindow());
        rateLimit.put("failStrategy", properties.getRateLimit().getFailStrategy());
        rateLimit.put("dynamicRules",
                properties.getRateLimit().getDynamicRules().isEnabled());
        info.put("rateLimit", rateLimit);

        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("enabled", properties.getAlert().isEnabled());
        alert.put("recoveryEnabled", properties.getAlert().isRecoveryEnabled());
        alert.put("clusterDedup", properties.getAlert().isClusterDedupEnabled());
        info.put("alert", alert);

        Map<String, Object> tracing = new LinkedHashMap<>();
        tracing.put("enabled", properties.getTracing().isEnabled());
        tracing.put("kafka", properties.getTracing().isKafka());
        tracing.put("rabbit", properties.getTracing().isRabbit());
        info.put("tracing", tracing);

        builder.withDetail("apiGovernance", info);
    }

    private String resolveVersion() {
        String version = ApiGovernanceInfoContributor.class.getPackage()
                .getImplementationVersion();
        return version == null ? "development" : version;
    }
}
