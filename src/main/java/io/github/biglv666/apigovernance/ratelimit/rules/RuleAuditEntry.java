package io.github.biglv666.apigovernance.ratelimit.rules;

/**
 * 一条动态规则变更审计记录（0.8.0 新增）。
 *
 * <p>记录管理接口对规则的成功与失败变更（PUT / DELETE / IMPORT / REPLACE）以及
 * 启动种子加载（SEED）。变更前后值用可空 {@link Integer} 表示：
 * 新增时 {@code before*} 为 null，删除时 {@code after*} 为 null。
 *
 * <p>类名与方法名的 {@code apiKey} 不单独记录，{@code pattern} 即规则匹配模式；
 * 精确规则的 pattern 就是 apiKey。
 *
 * @param timestampMs  发生时间戳（毫秒）
 * @param action       动作类型
 * @param pattern      规则 pattern
 * @param beforeLimit  变更前 limit（可空）
 * @param beforeWindow 变更前 window（可空）
 * @param afterLimit   变更后 limit（可空）
 * @param afterWindow  变更后 window（可空）
 * @param beforeMaxConcurrent 变更前并发上限（可空，1.0.0 新增）
 * @param afterMaxConcurrent  变更后并发上限（可空，1.0.0 新增）
 * @param expiresAtMs  变更后到期时间（毫秒，0 = 永久）
 * @param operator     操作人
 * @param clientIp     来源 IP（非 HTTP 调用时为空串）
 * @param success      是否成功
 * @param message      失败原因或补充说明
 * @author API Governance Team
 * @since 0.8.0
 */
public record RuleAuditEntry(long timestampMs, Action action, String pattern,
                            Integer beforeLimit, Integer beforeWindow,
                            Integer afterLimit, Integer afterWindow,
                            Integer beforeMaxConcurrent, Integer afterMaxConcurrent,
                            long expiresAtMs, String operator, String clientIp,
                            boolean success, String message) {

    /** 审计动作类型。 */
    public enum Action {
        /** 管理接口新增/覆盖单条规则。 */
        PUT,
        /** 管理接口删除单条规则。 */
        DELETE,
        /** 管理接口批量导入。 */
        IMPORT,
        /** 管理接口整体替换。 */
        REPLACE,
        /** 启动时从种子文件加载。 */
        SEED
    }
}
