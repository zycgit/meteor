/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.rpcbind;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.domain.ProtocolConfig;

/** Explicit rpcbind control calls through Meteor; borrows its context and never automatically registers a program. */
public final class RpcbindClient {
    private final InterAddress      target;
    private final String            netid;
    private final PortMapperService ports;
    private final RpcbindV3Service  version3;
    private final RpcbindV4Service  version4;

    public RpcbindClient(MetContext context, InterAddress target) {
        this.target = Objects.requireNonNull(target, "target");
        Objects.requireNonNull(context, "context");
        this.netid = transportNetid(context, target);

        // Register only missing consumer contracts. Their lifetime belongs to the supplied Meteor context.
        MetClient connection = context.getRsfClient(target);
        this.ports = connection.getRemote(service(context, PortMapperService.class));
        this.version3 = connection.getRemote(service(context, RpcbindV3Service.class));
        this.version4 = connection.getRemote(service(context, RpcbindV4Service.class));
    }

    /** Version 2 discovery; zero means absent. The requested tcp/udp transport is independent of this call. */
    public int getPort(long program, long version, String netid) throws Exception {
        long protocol = ipProtocol(netid);
        long result = this.ports.getPort(new PortMapperMapping(program, version, protocol, 0));
        if (result < 0 || result > 65535) {
            throw new IllegalArgumentException("Port Mapper returned an invalid IP port");
        }
        return (int) result;
    }

    /** Version 3 GETADDR may return another version of the program, as specified by RFC 1833. */
    public String getAddress(long program, long version, String netid) throws Exception {
        RpcbindMapping query = this.lookupQuery(program, version, netid);
        return absent(this.version3.getAddress(query));
    }

    /** Version 4 GETADDR has the same fallback semantics as version 3. */
    public String getAddressV4(long program, long version, String netid) throws Exception {
        RpcbindMapping query = this.lookupQuery(program, version, netid);
        return absent(this.version4.getAddress(query));
    }

    /** Version 4 GETVERSADDR matches the program version exactly; null means absent. */
    public String getVersionAddress(long program, long version, String netid) throws Exception {
        RpcbindMapping query = this.lookupQuery(program, version, netid);
        return absent(this.version4.getVersionAddress(query));
    }

    public PortMapperMapping[] dumpPorts() throws Exception {
        return this.ports.dump();
    }

    public RpcbindMapping[] dumpMappings() throws Exception {
        return this.version3.dump();
    }

    /** rpcbind server time, in unsigned seconds since the Unix epoch. */
    public long getTime() throws Exception {
        return this.version3.getTime();
    }

    /** Explicit local version 2 SET; the rpcbind server decides registration ownership and authorization. */
    public boolean setPort(PortMapperMapping mapping) throws Exception {
        this.requireLocalTarget();
        requirePortMapping(mapping);
        return this.ports.set(mapping);
    }

    /** Explicit version 2 UNSET removes both TCP and UDP registrations for this program/version. */
    public boolean unsetPort(long program, long version) throws Exception {
        this.requireLocalTarget();
        return this.ports.unset(new PortMapperMapping(program, version, 0, 0));
    }

    /** Explicit local version 3 SET; the universal address must describe a local endpoint. */
    public boolean setMapping(RpcbindMapping mapping) throws Exception {
        this.requireLocalTarget();
        requireNetid(mapping.getNetid());
        InterAddress address = toAddress("oncrpc", mapping.getAddress(), "rpcbind");
        if (address == null || !localAddress(address.getHost())) {
            throw new IllegalArgumentException("rpcbind SET requires a local universal address");
        }
        if (mapping.getNetid().endsWith("6") != address.getHost().contains(":")) {
            throw new IllegalArgumentException("rpcbind netid and universal address families differ");
        }
        return this.version3.set(mapping);
    }

    /** Explicit local version 3 UNSET of one netid; the rpcbind server enforces its owner policy. */
    public boolean unsetMapping(long program, long version, String netid) throws Exception {
        this.requireLocalTarget();
        return this.version3.unset(query(program, version, netid));
    }

    /** Convert an IP universal address, including IPv6; its final two decimal components encode the port. */
    public static InterAddress toAddress(String scheme, String universalAddress, String unit) {
        if (universalAddress == null || universalAddress.isEmpty()) {
            return null;
        }
        int lowSeparator = universalAddress.lastIndexOf('.');
        int highSeparator = universalAddress.lastIndexOf('.', lowSeparator - 1);
        if (highSeparator <= 0) {
            throw new IllegalArgumentException("Invalid rpcbind universal address");
        }
        int high = octet(universalAddress.substring(highSeparator + 1, lowSeparator));
        int low = octet(universalAddress.substring(lowSeparator + 1));
        int port = high * 256 + low;
        if (port == 0) {
            return null;
        }
        String host = universalAddress.substring(0, highSeparator);
        return new InterAddress(scheme, host, port, unit);
    }

    private static <T> MetBindInfo<T> service(MetContext context, Class<T> type) {
        synchronized (context) {
            MetBindInfo<T> service = context.getServiceInfo(type);
            if (service == null) {
                String[] protocols = protocols(context);
                String[] additional = Arrays.copyOfRange(protocols, 1, protocols.length);
                var binding = context.publisher().rsfService(type) //
                        .protocol(protocols[0], additional);
                binding.asShadow().asDistributedCall();
                var resource = RpcbindCodecs.resource(type);
                for (String protocol : protocols) {
                    binding.bindResource(protocol, resource);
                }
                service = binding.register();
            }
            return service;
        }
    }

    private static String[] protocols(MetContext context) {
        Set<String> names = new LinkedHashSet<>();
        for (ConnectorConfig connector : context.getSettings().getConnectorConfigs()) {
            for (ProtocolConfig protocol : connector.protocols()) {
                if (oncRpcFactory(protocol)) {
                    names.add(protocol.name());
                }
            }
        }
        return names.toArray(String[]::new);
    }

    private void requireLocalTarget() throws UnknownHostException, SocketException {
        if (!localAddress(this.target.getHost())) {
            throw new IllegalStateException("rpcbind SET and UNSET are restricted to a local rpcbind target");
        }
    }

    private static boolean localAddress(String host) throws UnknownHostException, SocketException {
        InetAddress address = InetAddress.getByName(host);
        return address.isLoopbackAddress() || NetworkInterface.getByInetAddress(address) != null;
    }

    private static RpcbindMapping query(long program, long version, String netid) {
        requireNetid(netid);
        return new RpcbindMapping(program, version, netid, "", "");
    }

    private RpcbindMapping lookupQuery(long program, long version, String netid) {
        if (!StringUtils.equals(this.netid, netid)) {
            throw new IllegalArgumentException("GETADDR derives netid from its call transport; use a matching target");
        }
        return query(program, version, netid);
    }

    private static String transportNetid(MetContext context, InterAddress target) {
        String selected = null;
        for (ConnectorConfig connector : context.getSettings().getConnectorConfigs()) {
            for (ProtocolConfig protocol : connector.protocols()) {
                if (!StringUtils.equalsIgnoreCase(protocol.scheme(), target.getSchema())) {
                    continue;
                }
                if (!oncRpcFactory(protocol)) {
                    throw new IllegalArgumentException("rpcbind target must use an ONC RPC protocol");
                }
                String transport = connector.transportType();
                boolean supported = StringUtils.equals("tcp", transport) || StringUtils.equals("udp", transport);
                if (!supported || selected != null && !StringUtils.equals(selected, transport)) {
                    throw new IllegalArgumentException("rpcbind target scheme has an ambiguous IP transport");
                }
                selected = transport;
            }
        }
        if (selected == null) {
            throw new IllegalArgumentException("rpcbind target scheme must be mounted on a TCP or UDP endpoint");
        }
        return target.getHost().contains(":") ? selected + "6" : selected;
    }

    private static boolean oncRpcFactory(ProtocolConfig protocol) {
        return StringUtils.equals("oncrpc", protocol.name()) //
                || StringUtils.equals("oncrpc-udp", protocol.name());
    }

    private static String absent(String address) {
        return address == null || address.isEmpty() ? null : address;
    }

    private static long ipProtocol(String netid) {
        if (StringUtils.equals("tcp", netid)) {
            return 6;
        }
        if (StringUtils.equals("udp", netid)) {
            return 17;
        }
        throw new IllegalArgumentException("Port Mapper version 2 netid must be tcp or udp");
    }

    private static void requireNetid(String netid) {
        boolean tcp = StringUtils.equals("tcp", netid) || StringUtils.equals("tcp6", netid);
        boolean udp = StringUtils.equals("udp", netid) || StringUtils.equals("udp6", netid);
        if (!tcp && !udp) {
            throw new IllegalArgumentException("rpcbind netid must be tcp, tcp6, udp or udp6");
        }
    }

    private static void requirePortMapping(PortMapperMapping mapping) {
        long protocol = mapping.getProtocol();
        if ((protocol != 6 && protocol != 17) || mapping.getPort() < 1 || mapping.getPort() > 65535) {
            throw new IllegalArgumentException("Port Mapper SET requires TCP/UDP and an IP port");
        }
    }

    private static int octet(String text) {
        try {
            if (text.isEmpty() || text.length() > 3) {
                throw new NumberFormatException();
            }
            for (int index = 0; index < text.length(); index++) {
                if (text.charAt(index) < '0' || text.charAt(index) > '9') {
                    throw new NumberFormatException();
                }
            }
            int value = Integer.parseInt(text);
            if (value > 255) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Invalid rpcbind universal address port", invalid);
        }
    }
}
