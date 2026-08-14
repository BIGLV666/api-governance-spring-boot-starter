package org.example.apigovernancespringbootstarter.management;

/**
 * 限流器状态 DTO（供管理接口返回）。
 *
 * @author API Governance Team
 * @since 1.0
 */
public class RateLimiterStatus {

    /** 限流器名称。 */
    private String name;

    /** 限流器类型（local/redis/custom）。 */
    private String type;

    /** 限流算法（token-bucket/sliding-window/custom）。 */
    private String algorithm;

    /** 当前管理的限流键数量（本机限流器支持；Redis 返回 -1）。 */
    private long managedKeys = -1;

    /** 是否已装配限流器。 */
    private boolean enabled;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(String algorithm) {
        this.algorithm = algorithm;
    }

    public long getManagedKeys() {
        return managedKeys;
    }

    public void setManagedKeys(long managedKeys) {
        this.managedKeys = managedKeys;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
