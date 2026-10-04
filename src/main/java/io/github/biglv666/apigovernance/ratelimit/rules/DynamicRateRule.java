package io.github.biglv666.apigovernance.ratelimit.rules;

import java.util.Objects;

/**
 * 动态限流规则 —— 不可变的单条运行时规则（0.7.0 新增）。
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
 * <h3>优先级</h3>
 * <p>动态规则 &gt; 方法 {@code @RateLimit} &gt; 类 {@code @RateLimit} &gt; 全局默认。
 * 规则只覆盖 {@code limit}/{@code window}，不影响 SpEL 参数维度键。
 *
 * @author API Governance Team
 * @since 0.7.0
 */
public final class DynamicRateRule {

    /** 规则匹配模式：精确 API 标识或以 * 结尾的前缀通配。 */
    private final String pattern;

    /** 限流阈值：-1 不限流 / 0 封禁 / >0 窗口内上限。 */
    private final int limit;

    /** 时间窗口（秒），至少 1。 */
    private final int window;

    /** 规则最近一次写入的时间戳（毫秒），用于审计展示。 */
    private final long updatedAtMs;

    /**
     * 构造动态规则（参数已校验）。
     *
     * @param pattern     匹配模式
     * @param limit       限流阈值
     * @param window      时间窗口（秒）
     * @param updatedAtMs 写入时间戳（毫秒）
     */
    private DynamicRateRule(String pattern, int limit, int window, long updatedAtMs) {
        this.pattern = pattern;
        this.limit = limit;
        this.window = window;
        this.updatedAtMs = updatedAtMs;
    }

    /**
     * 创建并校验一条动态规则。
     *
     * @param pattern 匹配模式（非空；{@code *} 只允许作为末字符出现）
     * @param limit   限流阈值（至少 -1）
     * @param window  时间窗口秒数（至少 1）
     * @return 不可变规则
     * @throws IllegalArgumentException 参数不合法时抛出（管理接口转换为 400 响应）
     */
    public static DynamicRateRule of(String pattern, int limit, int window) {
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
        if (limit < -1) {
            throw new IllegalArgumentException("规则 limit 至少为 -1（-1=不限流，0=封禁），收到: " + limit);
        }
        if (window < 1) {
            throw new IllegalArgumentException("规则 window 至少为 1 秒，收到: " + window);
        }
        return new DynamicRateRule(trimmed, limit, window, System.currentTimeMillis());
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
        return new DynamicRateRule(pattern, limit, window, updatedAtMs);
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

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DynamicRateRule that)) {
            return false;
        }
        return limit == that.limit && window == that.window && pattern.equals(that.pattern);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pattern, limit, window);
    }

    @Override
    public String toString() {
        return "DynamicRateRule{pattern='" + pattern + "', limit=" + limit
                + ", window=" + window + ", updatedAt=" + updatedAtMs + "}";
    }
}
