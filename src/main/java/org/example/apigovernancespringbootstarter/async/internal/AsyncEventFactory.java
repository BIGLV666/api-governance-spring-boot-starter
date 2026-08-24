package org.example.apigovernancespringbootstarter.async.internal;

import org.example.apigovernancespringbootstarter.async.AsyncEventBuilder;
import org.example.apigovernancespringbootstarter.async.AsyncInvocation;
import org.example.apigovernancespringbootstarter.async.event.AsyncEvent;
import org.example.apigovernancespringbootstarter.async.spi.AsyncEventEnricher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/** Creates immutable events and isolates failures in optional enrichers. */
public final class AsyncEventFactory {

    private static final Logger log = LoggerFactory.getLogger(AsyncEventFactory.class);

    private final List<AsyncEventEnricher> enrichers;

    public AsyncEventFactory(List<AsyncEventEnricher> enrichers) {
        this.enrichers = List.copyOf(enrichers);
    }

    public AsyncEvent create(AsyncInvocation invocation) {
        AsyncEventBuilder builder = new AsyncEventBuilder(invocation);
        for (AsyncEventEnricher enricher : enrichers) {
            try {
                enricher.enrich(builder, invocation);
            } catch (Throwable ex) {
                log.error("Async event enricher failed: action={}, phase={}, enricher={}",
                        invocation.getAction(), invocation.getPhase(),
                        enricher.getClass().getName(), ex);
            }
        }
        return builder.build();
    }
}
