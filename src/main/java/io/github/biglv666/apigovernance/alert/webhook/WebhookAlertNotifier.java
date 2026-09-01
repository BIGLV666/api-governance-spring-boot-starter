package io.github.biglv666.apigovernance.alert.webhook;

import io.github.biglv666.apigovernance.alert.GovernanceAlertEvent;
import io.github.biglv666.apigovernance.alert.GovernanceAlertNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Webhook 告警通知器 —— 将告警事件 POST 到指定地址，按 {@code platform} 适配
 * 钉钉 / 企业微信 / 飞书机器人的原生消息格式（或任意接收方的 generic JSON 格式）。
 *
 * <h3>平台消息格式</h3>
 * <ul>
 *   <li>{@code generic}（默认）：框架自有 JSON 格式，字段为完整事件元数据；</li>
 *   <li>{@code dingtalk} / {@code wecom}：{@code {"msgtype":"text","text":{"content":"..."}}}；</li>
 *   <li>{@code feishu}：{@code {"msg_type":"text","content":{"text":"..."}}}。</li>
 * </ul>
 *
 * <h3>钉钉加签</h3>
 * <p>{@code platform=dingtalk} 且配置 {@code signSecret} 时，按钉钉「加签」安全设置，
 * 在 URL 上追加 {@code &timestamp=...&sign=...}：{@code sign = Base64(HmacSHA256(timestamp+"\n"+secret, secret))}
 * 并做 URL 编码。密钥建议通过环境变量注入，不写入配置文件与日志。
 *
 * <h3>实现说明</h3>
 * <ul>
 *   <li>基于 JDK 17 {@link HttpClient}，<b>零新增第三方依赖</b>；</li>
 *   <li>{@link #notify} 使用 {@code sendAsync} 异步发送，立即返回，<b>不阻塞请求线程</b>；
 *       发送结果在回调线程中记录日志（失败仅 warn，不重试）；</li>
 *   <li>JSON 手工构建并做严格转义（引号、反斜杠、控制字符），事件字段均为框架元数据，
 *       不含用户输入，仍按防注入标准处理；</li>
 *   <li>可选 {@code secretToken} 以 {@code X-Governance-Token} 请求头携带，供接收端校验来源。</li>
 * </ul>
 *
 * @author API Governance Team
 * @since 0.2.0
 */
public class WebhookAlertNotifier implements GovernanceAlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookAlertNotifier.class);

    /** 鉴权令牌请求头名称。 */
    public static final String TOKEN_HEADER = "X-Governance-Token";

    /** 通知平台：框架自有 JSON 格式（默认）。 */
    public static final String PLATFORM_GENERIC = "generic";

    /** 通知平台：钉钉机器人。 */
    public static final String PLATFORM_DINGTALK = "dingtalk";

    /** 通知平台：企业微信机器人。 */
    public static final String PLATFORM_WECOM = "wecom";

    /** 通知平台：飞书机器人。 */
    public static final String PLATFORM_FEISHU = "feishu";

    private final String url;
    private final String secretToken;
    private final String platform;
    private final String signSecret;
    private final long requestTimeoutMs;
    private final HttpClient httpClient;

    /**
     * 构造 Webhook 通知器（generic 平台，等价于五参构造的兼容入口）。
     *
     * @param url         webhook 目标地址（非空）
     * @param timeoutMs   单次请求超时（毫秒）
     * @param secretToken 可选鉴权令牌（空字符串表示不携带）
     */
    public WebhookAlertNotifier(String url, long timeoutMs, String secretToken) {
        this(url, timeoutMs, secretToken, PLATFORM_GENERIC, "");
    }

    /**
     * 构造 Webhook 通知器。
     *
     * @param url         webhook 目标地址（非空）
     * @param timeoutMs   单次请求超时（毫秒）
     * @param secretToken 可选鉴权令牌（空字符串表示不携带）
     * @param platform    通知平台：generic / dingtalk / wecom / feishu（其他值抛出异常，快速失败）
     * @param signSecret  可选加签密钥（仅 dingtalk 生效）
     */
    public WebhookAlertNotifier(String url, long timeoutMs, String secretToken,
                                String platform, String signSecret) {
        this.url = url;
        this.secretToken = secretToken == null ? "" : secretToken;
        this.platform = platform == null ? PLATFORM_GENERIC : platform.trim().toLowerCase(Locale.ROOT);
        // 未知平台快速失败：告警通道静默降级为 generic 会让用户误以为已对接成功
        if (!PLATFORM_GENERIC.equals(this.platform) && !PLATFORM_DINGTALK.equals(this.platform)
                && !PLATFORM_WECOM.equals(this.platform) && !PLATFORM_FEISHU.equals(this.platform)) {
            throw new IllegalStateException(
                    "未知的 webhook 通知平台: '" + platform + "'，支持 generic / dingtalk / wecom / feishu");
        }
        this.signSecret = signSecret == null ? "" : signSecret;
        this.requestTimeoutMs = Math.max(100, timeoutMs);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(requestTimeoutMs))
                .build();
    }

    /**
     * 异步推送一条告警事件。本方法立即返回；发送失败仅记录 warn 日志，不抛出异常。
     *
     * @param event 告警事件
     */
    @Override
    public void notify(GovernanceAlertEvent event) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(buildUrl(System.currentTimeMillis())))
                    .timeout(Duration.ofMillis(requestTimeoutMs))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(buildPayload(event)));
            if (!secretToken.isEmpty()) {
                builder.header(TOKEN_HEADER, secretToken);
            }
            httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                    .whenComplete(this::logIfFailed);
        } catch (Exception e) {
            // URL 非法、加签失败等构建期错误：只影响告警，不影响业务
            log.warn("Webhook 告警构建失败 - 错误: {}", e.getMessage());
        }
    }

    /**
     * 异步回调：非 2xx 响应或网络异常统一记 warn（不重试，避免告警风暴放大）。
     */
    private void logIfFailed(HttpResponse<String> response, Throwable error) {
        if (error != null) {
            log.warn("Webhook 告警发送失败 - url: {}, 错误: {}", url, error.getMessage());
        } else if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.warn("Webhook 告警接收方返回非 2xx - url: {}, status: {}", url, response.statusCode());
        }
    }

    @Override
    public String getName() {
        return "webhook";
    }

    /** 通知平台（归一化小写）。 */
    public String getPlatform() {
        return platform;
    }

    /**
     * 构建最终请求 URL：钉钉加签时追加 {@code timestamp} 与 {@code sign} 参数，其余平台原样返回。
     *
     * @param timestampMs 当前时间戳（毫秒），抽取为参数便于测试签名确定性
     * @return 最终请求 URL
     */
    String buildUrl(long timestampMs) {
        if (!PLATFORM_DINGTALK.equals(platform) || signSecret.isEmpty()) {
            return url;
        }
        // 钉钉加签规范：sign = Base64(HmacSHA256(secret, timestamp + "\n" + secret))，再做 URL 编码
        String stringToSign = timestampMs + "\n" + signSecret;
        String sign = Base64.getEncoder().encodeToString(hmacSha256(stringToSign, signSecret));
        String encoded = URLEncoder.encode(sign, StandardCharsets.UTF_8);
        return url + (url.contains("?") ? "&" : "?") + "timestamp=" + timestampMs + "&sign=" + encoded;
    }

    /**
     * 按平台构建请求体 JSON。
     *
     * @param event 告警事件
     * @return JSON 请求体
     */
    String buildPayload(GovernanceAlertEvent event) {
        return switch (platform) {
            case PLATFORM_DINGTALK, PLATFORM_WECOM ->
                    "{\"msgtype\":\"text\",\"text\":{\"content\":\"" + escape(toPlainText(event)) + "\"}}";
            case PLATFORM_FEISHU ->
                    "{\"msg_type\":\"text\",\"content\":{\"text\":\"" + escape(toPlainText(event)) + "\"}}";
            default -> toGenericJson(event);
        };
    }

    /**
     * 机器人文案（钉钉/企微/飞书 text 消息共用的纯文本内容）。
     */
    private String toPlainText(GovernanceAlertEvent event) {
        StringBuilder sb = new StringBuilder(128);
        sb.append("【API治理告警】").append(event.getType().name()).append('\n');
        sb.append(event.getMessage());
        if (event.getApiKey() != null) {
            sb.append("\nAPI: ").append(event.getApiKey());
        }
        if (event.getHttpMethod() != null || event.getPath() != null) {
            sb.append("\n请求: ").append(event.getHttpMethod() == null ? "-" : event.getHttpMethod())
                    .append(' ').append(event.getPath() == null ? "-" : event.getPath());
        }
        if (event.getElapsedMs() >= 0) {
            sb.append("\n耗时: ").append(event.getElapsedMs()).append("ms (阈值 ")
                    .append(event.getThresholdMs()).append("ms)");
        }
        sb.append("\n时间: ").append(Instant.ofEpochMilli(event.getTimestampMs()));
        return sb.toString();
    }

    /**
     * generic 平台的完整事件元数据 JSON（0.2.0 起的原始格式，保持兼容）。
     */
    private String toGenericJson(GovernanceAlertEvent event) {
        return "{"
                + "\"type\":\"" + escape(event.getType().name()) + "\","
                + "\"apiKey\":\"" + escape(event.getApiKey()) + "\","
                + "\"httpMethod\":" + quotedOrNull(event.getHttpMethod()) + ","
                + "\"path\":" + quotedOrNull(event.getPath()) + ","
                + "\"message\":\"" + escape(event.getMessage()) + "\","
                + "\"elapsedMs\":" + event.getElapsedMs() + ","
                + "\"thresholdMs\":" + event.getThresholdMs() + ","
                + "\"timestampMs\":" + event.getTimestampMs() + ","
                + "\"timestamp\":\"" + escape(Instant.ofEpochMilli(event.getTimestampMs()).toString()) + "\""
                + "}";
    }

    /**
     * 计算 HMAC-SHA256 签名字节。
     *
     * @param data   待签名内容
     * @param secret 密钥
     * @return 签名字节
     */
    private byte[] hmacSha256(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // JVM 必带 HmacSHA256，此处仅为编译期受检异常兜底
            throw new IllegalStateException("计算钉钉加签失败", e);
        }
    }

    /**
     * 可空字符串字段：null 输出 JSON null，非空输出带引号转义字符串。
     */
    private String quotedOrNull(String value) {
        return value == null ? "null" : "\"" + escape(value) + "\"";
    }

    /**
     * JSON 字符串转义：反斜杠、双引号及全部 ASCII 控制字符（<0x20）转 \\uXXXX 形式。
     */
    private String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
