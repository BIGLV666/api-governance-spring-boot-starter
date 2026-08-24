package io.github.biglv666.apigovernance.async.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a public Spring Bean method as an asynchronous action trigger.
 *
 * <p>The target method itself remains synchronous. The framework only submits
 * matching {@link AsyncHandler} methods around its lifecycle.</p>
 *
 * @since 1.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AsyncAction {

    /**
     * Logical action name used to match handlers.
     */
    String value();
}
