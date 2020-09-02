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
package brave.baggage;

import brave.internal.MyCorrelationContext;
import brave.internal.Nullable;
import brave.propagation.CurrentTraceContext.Scope;
import brave.propagation.MyCurrentTraceContext;

import java.util.concurrent.atomic.AtomicBoolean;

import static brave.baggage.CorrelationScopeDecorator.equal;
import static brave.baggage.CorrelationScopeDecorator.isSet;
import static brave.baggage.CorrelationScopeDecorator.setBit;

/** Handles reverting potentially late value updates to baggage fields. */
abstract class  MyCorrelationUpdateScope extends AtomicBoolean implements MyCurrentTraceContext.Scope {
     MyCorrelationContext context;

     MyCorrelationUpdateScope( MyCorrelationContext context) {
        this.context = context;
    }

    /**
     * Called to get the name of the field, before it is flushed to the underlying context.
     *
     * @return the name used for this field, or null if not configured.
     */
    @Nullable abstract String name(MyBaggageField field);

    /**
     * Called after a field value is flushed to the underlying context. Only take action if the input
     * field is current being tracked.
     */
    abstract void handleUpdate(MyBaggageField field, @Nullable String value);

    static final class Single extends  MyCorrelationUpdateScope {
        final MyCurrentTraceContext.Scope delegate;
        final MyCorrelationScopeConfig.SingleCorrelationField field;
        final @Nullable String valueToRevert;
        boolean shouldRevert;

        Single(
                MyCurrentTraceContext.Scope delegate,
                 MyCorrelationContext context,
                MyCorrelationScopeConfig.SingleCorrelationField field,
                @Nullable String valueToRevert,
                boolean shouldRevert
        ) {
            super(context);
            this.delegate = delegate;
            this.field = field;
            this.valueToRevert = valueToRevert;
            this.shouldRevert = shouldRevert;
        }

        @Override public void close() {
            // don't duplicate work if called multiple times.
            if (!compareAndSet(false, true)) return;
            delegate.close();
            if (shouldRevert) context.update(field.name, valueToRevert);
        }

        @Override String name(MyBaggageField field) {
            return field.name;
        }

        @Override void handleUpdate(MyBaggageField field, String value) {
            if (!this.field.baggageField.equals(field)) return;
            if (!equal(value, valueToRevert)) shouldRevert = true;
        }
    }

    static final class Multiple extends  MyCorrelationUpdateScope {
        final MyCurrentTraceContext.Scope delegate;
        final MyCorrelationScopeConfig.SingleCorrelationField[] fields;
        final String[] valuesToRevert;
        int shouldRevert;

        Multiple(
                MyCurrentTraceContext.Scope delegate,
                 MyCorrelationContext context,
                MyCorrelationScopeConfig.SingleCorrelationField[] fields,
                String[] valuesToRevert,
                int shouldRevert
        ) {
            super(context);
            this.delegate = delegate;
            this.fields = fields;
            this.valuesToRevert = valuesToRevert;
            this.shouldRevert = shouldRevert;
        }

        @Override public void close() {
            // don't duplicate work if called multiple times.
            if (!compareAndSet(false, true)) return;

            delegate.close();
            for (int i = 0; i < fields.length; i++) {
                if (isSet(shouldRevert, i)) context.update(fields[i].name, valuesToRevert[i]);
            }
        }

        @Override String name(MyBaggageField field) {
            for (int i = 0; i < fields.length; i++) {
                if (fields[i].baggageField.equals(field)) {
                    return fields[i].name;
                }
            }
            return null;
        }

        @Override void handleUpdate(MyBaggageField field, String value) {
            for (int i = 0; i < fields.length; i++) {
                if (fields[i].baggageField.equals(field)) {
                    if (!equal(value, valuesToRevert[i])) shouldRevert = setBit(shouldRevert, i);
                    return;
                }
            }
        }
    }
}
