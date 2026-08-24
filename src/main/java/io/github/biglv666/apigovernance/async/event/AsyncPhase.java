package io.github.biglv666.apigovernance.async.event;

/**
 * Supported asynchronous action lifecycle phases.
 *
 * @since 1.0
 */
public enum AsyncPhase {
    /** Submitted before the target method starts. */
    BEFORE,
    /** Submitted only after the target method returns normally. */
    AFTER_SUCCESS,
    /** Submitted only after the target method throws an error. */
    AFTER_ERROR,
    /** Submitted after either successful or failed completion. */
    AFTER_COMPLETION
}
