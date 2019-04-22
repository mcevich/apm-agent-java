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

import co.elastic.apm.agent.bci.ElasticApmInstrumentation;
import co.elastic.apm.agent.impl.ElasticApmTracer;
import net.bytebuddy.description.NamedElement;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.util.Collection;
import java.util.Collections;

import static net.bytebuddy.matcher.ElementMatchers.*;
import static net.bytebuddy.matcher.ElementMatchers.named;

public class ValveInstrumentation extends ElasticApmInstrumentation {

    static final String VALVE_API = "valve-api";

    @Override
    public void init(ElasticApmTracer tracer) {
        ValveApiAdvice.init(tracer);
    }

    @Override
    public ElementMatcher<? super NamedElement> getTypeMatcherPreFilter() {
        return nameContains("Valve");
    }

    @Override
    public ElementMatcher<? super TypeDescription> getTypeMatcher() {
        return not(isInterface())
            .and(hasSuperType(named("org.apache.catalina.valves.ValveBase")));
    }

    @Override
    public ElementMatcher<? super MethodDescription> getMethodMatcher() {
        return named("invoke")
            .and(takesArgument(0, named("org.apache.catalina.connector.Request")))
            .and(takesArgument(1, named("org.apache.catalina.connector.Response")));
    }

    @Override
    public Class<?> getAdviceClass() {
        return ValveApiAdvice.class;
    }

    @Override
    public Collection<String> getInstrumentationGroupNames()  {
        return Collections.singleton(VALVE_API);
    }
}
