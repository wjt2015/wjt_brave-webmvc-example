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
package brave.internal.baggage;

import brave.baggage.MyBaggageField;
import brave.internal.Nullable;
import brave.propagation.MyTraceContext;
import brave.propagation.MyTraceContextOrSamplingFlags;
import brave.propagation.TraceContext;

/** Internal type that implements context storage for the field. */
public abstract class MyBaggageContext {
    @Nullable
    public abstract String getValue(MyBaggageField field, MyTraceContextOrSamplingFlags extracted);

    @Nullable public abstract String getValue(MyBaggageField field, MyTraceContext context);

    /** Returns false if the update was ignored. */
    public abstract boolean updateValue(MyBaggageField field, MyTraceContextOrSamplingFlags extracted,
                                        @Nullable String value);

    /** Returns false if the update was ignored. */
    public abstract boolean updateValue(MyBaggageField field, MyTraceContext context,
                                        @Nullable String value);

    /** Appropriate for constants or immutable fields defined in {@link MyTraceContext}. */
    public static abstract class ReadOnly extends MyBaggageContext {
        @Override public boolean updateValue(MyBaggageField field, MyTraceContextOrSamplingFlags extracted,
                                             @Nullable String value) {
            return false;
        }

        @Override public boolean updateValue(MyBaggageField field, MyTraceContext context, String value) {
            return false;
        }
    }
}
