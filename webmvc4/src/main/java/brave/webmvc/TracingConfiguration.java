package brave.webmvc;

import brave.CurrentSpanCustomizer;
import brave.MyCurrentSpanCustomizer;
import brave.SpanCustomizer;
import brave.Tracing;
import brave.baggage.BaggageField;
import brave.baggage.BaggagePropagation;
import brave.baggage.BaggagePropagationConfig.SingleBaggageField;
import brave.baggage.CorrelationScopeConfig.SingleCorrelationField;
import brave.context.log4j2.ThreadContextScopeDecorator;
import brave.http.HttpTracing;
import brave.httpclient.TracingHttpClientBuilder;
import brave.propagation.CurrentTraceContext.ScopeDecorator;
import brave.propagation.MyB3Propagation;
import brave.propagation.Propagation;
import brave.propagation.ThreadLocalCurrentTraceContext;
import brave.spring.webmvc.DelegatingTracingFilter;
import brave.spring.webmvc.MySpanCustomizingAsyncHandlerInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.HttpClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter;
import zipkin2.reporter.Sender;
import zipkin2.reporter.brave.AsyncZipkinSpanHandler;
import zipkin2.reporter.okhttp3.OkHttpSender;

import javax.annotation.Resource;

/**
 * This adds tracing configuration to any web mvc controllers or rest template clients.
 *
 * <p>This is a {@link Initializer#getRootConfigClasses() root config class}, so the
 * {@linkplain DelegatingTracingFilter} added in {@link Initializer#getServletFilters()} can wire up
 * properly.
 */
@Slf4j
@Configuration
// Importing this class is effectively the same as declaring bean methods
@Import(MySpanCustomizingAsyncHandlerInterceptor.class)
public class TracingConfiguration extends WebMvcConfigurerAdapter {
    static final BaggageField USER_NAME = BaggageField.create("userName");

    /**
     * Allows log patterns to use {@code %{traceId}} {@code %{spanId}} and {@code %{userName}}
     */
    @Bean
    ScopeDecorator correlationScopeDecorator() {
        return ThreadContextScopeDecorator.newBuilder()
                .add(SingleCorrelationField.create(USER_NAME)).build();
    }

    /**
     * Configures propagation for {@link #USER_NAME}, using the remote header "user_name"
     */
    @Bean
    Propagation.Factory propagationFactory() {
        return BaggagePropagation.newFactoryBuilder(MyB3Propagation.FACTORY)
                .add(SingleBaggageField.newBuilder(USER_NAME).addKeyName("user_name").build())
                .build();
    }

    /**
     * Configuration for how to send spans to Zipkin
     */
    @Bean
    Sender sender() {
        return OkHttpSender.create("http://127.0.0.1:9411/api/v2/spans");
    }

    /**
     * Configuration for how to buffer spans into messages for Zipkin
     */
    @Bean
    AsyncZipkinSpanHandler zipkinSpanHandler() {
        return AsyncZipkinSpanHandler.create(sender());
    }

    /**
     * Controls aspects of tracing such as the service name that shows up in the UI
     */
    @Bean
    Tracing tracing(@Value("${zipkin.service:brave-webmvc-example}") String serviceName) {
        Tracing tracing = Tracing.newBuilder()
                .localServiceName(serviceName)
                .propagationFactory(propagationFactory())
                .currentTraceContext(ThreadLocalCurrentTraceContext.newBuilder()
                        .addScopeDecorator(correlationScopeDecorator())
                        .build()
                )
                .addSpanHandler(zipkinSpanHandler()).build();
        log.info("serviceName={};tracing={};", serviceName, tracing);
        return tracing;
    }

    /**
     * Allows someone to add tags to a span if a trace is in progress.
     */
    @Bean
    SpanCustomizer spanCustomizer(Tracing tracing) {
        final MyCurrentSpanCustomizer currentSpanCustomizer = MyCurrentSpanCustomizer.create(tracing);
        log.info("tracing={};currentSpanCustomizer={};", tracing, currentSpanCustomizer);
        return currentSpanCustomizer;
    }

    /**
     * Decides how to name and tag spans. By default they are named the same as the http method.
     */
    @Bean
    HttpTracing httpTracing(Tracing tracing) {
        HttpTracing httpTracing = HttpTracing.create(tracing);
        log.info("tracing={};httpTracing={};", tracing, httpTracing);
        return httpTracing;
    }

    /**
     * adds tracing to any underlying http client calls
     */
    @Bean
    HttpClient httpClient(HttpTracing httpTracing) {
        return TracingHttpClientBuilder.create(httpTracing).build();
    }

    @Resource(name = "mySpanCustomizingAsyncHandlerInterceptor")
    MySpanCustomizingAsyncHandlerInterceptor serverInterceptor;

    /**
     * adds tracing to the application-defined web controller
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(serverInterceptor);
    }
}
