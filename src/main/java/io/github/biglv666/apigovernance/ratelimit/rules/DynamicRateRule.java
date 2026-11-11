package io.github.biglv666.apigovernance.ratelimit.rules;

import java.util.Objects;

/**
 * 动态限流规则 —— 不可变的单条运行时规则（0.7.0 新增，0.8.0 增加 TTL 与操作元数据）。
 *
 * <p>规则通过管理接口在运行期提交（无需重启/重新发布），按 {@link #pattern} 匹配
 * API 标识（{@code 全限定类名#方法名}）后覆盖注解 / yml 解析出的限流参数。
 *
 * <h3>pattern 语义</h3>
 * <ul>
 *   <li>精确：{@code com.x.UserController#get} —— 只命中该标识；</li>
 *   <li>前缀通配：{@code com.x.UserController#*} / {@code com.x.*} —— 以 {@code *} 结尾，
 *       匹配所有以去掉 {@code *} 后的前缀开头的 API 标识（普通 {@code startsWith} 语义，
 *       不做单词边界约束：{@code com.x.User*} 会同时命中 {@code com.x.UserController#get}
 *       与 {@code com.x.UserV2Controller#get}，需要精确范围时请写到 {@code #} 或类名全称）。</li>
 * </ul>
 * <p>多条规则同时命中时：精确 &gt; 更长前缀。
 *
 * <h3>limit 语义（与注解一致）</h3>
 * <ul>
 *   <li>{@code -1}：显式不限流 —— 覆盖接口上已存在的 {@code @RateLimit} 注解；</li>
 *   <li>{@code 0}：封禁 —— 该接口全部请求被 429 拒绝；</li>
 *   <li>{@code > 0}：窗口内最多放行该数量。</li>
 * </ul>
 *
 * <h3>TTL 语义（0.8.0 新增）</h3>
 * <p>{@code expiresAtMs = 0} 表示永久有效；大于 0 时，请求热路径在 {@code now >= expiresAtMs}
 * 后不再命中该规则（惰性失效，无需后台线程）。临时封禁/临时收紧建议设置 TTL，
 * 避免事故结束后忘记解除。
 *
 * <h3>优先级</h3>
 * <p>动态规则 &gt; 方法 {@code @RateLimit} &gt; 类 {@code @RateLimit} &gt; 全局默认。
 * 规则只覆盖 {@code limit}/{@code window}，不影响 SpEL 参数维度键。
 *
 * @author API Governance Team
 * @since 0.7.0
 */
public final class DynamicRateRule {

    /** 操作人 / 原因字段最大长度，防止管理接口写入超长文本灌满存储。 */
    public static final int MAX_METADATA_LENGTH = 256;

    /** 规则匹配模式：精确 API 标识或以 * 结尾的前缀通配。 */
    private final String pattern;

    /** 限流阈值：-1 不限流 / 0 封禁 / >0 窗口内上限。 */
    private final int limit;

    /** 时间窗口（秒），至少 1。 */
    private final int window;

    /** 规则最近一次写入的时间戳（毫秒），用于审计展示。 */
    private final long updatedAtMs;

    /** 规则到期时间戳（毫秒）；0 表示永久有效（0.8.0 新增）。 */
    private final long expiresAtMs;

    /** 操作人（0.8.0 新增，可为空字符串）。 */
    private final String operator;

    /** 变更原因（0.8.0 新增，可为空字符串）。 */
    private final String reason;

    /**
     * 并发上限（1.0.0 新增）：{@code >0} 覆盖注解；{@code -1} 关闭并发限制；
     * {@code 0} 表示不干预（沿用注解 / 全局默认）。
     */
    private final int maxConcurrent;

    /**
     * 构造动态规则（参数已校验）。
     *
     * @param pattern       匹配模式
     * @param limit         限流阈值
     * @param window        时间窗口（秒）
     * @param updatedAtMs   写入时间戳（毫秒）
     * @param expiresAtMs   到期时间戳（毫秒，0 = 永久）
     * @param operator      操作人（可为 null）
     * @param reason        变更原因（可为 null）
     * @param maxConcurrent 并发上限（0 = 不干预）
     */
    private DynamicRateRule(String pattern, int limit, int window, long updatedAtMs,
                            long expiresAtMs, String operator, String reason,
                            int maxConcurrent) {
        this.pattern = pattern;
        this.limit = limit;
        this.window = window;
        this.updatedAtMs = updatedAtMs;
        this.expiresAtMs = expiresAtMs;
        this.operator = operator == null ? "" : operator;
        this.reason = reason == null ? "" : reason;
        this.maxConcurrent = maxConcurrent;
    }

    /**
     * 创建并校验一条永久有效的动态规则（0.7.0 兼容入口）。
     *
     * @param pattern 匹配模式（非空；{@code *} 只允许作为末字符出现）
     * @param limit   限流阈值（至少 -1）
     * @param window  时间窗口秒数（至少 1）
     * @return 不可变规则
     * @throws IllegalArgumentException 参数不合法时抛出（管理接口转换为 400 响应）
     */
    public static DynamicRateRule of(String pattern, int limit, int window) {
        return of(pattern, limit, window, 0L, null, null, 0);
    }

    /**
     * 创建并校验一条带 TTL 与元数据的动态规则（0.8.0 新增）。
     *
     * @param pattern    匹配模式（非空；{@code *} 只允许作为末字符出现）
     * @param limit      限流阈值（至少 -1）
     * @param window     时间窗口秒数（至少 1）
     * @param ttlSeconds 有效期秒数；{@code null} / {@code <= 0} 表示永久有效
     * @param operator   操作人（可空，最长 {@value #MAX_METADATA_LENGTH}）
     * @param reason     变更原因（可空，最长 {@value #MAX_METADATA_LENGTH}）
     * @return 不可变规则
     * @throws IllegalArgumentException 参数不合法时抛出（管理接口转换为 400 响应）
     */
    public static DynamicRateRule of(String pattern, int limit, int window,
                                     Long ttlSeconds, String operator, String reason) {
        return of(pattern, limit, window, ttlSeconds, operator, reason, 0);
    }

    /**
     * 创建并校验一条完整动态规则（1.0.0 新增，含并发上限）。
     *
     * @param pattern       匹配模式（非空；{@code *} 只允许作为末字符出现）
     * @param limit         限流阈值（至少 -1）
     * @param window        时间窗口秒数（至少 1）
     * @param ttlSeconds    有效期秒数；{@code null} / {@code <= 0} 表示永久有效
     * @param operator      操作人（可空，最长 {@value #MAX_METADATA_LENGTH}）
     * @param reason        变更原因（可空，最长 {@value #MAX_METADATA_LENGTH}）
     * @param maxConcurrent 并发上限（0 = 不干预，-1 = 关闭，&gt;0 = 上限）
     * @return 不可变规则
     * @throws IllegalArgumentException 参数不合法时抛出（管理接口转换为 400 响应）
     */
    public static DynamicRateRule of(String pattern, int limit, int window,
                                     Long ttlSeconds, String operator, String reason,
                                     Integer maxConcurrent) {
        String trimmed = validatePattern(pattern);
        validateLimit(limit);
        validateWindow(window);
        validateMaxConcurrent(maxConcurrent);
        long now = System.currentTimeMillis();
        long expiresAt = expiresAt(now, ttlSeconds);
        return new DynamicRateRule(trimmed, limit, window, now, expiresAt,
                validateMetadata(operator, "operator"), validateMetadata(reason, "reason"),
                maxConcurrent == null ? 0 : maxConcurrent);
    }

    /**
     * 从存储层还原规则（保留原写入时间戳，不重新计时）。
     * 仅包内使用：Redis 快照重放 / 测试。
     *
     * @param pattern     匹配模式（存储层已校验过）
     * @param limit       限流阈值
     * @param window      时间窗口（秒）
     * @param updatedAtMs 原写入时间戳（毫秒）
     * @return 不可变规则
     */
    static DynamicRateRule restore(String pattern, int limit, int window, long updatedAtMs) {
        return new DynamicRateRule(pattern, limit, window, updatedAtMs, 0L, "", "", 0);
    }

    /**
     * 从存储层还原完整规则（0.8.0 新增）。
     *
     * @param pattern     匹配模式（存储层已校验过）
     * @param limit       限流阈值
     * @param window      时间窗口（秒）
     * @param updatedAtMs 原写入时间戳（毫秒）
     * @param expiresAtMs 原到期时间戳（毫秒，0 = 永久）
     * @param operator    操作人
     * @param reason      变更原因
     * @return 不可变规则
     */
    static DynamicRateRule restore(String pattern, int limit, int window, long updatedAtMs,
                                   long expiresAtMs, String operator, String reason) {
        return new DynamicRateRule(pattern, limit, window, updatedAtMs, expiresAtMs,
                operator, reason, 0);
    }

    /**
     * 从存储层还原完整规则（1.0.0 新增，含并发上限）。
     *
     * @param pattern       匹配模式（存储层已校验过）
     * @param limit         限流阈值
     * @param window        时间窗口（秒）
     * @param updatedAtMs   原写入时间戳（毫秒）
     * @param expiresAtMs   原到期时间戳（毫秒，0 = 永久）
     * @param operator      操作人
     * @param reason        变更原因
     * @param maxConcurrent 并发上限（0 = 不干预）
     * @return 不可变规则
     */
    static DynamicRateRule restore(String pattern, int limit, int window, long updatedAtMs,
                                   long expiresAtMs, String operator, String reason,
                                   int maxConcurrent) {
        return new DynamicRateRule(pattern, limit, window, updatedAtMs, expiresAtMs,
                operator, reason, maxConcurrent);
    }

    /**
     * 规则是否已在给定时刻过期。
     *
     * @param nowMs 当前时间戳（毫秒）
     * @return true 表示 {@code expiresAtMs > 0} 且已到期
     */
    public boolean isExpired(long nowMs) {
        return expiresAtMs > 0 && nowMs >= expiresAtMs;
    }

    /**
     * 剩余有效秒数（向上取整）；永久规则返回 -1。
     *
     * @param nowMs 当前时间戳（毫秒）
     * @return 剩余秒数或 -1
     */
    public long getTtlSecondsRemaining(long nowMs) {
        if (expiresAtMs <= 0) {
            return -1L;
        }
        long remainingMs = expiresAtMs - nowMs;
        if (remainingMs <= 0) {
            return 0L;
        }
        return (remainingMs + 999L) / 1000L;
    }

    /** 匹配模式（已去除首尾空白）。 */
    public String getPattern() {
        return pattern;
    }

    /** 限流阈值：-1 不限流 / 0 封禁 / >0 窗口内上限。 */
    public int getLimit() {
        return limit;
    }

    /** 时间窗口（秒）。 */
    public int getWindow() {
        return window;
    }

    /** 规则最近一次写入的时间戳（毫秒）。 */
    public long getUpdatedAtMs() {
        return updatedAtMs;
    }

    /** 到期时间戳（毫秒）；0 表示永久有效（0.8.0 新增）。 */
    public long getExpiresAtMs() {
        return expiresAtMs;
    }

    /** 操作人（0.8.0 新增，非 null，可能为空字符串）。 */
    public String getOperator() {
        return operator;
    }

    /** 变更原因（0.8.0 新增，非 null，可能为空字符串）。 */
    public String getReason() {
        return reason;
    }

    /**
     * 并发上限（1.0.0 新增）。
     *
     * @return {@code >0} 并发上限；{@code -1} 关闭；{@code 0} 不干预注解/全局默认
     */
    public int getMaxConcurrent() {
        return maxConcurrent;
    }

    private static String validatePattern(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("规则 pattern 不能为空");
        }
        String trimmed = pattern.trim();
        int starIndex = trimmed.indexOf('*');
        if (starIndex >= 0 && starIndex != trimmed.length() - 1) {
            throw new IllegalArgumentException(
                    "规则 pattern 仅支持尾部 * 通配，不支持中间通配: '" + trimmed + "'");
        }
        if (trimmed.length() == 1 && starIndex == 0) {
            throw new IllegalArgumentException("规则 pattern 不能为单独的 *");
        }
        return trimmed;
    }

    private static void validateLimit(int limit) {
        if (limit < -1) {
            throw new IllegalArgumentException("规则 limit 至少为 -1（-1=不限流，0=封禁），收到: " + limit);
        }
    }

    private static void validateWindow(int window) {
        if (window < 1) {
            throw new IllegalArgumentException("规则 window 至少为 1 秒，收到: " + window);
        }
    }

    private static void validateMaxConcurrent(Integer maxConcurrent) {
        if (maxConcurrent != null && maxConcurrent < -1) {
            throw new IllegalArgumentException(
                    "规则 maxConcurrent 至少为 -1（-1=关闭，0=不干预），收到: " + maxConcurrent);
        }
    }

    private static String validateMetadata(String value, String field) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_METADATA_LENGTH) {
            throw new IllegalArgumentException("规则 " + field + " 最长 "
                    + MAX_METADATA_LENGTH + " 个字符，收到: " + trimmed.length());
        }
        return trimmed;
    }

    private static long expiresAt(long nowMs, Long ttlSeconds) {
        if (ttlSeconds == null || ttlSeconds <= 0) {
            return 0L;
        }
        long ttlMs = ttlSeconds > Long.MAX_VALUE / 1000L
                ? Long.MAX_VALUE : ttlSeconds * 1000L;
        long expiresAt = nowMs + ttlMs;
        return expiresAt < 0 ? Long.MAX_VALUE : expiresAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DynamicRateRule that)) {
            return false;
        }
        return limit == that.limit && window == that.window
                && maxConcurrent == that.maxConcurrent && pattern.equals(that.pattern);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pattern, limit, window, maxConcurrent);
    }

    @Override
    public String toString() {
        return "DynamicRateRule{pattern='" + pattern + "', limit=" + limit
                + ", window=" + window + ", updatedAt=" + updatedAtMs
                + ", expiresAt=" + expiresAtMs + ", operator='" + operator
                + "', reason='" + reason + "', maxConcurrent=" + maxConcurrent + '}';
    }
}
