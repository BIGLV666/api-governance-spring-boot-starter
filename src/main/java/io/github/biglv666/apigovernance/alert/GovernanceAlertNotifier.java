package io.github.biglv666.apigovernance.alert;

/**
 * 治理告警通知器插件 —— 告警事件的下游消费者。
 *
 * <p>将实现注册为 Spring Bean，即自动接收所有 {@link GovernanceAlertEvent}
 * （慢方法、限流拒绝、限流器故障）。框架内置 {@code WebhookAlertNotifier} 可对接
 * 钉钉 / 企业微信 / 飞书等 webhook 机器人；也可自行实现对接邮件、短信、PagerDuty 等。
 *
 * <h3>契约</h3>
 * <ul>
 *   <li>通知器在<b>请求线程上被同步调用</b>（Webhook 内置实现内部使用异步 HTTP，不阻塞请求），
 *       自定义实现若涉及慢 IO，请自行异步化；</li>
 *   <li>通知器抛出的任何异常都会被 {@code AlertDispatcher} 捕获并吞掉（记 warn 日志），
 *       绝不影响业务请求；</li>
 *   <li>同一 {@code (告警类型, apiKey)} 在 {@code api.governance.alert.suppress-interval-ms}
 *       窗口内只分发一次，防止告警风暴；抑制逻辑由分发器统一执行，通知器无需关心。</li>
 * </ul>
 *
 * @author API Governance Team
 * @since 0.2.0
 */
public interface GovernanceAlertNotifier {

    /**
     * 处理一条告警事件。
     *
     * @param event 告警事件（不可变快照，可安全保存或转发）
     */
    void notify(GovernanceAlertEvent event);

    /**
     * 通知器名称（用于日志与管理接口展示）。
     *
     * @return 名称
     */
    default String getName() {
        return getClass().getSimpleName();
    }
}
