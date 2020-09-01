/*
 * Copyright 2013-2019 The OpenZipkin Authors
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
package brave;

import lombok.extern.slf4j.Slf4j;

/**
 * Provides a mechanism for end users to be able to customise the current span.
 *
 * <p>Handles the case of there being no current span in scope.
 */
@Slf4j
public final class MyCurrentSpanCustomizer implements SpanCustomizer {

    private final Tracer tracer;

    /**
     * Creates a span customizer that will affect the current span in scope if present
     */
    public static MyCurrentSpanCustomizer create(Tracing tracing) {
        return new MyCurrentSpanCustomizer(tracing);
    }

    MyCurrentSpanCustomizer(Tracing tracing) {
        this.tracer = tracing.tracer();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SpanCustomizer name(String name) {
        log.info("name;name={};", name);
        return tracer.currentSpanCustomizer().name(name);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SpanCustomizer tag(String key, String value) {
        log.info("tag;key={};value={};", key, value);
        return tracer.currentSpanCustomizer().tag(key, value);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SpanCustomizer annotate(String value) {
        log.info("annotate;value={};", value);
        return tracer.currentSpanCustomizer().annotate(value);
    }
}
