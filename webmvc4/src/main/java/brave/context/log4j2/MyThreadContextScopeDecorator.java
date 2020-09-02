/*
 * Copyright 2013-2020 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package brave.context.log4j2;

import brave.baggage.BaggageFields;
import brave.baggage.CorrelationScopeConfig.SingleCorrelationField;
import brave.baggage.CorrelationScopeDecorator;
import brave.baggage.MyCorrelationScopeDecorator;
import brave.internal.CorrelationContext;
import brave.internal.MyCorrelationContext;
import brave.internal.Nullable;
import brave.propagation.CurrentTraceContext;
import brave.propagation.MyCurrentTraceContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.ThreadContext;

/**
 * Creates a {@link CorrelationScopeDecorator} for Log4j 2 {@linkplain ThreadContext Thread
 * Context}.
 *
 * <p>Ex.
 * <pre>{@code
 * tracing = Tracing.newBuilder()
 *                  .currentTraceContext(ThreadLocalCurrentTraceContext.newBuilder()
 *                    .addScopeDecorator(MyThreadContextScopeDecorator.get())
 *                    .build()
 *                  )
 *                  ...
 *                  .build();
 * }</pre>
 *
 * @see CorrelationScopeDecorator
 */
@Slf4j
public final class MyThreadContextScopeDecorator {
    static final MyCurrentTraceContext.ScopeDecorator INSTANCE = new Builder().build();

    /**
     * Returns a singleton that configures {@link BaggageFields#TRACE_ID} and {@link
     * BaggageFields#SPAN_ID}.
     *
     * @since 5.11
     */
    public static MyCurrentTraceContext.ScopeDecorator get() {
        return INSTANCE;
    }

    /**
     * Returns a builder that configures {@link BaggageFields#TRACE_ID} and {@link
     * BaggageFields#SPAN_ID}.
     *
     * @since 5.11
     */
    public static MyCorrelationScopeDecorator.Builder newBuilder() {
        return new Builder();
    }

    /**
     * Returns a scope decorator that configures {@link BaggageFields#TRACE_ID}, {@link
     * BaggageFields#PARENT_ID}, {@link BaggageFields#SPAN_ID} and {@link BaggageFields#SAMPLED}
     *
     * @since 5.2
     * @deprecated since 5.11 use {@link #get()} or {@link #newBuilder()}
     */
    @Deprecated
    public static MyCurrentTraceContext.ScopeDecorator create() {
        return new Builder()
                .clear()
                .add(SingleCorrelationField.create(BaggageFields.TRACE_ID))
                .add(SingleCorrelationField.create(BaggageFields.PARENT_ID))
                .add(SingleCorrelationField.create(BaggageFields.SPAN_ID))
                .add(SingleCorrelationField.create(BaggageFields.SAMPLED))
                .build();
    }

    static final class Builder extends MyCorrelationScopeDecorator.Builder {
        Builder() {
            super(MyThreadContextCorrelationContext.INSTANCE);
        }
    }

    // TODO: see if we can read/write directly to skip some overhead similar to
    // https://github.com/census-instrumentation/opencensus-java/blob/2903747aca08b1e2e29da35c5527ff046918e562/contrib/log_correlation/log4j2/src/main/java/io/opencensus/contrib/logcorrelation/log4j2/OpenCensusTraceContextDataInjector.java
    enum MyThreadContextCorrelationContext implements MyCorrelationContext {
        INSTANCE;

        @Override
        public String getValue(String name) {
            return ThreadContext.get(name);
        }

        @Override
        public boolean update(String name, @Nullable String value) {
            if (value != null) {
                ThreadContext.put(name, value);
            } else if (ThreadContext.containsKey(name)) {
                ThreadContext.remove(name);
            } else {
                return false;
            }
            return true;
        }
    }
}
