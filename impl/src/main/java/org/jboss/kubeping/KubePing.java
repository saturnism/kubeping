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

package org.jboss.kubeping;

import java.util.List;

import org.jboss.kubeping.rest.Client;
import org.jboss.kubeping.rest.Container;
import org.jboss.kubeping.rest.Context;
import org.jboss.kubeping.rest.Pod;
import org.jboss.kubeping.rest.Server;
import org.jboss.kubeping.rest.ServerFactory;
import org.jboss.kubeping.rest.Utils;
import org.jgroups.Address;
import org.jgroups.Event;
import org.jgroups.PhysicalAddress;
import org.jgroups.View;
import org.jgroups.annotations.MBean;
import org.jgroups.annotations.Property;
import org.jgroups.conf.ClassConfigurator;
import org.jgroups.protocols.Discovery;
import org.jgroups.protocols.PingData;
import org.jgroups.util.Responses;
import org.jgroups.util.UUID;

/**
 * @author <a href="mailto:ales.justin@jboss.org">Ales Justin</a>
 */
@MBean(description = "Kubernetes based discovery protocol")
public class KubePing extends Discovery {
    static {
        ClassConfigurator.addProtocol(Constants.KUBE_PING_ID, KubePing.class);
    }

    @Property
    private String host;

    @Property
    private String port;

    @Property
    private String version;

    @Property
    private int serverPort;

    @Property
    private String labelsQuery;

    @Property
    private String pingPortName = "ping";

    private ServerFactory factory;
    private Server server;
    private Client client;

    public void setFactory(ServerFactory factory) {
        this.factory = factory;
    }

    private String getHost() {
        if (host != null) {
            return host;
        } else {
            return System.getenv("KUBERNETES_RO_SERVICE_HOST");
        }
    }

    public void setHost(String host) {
        this.host = host;
    }

    private String getPort() {
        if (port != null) {
            return port;
        } else {
            return System.getenv("KUBERNETES_RO_SERVICE_PORT");
        }
    }

    public void setPort(String port) {
        this.port = port;
    }

    private String getVersion() {
        if (version != null) {
            return version;
        } else {
            return "v1beta1";
        }
    }

    private int getServerPort() {
        if (serverPort > 0) {
            return serverPort;
        } else {
            return 8888;
        }
    }

    public String getLabelsQuery() {
        return labelsQuery;
    }

    public void setLabelsQuery(String labelsQuery) {
        this.labelsQuery = labelsQuery;
    }

    public String getPingPortName() {
        return pingPortName;
    }

    public void setPingPortName(String pingPortName) {
        this.pingPortName = pingPortName;
    }

    protected Client createClient() throws Exception {
        return new Client(getHost(), getPort(), getVersion());
    }

    @Override
    public void start() throws Exception {
        super.start();

        client = createClient();
        log.info(client.info());

        if (factory != null) {
            server = factory.create(getServerPort(), stack.getChannel());
        } else {
            server = Utils.createServer(getServerPort(), stack.getChannel());
        }
        log.info(String.format("Server deamon port: %s, channel address: %s, server: %s", getServerPort(), stack.getChannel().getAddress(), server.getClass().getSimpleName()));
        server.start();
    }

    @Override
    public void stop() {
        try {
            server.stop();
        } finally {
            super.stop();
        }
    }

    /**
     * @return all data
     */
    protected synchronized void readAll(List<Address> members, String clusterName, Responses responses) {
        try {
            log.trace("Find pods from k8s with query %s", getLabelsQuery());
            List<Pod> pods = client.getPods(getLabelsQuery());
            for (Pod pod : pods) {
                log.trace("Checking if pod %s belongs to this cluster", pod.getPodIP());
                List<Container> containers = pod.getContainers();
                for (Container container : containers) {
                    log.trace("Checking if container %s belongs to this cluster", container.getName());
                    Context context = new Context(pod, container, getPingPortName());
                    if (client.accept(context)) {
                        log.trace("Pod %s container %s belongs here", pod.getPodIP(), container.getName());
                        PingData data = client.getPingData(container.getPodIP(), container.getPort(getPingPortName()).getContainerPort());
                        log.trace("Pod %s container %s ping response %s", pod.getPodIP(), container.getName(), data.toString());

                        if(members == null || members.contains(data.getAddress())) {
                            log.trace("Add response: %s", data.toString());
                            responses.addResponse(data, true);
                        }

                        if(local_addr != null && !local_addr.equals(data.getAddress())) {
                            log.trace("Add to cache: %s", data.toString());
                            addDiscoveryResponseToCaches(data.getAddress(), data.getLogicalName(), data.getPhysicalAddr());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn(String.format("Failed to read ping data from Kubernetes [%s] for cluster: %s", client.info(), clusterName), e);
        }
    }

    @Override
    public boolean isDynamic() {
        return true;
    }

    @Override
    protected void findMembers(List<Address> members,
            boolean initial_discovery, Responses responses) {

        try {
            log.trace("findMembers, initial_discovery = %s", initial_discovery);

            readAll(members, cluster_name, responses);
            if(responses.isEmpty()) {
                server.coord(is_coord);
                log.trace("No members found, returning");
                return;
            }

            PhysicalAddress phys_addr=(PhysicalAddress)down_prot.down(new Event(Event.GET_PHYSICAL_ADDRESS, local_addr));
            PingData data=responses.findResponseFrom(local_addr);
            // the logical addr *and* IP address:port have to match
            if(data != null && data.getPhysicalAddr().equals(phys_addr)) {
                if(data.isCoord() && initial_discovery) {
                    log.trace("is coordinator and is initial discovery - clear responses?");
                    responses.clear();
                }
                else {
                    log.trace("Do nothing");
                    ; // use case #1 if we have predefined files: most members join but are not coord
                }
            }
            else {
                log.info("Send discovery response local_addr=[%s], phys_addr=[%s]", local_addr, phys_addr);
                sendDiscoveryResponse(local_addr, phys_addr, UUID.get(local_addr), null, false);
            }
        } finally {
            responses.done();
        }
    }

    @Override
    protected boolean addDiscoveryResponseToCaches(Address mbr, String logical_name, PhysicalAddress physical_addr) {
        PhysicalAddress phys_addr=(PhysicalAddress)down_prot.down(new Event(Event.GET_PHYSICAL_ADDRESS, mbr));
        boolean added=phys_addr == null || !phys_addr.equals(physical_addr);
        super.addDiscoveryResponseToCaches(mbr, logical_name, physical_addr);
        return added;
    }

    @Override
    public Object down(Event evt) {
        switch(evt.getType()) {
        case Event.VIEW_CHANGE:
            View old_view=view;
            boolean previous_coord=is_coord;
            Object retval=super.down(evt);
            View new_view=(View)evt.getArg();
            handleView(new_view, old_view, previous_coord != is_coord);
            return retval;
        }
        return super.down(evt);
    }

    private void handleView(View new_view, View old_view, boolean coord_changed) {
        server.coord(is_coord);
    }
}
