/*
 * Copyright 2017 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.opentracing.contrib.specialagent.webservletfilter;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.EnumSet;
import java.util.List;

import javax.servlet.DispatcherType;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import io.opentracing.contrib.specialagent.AgentRunner;
import io.opentracing.mock.MockSpan;
import io.opentracing.mock.MockTracer;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * @author gbrown
 * @author Seva Safris
 */
@RunWith(AgentRunner.class)
@AgentRunner.Config(disable = "okhttp")
public class JettyServletTest {
  // jetty starts on random port
  private int serverPort;
  private Server server;

  @Before
  public void beforeTest(MockTracer tracer) throws Exception {
    tracer.reset();

    MockFilter.count = 0;

    server = new Server(0);

    final ServletContextHandler servletContextHandler = new ServletContextHandler();
    servletContextHandler.setContextPath("/");
    server.setHandler(servletContextHandler);
    servletContextHandler.addFilter(MockFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
    servletContextHandler.addServlet(new ServletHolder(new MockServlet()), "/*");

    server.start();
    serverPort = ((ServerConnector)server.getConnectors()[0]).getLocalPort();
  }

  @Test
  public void testHelloF5Request(final MockTracer tracer) throws IOException {
    final OkHttpClient client = new OkHttpClient();
    final Request request = new Request.Builder().url("http://localhost:" + serverPort + "/hello")
        .addHeader("F5_test", "value")
        .addHeader("F5_ingressTime", "123")
        .addHeader("F5_egressTime", "321")
        .build();
    final Response response = client.newCall(request).execute();

    assertEquals(HttpServletResponse.SC_OK, response.code());
    assertEquals(1, MockFilter.count);

    final List<MockSpan> spans = tracer.finishedSpans();
    assertEquals(spans.toString(), 2, spans.size());

    MockSpan f5Span = spans.get(1);
    assertEquals("value", f5Span.tags().get("test"));
    assertEquals("F5", f5Span.tags().get("ServiceName"));
    assertEquals("TransitTime", f5Span.operationName());
    assertTrue(f5Span.logEntries().isEmpty());
    assertEquals(123000, f5Span.startMicros()); // ingressTime
    assertEquals(321000, f5Span.finishMicros()); // egressTime
    assertEquals(spans.get(0).context().traceId(), spans.get(1).context().traceId());
    assertEquals(spans.get(0).parentId(), spans.get(1).context().spanId());
  }

  @Test
  public void testHelloRequest(final MockTracer tracer) throws IOException {
    final OkHttpClient client = new OkHttpClient();
    final Request request = new Request.Builder().url("http://localhost:" + serverPort + "/hello").build();
    final Response response = client.newCall(request).execute();

    assertEquals(HttpServletResponse.SC_OK, response.code());
    assertEquals(1, MockFilter.count);

    final List<MockSpan> spans = tracer.finishedSpans();
    assertEquals(spans.toString(), 1, spans.size());

    assertEquals("GET", spans.get(0).operationName());
  }

  @After
  public void afterTest() throws Exception {
    server.stop();
    server.join();
  }
}