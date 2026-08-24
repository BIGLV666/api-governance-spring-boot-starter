package org.example.apigovernancespringbootstarter.async.annotation;

import org.example.apigovernancespringbootstarter.async.event.AsyncPhase;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Registers a public Spring Bean method as an asynchronous action handler.
 *
 * <p>Handler methods must return {@code void} and declare either no parameter
 * or one {@code AsyncEvent} parameter. They are discovered and validated once
 * during application startup.</p>
 *
 * @since 1.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Repeatable(AsyncHandlers.class)
public @interface AsyncHandler {

    /**
     * Action name to handle.
     */
    String value();

    /**
     * Method lifecycle phase to handle.
     */
    AsyncPhase phase() default AsyncPhase.AFTER_SUCCESS;

    /**
     * Submission order within the same action and phase. Completion order is
     * not guaranteed because handlers execute concurrently.
     */
    int order() default 0;
}
