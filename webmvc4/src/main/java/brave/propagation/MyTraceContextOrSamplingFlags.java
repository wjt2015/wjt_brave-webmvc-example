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
package brave.propagation;

import brave.Tracer;
import brave.internal.InternalPropagation;
import brave.internal.MyInternalPropagation;
import brave.internal.Nullable;
import brave.propagation.TraceContext.Extractor;
import brave.sampler.SamplerFunction;

import java.util.ArrayList;
import java.util.List;

import static brave.internal.InternalPropagation.FLAG_SAMPLED;
import static brave.internal.InternalPropagation.FLAG_SAMPLED_LOCAL;
import static brave.internal.InternalPropagation.FLAG_SAMPLED_SET;
import static brave.internal.collect.Lists.ensureImmutable;
import static brave.propagation.TraceContext.ensureExtraAdded;
import static java.util.Collections.emptyList;

/**
 * Union type that contains only one of trace context, trace ID context or sampling flags. This type
 * is designed for use with {@link Tracer#nextSpan(TraceContextOrSamplingFlags)}.
 *
 * <p>Users should not create instances of this, rather use {@link Extractor} provided
 * by a {@link Propagation} implementation such as {@link Propagation#B3_STRING}.
 *
 * <p>Those implementing {@link Propagation} should use the following advice:
 * <pre><ul>
 *   <li>If you have the trace and span ID, use {@link #create(MyTraceContext)}</li>
 *   <li>If you have only a trace ID, use {@link #create(MyTraceIdContext)}</li>
 *   <li>Otherwise, use {@link #create(MySamplingFlags)}</li>
 * </ul></pre>
 * <p>If your propagation implementation needs additional state, append it via {@link
 * Builder#addExtra(Object)}.
 *
 *
 * <p>This started as a port of {@code com.github.kristofa.brave.TraceData}, which served the same
 * purpose.
 *
 * @see Extractor
 * @since 4.0
 */
//@Immutable
public final class MyTraceContextOrSamplingFlags {
    public static final MyTraceContextOrSamplingFlags
            EMPTY = new MyTraceContextOrSamplingFlags(3, MySamplingFlags.EMPTY, emptyList()),
            NOT_SAMPLED = new MyTraceContextOrSamplingFlags(3, MySamplingFlags.NOT_SAMPLED, emptyList()),
            SAMPLED = new MyTraceContextOrSamplingFlags(3, MySamplingFlags.SAMPLED, emptyList()),
            DEBUG = new MyTraceContextOrSamplingFlags(3, MySamplingFlags.DEBUG, emptyList());

    /**
     * Used to implement {@link Extractor#extract(Object)} for a format that can extract a complete
     * {@link TraceContext}, including a {@linkplain TraceContext#traceIdString() trace ID} and
     * {@linkplain TraceContext#spanIdString() span ID}.
     *
     * @see #context()
     * @see #newBuilder(MyTraceContext)
     * @see Extractor#extract(Object)
     * @since 4.3
     */
    public static MyTraceContextOrSamplingFlags create(MyTraceContext context) {
        return new MyTraceContextOrSamplingFlags(1, context, emptyList());
    }

    /**
     * Used to implement {@link Extractor#extract(Object)} when the format allows extracting a
     * {@linkplain TraceContext#traceIdString() trace ID} without a {@linkplain
     * TraceContext#spanIdString() span ID}
     *
     * @see #traceIdContext()
     * @see #newBuilder(MyTraceIdContext)
     * @see Extractor#extract(Object)
     * @since 4.9
     */
    public static MyTraceContextOrSamplingFlags create(MyTraceIdContext traceIdContext) {
        return new MyTraceContextOrSamplingFlags(2, traceIdContext, emptyList());
    }

    /**
     * Used to implement {@link Extractor#extract(Object)} when the format allows extracting only
     * {@linkplain MySamplingFlags sampling flags}.
     *
     * @see #samplingFlags()
     * @see #newBuilder(MyTraceIdContext)
     * @see Extractor#extract(Object)
     * @since 4.3
     */
    public static MyTraceContextOrSamplingFlags create(MySamplingFlags flags) {
        // reuses constants to avoid excess allocation
        if (flags == MySamplingFlags.SAMPLED) return SAMPLED;
        if (flags == MySamplingFlags.EMPTY) return EMPTY;
        if (flags == MySamplingFlags.NOT_SAMPLED) return NOT_SAMPLED;
        if (flags == MySamplingFlags.DEBUG) return DEBUG;
        return new MyTraceContextOrSamplingFlags(3, flags, emptyList());
    }

    /**
     * Use when implementing {@link Extractor#extract(Object)} requires {@link Builder#sampledLocal()}
     * or {@link Builder#addExtra(Object)}. Otherwise, use {@link #create(MyTraceContext)} as it is more
     * efficient.
     *
     * @see #create(MyTraceContext)
     * @see Extractor#extract(Object)
     * @since 5.12
     */
    public static Builder newBuilder(MyTraceContext context) {
        if (context == null) throw new NullPointerException("context == null");
        return new Builder(1, context, context.extra());
    }

    /**
     * Use when implementing {@link Extractor#extract(Object)} requires {@link Builder#sampledLocal()}
     * or {@link Builder#addExtra(Object)}. Otherwise, use {@link #create(MyTraceIdContext)} as it is
     * more efficient.
     *
     * @see #create(MyTraceIdContext)
     * @see Extractor#extract(Object)
     * @since 5.12
     */
    public static Builder newBuilder(MyTraceIdContext traceIdContext) {
        if (traceIdContext == null) throw new NullPointerException("traceIdContext == null");
        return new Builder(2, traceIdContext, emptyList());
    }

    /**
     * Use when implementing {@link Extractor#extract(Object)} requires {@link Builder#sampledLocal()}
     * or {@link Builder#addExtra(Object)}. Otherwise, use {@link #create(MySamplingFlags)} as it is
     * more efficient.
     *
     * @see #create(MySamplingFlags)
     * @see Extractor#extract(Object)
     * @since 5.12
     */
    public static Builder newBuilder(MySamplingFlags flags) {
        if (flags == null) throw new NullPointerException("flags == null");
        return new Builder(3, flags, emptyList());
    }

    /**
     * @deprecated Since 5.12, use {@link #newBuilder(MyTraceContext)}, {@link
     * #newBuilder(MyTraceIdContext)} or {@link #newBuilder(MySamplingFlags)}.
     */
    @Deprecated public static Builder newBuilder() {
        return new Builder(0, null, emptyList());
    }

    /** Returns {@link MySamplingFlags#sampled()}, regardless of subtype. */
    @Nullable public Boolean sampled() {
        return value.sampled();
    }

    /** Returns {@link MySamplingFlags#sampledLocal()}, regardless of subtype. */
    public final boolean sampledLocal() {
        return (value.flags & FLAG_SAMPLED_LOCAL) == FLAG_SAMPLED_LOCAL;
    }

    /** @deprecated do not use object variant.. only set when you have a sampling decision */
    @Deprecated
    public MyTraceContextOrSamplingFlags sampled(@Nullable Boolean sampled) {
        if (sampled != null) return sampled(sampled.booleanValue());
        int flags = value.flags;
        flags &= ~FLAG_SAMPLED_SET;
        flags &= ~FLAG_SAMPLED;
        if (flags == value.flags) return this; // save effort if no change
        return withFlags(flags);
    }

    /**
     * This is used to apply a {@link SamplerFunction} decision with least overhead.
     *
     * Ex.
     * <pre>{@code
     * Boolean sampled = extracted.sampled();
     * // only recreate the context if the messaging sampler made a decision
     * if (sampled == null && (sampled = sampler.trySample(request)) != null) {
     *   extracted = extracted.sampled(sampled.booleanValue());
     * }
     * }</pre>
     *
     * @param sampled decision to apply
     * @return {@code this} unless {@link #sampled()} differs from the input.
     * @since 5.2
     */
    public MyTraceContextOrSamplingFlags sampled(boolean sampled) {
        Boolean thisSampled = sampled();
        if (thisSampled != null && thisSampled.equals(sampled)) return this;
        int flags = InternalPropagation.sampled(sampled, value.flags);
        if (flags == value.flags) return this; // save effort if no change
        return withFlags(flags);
    }

    /**
     * Returns non-{@code null} when both a {@linkplain TraceContext#traceIdString() trace ID} and
     * {@linkplain TraceContext#spanIdString() span ID} were  {@link Extractor#extract(Object)
     * extracted} from a request.
     *
     * <p>For example, given the header "b3: 80f198ee56343ba864fe8b2a57d3eff7-e457b5a2e4d86bd1-1",
     * {@link B3Propagation} extracts the following:
     * <ul>
     *   <li>{@link TraceContext#traceIdString()}: "80f198ee56343ba864fe8b2a57d3eff7"</li>
     *   <li>{@link TraceContext#spanIdString()}: "e457b5a2e4d86bd1"</li>
     *   <li>{@link TraceContext#sampled()}: {@code true}</li>
     * </ul>
     *
     * @return the trace context when {@link #traceIdContext()} ()} and {@link #samplingFlags()} are
     * not {@code null}
     * @see #create(MyTraceContext)
     * @see #newBuilder(MyTraceContext)
     * @since 4.0
     */
    @Nullable public MyTraceContext context() {
        return type == 1 ? (MyTraceContext) value : null;
    }

    /**
     * Returns non-{@code null} when a {@linkplain TraceIdContext#traceIdString() trace ID} was {@link
     * Extractor#extract(Object) extracted} from a request, but a {@linkplain
     * TraceContext#spanIdString() span ID} was not.
     *
     * <p>For example, given the header "x-amzn-trace-id: Root=1-5759e988-bd862e3fe1be46a994272793",
     * <a href="https://github.com/openzipkin/zipkin-aws/tree/master/brave-propagation-aws">AWSPropagation</a>
     * extracts the following:
     * <ul>
     *   <li>{@link TraceIdContext#traceIdString()}: "15759e988bd862e3fe1be46a994272793"</li>
     *   <li>{@link TraceIdContext#sampled()}: {@code null}</li>
     * </ul>
     *
     * @return the trace ID context when {@link #context()} and {@link #samplingFlags()} are not
     * {@code null}
     * @see #create(MyTraceIdContext)
     * @see #newBuilder(MyTraceIdContext)
     * @since 4.9
     */
    @Nullable public MyTraceIdContext traceIdContext() {
        return type == 2 ? (MyTraceIdContext) value : null;
    }

    /**
     * Returns non-{@code null} when a {@linkplain TraceContext#traceIdString() trace ID} was not
     * {@link Extractor#extract(Object) extracted} from a request.
     *
     * <p>For example, given the header "b3: 1", {@link B3Propagation} extracts {@link #SAMPLED}.
     *
     * @return sampling flags when {@link #context()} and {@link #traceIdContext()} are not {@code
     * null}
     * @see #create(MySamplingFlags)
     * @see #newBuilder(MySamplingFlags)
     * @since 4.9
     */
    @Nullable public MySamplingFlags samplingFlags() {
        return type == 3 ? value : null;
    }

    /**
     * Returns a list of additional state extracted from the request. Will be non-empty when {@link
     * #context()} is {@code null}.
     *
     * @see TraceContext#extra()
     * @since 4.9
     */
    public final List<Object> extra() {
        return extraList;
    }

    /**
     * Use to decorate an {@link Extractor#extract extraction result} with {@link #sampledLocal()} or
     * additional {@link #extra()}.
     *
     * @since 4.9
     */
    public Builder toBuilder() {
        return new Builder(type, value, effectiveExtra());
    }

    @Override public String toString() {
        List<Object> extra = effectiveExtra();
        StringBuilder result = new StringBuilder("Extracted{");
        String valueClass = value.getClass().getSimpleName();
        // Lowercase first char of class name
        result.append(Character.toLowerCase(valueClass.charAt(0)));
        result.append(valueClass, 1, valueClass.length()).append('=').append(value);
        if (type != 3) {
            String flagsString = MySamplingFlags.toString(value.flags);
            if (!flagsString.isEmpty()) result.append(", samplingFlags=").append(flagsString);
        }
        if (!extra.isEmpty()) result.append(", extra=").append(extra);
        // NOTE: it would be nice to rename this type, but it would cause a major Api break:
        // This is the result of Extractor::extract which is used in a lot of 3rd party code.
        return result.append('}').toString();
    }

    /** @deprecated Since 5.12, use constants defined on this type as needed. */
    @Deprecated
    public static MyTraceContextOrSamplingFlags create(@Nullable Boolean sampled, boolean debug) {
        if (debug) return DEBUG;
        if (sampled == null) return EMPTY;
        return sampled ? SAMPLED : NOT_SAMPLED;
    }

    final int type;
    final MySamplingFlags value;
    final List<Object> extraList;

    MyTraceContextOrSamplingFlags(int type, MySamplingFlags value, List<Object> extraList) {
        if (value == null) throw new NullPointerException("value == null");
        if (extraList == null) throw new NullPointerException("extra == null");
        this.type = type;
        this.value = value;
        this.extraList = extraList;
    }

    public static final class Builder {
        int type;
        MySamplingFlags value;
        List<Object> extraList;
        boolean sampledLocal = false;

        Builder(int type, MySamplingFlags value, List<Object> extraList) {
            this.type = type;
            this.value = value;
            this.extraList = extraList;
        }

        /** @deprecated Since 5.12, use {@link #newBuilder(MyTraceIdContext)} */
        @Deprecated public Builder context(MyTraceContext context) {
            return copyStateTo(newBuilder(context));
        }

        /** @deprecated Since 5.12, use {@link #newBuilder(MyTraceIdContext)} */
        @Deprecated public Builder traceIdContext(MyTraceIdContext traceIdContext) {
            return copyStateTo(newBuilder(traceIdContext));
        }

        /** @deprecated Since 5.12, use {@link #newBuilder(MySamplingFlags)} */
        @Deprecated public Builder samplingFlags(MySamplingFlags samplingFlags) {
            return copyStateTo(newBuilder(samplingFlags));
        }

        Builder copyStateTo(Builder builder) {
            if (sampledLocal) builder.sampledLocal();
            for (Object extra : extraList) builder.addExtra(extra);
            return builder;
        }

        /** @see TraceContext#sampledLocal() */
        public Builder sampledLocal() {
            this.sampledLocal = true;
            return this;
        }

        /** @deprecated Since 5.12, use {@link #addExtra(Object)} */
        @Deprecated public Builder extra(List<Object> extraList) {
            if (extraList == null) throw new NullPointerException("extraList == null");
            this.extraList = new ArrayList<>();
            for (Object extra : extraList) addExtra(extra);
            return this;
        }

        /**
         * @see MyTraceContextOrSamplingFlags#extra()
         * @since 4.9
         */
        public Builder addExtra(Object extra) {
            extraList = ensureExtraAdded(extraList, extra);
            return this;
        }

        /** Returns an immutable result from the values currently in the builder */
        public final MyTraceContextOrSamplingFlags build() {
            if (value == null) {
                throw new IllegalArgumentException(
                        "Value unset. Use a non-deprecated newBuilder method instead.");
            }
            final MyTraceContextOrSamplingFlags result;
            if (!extraList.isEmpty() && type == 1) { // move extra to the trace context
                MyTraceContext context = (MyTraceContext) value;
                context = MyInternalPropagation.instance.withExtra(context, ensureImmutable(extraList));
                result = new MyTraceContextOrSamplingFlags(type, context, emptyList());
            } else {
                // make sure the extra state is immutable and unmodifiable
                result = new MyTraceContextOrSamplingFlags(type, value, ensureImmutable(extraList));
            }

            if (!sampledLocal) return result; // save effort if no change
            return result.withFlags(value.flags | FLAG_SAMPLED_LOCAL);
        }
    }

    @Override public boolean equals(Object o) {
        if (o == this) return true;
        if (!(o instanceof MyTraceContextOrSamplingFlags)) return false;
        MyTraceContextOrSamplingFlags that = (MyTraceContextOrSamplingFlags) o;
        return type == that.type && value.equals(that.value)
                && effectiveExtra().equals(that.effectiveExtra());
    }

    List<Object> effectiveExtra() {
        return type == 1 ? ((MyTraceContext) value).extra() : extraList;
    }

    @Override public int hashCode() {
        int h = 1;
        h *= 1000003;
        h ^= type;
        h *= 1000003;
        h ^= value.hashCode();
        h *= 1000003;
        h ^= effectiveExtra().hashCode();
        return h;
    }

    MyTraceContextOrSamplingFlags withFlags(int flags) {
        switch (type) {
            case 1:
                MyTraceContext context = MyInternalPropagation.instance.withFlags((MyTraceContext) value, flags);
                return new MyTraceContextOrSamplingFlags(type, context, extraList);
            case 2:
                MyTraceIdContext traceIdContext = idContextWithFlags(flags);
                return new MyTraceContextOrSamplingFlags(type, traceIdContext, extraList);
            case 3:
                MySamplingFlags samplingFlags = MySamplingFlags.toSamplingFlags(flags);
                if (extraList.isEmpty()) return create(samplingFlags);
                return new MyTraceContextOrSamplingFlags(type, samplingFlags, extraList);
        }
        throw new AssertionError("programming error");
    }

    MyTraceIdContext idContextWithFlags(int flags) {
        MyTraceIdContext traceIdContext = (MyTraceIdContext) value;
        return new MyTraceIdContext(flags, traceIdContext.traceIdHigh, traceIdContext.traceId);
    }
}
