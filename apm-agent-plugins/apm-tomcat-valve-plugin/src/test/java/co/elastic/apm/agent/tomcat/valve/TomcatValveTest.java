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

import co.elastic.apm.agent.MockReporter;
import co.elastic.apm.agent.bci.ElasticApmAgent;
import co.elastic.apm.agent.configuration.SpyConfiguration;
import co.elastic.apm.agent.impl.ElasticApmTracerBuilder;
import co.elastic.apm.agent.impl.stacktrace.StacktraceConfiguration;
import co.elastic.apm.agent.impl.transaction.Transaction;
import co.elastic.apm.agent.matcher.WildcardMatcher;
import co.elastic.apm.agent.report.serialize.DslJsonSerializer;
import co.elastic.apm.agent.web.WebConfiguration;
import net.bytebuddy.agent.ByteBuddyAgent;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.stagemonitor.configuration.ConfigurationRegistry;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// TEST does not work with older catalina version
public class TomcatValveTest {

    private static final Logger log = LoggerFactory.getLogger(TomcatValveTest.class);

    protected static final MockReporter reporter = new MockReporter();
    private static ConfigurationRegistry config;
    private OkHttpClient httpClient = new OkHttpClient.Builder()
        // set to 0 for debugging
        .readTimeout(10,TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build();

    private DslJsonSerializer jsonSerializer;
    private static Tomcat tomcat;

    @BeforeEach
    void setUp() {
        SpyConfiguration.reset(config);
        jsonSerializer = new DslJsonSerializer(mock(StacktraceConfiguration.class));
    }

    @BeforeAll
    static void initInstrumentation() throws LifecycleException {
        config = SpyConfiguration.createSpyConfig();
        when(config.getConfig(WebConfiguration.class).getIgnoreUrls()).thenReturn(List.of(WildcardMatcher.valueOf("/init")));
        ElasticApmAgent.initInstrumentation(new ElasticApmTracerBuilder()
            .configurationRegistry(config)
            .reporter(reporter)
            .build(), ByteBuddyAgent.install());

    }

    @AfterAll
    static void afterAll() throws LifecycleException {
        ElasticApmAgent.reset();
        tomcat.stop();
    }

    @BeforeAll
    static void initServer() throws Exception {
        tomcat = new Tomcat();
        tomcat.setBaseDir("temp");
        tomcat.setPort(8080);

        String contextPath = "";
        String docBase = new File(".").getAbsolutePath();

        Context context = tomcat.addContext(contextPath, docBase);

        HttpServlet servlet = new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
                PrintWriter writer = resp.getWriter();

                writer.print("Hello World!");
            }
        };

        String servletName = "Servlet1";
        String urlPattern = "/go";

        tomcat.addServlet(contextPath, servletName, servlet);
        context.addServletMapping(urlPattern, servletName);

        tomcat.start();
    }

    @Test
    public void should_have_index() throws IOException, LifecycleException, InterruptedException {


        callServlet(1, "/go");

    }

    protected Response get(String path) throws IOException {
        return httpClient.newCall(new okhttp3.Request.Builder().url("http://localhost:" + "8080" + path).build()).execute();
    }

    private void callServlet(int expectedTransactions, String path) throws IOException, InterruptedException {
        final Response response = get(path);
        assertThat(response.code()).isEqualTo(200);
        assertThat(response.body().string()).isEqualTo("Hello World!");

        if (expectedTransactions > 0) {
            Transaction t = reporter.getFirstTransaction(500);
            log.info(jsonSerializer.toJsonString(t));
//            assertThat(reporter.getTransactions().stream().map(transaction -> transaction.getTraceContext().getServiceName()).distinct()).containsExactly(getClass().getSimpleName());
        }
        assertThat(reporter.getTransactions()).hasSize(expectedTransactions);
    }
}
