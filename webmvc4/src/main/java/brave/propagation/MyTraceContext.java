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

import brave.Span;
import brave.internal.InternalPropagation;
import brave.internal.Nullable;
import brave.internal.Platform;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.List;

import static brave.internal.InternalPropagation.FLAG_LOCAL_ROOT;
import static brave.internal.InternalPropagation.FLAG_SAMPLED;
import static brave.internal.InternalPropagation.FLAG_SAMPLED_LOCAL;
import static brave.internal.InternalPropagation.FLAG_SAMPLED_SET;
import static brave.internal.InternalPropagation.FLAG_SHARED;
import static brave.internal.codec.HexCodec.lenientLowerHexToUnsignedLong;
import static brave.internal.codec.HexCodec.toLowerHex;
import static brave.internal.codec.HexCodec.writeHexLong;
import static brave.internal.collect.Lists.ensureImmutable;
import static brave.internal.collect.Lists.ensureMutable;
import static brave.propagation.TraceIdContext.toTraceIdString;

/**
 * Contains trace identifiers and sampling data propagated in and out-of-process.
 *
 * <p>Particularly, this includes trace identifiers and sampled state.
 *
 * <p>The implementation was originally {@code com.github.kristofa.brave.SpanId}, which was a
 * port of {@code com.twitter.finagle.tracing.TraceId}. Unlike these mentioned, this type does not
 * expose a single binary representation. That's because propagation forms can now vary.
 *
 * @since 4.0
 */
//@Immutable
public final class MyTraceContext extends MySamplingFlags {
    /**
     * Used to send the trace context downstream. For example, as http headers.
     *
     * <p>For example, to put the context on an {@link java.net.HttpURLConnection}, you can do this:
     * <pre>{@code
     * // in your constructor
     * injector = tracing.propagation().injector(URLConnection::setRequestProperty);
     *
     * // later in your code, reuse the function you created above to add trace headers
     * HttpURLConnection connection = (HttpURLConnection) new URL("http://myserver").openConnection();
     * injector.inject(span.context(), connection);
     * }</pre>
     *
     * <p><em>Note</em>: This type is safe to implement as a lambda, or use as a method reference as
     * it is effectively a {@code FunctionalInterface}. It isn't annotated as such because the project
     * has a minimum Java language level 6.
     *
     * @since 4.0
     */
    // @FunctionalInterface, except Java language level 6. Do not add methods as it will break API!
    public interface Injector<R> {
        /**
         * Usually calls a setter for each propagation field to send downstream.
         *
         * @param traceContext possibly unsampled.
         * @param request holds propagation fields. For example, an outgoing message or http request.
         */
        void inject(MyTraceContext traceContext, R request);
    }

    /**
     * Used to continue an incoming trace. For example, by reading http headers.
     *
     * <p><em>Note</em>: This type is safe to implement as a lambda, or use as a method reference as
     * it is effectively a {@code FunctionalInterface}. It isn't annotated as such because the project
     * has a minimum Java language level 6.
     *
     * @see brave.Tracer#nextSpan(TraceContextOrSamplingFlags)
     * @since 4.0
     */
    // @FunctionalInterface, except Java language level 6. Do not add methods as it will break API!
    public interface Extractor<R> {

        /**
         * Returns either a trace context or sampling flags parsed from the request. If nothing was
         * parsable, sampling flags will be set to {@link MySamplingFlags#EMPTY}.
         *
         * @param request holds propagation fields. For example, an incoming message or http request.
         */
        MyTraceContextOrSamplingFlags extract(R request);
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    /** When non-zero, the trace containing this span uses 128-bit trace identifiers. */
    public long traceIdHigh() {
        return traceIdHigh;
    }

    /** Unique 8-byte identifier for a trace, set on all spans within it. */
    public long traceId() {
        return traceId;
    }

    /**
     * Returns the first {@link #spanId()} in a partition of a trace: otherwise known as an entry
     * span. This could be a root span or a span representing incoming work (ex {@link
     * Span.Kind#SERVER} or {@link Span.Kind#CONSUMER}. Unlike {@link #parentIdAsLong()}, this value
     * is inherited to child contexts until the trace exits the process. This value is inherited for
     * all child spans until the trace exits the process. This could also be described as an entry
     * span.
     *
     * <p>When {@link #isLocalRoot()}, this ID will be the same as the {@link #spanId() span ID}.
     *
     * <p>The local root ID can be used for dependency link processing, skipping data or partitioning
     * purposes. For example, one processor could skip all intermediate (local) spans between an
     * incoming service call and any outgoing ones.
     *
     * <p>This does not group together multiple points of entry in the same trace. For example,
     * repetitive consumption of the same incoming message results in different local roots.
     *
     * @return the {@link #spanId() span ID} of the local root or zero if this context wasn't
     * initialized by a {@link brave.Tracer}.
     */
    // This is the first span ID that became a Span or ScopedSpan
    public long localRootId() {
        return localRootId;
    }

    public boolean isLocalRoot() {
        return (flags & FLAG_LOCAL_ROOT) == FLAG_LOCAL_ROOT;
    }

    /**
     * The parent's {@link #spanId} or null if this the root span in a trace.
     *
     * @see #parentIdAsLong()
     */
    @Nullable public final Long parentId() {
        return parentId != 0 ? parentId : null;
    }

    /**
     * Like {@link #parentId()} except returns a primitive where zero implies absent.
     *
     * <p>Using this method will avoid allocation, so is encouraged when copying data.
     */
    public long parentIdAsLong() {
        return parentId;
    }

    /**
     * Unique 8-byte identifier of this span within a trace.
     *
     * <p>A span is uniquely identified in storage by ({@linkplain #traceId}, {@linkplain #spanId}).
     */
    public long spanId() {
        return spanId;
    }

    /**
     * True if we are recording a server span with the same span ID parsed from incoming headers.
     *
     * <h3>Impact on indexing</h3>
     * <p>When an RPC trace is client-originated, it will be sampled and the same span ID is used for
     * the server side. The shared flag helps prioritize timestamp and duration indexing in favor of
     * the client. In v1 format, there is no shared flag, so it implies converters should not store
     * timestamp and duration on the server span explicitly.
     */
    public boolean shared() {
        return (flags & FLAG_SHARED) == FLAG_SHARED;
    }

    /**
     * Returns a list of additional data propagated through this trace.
     *
     * <p>The contents are intentionally opaque, deferring to {@linkplain Propagation} to define. An
     * example implementation could be storing a class containing a correlation value, which is
     * extracted from incoming requests and injected as-is onto outgoing requests.
     *
     * <p>Implementations are responsible for scoping any data stored here. This can be performed
     * when {@link Propagation.Factory#decorate(TraceContext)} is called.
     *
     * @since 4.9
     */
    public List<Object> extra() {
        return extraList;
    }

    /**
     * Returns a {@linkplain #extra() propagated state} of the given type if present or null if not.
     *
     * <p>Note: It is the responsibility of {@link Propagation.Factory#decorate(TraceContext)}
     * to consolidate elements. If it doesn't, there could be multiple instances of a given type and
     * this can break logic.
     */
    @Nullable public <T> T findExtra(Class<T> type) {
        return findExtra(type, extraList);
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    volatile String traceIdString; // Lazily initialized and cached.

    /** Returns the hex representation of the span's trace ID */
    public String traceIdString() {
        String r = traceIdString;
        if (r == null) {
            r = toTraceIdString(traceIdHigh, traceId);
            traceIdString = r;
        }
        return r;
    }

    volatile String parentIdString; // Lazily initialized and cached.

    /** Returns the hex representation of the span's parent ID */
    @Nullable public String parentIdString() {
        String r = parentIdString;
        if (r == null && parentId != 0L) {
            r = parentIdString = toLowerHex(parentId);
        }
        return r;
    }

    volatile String localRootIdString; // Lazily initialized and cached.

    /** Returns the hex representation of the span's local root ID */
    @Nullable public String localRootIdString() {
        String r = localRootIdString;
        if (r == null && localRootId != 0L) {
            r = localRootIdString = toLowerHex(localRootId);
        }
        return r;
    }

    volatile String spanIdString; // Lazily initialized and cached.

    /** Returns the hex representation of the span's ID */
    public String spanIdString() {
        String r = spanIdString;
        if (r == null) {
            r = spanIdString = toLowerHex(spanId);
        }
        return r;
    }

    /** Returns {@code $traceId/$spanId} */
    @Override public String toString() {
        boolean traceHi = traceIdHigh != 0;
        char[] result = new char[((traceHi ? 3 : 2) * 16) + 1]; // 2 ids and the delimiter
        int pos = 0;
        if (traceHi) {
            writeHexLong(result, pos, traceIdHigh);
            pos += 16;
        }
        writeHexLong(result, pos, traceId);
        pos += 16;
        result[pos++] = '/';
        writeHexLong(result, pos, spanId);
        return new String(result);
    }

    public static final class Builder {
        long traceIdHigh, traceId, parentId, spanId;
        long localRootId; // intentionally only mutable by the copy constructor to control usage.
        int flags;
        List<Object> extraList = Collections.emptyList();

        Builder(MyTraceContext context) { // no external implementations
            traceIdHigh = context.traceIdHigh;
            traceId = context.traceId;
            localRootId = context.localRootId;
            parentId = context.parentId;
            spanId = context.spanId;
            flags = context.flags;
            extraList = context.extraList;
        }

        /** @see TraceContext#traceIdHigh() */
        public Builder traceIdHigh(long traceIdHigh) {
            this.traceIdHigh = traceIdHigh;
            return this;
        }

        /** @see TraceContext#traceId() */
        public Builder traceId(long traceId) {
            this.traceId = traceId;
            return this;
        }

        /** @see TraceContext#parentIdAsLong() */
        public Builder parentId(long parentId) {
            this.parentId = parentId;
            return this;
        }

        /** @see TraceContext#parentId() */
        public Builder parentId(@Nullable Long parentId) {
            if (parentId == null) parentId = 0L;
            this.parentId = parentId;
            return this;
        }

        /** @see TraceContext#spanId() */
        public Builder spanId(long spanId) {
            this.spanId = spanId;
            return this;
        }

        /** @see TraceContext#sampledLocal() */
        public Builder sampledLocal(boolean sampledLocal) {
            if (sampledLocal) {
                flags |= FLAG_SAMPLED_LOCAL;
            } else {
                flags &= ~FLAG_SAMPLED_LOCAL;
            }
            return this;
        }

        /** @see TraceContext#sampled() */
        public Builder sampled(boolean sampled) {
            flags = InternalPropagation.sampled(sampled, flags);
            return this;
        }

        /** @see TraceContext#sampled() */
        public Builder sampled(@Nullable Boolean sampled) {
            if (sampled == null) {
                flags &= ~(FLAG_SAMPLED_SET | FLAG_SAMPLED);
                return this;
            }
            return sampled(sampled.booleanValue());
        }

        /** @see TraceContext#debug() */
        public Builder debug(boolean debug) {
            flags = MySamplingFlags.debug(debug, flags);
            return this;
        }

        /** @see TraceContext#shared() */
        public Builder shared(boolean shared) {
            if (shared) {
                flags |= FLAG_SHARED;
            } else {
                flags &= ~FLAG_SHARED;
            }
            return this;
        }

        /**
         * @since 4.9
         * @deprecated Since 5.12, use {@link #addExtra(Object)}
         */
        @Deprecated public Builder extra(List<Object> extraList) {
            if (extraList == null) throw new NullPointerException("extraList == null");
            for (Object extra : extraList) {
                addExtra(extra);
            }
            return this;
        }

        /**
         * Allows you to control {@link #extra()} explicitly.
         *
         * @since 5.12
         */
        public Builder clearExtra() {
            extraList = Collections.emptyList();
            return this;
        }

        /**
         * @see #extra()
         * @since 5.12
         */
        public Builder addExtra(Object extra) {
            extraList = ensureExtraAdded(extraList, extra);
            return this;
        }

        /**
         * Returns true when {@link TraceContext#traceId()} and potentially also {@link
         * TraceContext#traceIdHigh()} were parsed from the input. This assumes the input is valid, an
         * up to 32 character lower-hex string.
         *
         * <p>Returns boolean, not this, for conditional, exception free parsing:
         *
         * <p>Example use:
         * <pre>{@code
         * // Attempt to parse the trace ID or break out if unsuccessful for any reason
         * String traceIdString = getter.get(request, key);
         * if (!builder.parseTraceId(traceIdString, propagation.traceIdKey)) {
         *   return TraceContextOrSamplingFlags.EMPTY;
         * }
         * }</pre>
         *
         * @param traceIdString the 1-32 character lowerhex string
         * @param key the name of the propagation field representing the trace ID; only using in
         * logging
         * @return false if the input is null or malformed
         */
        // temporarily package protected until we figure out if this is reusable enough to expose
        boolean parseTraceId(String traceIdString, Object key) {
            if (isNull(key, traceIdString)) return false;
            int length = traceIdString.length();
            if (invalidIdLength(key, length, 32)) return false;

            boolean traceIdHighAllZeros = false, traceIdAllZeros = false;

            // left-most characters, if any, are the high bits
            int traceIdIndex = Math.max(0, length - 16);
            if (traceIdIndex > 0) {
                traceIdHigh = lenientLowerHexToUnsignedLong(traceIdString, 0, traceIdIndex);
                if (traceIdHigh == 0L) {
                    traceIdHighAllZeros = isAllZeros(traceIdString, 0, traceIdIndex);
                    if (!traceIdHighAllZeros) {
                        maybeLogNotLowerHex(traceIdString);
                        return false;
                    }
                }
            } else {
                traceIdHighAllZeros = true;
            }

            // right-most up to 16 characters are the low bits
            traceId = lenientLowerHexToUnsignedLong(traceIdString, traceIdIndex, length);
            if (traceId == 0L) {
                traceIdAllZeros = isAllZeros(traceIdString, traceIdIndex, length);
                if (!traceIdAllZeros) {
                    maybeLogNotLowerHex(traceIdString);
                    return false;
                }
            }

            if (traceIdHighAllZeros && traceIdAllZeros) {
                Platform.get().log("Invalid input: traceId was all zeros", null);
            }
            return traceIdHigh != 0L || traceId != 0L;
        }

        /** Parses the parent id from the input string. Returns true if the ID was missing or valid. */
        <R, K> boolean parseParentId(MyPropagation.Getter<R, K> getter, R request, K key) {
            String parentIdString = getter.get(request, key);
            if (parentIdString == null) return true; // absent parent is ok
            int length = parentIdString.length();
            if (invalidIdLength(key, length, 16)) return false;

            parentId = lenientLowerHexToUnsignedLong(parentIdString, 0, length);
            if (parentId != 0) return true;
            maybeLogNotLowerHex(parentIdString);
            return false;
        }

        /** Parses the span id from the input string. Returns true if the ID is valid. */
        <R, K> boolean parseSpanId(MyPropagation.Getter<R, K> getter, R request, K key) {
            String spanIdString = getter.get(request, key);
            if (isNull(key, spanIdString)) return false;
            int length = spanIdString.length();
            if (invalidIdLength(key, length, 16)) return false;

            spanId = lenientLowerHexToUnsignedLong(spanIdString, 0, length);
            if (spanId == 0) {
                if (isAllZeros(spanIdString, 0, length)) {
                    Platform.get().log("Invalid input: spanId was all zeros", null);
                    return false;
                }
                maybeLogNotLowerHex(spanIdString);
                return false;
            }
            return true;
        }

        static boolean invalidIdLength(Object key, int length, int max) {
            if (length > 1 && length <= max) return false;

            assert max == 32 || max == 16;
            Platform.get().log(max == 32
                    ? "{0} should be a 1 to 32 character lower-hex string with no prefix"
                    : "{0} should be a 1 to 16 character lower-hex string with no prefix", key, null);

            return true;
        }

        static boolean isNull(Object key, String maybeNull) {
            if (maybeNull != null) return false;
            Platform.get().log("{0} was null", key, null);
            return true;
        }

        /** Helps differentiate a parse failure from a successful parse of all zeros. */
        static boolean isAllZeros(String value, int beginIndex, int endIndex) {
            for (int i = beginIndex; i < endIndex; i++) {
                if (value.charAt(i) != '0') return false;
            }
            return true;
        }

        static void maybeLogNotLowerHex(String notLowerHex) {
            Platform.get().log("{0} is not a lower-hex string", notLowerHex, null);
        }

        /** @throws IllegalArgumentException if missing trace ID or span ID */
        public MyTraceContext build() {
            String missing = "";
            if (traceIdHigh == 0L && traceId == 0L) missing += " traceId";
            if (spanId == 0L) missing += " spanId";
            if (!"".equals(missing)) throw new IllegalArgumentException("Missing:" + missing);
            return new MyTraceContext(
                    flags, traceIdHigh, traceId, localRootId, parentId, spanId, ensureImmutable(extraList)
            );
        }

        Builder() { // no external implementations
        }
    }

    MyTraceContext shallowCopy() {
        return new MyTraceContext(flags, traceIdHigh, traceId, localRootId, parentId, spanId, extraList);
    }

    MyTraceContext withExtra(List<Object> extra) {
        return new MyTraceContext(flags, traceIdHigh, traceId, localRootId, parentId, spanId, extra);
    }

    MyTraceContext withFlags(int flags) {
        return new MyTraceContext(flags, traceIdHigh, traceId, localRootId, parentId, spanId, extraList);
    }

    final long traceIdHigh, traceId, localRootId, parentId, spanId;
    final List<Object> extraList;

    MyTraceContext(
            int flags,
            long traceIdHigh,
            long traceId,
            long localRootId,
            long parentId,
            long spanId,
            List<Object> extraList
    ) {
        super(flags);
        this.traceIdHigh = traceIdHigh;
        this.traceId = traceId;
        this.localRootId = localRootId;
        this.parentId = parentId;
        this.spanId = spanId;
        this.extraList = extraList;
    }

    /**
     * Includes mandatory fields {@link #traceIdHigh()}, {@link #traceId()}, {@link #spanId()} and the
     * {@link #shared() shared flag}.
     *
     * <p>The shared flag is included to have parity with the {@link #hashCode()}.
     */
    @Override public boolean equals(Object o) {
        if (o == this) return true;
        // Hack that allows WeakConcurrentMap to lookup without allocating a new object.
        if (o instanceof WeakReference) o = ((WeakReference) o).get();
        if (!(o instanceof TraceContext)) return false;
        MyTraceContext that = (MyTraceContext) o;
        return (traceIdHigh == that.traceIdHigh)
                && (traceId == that.traceId)
                && (spanId == that.spanId)
                && ((flags & FLAG_SHARED) == (that.flags & FLAG_SHARED));
    }

    volatile int hashCode; // Lazily initialized and cached.

    /**
     * Includes mandatory fields {@link #traceIdHigh()}, {@link #traceId()}, {@link #spanId()} and the
     * {@link #shared() shared flag}.
     *
     * <p>The shared flag is included in the hash code to ensure loopback span data are partitioned
     * properly. For example, if a client calls itself, the server-side shouldn't overwrite the client
     * side.
     */
    @Override public int hashCode() {
        int h = hashCode;
        if (h == 0) {
            h = 1000003;
            h ^= (int) ((traceIdHigh >>> 32) ^ traceIdHigh);
            h *= 1000003;
            h ^= (int) ((traceId >>> 32) ^ traceId);
            h *= 1000003;
            h ^= (int) ((spanId >>> 32) ^ spanId);
            h *= 1000003;
            h ^= flags & FLAG_SHARED;
            hashCode = h;
        }
        return h;
    }

    static List<Object> ensureExtraAdded(List<Object> extraList, Object extra) {
        if (extra == null) throw new NullPointerException("extra == null");
        // ignore adding the same instance twice
        for (int i = 0, length = extraList.size(); i < length; i++) {
            if (extra == extraList.get(i)) return extraList;
        }
        extraList = ensureMutable(extraList);
        extraList.add(extra);
        return extraList;
    }

    static <T> T findExtra(Class<T> type, List<Object> extra) {
        if (type == null) throw new NullPointerException("type == null");
        for (int i = 0, length = extra.size(); i < length; i++) {
            Object nextExtra = extra.get(i);
            if (nextExtra.getClass() == type) return (T) nextExtra;
        }
        return null;
    }
}
