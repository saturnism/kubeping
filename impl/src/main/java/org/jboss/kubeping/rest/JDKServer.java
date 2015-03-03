/**
 *  Copyright 2014 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package org.jboss.kubeping.rest;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.jgroups.Channel;
import org.jgroups.protocols.PingData;

/**
 * @author <a href="mailto:ales.justin@jboss.org">Ales Justin</a>
 */
public class JDKServer extends AbstractServer {
    private HttpServer server;

    public JDKServer(int port, Channel channel) {
        super(port, channel);
    }

    @Override
    public void start() throws Exception {
        InetSocketAddress address = new InetSocketAddress("0.0.0.0", port);
        server = HttpServer.create(address, 0);
        server.setExecutor(Executors.newCachedThreadPool());

        server.createContext("/", new Handler());

        server.start();
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private class Handler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.sendResponseHeaders(200, 0);
            try {
                PingData data = Utils.createPingData(channel, coord);
                try (OutputStream outputStream = exchange.getResponseBody()) {
                    data.writeTo(new DataOutputStream(outputStream));
                }
            } catch (Exception e) {
                throw new IOException(e);
            }
        }
    }
}
