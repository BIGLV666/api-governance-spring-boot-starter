package io.github.biglv666.apigovernance.async.internal;

import io.github.biglv666.apigovernance.async.AsyncHandlerInfo;
import io.github.biglv666.apigovernance.async.event.AsyncEvent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Internal pre-resolved handler invocation. */
final class RegisteredAsyncHandler {

    private final Object bean;
    private final Method method;
    private final boolean eventParameter;
    private final AsyncHandlerInfo info;

    RegisteredAsyncHandler(Object bean, Method method, boolean eventParameter,
                           AsyncHandlerInfo info) {
        this.bean = bean;
        this.method = method;
        this.eventParameter = eventParameter;
        this.info = info;
    }

    AsyncHandlerInfo info() {
        return info;
    }

    void invoke(AsyncEvent event) throws Throwable {
        try {
            if (eventParameter) {
                method.invoke(bean, event);
            } else {
                method.invoke(bean);
            }
        } catch (InvocationTargetException ex) {
            throw ex.getTargetException();
        }
    }
}
