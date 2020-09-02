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
import brave.Tracing;
import brave.internal.Nullable;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

/**
 * This makes a given span the current span by placing it in scope (usually but not always a thread
 * local scope).
 *
 * <p>This type is an SPI, and intended to be used by implementors looking to change thread-local
 * storage, or integrate with other contexts such as logging (MDC).
 *
 * <h3>Design</h3>
 * <p>
 * This design was inspired by com.google.instrumentation.trace.ContextUtils,
 * com.google.inject.servlet.RequestScoper and com.github.kristofa.brave.CurrentSpan
 */
public abstract class MyCurrentTraceContext {
    static {
        // ensure a reference to InternalPropagation exists
        SamplingFlags.DEBUG.toString();
    }

    /**
     * Implementations of this allow standardized configuration, for example scope decoration.
     */
    public abstract static class Builder {
        ArrayList<ScopeDecorator> scopeDecorators = new ArrayList<>();

        /**
         * Implementations call decorators in order to add features like log correlation to a scope.
         *
         * @since 5.2
         */
        public Builder addScopeDecorator(ScopeDecorator scopeDecorator) {
            if (scopeDecorator == null) throw new NullPointerException("scopeDecorator == null");
            if (scopeDecorator == ScopeDecorator.NOOP) return this;
            this.scopeDecorators.add(scopeDecorator);
            return this;
        }

        public abstract MyCurrentTraceContext build();
    }

    /**
     * Returns the current span in scope or null if there isn't one.
     */
    public abstract @Nullable
    MyTraceContext get();

    /**
     * Sets the current span in scope until the returned object is closed. It is a programming error
     * to drop or never close the result. Using try-with-resources is preferred for this reason.
     *
     * @param context span to place into scope or null to clear the scope
     */
    public abstract Scope newScope(@Nullable MyTraceContext context);

    final ScopeDecorator[] scopeDecorators;

    protected MyCurrentTraceContext() {
        this.scopeDecorators = new ScopeDecorator[0];
    }

    protected MyCurrentTraceContext(Builder builder) {
        this.scopeDecorators = builder.scopeDecorators.toArray(new ScopeDecorator[0]);
    }

    /**
     * When implementing {@linkplain #newScope(MyTraceContext)}, decorate the result before returning
     * it.
     *
     * <p>Ex.
     * <pre>{@code
     *   @Override public Scope newScope(@Nullable MyTraceContext currentSpan) {
     *     final MyTraceContext previous = local.get();
     *     local.set(currentSpan);
     *     class ThreadLocalScope implements Scope {
     *       @Override public void close() {
     *         local.set(previous);
     *       }
     *     }
     *     Scope result = new ThreadLocalScope();
     *     // ensure scope hooks are attached to the result
     *     return decorateScope(currentSpan, result);
     *   }
     * }</pre>
     *
     * @param scope {@link Scope#NOOP} if the prior context was equal to the {@code context}
     *              parameter.
     */
    protected Scope decorateScope(@Nullable MyTraceContext context, Scope scope) {
        for (ScopeDecorator scopeDecorator : scopeDecorators) {
            scope = scopeDecorator.decorateScope(context, scope);
        }
        return scope;
    }

    /**
     * Like {@link #newScope(MyTraceContext)}, except returns {@link Scope#NOOP} if the given context is
     * already in scope. This can reduce overhead when scoping callbacks. However, this will not apply
     * any changes, notably in {@link MyTraceContext#extra()}. As such, it should be used carefully and
     * only in conditions where redundancy is possible and the intent is primarily to facilitate
     * {@link Tracer#currentSpan}. Most often, this is used to eliminate redundant scopes by
     * wrappers.
     *
     * <p>For example, RxJava includes hooks to wrap types that represent an asynchronous functional
     * composition. For example, {@code flowable.parallel().flatMap(Y).sequential()} Assembly hooks
     * can ensure each stage of this operation can see the initial trace context. However, other tools
     * can also instrument the stages, including vert.x or even agent instrumentation. When wrapping
     * callbacks, it can reduce overhead to use {@code maybeScope} as opposed to {@code newScope}.
     *
     * <p>Generally speaking, this is best used for wrappers, such as executor services or lifecycle
     * hooks, which usually have no current trace context when invoked.
     *
     * <h3>Implementors note</h3>
     * <p>For those overriding this method, you must compare {@link MyTraceContext#traceIdHigh()},
     * {@link MyTraceContext#traceId()} and {@link MyTraceContext#spanId()} to decide if the contexts are
     * equivalent. Due to details of propagation, other data like parent ID are not considered in
     * equivalence checks.
     *
     * @param context span to place into scope or null to clear the scope
     * @return a new scope object or {@link Scope#NOOP} if the input is already the case
     */
    public Scope maybeScope(@Nullable MyTraceContext context) {
        MyTraceContext current = get();
        if (equals(current, context)) return decorateScope(context, Scope.NOOP);
        return newScope(context);
    }

    /**
     * A span remains in the scope it was bound to until close is called.
     */
    public interface Scope extends Closeable {
        /**
         * Returned when {@link MyCurrentTraceContext#maybeScope(MyTraceContext)} detected scope
         * redundancy.
         */
        Scope NOOP = new Scope() {
            @Override
            public void close() {
            }

            @Override
            public String toString() {
                return "NoopScope";
            }
        };

        /**
         * No exceptions are thrown when unbinding a span scope.
         */
        @Override
        void close();
    }

    /**
     * Use this to add features such as thread checks or log correlation when a scope is created or
     * closed.
     *
     * <p>While decoration technically occurs with {@link #newScope(MyTraceContext)} or
     * {@link #maybeScope(MyTraceContext)}, many tools use these underneath. For example, {@link
     * brave.Tracer#startScopedSpan(String)} and {@link brave.Tracer#withSpanInScope(brave.Span)} set
     * a span in scope. An executor wrapped with {@link #executor(Executor)} would decorate each
     * runnable.
     *
     * @since 5.2
     */
    public interface ScopeDecorator {
        /**
         * Use this when configuration results in no decoration needed.
         *
         * @since 5.11
         */
        ScopeDecorator NOOP = new ScopeDecorator() {
            @Override
            public Scope decorateScope(MyTraceContext context, Scope scope) {
                return scope;
            }

            @Override
            public String toString() {
                return "NoopScopeDecorator";
            }
        };

        /**
         * @param context null implies the scope should be cleared
         * @param scope   {@link Scope#NOOP} if the former decoration resulted in no change.
         */
        Scope decorateScope(@Nullable MyTraceContext context, Scope scope);
    }

    /**
     * Default implementation which is backed by a static thread local.
     *
     * <p>A static thread local ensures we have one context per thread, as opposed to one per thread-
     * tracer. This means all tracer instances will be able to see any tracer's contexts.
     *
     * <p>The trade-off of this (instance-based reference) vs the reverse: trace contexts are not
     * separated by tracer by default. For example, to make a trace invisible to another tracer, you
     * have to use a non-default implementation.
     *
     * <p>Sometimes people make different instances of the tracer just to change configuration like
     * the local service name. If we used a thread-instance approach, none of these would be able to
     * see eachother's scopes. This would break {@link Tracing#currentTracer()} scope visibility in a
     * way few would want to debug. It might be phrased as "MySQL always starts a new trace and I
     * don't know why."
     *
     * <p>If you want a different behavior, use a different subtype of {@link CurrentTraceContext},
     * possibly your own, or raise an issue and explain what your use case is.
     */
    public static final class Default extends MyThreadLocalCurrentTraceContext {
        // Inheritable as Brave 3's ThreadLocalServerClientAndLocalSpanState was inheritable
        static final InheritableThreadLocal<MyTraceContext> INHERITABLE = new InheritableThreadLocal<>();

        /**
         * Uses a non-inheritable static thread local
         */
        public static MyCurrentTraceContext create() {
            return MyThreadLocalCurrentTraceContext.create();
        }

        /**
         * Uses an inheritable static thread local which allows arbitrary calls to {@link
         * Thread#start()} to automatically inherit this context. This feature is available as it is was
         * the default in Brave 3, because some users couldn't control threads in their applications.
         *
         * <p>This can be a problem in scenarios such as thread pool expansion, leading to data being
         * recorded in the wrong span, or spans with the wrong parent. If you are impacted by this,
         * switch to {@link #create()}.
         */
        public static MyCurrentTraceContext inheritable() {
            return new Default();
        }

        Default() {
            super(new Builder(INHERITABLE));
        }
    }

    /**
     * Wraps the input so that it executes with the same context as now.
     */
    public <C> Callable<C> wrap(Callable<C> task) {
        final MyTraceContext invocationContext = get();
        class CurrentMyTraceContextCallable implements Callable<C> {
            @Override
            public C call() throws Exception {
                try (Scope scope = maybeScope(invocationContext)) {
                    return task.call();
                }
            }
        }
        return new CurrentMyTraceContextCallable();
    }

    /**
     * Wraps the input so that it executes with the same context as now.
     */
    public Runnable wrap(Runnable task) {
        final MyTraceContext invocationContext = get();
        class CurrentMyTraceContextRunnable implements Runnable {
            @Override
            public void run() {
                try (Scope scope = maybeScope(invocationContext)) {
                    task.run();
                }
            }
        }
        return new CurrentMyTraceContextRunnable();
    }

    /**
     * Decorates the input such that the {@link #get() current trace context} at the time a task is
     * scheduled is made current when the task is executed.
     */
    public Executor executor(Executor delegate) {
        class CurrentMyTraceContextExecutor implements Executor {
            @Override
            public void execute(Runnable task) {
                delegate.execute(MyCurrentTraceContext.this.wrap(task));
            }
        }
        return new CurrentMyTraceContextExecutor();
    }

    /**
     * Decorates the input such that the {@link #get() current trace context} at the time a task is
     * scheduled is made current when the task is executed.
     */
    public ExecutorService executorService(ExecutorService delegate) {
        class CurrentMyTraceContextExecutorService extends brave.internal.WrappingExecutorService {

            @Override
            protected ExecutorService delegate() {
                return delegate;
            }

            @Override
            protected <C> Callable<C> wrap(Callable<C> task) {
                return MyCurrentTraceContext.this.wrap(task);
            }

            @Override
            protected Runnable wrap(Runnable task) {
                return MyCurrentTraceContext.this.wrap(task);
            }
        }
        return new CurrentMyTraceContextExecutorService();
    }

    static boolean equals(@Nullable MyTraceContext a, @Nullable MyTraceContext b) {
        return a == null ? b == null : a.equals(b); // Java 6 can't use Objects.equals()
    }
}
