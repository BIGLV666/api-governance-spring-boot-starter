package org.example.apigovernancespringbootstarter.async;

import org.example.apigovernancespringbootstarter.async.event.AsyncPhase;

/**
 * Immutable metadata describing a registered asynchronous handler.
 *
 * @param action action name
 * @param phase lifecycle phase
 * @param order submission order
 * @param beanName Spring Bean name
 * @param beanType handler Bean type
 * @param method handler method name
 * @since 1.0
 */
public record AsyncHandlerInfo(
        String action,
        AsyncPhase phase,
        int order,
        String beanName,
        String beanType,
        String method) {
}
