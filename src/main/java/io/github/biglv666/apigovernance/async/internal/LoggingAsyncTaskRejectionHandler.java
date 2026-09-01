package io.github.biglv666.apigovernance.async.internal;

import io.github.biglv666.apigovernance.alert.GovernanceAlertEvent;
import io.github.biglv666.apigovernance.alert.internal.AlertDispatcher;
import io.github.biglv666.apigovernance.async.AsyncHandlerInfo;
import io.github.biglv666.apigovernance.async.event.AsyncEvent;
import io.github.biglv666.apigovernance.async.spi.AsyncTaskRejectionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 默认拒绝任务处理器：记录 error 日志，并在告警分发器存在时发布
 * {@code ASYNC_TASK_REJECTED} 告警（0.5.0 起，复用告警风暴抑制）。
 */
public final class LoggingAsyncTaskRejectionHandler implements AsyncTaskRejectionHandler {

    private static final Logger log = LoggerFactory.getLogger(LoggingAsyncTaskRejectionHandler.class);

    private final AlertDispatcher alertDispatcher;

    public LoggingAsyncTaskRejectionHandler() {
        this(null);
    }

    /**
     * @param alertDispatcher 告警分发器（可空：告警未启用时仅记日志）
     */
    public LoggingAsyncTaskRejectionHandler(AlertDispatcher alertDispatcher) {
        this.alertDispatcher = alertDispatcher;
    }

    @Override
    public void handle(AsyncEvent event, AsyncHandlerInfo handler, RuntimeException error) {
        log.error("Async task rejected: eventId={}, action={}, phase={}, bean={}, method={}",
                event.id(), event.action(), event.phase(), handler.beanName(), handler.method(), error);
        if (alertDispatcher != null) {
            alertDispatcher.publishAsyncTaskRejected(event.action(), handler.method(),
                    error == null ? "-" : error.getMessage());
        }
    }
}
