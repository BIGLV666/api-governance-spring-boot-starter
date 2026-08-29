package io.github.biglv666.apigovernance.alert.webhook;

import io.github.biglv666.apigovernance.alert.GovernanceAlertEvent;
import io.github.biglv666.apigovernance.alert.GovernanceAlertNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * Webhook 告警通知器 —— 将告警事件以 JSON POST 到指定地址，
 * 可直接对接钉钉 / 企业微信 / 飞书机器人的自定义 webhook（或经一层网关适配）。
 *
 * <h3>实现说明</h3>
 * <ul>
 *   <li>基于 JDK 17 {@link HttpClient}，<b>零新增第三方依赖</b>；</li>
 *   <li>{@link #notify} 使用 {@code sendAsync} 异步发送，立即返回，<b>不阻塞请求线程</b>；
 *       发送结果在回调线程中记录日志（失败仅 warn，不重试）；</li>
 *   <li>JSON 手工构建并做严格转义（引号、反斜杠、控制字符），事件字段均为框架元数据，
 *       不含用户输入，仍按防注入标准处理；</li>
 *   <li>可选 {@code secretToken} 以 {@code X-Governance-Token} 请求头携带，供接收端校验来源；
 *       令牌不写入任何日志。</li>
 * </ul>
 *
 * @author API Governance Team
 * @since 0.2.0
 */
public class WebhookAlertNotifier implements GovernanceAlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookAlertNotifier.class);

    /** 鉴权令牌请求头名称。 */
    public static final String TOKEN_HEADER = "X-Governance-Token";

    private final String url;
    private final String secretToken;
    private final long requestTimeoutMs;
    private final HttpClient httpClient;

    /**
     * 构造 Webhook 通知器。
     *
     * @param url         webhook 目标地址（非空）
     * @param timeoutMs   单次请求超时（毫秒）
     * @param secretToken 可选鉴权令牌（空字符串表示不携带）
     */
    public WebhookAlertNotifier(String url, long timeoutMs, String secretToken) {
        this.url = url;
        this.secretToken = secretToken == null ? "" : secretToken;
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
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(requestTimeoutMs))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(toJson(event)));
            if (!secretToken.isEmpty()) {
                builder.header(TOKEN_HEADER, secretToken);
            }
            httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                    .whenComplete(this::logIfFailed);
        } catch (Exception e) {
            // URL 非法等构建期错误：只影响告警，不影响业务
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

    /**
     * 构建告警事件 JSON（字段均为元数据，值经严格转义）。
     */
    private String toJson(GovernanceAlertEvent event) {
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
