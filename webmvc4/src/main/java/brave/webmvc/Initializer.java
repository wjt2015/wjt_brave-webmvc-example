package brave.webmvc;

import brave.spring.webmvc.MyDelegatingTracingFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.SpringServletContainerInitializer;
import org.springframework.web.servlet.support.MyAbstractAnnotationConfigDispatcherServletInitializer;

import javax.servlet.Filter;
import java.util.Arrays;

/**
 * Indirectly invoked by {@link SpringServletContainerInitializer} in a Servlet 3+ container
 */
@Slf4j
public class Initializer extends MyAbstractAnnotationConfigDispatcherServletInitializer {

    @Override
    protected String[] getServletMappings() {
        return new String[]{"/"};
    }

    @Override
    protected Class<?>[] getRootConfigClasses() {
        final Class<?>[] rootConfigClasses = new Class[]{TracingConfiguration.class, AppConfiguration.class};
        log.info("rootConfigClasses={};", Arrays.asList(rootConfigClasses));
        return rootConfigClasses;
    }

    /**
     * Ensures tracing is setup for all HTTP requests.
     */
    @Override
    protected Filter[] getServletFilters() {
        return new Filter[]{new MyDelegatingTracingFilter()};
    }

    @Override
    protected Class<?>[] getServletConfigClasses() {
        return new Class[]{Frontend.class, Backend.class};
    }
}
