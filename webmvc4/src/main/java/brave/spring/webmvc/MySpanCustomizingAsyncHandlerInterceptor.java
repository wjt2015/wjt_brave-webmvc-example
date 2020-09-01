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
package brave.spring.webmvc;

import brave.SpanCustomizer;
import brave.servlet.TracingFilter;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.handler.HandlerInterceptorAdapter;

import static brave.spring.webmvc.SpanCustomizingHandlerInterceptor.setErrorAttribute;
import static brave.spring.webmvc.SpanCustomizingHandlerInterceptor.setHttpRouteAttribute;

/**
 * Same as {@link SpanCustomizingHandlerInterceptor} except it can be used as both an {@link
 * AsyncHandlerInterceptor} or a normal {@link HandlerInterceptor}.
 */
@Slf4j
@Service(value = "mySpanCustomizingAsyncHandlerInterceptor")
public final class MySpanCustomizingAsyncHandlerInterceptor extends HandlerInterceptorAdapter {
    @Autowired(required = false)
    HandlerParser handlerParser = new HandlerParser();

    MySpanCustomizingAsyncHandlerInterceptor() { // hide the ctor so we can change later if needed
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object o) {
        Object span = request.getAttribute(SpanCustomizer.class.getName());
        if (span instanceof SpanCustomizer) {
            SpanCustomizer spanCustomizer=(SpanCustomizer)span;

            spanCustomizer.tag("tagA","interceptor");

            handlerParser.preHandle(request, o, spanCustomizer);
        }
        log.info("request.url={};span={};", request.getRequestURL(), span);
        return true;
    }

    /**
     * Sets the "error" and "http.route" attributes so that the {@link TracingFilter} can read them.
     */
    @Override
    public void afterCompletion(
            HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        Object span = request.getAttribute(SpanCustomizer.class.getName());

        if (span instanceof SpanCustomizer) {
            setErrorAttribute(request, ex);
            setHttpRouteAttribute(request);
        }
        log.info("request.url={};handler={};ex={};span={};", request.getRequestURL(), handler, ex, span);
    }
}
