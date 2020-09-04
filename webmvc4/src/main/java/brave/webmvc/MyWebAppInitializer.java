package brave.webmvc;

import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.web.WebApplicationInitializer;
import org.springframework.web.context.ContextLoaderListener;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.MyDispatcherServlet;

import javax.servlet.Servlet;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EventListener;
import java.util.List;

@Slf4j
public class MyWebAppInitializer implements WebApplicationInitializer {
    @Override
    public void onStartup(ServletContext servletContext) throws ServletException {
        setParams(servletContext);
        registerListener(servletContext);
        registerServlet(servletContext);
        registerFilter(servletContext);
    }

    public void registerListener(final ServletContext servletContext) {

        List<EventListener> listeners = getListeners();
        if (CollectionUtils.isNotEmpty(listeners)) {
            listeners.forEach(listener -> {
                servletContext.addListener(listener);
            });
        }

    }

    public void registerServlet(final ServletContext servletContext) {

    }

    public void registerFilter(final ServletContext servletContext) {

    }

    public void setParams(final ServletContext servletContext) {

    }

    protected List<EventListener> getListeners() {
        ContextLoaderListener contextLoaderListener = new ContextLoaderListener(getRootAppCtx());
        List<EventListener> listeners = Lists.newArrayList(contextLoaderListener);
        //return Collections.EMPTY_LIST;
        return listeners;
    }

    protected WebApplicationContext getRootAppCtx() {
        AnnotationConfigWebApplicationContext rootAppCtx=new AnnotationConfigWebApplicationContext();
        rootAppCtx.register(getRootConfigClasses());
        return rootAppCtx;
    }

    protected Class<?>[] getRootConfigClasses(){
        return new Class[]{AppConfiguration.class,TracingConfiguration.class};
    }

    public List<ServletInfo> getServlets(){
        MyDispatcherServlet dispatcherServlet=new MyDispatcherServlet(getServletAppCtx());

        return Lists.newArrayList(new ServletInfo(dispatcherServlet,"/",true));
    }

    public WebApplicationContext getServletAppCtx(){
        AnnotationConfigWebApplicationContext servletAppCtx=new AnnotationConfigWebApplicationContext();
        servletAppCtx.register(getServletConfigClasses());
        return servletAppCtx;
    }

    public Class<?>[] getServletConfigClasses(){
        return new Class[]{Frontend.class,Backend.class};
    }



    private static class ServletInfo{
        public Servlet servlet;
        public String urlMapping;
        public boolean matchAfter;

        public ServletInfo(Servlet servlet, String urlMapping, boolean matchAfter) {
            this.servlet = servlet;
            this.urlMapping = urlMapping;
            this.matchAfter = matchAfter;
        }
    }

}
