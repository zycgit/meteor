/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.address;
import java.net.*;
import java.util.*;
import java.util.regex.Pattern;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.cobble.logging.LoggerFactory;

/**
 * 服务地址例：“rsf://127.0.0.1:8000/unit”
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2014年9月12日
 */
public final class InterAddress {
    private static final Logger logger         = LoggerFactory.getLogger(InterAddress.class);
    public static final  String DEFAULT_SCHEMA = "rsf";
    private final        String schema;                                              //协议
    private final        String formUnit;                                            //所属单元
    private final        String hostAddress;                                         //地址
    private final        int    hostPort;                                            //端口
    private final        String hostSchema;

    public InterAddress(String newAddressURL) throws URISyntaxException {
        this(new URI(newAddressURL));
    }

    public InterAddress(URI newAddressURL) {
        this(newAddressURL, false);
    }

    private InterAddress(URI newAddressURL, boolean local) {
        if (!checkFormat(newAddressURL, local)) {
            throw new IllegalStateException(newAddressURL + " format error.");
        }

        String formPath = newAddressURL.getPath();
        if (formPath.startsWith("/")) {
            formPath = formPath.substring(1);
        }

        this.schema = newAddressURL.getScheme().toLowerCase(Locale.ROOT);
        this.formUnit = formPath.split("/")[0];
        this.hostAddress = newAddressURL.getHost();//
        this.hostPort = newAddressURL.getPort();
        this.hostSchema = String.format("%s://%s:%s/%s", this.schema, this.hostAddress, this.hostPort, this.formUnit);
    }

    public InterAddress(String hostAddress, int hostPort, String formUnit) {
        this(DEFAULT_SCHEMA, hostAddress, hostPort, formUnit);
    }

    public InterAddress(String schema, String hostAddress, int hostPort, String formUnit) {
        this(addressURI(schema, hostAddress, hostPort, formUnit));
    }

    /** Local endpoint configuration may use port zero; remote service addresses must use a concrete port. */
    public static InterAddress forBinding(String schema, String host, int port, String unit) {
        return new InterAddress(addressURI(schema, host, port, unit), true);
    }

    private static URI addressURI(String schema, String host, int port, String unit) {
        Objects.requireNonNull(schema, "schema is null.");
        Objects.requireNonNull(host, "hostAddress is null.");
        Objects.requireNonNull(unit, "formUnit is null.");

        try {
            return new URI(schema, null, host, port, "/" + unit, null, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid service address", e);
        }
    }

    private static String hostPort(String host, int port) {
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        return host + ":" + port;
    }

    /** 返回协议头 */
    public String getSchema() {
        return this.schema;
    }

    /** 返回地址所属单元 */
    public String getFormUnit() {
        return this.formUnit;
    }

    /** 返回目标IP地址 */
    public String getHost() {
        if (StringUtils.equalsIgnoreCase("local", this.hostAddress)) {
            List<String> localIpAddr = localIpAddr();
            if (localIpAddr.isEmpty()) {
                try {
                    return InetAddress.getLocalHost().getHostName();
                } catch (Exception e) {
                    return "localhost";
                }
            } else {
                return localIpAddr.get(0);
            }
        }
        return this.hostAddress;
    }

    /** 返回IPv4地址 */
    public String getIp() throws UnknownHostException {
        return InetAddress.getByName(getHost()).getHostAddress();
    }

    /** 返回目标地址的端口号 */
    public int getPort() {
        return this.hostPort;
    }

    /** 返回IP地址和端口，格式为：192.168.25.33:8000 */
    public String getHostPort() {
        return hostPort(getHost(), getPort());
    }

    /** 返回IP地址和端口，格式为：192.168.25.33:8000 */
    public String getIpPort() throws UnknownHostException {
        return hostPort(getIp(), getPort());
    }

    /** 转换地址为URL形式 */
    public URI toURI() throws URISyntaxException {
        return new URI(this.getSchema(), null, this.getHost(), this.getPort(), "/" + this.formUnit, null, null);
    }

    /** 返回RSF协议形式表述的主机地址。格式为：“rsf://127.0.0.1:8000/unit” */
    public String toHostSchema() {
        return this.hostSchema;
    }

    /** 转换成{@link SocketAddress}类型对象 */
    public InetSocketAddress toSocketAddress() throws UnknownHostException {
        return new InetSocketAddress(getIp(), getPort());
    }

    /**
     * 两个 Address 可以比较是否相等
     * @param obj 另一个对象
     * @return 返回结果。
     */
    public boolean equals(Object obj) {
        String diffURI = "";
        if (obj instanceof InterAddress) {
            diffURI = ((InterAddress) obj).toHostSchema();
            return StringUtils.equalsIgnoreCase(diffURI, this.toHostSchema());
        } else {
            return false;
        }
    }

    /** 判断连接地址是否是同一个。判断依据是参数的{@link #getHostPort()}返回值和该对象的{@link #getHostPort()}返回值做比较 */
    public boolean equalsHost(InterAddress evalResult) throws UnknownHostException {
        return evalResult != null && equalsHost(evalResult.getIpPort());
    }

    /** 判断连接地址是否是同一个。判断依据是参数值和{@link #getHostPort()}返回值做比较 */
    public boolean equalsHost(String evalResult) throws UnknownHostException {
        return evalResult != null && (StringUtils.equalsIgnoreCase(getHostPort(), evalResult) || StringUtils.equalsIgnoreCase(getIpPort(), evalResult));
    }

    @Override
    public int hashCode() {
        return this.hostSchema.toLowerCase(Locale.ROOT).hashCode();
    }

    public String toString() {
        return toHostSchema();
    }

    public static boolean checkFormat(URI serviceURL) {
        return checkFormat(serviceURL, false);
    }

    private static boolean checkFormat(URI serviceURL, boolean local) {
        if (serviceURL == null || StringUtils.isBlank(serviceURL.getScheme()) || StringUtils.isBlank(serviceURL.getHost()) || serviceURL.getPort() < (local ? 0 : 1) || serviceURL.getPort() > 65535) {
            return false;
        }
        String path = serviceURL.getPath();
        if (path == null || !path.startsWith("/") || path.length() == 1) {
            return false;
        }

        // Keep the existing first-path-segment unit convention.
        String unit = path.substring(1).split("/", 2)[0];
        return Pattern.matches("[A-Za-z0-9_.]+", unit);
    }

    /** 获取本机地址 */
    private static List<String> localIpAddr() {
        List<String> ipList = new ArrayList<>();
        try {
            Enumeration interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = (NetworkInterface) interfaces.nextElement();
                Enumeration ipAddrEnum = ni.getInetAddresses();
                while (ipAddrEnum.hasMoreElements()) {
                    InetAddress addr = (InetAddress) ipAddrEnum.nextElement();
                    if (addr.isLoopbackAddress()) {
                        continue;
                    }
                    String ip = addr.getHostAddress();
                    if (ip.contains(":")) {
                        continue;//skip the IPv6 addr
                    }
                    logger.debug("Interface: " + ni.getName() + ", IP: " + ip);
                    ipList.add(ip);
                }
            }
            Collections.sort(ipList);
        } catch (Exception e) {
            logger.error("Failed to get local ip list. " + e.getMessage());
            throw new RuntimeException("Failed to get local ip list");
        }
        return ipList;
    }
}
