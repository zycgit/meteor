/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.channel;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.text.StringEscapeUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.RouteMount;
import net.hasor.meteor.connector.RouteSelection;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.protocol.soap.codec.SoapInboundResult;
import net.hasor.meteor.protocol.soap.codec.SoapMessageCodec;
import net.hasor.meteor.protocol.soap.definition.SoapDefinitionResource;

/** SOAP 目录、服务端点和定义资源的 HTTP 路由。 */
final class SoapEndpointRouter {
    private final ProtocolConfig                                   config;
    private final MetContext                                       context;
    private final InterAddress                                     bindAddress;
    private final SoapMessageCodec                                 codec;
    private final Function<MetBindInfo<?>, SoapDefinitionResource> resources;
    private final String                                           directoryPath;

    SoapEndpointRouter(ProtocolConfig config, MetContext context, InterAddress bindAddress, //
            SoapMessageCodec codec, Function<MetBindInfo<?>, SoapDefinitionResource> resources) {
        this.config = config;
        this.context = context;
        this.bindAddress = bindAddress;
        this.codec = codec;
        this.resources = resources;
        this.directoryPath = SoapProtocol.directoryPath(config);
    }

    SoapInboundResult route(HttpRequest request, long requestId, int requestTimeout) throws Exception {
        HttpResponse directory = this.directory(request);
        if (directory != null) {
            return SoapInboundResult.response(directory);
        }

        RouteSelection route = request.route();
        if (route == null || !StringUtils.equals(this.config.mountId(), route.mountId())) {
            return SoapInboundResult.response(this.simple(404));
        }

        MetBindInfo<?> service = this.select(request, route.mount());
        if (service == null) {
            return SoapInboundResult.response(this.simple(404));
        }
        SoapDefinitionResource resource = this.resources.apply(service);

        boolean get = StringUtils.equalsIgnoreCase(request.method(), "GET");
        boolean head = StringUtils.equalsIgnoreCase(request.method(), "HEAD");
        String query = this.query(request.uri());
        if ((get || head) && (query == null || !query.startsWith("method="))) {
            return SoapInboundResult.response(this.definition(request, service, resource, route.mount(), head));
        }
        return this.codec.receive(request, service, resource, requestId, requestTimeout);
    }

    private MetBindInfo<?> select(HttpRequest request, RouteMount mount) {
        String path = this.path(request.uri());
        MetBindInfo<?> selected = null;
        for (String id : this.context.getServiceIDs()) {
            MetBindInfo<?> service = this.context.getServiceInfo(id);
            if (service == null || service.isShadow() || service.getServiceType() != MetServiceType.Provider) {
                continue;
            }
            if (!this.config.accepts(service, mount)) {
                continue;
            }

            String alias = service.getAliasName("soap");
            if (StringUtils.isBlank(alias) || !StringUtils.equals(path, this.endpoint(mount, alias))) {
                continue;
            }
            if (selected != null) {
                throw new IllegalArgumentException("Ambiguous SOAP service endpoint: " + path);
            }
            selected = service;
        }
        return selected;
    }

    private HttpResponse directory(HttpRequest request) {
        if (this.directoryPath == null || !StringUtils.equals(this.directoryPath, this.path(request.uri()))) {
            return null;
        }

        boolean get = StringUtils.equalsIgnoreCase(request.method(), "GET");
        boolean head = StringUtils.equalsIgnoreCase(request.method(), "HEAD");
        if (!get && !head) {
            Map<String, List<String>> headers = Map.of("Allow", List.of("GET, HEAD"));
            return HttpResponse.newBuffered(405, headers, new byte[0], Map.of());
        }

        byte[] content = this.renderDirectory().getBytes(StandardCharsets.UTF_8);
        Map<String, List<String>> headers = Map.of(//
                "Content-Type", List.of("text/html; charset=utf-8"), //
                "Content-Length", List.of(Integer.toString(content.length)), //
                "Cache-Control", List.of("no-store"));
        return HttpResponse.newBuffered(200, headers, head ? new byte[0] : content, Map.of());
    }

    private HttpResponse definition(HttpRequest request, MetBindInfo<?> service, SoapDefinitionResource resource, //
            RouteMount mount, boolean head) throws Exception {
        String query = this.query(request.uri());
        URI endpoint = this.endpointUri(request, service, mount);
        byte[] content;
        String contentType;
        if (StringUtils.equalsIgnoreCase(query, "wsdl")) {
            content = resource.renderWsdl(endpoint);
            contentType = "text/xml; charset=utf-8";
        } else if (query != null && query.toLowerCase(Locale.ROOT).startsWith("wsdl=")) {
            String name = URLDecoder.decode(query.substring(5), StandardCharsets.UTF_8);
            content = resource.renderResource(name, endpoint);
            contentType = "text/xml; charset=utf-8";
        } else if (query != null && query.toLowerCase(Locale.ROOT).startsWith("xsd=")) {
            String name = URLDecoder.decode(query.substring(4), StandardCharsets.UTF_8);
            content = resource.renderResource(name, endpoint);
            contentType = "application/xml; charset=utf-8";
        } else {
            return this.simple(404);
        }
        if (content == null) {
            return this.simple(404);
        }

        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("Content-Type", List.of(contentType));
        headers.put("Content-Length", List.of(Integer.toString(content.length)));
        return HttpResponse.newBuffered(200, headers, head ? new byte[0] : content, Map.of());
    }

    private URI endpointUri(HttpRequest request, MetBindInfo<?> service, RouteMount mount) {
        String alias = service.getAliasName("soap");
        String path = this.endpoint(mount, alias);
        String publicBase = this.config.option("publicBaseUrl", null);
        if (StringUtils.isNotBlank(publicBase)) {
            String base = publicBase.endsWith("/") ? publicBase.substring(0, publicBase.length() - 1) : publicBase;
            return URI.create(base + path);
        }

        String host = request.header("Host");
        if (StringUtils.isBlank(host)) {
            if (this.bindAddress == null) {
                throw new IllegalStateException("SOAP definition cannot determine the public endpoint");
            }
            host = this.bindAddress.getHostPort();
        }
        boolean secure = Boolean.parseBoolean(this.config.option("tlsEnabled", "false"));
        return URI.create((secure ? "https" : "http") + "://" + host + path);
    }

    private String renderDirectory() {
        Map<String, String> services = new TreeMap<>();
        Map<String, String> exactServices = new HashMap<>();
        for (String id : this.context.getServiceIDs()) {
            MetBindInfo<?> service = this.context.getServiceInfo(id);
            if (service == null) {
                continue;
            }
            Map<String, String> selected = this.config.exactPaths(service).isEmpty() ? services : exactServices;
            for (String endpoint : SoapProtocol.serviceEndpoints(this.config, service)) {
                selected.put(endpoint, service.getAliasName("soap"));
            }
        }
        services.putAll(exactServices);

        StringBuilder page = new StringBuilder();
        page.append("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">");
        page.append("<title>SOAP 服务目录</title></head><body><h1>SOAP 服务目录</h1>");
        page.append("<table><thead><tr><th>服务</th><th>调用地址</th><th>服务描述</th></tr></thead><tbody>");
        for (Map.Entry<String, String> service : services.entrySet()) {
            String endpoint = this.publicEndpoint(service.getKey());
            String name = StringEscapeUtils.escapeHtml4(service.getValue());
            String address = StringEscapeUtils.escapeHtml4(endpoint);
            String wsdl = StringEscapeUtils.escapeHtml4(endpoint + "?wsdl");
            page.append("<tr><td>").append(name).append("</td><td><code>").append(address);
            page.append("</code></td><td><a href=\"").append(wsdl).append("\">WSDL</a></td></tr>");
        }
        page.append("</tbody></table></body></html>");
        return page.toString();
    }

    private String publicEndpoint(String path) {
        String publicBase = this.config.option("publicBaseUrl", null);
        if (StringUtils.isBlank(publicBase)) {
            return path;
        }
        String base = publicBase.endsWith("/") ? publicBase.substring(0, publicBase.length() - 1) : publicBase;
        return base + path;
    }

    private String endpoint(RouteMount mount, String alias) {
        return mount.exact() ? mount.path() : mount.prefix() + alias;
    }

    private String path(String uri) {
        int query = uri.indexOf('?');
        return query < 0 ? uri : uri.substring(0, query);
    }

    private String query(String uri) {
        int query = uri.indexOf('?');
        return query < 0 || query + 1 == uri.length() ? null : uri.substring(query + 1);
    }

    private HttpResponse simple(int status) {
        return HttpResponse.newBuffered(status, Map.of(), new byte[0], Map.of());
    }

}
