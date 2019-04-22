/*-
 * #%L
 * Elastic APM Java agent
 * %%
 * Copyright (C) 2018 - 2019 Elastic and contributors
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package co.elastic.apm.agent.tomcat.valve;

import co.elastic.apm.agent.bci.VisibleForAdvice;
import co.elastic.apm.agent.impl.ElasticApmTracer;
import co.elastic.apm.agent.impl.Scope;
import co.elastic.apm.agent.impl.context.Request;
import co.elastic.apm.agent.impl.context.Response;
import co.elastic.apm.agent.impl.transaction.TraceContext;
import co.elastic.apm.agent.impl.transaction.Transaction;
import co.elastic.apm.agent.web.WebConfiguration;
import net.bytebuddy.asm.Advice;
import org.apache.catalina.valves.ValveBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.servlet.http.Cookie;
import java.security.Principal;
import java.util.Enumeration;
import java.util.Map;

public class ValveApiAdvice {

    public static final Logger log = LoggerFactory.getLogger(ValveApiAdvice.class);

    @Nullable
    @VisibleForAdvice
    public static ValveTransactionHelper valveTransactionHelper;
    @Nullable
    @VisibleForAdvice
    public static ElasticApmTracer tracer;
    @VisibleForAdvice
    public static ThreadLocal<Boolean> excluded = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return Boolean.FALSE;
        }
    };

    static void init(ElasticApmTracer tracer) {
        ValveApiAdvice.tracer = tracer;
        valveTransactionHelper = new ValveTransactionHelper(tracer);
    }

    @Nullable
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnterServletService(@Advice.Argument(0) org.apache.catalina.connector.Request servletRequest,
                                             @Advice.Local("transaction") Transaction transaction,
                                             @Advice.Local("scope") Scope scope) {
        if (tracer == null) {
            return;
        }

        if (valveTransactionHelper != null &&
//            servletRequest instanceof HttpServletRequest &&
            !Boolean.TRUE.equals(excluded.get())) {

            transaction = valveTransactionHelper.onBefore(
                servletRequest.getClass().getClassLoader(),
                servletRequest.getServletPath(), servletRequest.getPathInfo(),
                servletRequest.getHeader("User-Agent"),
                servletRequest.getHeader(TraceContext.TRACE_PARENT_HEADER));
            if (transaction == null) {
                // if the request is excluded, avoid matching all exclude patterns again on each filter invocation
                excluded.set(Boolean.TRUE);
                return;
            }
            final Request req = transaction.getContext().getRequest();
            if (transaction.isSampled() && tracer.getConfig(WebConfiguration.class).isCaptureHeaders()) {
                if (servletRequest.getCookies() != null) {
                    for (Cookie cookie : servletRequest.getCookies()) {
                        req.addCookie(cookie.getName(), cookie.getValue());
                    }
                }
                final Enumeration headerNames = servletRequest.getHeaderNames();
                if (headerNames != null) {
                    while (headerNames.hasMoreElements()) {
                        final String headerName = (String) headerNames.nextElement();
                        req.addHeader(headerName, servletRequest.getHeaders(headerName));
                    }
                }
            }

            valveTransactionHelper.fillRequestContext(transaction, servletRequest.getProtocol(), servletRequest.getMethod(), servletRequest.isSecure(),
                servletRequest.getScheme(), servletRequest.getServerName(), servletRequest.getServerPort(), servletRequest.getRequestURI(), servletRequest.getQueryString(),
                servletRequest.getRemoteAddr(), servletRequest.getHeader("Content-Type"));
        }
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void onExitServletService(@Advice.Argument(0) org.apache.catalina.connector.Request servletRequest,
                                            @Advice.Argument(1) org.apache.catalina.connector.Response servletResponse,
                                            @Advice.Local("transaction") @Nullable Transaction transaction,
                                            @Advice.Local("scope") @Nullable Scope scope,
                                            @Advice.Thrown @Nullable Throwable t,
                                            @Advice.This Object thiz) {
        if (tracer == null) {
            return;
        }
        excluded.set(Boolean.FALSE);
        if (scope != null) {
            scope.close();
        }
        if (thiz instanceof ValveBase) {
            Transaction currentTransaction = tracer.currentTransaction();
            if (currentTransaction != null) {

                ValveTransactionHelper.setTransactionNameByServletClass(servletRequest.getMethod(), thiz.getClass(), currentTransaction.getName());
                final Principal userPrincipal = servletRequest.getUserPrincipal();
                ValveTransactionHelper.setUsernameIfUnset(userPrincipal != null ? userPrincipal.getName() : null, currentTransaction.getContext());
            }
        }
        if (valveTransactionHelper != null &&
            transaction != null
        ) {
            if (transaction.isSampled() && tracer.getConfig(WebConfiguration.class).isCaptureHeaders()) {
                try {
                    final Response resp = transaction.getContext().getResponse();
                    for (String headerName : ValveUtil.getHeadersData(servletResponse.getHeaderNames())) {
                        resp.addHeader(headerName, servletResponse.getHeader(headerName));
                    }
                } catch(Exception e) {
                    log.error("Compile against another Tomcat version, getHeadesNames are collection in new ones");
                }
            }
            // request.getParameterMap() may allocate a new map, depending on the servlet container implementation
            // so only call this method if necessary
            final String contentTypeHeader = servletRequest.getHeader("Content-Type");
            final Map<String, String[]> parameterMap;
            if (transaction.isSampled() && valveTransactionHelper.captureParameters(servletRequest.getMethod(), contentTypeHeader)) {
                parameterMap = servletRequest.getParameterMap();
            } else {
                parameterMap = null;
            }
            valveTransactionHelper.onAfter(transaction, t, servletResponse.isCommitted(), servletResponse.getStatus(), servletRequest.getMethod(),
                parameterMap, servletRequest.getServletPath(), servletRequest.getPathInfo(), contentTypeHeader);
        }
    }
}
