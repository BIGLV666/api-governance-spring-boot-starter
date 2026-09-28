package io.github.biglv666.example;

import io.github.biglv666.apigovernance.alert.GovernanceAlertEvent;
import io.github.biglv666.apigovernance.alert.GovernanceAlertNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 自定义告警通知器演示：注册 GovernanceAlertNotifier Bean 即可接收
 * 慢方法 / 限流拒绝 / 限流器故障 / 异步任务拒绝四类事件，以及对应的恢复通知。
 * 生产中可在此对接钉钉、飞书、邮件等。
 *
 * <p>恢复事件（0.6.0 起）：{@link GovernanceAlertEvent#isRecovered()} 为 true，
 * {@link GovernanceAlertEvent#getSuppressedCount()} 携带抑制期内被静默丢弃的条数；
 * 正文以 [RECOVERED] 前缀与控制台区分，便于故障演练时肉眼核对完整生命周期。
 *
 * <p>注意：通知器在请求线程上被同步调用，慢 IO 请自行异步化（内置 Webhook 已异步）。
 */
@Component
public class ConsoleAlertNotifier implements GovernanceAlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(ConsoleAlertNotifier.class);

    @Override
    public void notify(GovernanceAlertEvent event) {
        if (event.isRecovered()) {
            log.warn("[RECOVERED] {} api={} 抑制期丢弃 {} 条 - {}", event.getType(), event.getApiKey(),
                    event.getSuppressedCount(), event.getMessage());
            return;
        }
        log.warn("[ALERT] {} api={} path={} - {}", event.getType(), event.getApiKey(),
                event.getPath(), event.getMessage());
    }
}