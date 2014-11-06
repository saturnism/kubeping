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

package org.jboss.test.kubeping;

import java.io.DataInputStream;
import java.io.InputStream;
import java.net.URL;

import org.jboss.kubeping.KubePing;
import org.jboss.kubeping.rest.Server;
import org.jgroups.protocols.PingData;
import org.jgroups.stack.Protocol;
import org.junit.Assert;
import org.junit.Test;

/**
 * @author <a href="mailto:ales.justin@jboss.org">Ales Justin</a>
 */
public class ServerTest extends TestBase {
    @Override
    protected int getNum() {
        return 1;
    }

    protected Protocol createPing() {
        return new KubePing();
    }

    @Test
    public void testResponse() throws Exception {
        URL url = new URL("http://localhost:8888");
        try (InputStream stream = url.openStream()) {
            PingData data = new PingData();
            data.readFrom(new DataInputStream(stream));
            Assert.assertEquals(data, Server.createPingData(channels[0]));
        }
    }

}
