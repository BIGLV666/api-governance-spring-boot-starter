package org.example.apigovernancespringbootstarter.async.event;

/**
 * Immutable error summary safe to carry across threads.
 *
 * <p>The original {@link Throwable} is deliberately not retained.</p>
 *
 * @param type fully qualified exception type
 * @param message exception message, possibly {@code null}
 * @since 1.0
 */
public record AsyncError(String type, String message) {

    /**
     * Creates an error summary without retaining the source exception.
     */
    public static AsyncError from(Throwable error) {
        return error == null ? null : new AsyncError(error.getClass().getName(), error.getMessage());
    }
}
