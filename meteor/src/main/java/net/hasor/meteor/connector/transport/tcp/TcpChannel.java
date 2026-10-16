/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.tcp;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.function.Function;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.RouteTable;
import net.hasor.meteor.connector.transport.*;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.routing.ProtoRoutingDataSelector;

/**
 * A byte connection: no RPC messages, request correlation or logical stream state.
 */
final class TcpChannel implements NetworkChannel<byte[]> {
    private final    NetChannel        socket;
    private final    InterAddress      remote;
    private final    Executor          executor;
    private final    BasicFuture<Void> closed = new BasicFuture<>();
    private          int               writes;
    private          boolean           draining;
    private volatile Throwable         failure;

    private TcpChannel(NetChannel socket, InterAddress remote, Executor executor) {
        this.socket = socket;
        this.remote = remote;
        this.executor = executor;
    }

    /**
     * Called after transport stages have been added.
     */
    static TcpChannel attach(ProtoBuildContext stack, InterAddress remote, Executor executor, int maxProbeSize, //
            RouteTable<byte[]> routes, String initialMountId, //
            ChannelListenerFactory<byte[]> factory) throws Exception {
        // 将底层连接包装为字节通道，并创建对应的上层接收器。
        NetChannel socket = (NetChannel) stack.getChannel();
        TcpChannel channel = new TcpChannel(socket, remote, executor);
        ChannelListenerRelay<byte[]> listenerRelay = new ChannelListenerRelay<>(channel::terminated);

        // 先安装物理连接生命周期，ChannelListenerFactory 此后取得的通道已经能够观察关闭。
        Runnable connected = () -> channel.execute(() -> {
            try {
                listenerRelay.connected();
            } catch (Throwable failure) {
                channel.fail(failure);
            }
        });

        // 上层关闭通知处理结束后，再完成通道的关闭 Future。
        Runnable closed = () -> channel.execute(() -> {
            listenerRelay.closed(channel.failure());
        });
        stack.addLastDecoder("lifecycle", new ChannelLifecycleHandler(connected, closed, channel::fail));

        // 每个协议分支将底层缓冲区转换为携带路由信息的字节消息。
        Function<String, ProtoInitializer> branch = route -> {
            return ctx -> {
                ctx.addLastDecoder("messages", new RouteByteHandler(route));
            };
        };

        configureRouting(stack, branch, routes, initialMountId, maxProbeSize);

        // 必须放在解码分支之后，将输入关闭标记排在已解码消息后面。
        // 提前挂载会让关闭标记进入只接收 ByteBuf 的路由或解码器。
        stack.addLastDecoder("input-close", new ChannelInputCloseHandler());

        // 网络事件只负责投递，业务收包仍在串行执行器中处理。
        socket.subscribe(event -> {
            return event.isInbound() || !event.isSuccess();
        }, SubscribeMode.SYNC, event -> {
            // 网络失败也投递到串行执行器，交由连接统一记录原因并关闭。
            if (!event.isSuccess()) {
                channel.execute(() -> channel.fail(event.getError()));
                return;
            }

            // 输入关闭标记排在数据后面，先交付最后收到的数据，再处理关闭。
            if (event.getData() instanceof SoInputCloseEvent) {
                channel.execute(() -> {
                    channel.fail(new IOException("TCP peer closed its input"));
                });
                return;
            }

            // 暂停读取，等待上层消费当前消息，避免消息持续堆积。
            RouteMessage<byte[]> message = (RouteMessage<byte[]>) event.getData();
            ReadPause pause = socket.pauseRead();
            channel.execute(() -> channel.receive(listenerRelay, message, pause));
        });

        // 所有关闭、路由和收包入口就绪后再把通道交给上层，并在同一执行器中绑定监听器。
        try {
            ChannelListener<byte[]> listener = factory.createListener(channel);
            channel.execute(() -> {
                try {
                    listenerRelay.attach(listener);
                } catch (Throwable failure) {
                    channel.fail(failure);
                }
            });
        } catch (Exception | Error failure) {
            listenerRelay.attachmentFailed();
            channel.fail(failure);
            throw failure;
        }
        return channel;
    }

    private void receive(ChannelListener<byte[]> receiver, RouteMessage<byte[]> message, ReadPause pause) {
        // 使用管线已经选定的路由交付消息；普通接收器只接收字节内容。
        try {
            if (receiver instanceof RouteReceiver) {
                ((RouteReceiver<byte[]>) receiver).receive(message.mountId(), message.message());
            } else {
                receiver.receive(message.message());
            }
        } catch (Throwable failure) {
            this.fail(failure);
        } finally {
            // 消费成功或失败都释放本次读取暂停。
            this.socket.resumeRead(pause);
        }
    }

    private static void configureRouting(ProtoBuildContext context, //
            Function<String, ProtoInitializer> branch, RouteTable<byte[]> routes, //
            String initialMountId, int limit) {
        // 没有协议路由的底层测试或扩展直接接收原始字节。
        if (routes.definitions().isEmpty()) {
            branch.apply("").config(context);
            return;
        }

        // 已指定路由或只有一个协议时，直接挂载分支，无需探测报文。
        String initial = routes.resolveMountId(initialMountId);

        if (initial != null) {
            branch.apply(initial).config(context);
            return;
        }

        // 多协议共用连接入口时，根据初始字节选择分支，后续数据沿用该路由。
        ProtoRoutingDataSelector<ByteBuf, Object> selector = (ctx, input, output) -> {
            return RouteByteUtils.select(input.peekMessage(-1), routes.definitions(), limit, false);
        };
        ProtoHelper.typed(ByteBuf.class, Object.class).nextRouteAsStatic("route", selector, routeBranches -> {
            routes.definitions().keySet().forEach(name -> {
                routeBranches.branchByInitializer(name, branch.apply(name));
            });
        }).build().config(context);
    }

    @Override
    public InterAddress getLocal() {
        return SocketAddressUtils.local(this.remote, this.socket.getLocalAddr());
    }

    @Override
    public InterAddress getRemote() {
        return this.remote;
    }

    @Override
    public boolean isOpen() {
        return !this.socket.isClose();
    }

    @Override
    public void execute(Runnable task) {
        this.executor.execute(task);
    }

    @Override
    public Future<Void> write(byte[] bytes) {
        // 受理写入与排空标记互斥，保证开始排空后不再接收新写入。
        BasicFuture<Void> result = new BasicFuture<>();
        synchronized (this) {
            if (this.draining || !this.isOpen()) {
                result.failed(new IllegalStateException("Byte connection is closing"));
                return result;
            }
            this.writes++;
        }

        // 复制调用方数据，避免排队等待期间被修改；实际发送交给串行执行器。
        byte[] owned = bytes.clone();
        try {
            this.execute(() -> this.write(owned, result));
        } catch (Throwable error) {
            // 投递失败也要结束本次写入计数，避免排空一直等待。
            result.failed(error);
            this.writeFinished();
        }
        return result;
    }

    private void write(byte[] bytes, BasicFuture<Void> result) {
        // 排队期间已取消的写入不再发送，但仍需归还已受理的写入计数。
        if (result.isCancelled()) {
            this.writeFinished();
            return;
        }

        // 以底层发送完成为准通知调用方，并在成功、失败或取消后结束写入计数。
        try {
            this.socket.sendData(ByteBuf.wrap(bytes)).onFinal(done -> {
                Throwable error = done.getCause();
                if (done.isCancelled()) {
                    error = new CancellationException("Socket write cancelled");
                }

                if (error == null) {
                    result.completed(null);
                } else {
                    result.failed(error);
                }
                this.writeFinished();
            });
        } catch (Throwable error) {
            result.failed(error);
            this.writeFinished();
        }
    }

    private void writeFinished() {
        synchronized (this) {
            this.writes--;
        }

        // 最后一个已受理写入结束后，可能满足排空关闭条件。
        this.checkDrain();
    }

    @Override
    public Future<Void> drainAndClose() {
        // 先停止受理新写入，再等待已受理写入结束；没有待写数据时直接推进关闭。
        synchronized (this) {
            this.draining = true;
        }

        this.checkDrain();
        return this.closed;
    }

    private void checkDrain() {
        // 只有进入排空且所有已受理写入均已结束，才允许关闭底层连接。
        synchronized (this) {
            if (!this.draining || this.writes != 0 || this.closed.isDone()) {
                return;
            }
        }

        // 写入完成回调可能运行在 Neta 管线内，关闭操作转交串行执行器，避免重入。
        this.execute(this::close);
    }

    @Override
    public Future<Void> close() {
        this.socket.closeNow();
        return this.closed;
    }

    private void fail(Throwable error) {
        this.failure = error;
        this.close();
    }

    private Throwable failure() {
        return this.failure == null ? new IOException("TCP connection closed") : this.failure;
    }

    private void terminated() {
        this.closed.completed(null);
    }
}
