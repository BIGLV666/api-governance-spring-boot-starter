package io.github.biglv666.apigovernance.alert.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.biglv666.apigovernance.alert.GovernanceAlertEvent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Webhook 告警通知器测试 —— 验证各平台消息格式、钉钉加签与 JSON 转义合法性。
 *
 * @author API Governance Team
 * @since 0.3.0
 */
class WebhookAlertNotifierTest {

    private static final String URL = "https://example.com/hook";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private GovernanceAlertEvent slowEvent() {
        return GovernanceAlertEvent.slowMethod("com.x.UserController#get", "GET", "/api/users/1",
                1500, 1000);
    }

    @Test
    void genericPayloadKeepsOriginalFormat() throws Exception {
        WebhookAlertNotifier notifier = new WebhookAlertNotifier(URL, 3000, "");
        JsonNode payload = objectMapper.readTree(notifier.buildPayload(slowEvent()));

        assertThat(payload.get("type").asText()).isEqualTo("SLOW_METHOD");
        assertThat(payload.get("apiKey").asText()).isEqualTo("com.x.UserController#get");
        assertThat(payload.get("httpMethod").asText()).isEqualTo("GET");
        assertThat(payload.get("path").asText()).isEqualTo("/api/users/1");
        assertThat(payload.get("elapsedMs").asLong()).isEqualTo(1500);
        assertThat(payload.get("thresholdMs").asLong()).isEqualTo(1000);
    }

    @Test
    void dingtalkAndWecomUseTextMessageType() throws Exception {
        for (String platform : new String[]{"dingtalk", "wecom"}) {
            WebhookAlertNotifier notifier = new WebhookAlertNotifier(URL, 3000, "", platform, "");
            JsonNode payload = objectMapper.readTree(notifier.buildPayload(slowEvent()));
            assertThat(payload.get("msgtype").asText()).isEqualTo("text");
            String content = payload.get("text").get("content").asText();
            assertThat(content).contains("SLOW_METHOD").contains("com.x.UserController#get");
        }
    }

    @Test
    void feishuUsesMsgTypeField() throws Exception {
        WebhookAlertNotifier notifier = new WebhookAlertNotifier(URL, 3000, "", "feishu", "");
        JsonNode payload = objectMapper.readTree(notifier.buildPayload(slowEvent()));
        assertThat(payload.get("msg_type").asText()).isEqualTo("text");
        assertThat(payload.get("content").get("text").asText()).contains("SLOW_METHOD");
    }

    @Test
    void unknownPlatformFailsFast() {
        assertThatThrownBy(() -> new WebhookAlertNotifier(URL, 3000, "", "slack", ""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void dingtalkSignIsDeterministicAndMatchesSpec() throws Exception {
        String secret = "secret-key";
        WebhookAlertNotifier notifier = new WebhookAlertNotifier(URL, 3000, "", "dingtalk", secret);
        long timestamp = 1700000000000L;

        String signedUrl = notifier.buildUrl(timestamp);
        assertThat(signedUrl).startsWith(URL + "?timestamp=" + timestamp + "&sign=");

        // 独立按钉钉规范重算签名比对：Base64(HmacSHA256(timestamp + "\n" + secret, secret))
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] expected = mac.doFinal((timestamp + "\n" + secret).getBytes(StandardCharsets.UTF_8));
        String expectedBase64 = Base64.getEncoder().encodeToString(expected);
        String encodedExpected = java.net.URLEncoder.encode(expectedBase64, StandardCharsets.UTF_8);
        assertThat(signedUrl).endsWith("&sign=" + encodedExpected);

        // 同一时间戳重复构建结果一致（确定性）
        assertThat(notifier.buildUrl(timestamp)).isEqualTo(signedUrl);
    }

    @Test
    void dingtalkWithoutSecretKeepsUrlUntouched() {
        WebhookAlertNotifier notifier = new WebhookAlertNotifier(URL, 3000, "", "dingtalk", "");
        assertThat(notifier.buildUrl(1700000000000L)).isEqualTo(URL);
    }

    @Test
    void nonGenericPayloadEscapesSpecialCharacters() throws Exception {
        // 消息含引号、换行、反斜杠时必须仍是合法 JSON
        GovernanceAlertEvent event = GovernanceAlertEvent.rateLimitReject(
                "com.x.A#b\"quote\\\n", "GET", "/x", "reason\"with<chars>");
        WebhookAlertNotifier notifier = new WebhookAlertNotifier(URL, 3000, "", "dingtalk", "");
        JsonNode payload = objectMapper.readTree(notifier.buildPayload(event));
        assertThat(payload.get("text").get("content").asText()).contains("RATE_LIMIT_REJECT");
    }

    @Test
    void defaultConstructorUsesGenericPlatform() {
        WebhookAlertNotifier notifier = new WebhookAlertNotifier(URL, 3000, "token");
        assertThat(notifier.getPlatform()).isEqualTo("generic");
        assertThat(notifier.getName()).isEqualTo("webhook");
    }
}
