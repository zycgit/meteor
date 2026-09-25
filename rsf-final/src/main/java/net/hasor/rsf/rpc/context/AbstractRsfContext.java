/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.rpc.context;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.RsfContext;
import net.hasor.cobble.bus.BusContext;


import net.hasor.rsf.*;
import net.hasor.rsf.container.RsfBeanContainer;
import net.hasor.rsf.domain.*;
import net.hasor.rsf.address.provider.AddressProvider;
import net.hasor.rsf.address.provider.InstanceAddressProvider;
import net.hasor.rsf.address.provider.PoolAddressProvider;
import net.hasor.rsf.rpc.caller.SenderListener;
import net.hasor.rsf.rpc.caller.remote.RemoteRsfCaller;
import net.hasor.rsf.rpc.client.RpcRsfClient;
import net.hasor.rsf.connector.*;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import java.util.concurrent.CancellationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.concurrent.TimeUnit;

/**
 * 服务上下文，负责提供 RSF 运行环境的支持。
 *
 * @version : 2014年11月12日
 * @author 赵永春 (zyc@hasor.net)
 */
public abstract class AbstractRsfContext implements RsfContext {
    protected     Logger               logger = LoggerFactory.getLogger(getClass());
    private final RsfBeanContainer     rsfBeanContainer; // 服务管理
    private final RsfEnvironment       rsfEnvironment;   // 环境&配置
    private final RemoteRsfCaller      rsfCaller;        // 调用器
    private final RuntimeNetwork        rsfNetManager;    // 网络管理器
    private final RuntimeAddressPool addressPool;      // 地址管理器
    private final PoolAddressProvider  poolProvider;     // 地址获取接口（addressPool的另一个形态）
    private final AtomicBoolean        onlineStatus;     // 在线状态
    private boolean started;
    private boolean closed;

    public AbstractRsfContext(RsfEnvironment rsfEnvironment) {
        this.addressPool = new RuntimeAddressPool(rsfEnvironment);
        this.poolProvider = new PoolAddressProvider(this.addressPool);
        this.rsfBeanContainer = new RsfBeanContainer(this.addressPool, rsfEnvironment);
        this.rsfEnvironment = rsfEnvironment;
        Transport transport = new Transport();
        this.rsfNetManager = new RuntimeNetwork(transport);
        this.rsfCaller = new RemoteRsfCaller(this, this.rsfBeanContainer::getFilterProviders, transport);
        this.onlineStatus = new AtomicBoolean(false);
    }

    @Override
    public synchronized void start() {
        if (this.closed) {
            throw new IllegalStateException("RSF runtime is closed");
        }
        if (this.started) {
            return;
        }
        try {
            this.rsfNetManager.start(this);
            this.addressPool.start();
            this.started = true;
            if (this.rsfEnvironment.getSettings().isAutomaticOnline()) {
                online();
            }
        } catch (RuntimeException | Error failure) {
            try { close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @Override
    public synchronized void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        try { offline(); }
        finally {
            try { this.rsfCaller.shutdown(); }
            finally {
                try { this.rsfNetManager.shutdown(); }
                finally {
                    try { this.addressPool.close(); }
                    finally { this.rsfEnvironment.close(); }
                }
            }
        }
    }

    /**应用上线(先置为上线，在引发事件)*/
    @Override
    public synchronized void online() {
        if (this.closed || !this.started) {
            throw new IllegalStateException("RSF runtime is not started");
        }
        if (!this.onlineStatus.compareAndSet(false, true)) {
            this.logger.error("rsfContext -> already online");
            return;
        }
        this.logger.info("rsfContext -> already online , fireSyncEvent ,eventType = {}", RsfEvent.Rsf_Online);
        BusContext ec = getEnvironment().getEventContext();
        try {
            ec.fireEvent(RsfEvent.Rsf_Online, this);
        } catch (Throwable e) {
            this.logger.error(e.getMessage(), e);
        }
    }

    /**应用下线(先置为下线，在引发事件)*/
    @Override
    public synchronized void offline() {
        if (!this.onlineStatus.compareAndSet(true, false)) {
            this.logger.error("rsfContext -> already offline");
            return;
        }
        this.logger.info("rsfContext -> already offline , fireSyncEvent ,eventType = {}", RsfEvent.Rsf_Online);
        BusContext ec = getEnvironment().getEventContext();
        try {
            ec.fireEvent(RsfEvent.Rsf_Offline, this);
        } catch (Throwable e) {
            this.logger.error(e.getMessage(), e);
        }
    }

    @Override
    public boolean isOnline() {
        return this.onlineStatus.get();
    }

    @Override
    public String getInstanceID() {
        return this.rsfEnvironment.getInstanceID();
    }



    /**获取运行着哪些协议*/
    @Override
    public Set<String> runProtocols() {
        return this.rsfNetManager.runProtocols();
    }

    @Override
    public String getDefaultProtocol() {
        return this.rsfEnvironment.getSettings().getDefaultProtocol();
    }

    @Override
    public RsfEnvironment getEnvironment() {
        return this.rsfEnvironment;
    }

    public RsfSettings getSettings() {
        return this.rsfEnvironment.getSettings();
    }

    public RsfUpdater getUpdater() {
        return this.addressPool;
    }

    public ClassLoader getClassLoader() {
        return this.rsfEnvironment.getClassLoader();
    }

    @Override
    public InterAddress bindAddress(String protocol) {
        Connector connector = this.rsfNetManager.findConnector(protocol);
        if (connector == null) {
            return null;
        }
        // Legacy facade selects the first active listener of the named protocol endpoint.
        for (RsfListen listen : connector.getListenList()) {
            if (listen.isActive()) {
                return listen.getBindAddress();
            }
        }
        return null;
    }

    public RsfClient getRsfClient() {
        return new RpcRsfClient(this.poolProvider, this.rsfCaller);
    }

    public RsfClient getRsfClient(String targetStr) throws URISyntaxException, UnknownHostException {
        return this.getRsfClient(new InterAddress(targetStr));
    }

    public RsfClient getRsfClient(URI targetURL) throws UnknownHostException {
        return this.getRsfClient(new InterAddress(targetURL));
    }

    public RsfClient getRsfClient(InterAddress target) {
        AddressProvider provider = new InstanceAddressProvider(target);
        return new RpcRsfClient(provider, this.rsfCaller);
    }

    public <T> RsfBindInfo<T> getServiceInfo(String serviceID) {
        return (RsfBindInfo<T>) this.rsfBeanContainer.getRsfBindInfo(serviceID);
    }

    public <T> RsfBindInfo<T> getServiceInfo(String aliasType, String aliasName) {
        return (RsfBindInfo<T>) this.rsfBeanContainer.getRsfBindInfo(aliasType, aliasName);
    }

    public <T> RsfBindInfo<T> getServiceInfo(Class<T> serviceType) {
        return this.rsfBeanContainer.getRsfBindInfo(serviceType);
    }

    public <T> RsfBindInfo<T> getServiceInfo(String group, String name, String version) {
        return (RsfBindInfo<T>) this.rsfBeanContainer.getRsfBindInfo(group, name, version);
    }

    public List<String> getServiceIDs() {
        return this.rsfBeanContainer.getServiceIDs();
    }

    public List<String> getServiceIDs(String aliasType) {
        return this.rsfBeanContainer.getServiceIDs(aliasType);
    }

    public <T> Supplier<T> getServiceProvider(RsfBindInfo<T> bindInfo) {
        return this.rsfBeanContainer.getProvider(bindInfo);
    }

    public RsfPublisher publisher() {
        return this.rsfBeanContainer.createPublisher(this.rsfBeanContainer, this);
    }

    /*接收到网络数据 & 发送网络数据*/
    private class Transport implements ReceivedListener, SenderListener {
        @Override
        public void onResponse(ResponseInfo response) {
            AbstractRsfContext.this.rsfCaller.putResponse(response);
        }

        @Override
        public void onRequest(RequestInfo request, Exchange exchange) {
            AbstractRsfContext.this.rsfCaller.onRequest(exchange, request);
        }

        @Override
        public void onFailure(long requestId, Throwable failure) {
            AbstractRsfContext.this.rsfCaller.putResponse(requestId, failure);
        }

        @Override
        public Future<Void> sendRequest(InterAddress toAddress, RequestInfo info) {
            BasicFuture<Void> sent = new BasicFuture<>();
            long started = System.nanoTime();
            try {
                Connector connector = findConnector(toAddress);
                AbstractRsfContext.this.rsfNetManager.connect(connector, toAddress).onFinal(connected -> {
                    if (connected.isCancelled()) {
                        sent.cancel();
                        return;
                    }
                    RsfChannel channel = connected.getResult();
                    Throwable failure = connected.getCause();
                    if (failure != null) {
                        AbstractRsfContext.this.addressPool.invalidAddress(toAddress);
                        sent.failed(failure);
                        return;
                    }
                    try {
                        RsfFuture pending = AbstractRsfContext.this.rsfCaller.getRequest(info.getRequestID());
                        long remaining = info.getClientTimeout() - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                        if (pending == null || pending.isDone() || sent.isCancelled()) {
                            sent.failed(new CancellationException("Request ended while connecting"));
                            return;
                        }
                        if (remaining <= 0) {
                            sent.failed(new RsfTimeoutException("Call expired while connecting"));
                            return;
                        }
                        info.setClientTimeout((int) remaining);
                        Future<RsfChannel> writing = channel.sendData(info);
                        writing.onCompleted(done -> sent.completed(null));
                        writing.onFailed(done -> sent.failed(done.getCause()));
                        writing.onCancel(done -> sent.cancel());
                    } catch (Throwable sendFailure) {
                        sent.failed(sendFailure);
                    }
                });
            } catch (Exception failure) {
                AbstractRsfContext.this.addressPool.invalidAddress(toAddress);
                sent.failed(failure);
            }
            return sent;
        }
    }

    private Connector findConnector(InterAddress target) {
        String sechma = target.getSchema();
        Connector connector = this.rsfNetManager.findConnectorBySechma(sechma);// tips：例如：如果本地都不支持 rsf 协议，那么也没有必要连接远程的 rsf 协议。
        if (connector == null) {
            throw new RsfException(ProtocolStatus.ProtocolUndefined, "protocol is not support, invalid address ->" + target.toHostSchema());
        }
        return connector;
    }
}
