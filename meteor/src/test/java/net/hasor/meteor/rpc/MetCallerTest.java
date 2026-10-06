/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.rpc;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.hasor.cobble.concurrent.future.*;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.*;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.address.provider.AddressProvider;
import net.hasor.meteor.address.provider.InstanceAddressProvider;
import net.hasor.meteor.connector.*;
import net.hasor.meteor.domain.*;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.domain.payload.ThrowPayload;
import net.hasor.meteor.serialize.coder.JavaSerializeCoder;
import org.junit.Test;
import static org.junit.Assert.*;

/** Module-local SPI transport exercises the real manager, subscription and RPC lifecycle. */
public class MetCallerTest {
    public interface Echo {
        String echo(String value);
    }

    @Test
    public void clientWithoutListenerCallsServerThroughConnectorSubscription() throws Exception {
        try (Host server = new Host(10000, "normal"); Host client = new Host(10000, "normal")) {
            server.bind();
            MetClient remote = client.client(server.address);
            assertEquals("hello", remote.getRemote(client.service).echo("hello"));
            assertNull(remote.getRemote(client.service).echo(null));
            assertTrue(client.connector().getListenList().isEmpty());
            assertEquals(2, server.remoteFilters.get());
            assertEquals(2, client.localFilters.get());
            assertEquals(2, client.cancelledTimers.get());
            assertNull(getClass().getClassLoader().getResource("net/hasor/meteor/protocol/hprose/HproseProtocol.class"));
        }
    }

    @Test
    public void coreInvokesAndDispatchesWithoutClientFacadeCalls() throws Exception {
        try (Host server = new Host(10000, "normal"); Host client = new Host(10000, "normal")) {
            server.bind();
            MetRequestObject request = client.rpc.createRequest(new InstanceAddressProvider(server.address), client.service, "echo", new Class<?>[] { String.class }, new Object[] { "core" });
            MetFuture call = client.rpc.invoke(request);
            assertEquals("core", call.getData(2, TimeUnit.SECONDS));
            assertEquals(1, client.localFilters.get());
            assertEquals(1, server.remoteFilters.get());
            assertNull(client.rpc.getRequest(request.getRequestID()));

            MemoryChannel channel = client.connector().outgoing;
            channel.peer.sendData(request(client, 42, "reverse")).get();
            assertEquals("reverse", finalResponse(channel).getReturnData());
            assertEquals(1, client.remoteFilters.get());
            assertTrue(client.connector().getListenList().isEmpty());
        }
    }

    @Test
    public void clientFacadeCallbacksDelegateToTheSharedCore() throws Exception {
        try (Host server = new Host(10000, "normal"); Host client = new Host(10000, "normal")) {
            server.bind();
            MetClient facade = client.client(server.address);
            BasicFuture<Object> data = new BasicFuture<>();
            facade.callbackInvoke(client.service, "echo", new Class<?>[] { String.class }, new Object[] { "data" }, new FutureCallback<Object>() {
                public void completed(Object result) {
                    data.completed(result);
                }

                public void failed(Throwable failure) {
                    data.failed(failure);
                }
            });
            assertEquals("data", data.get(2, TimeUnit.SECONDS));

            BasicFuture<MetResponse> response = new BasicFuture<>();
            facade.callbackRequest(client.service, "echo", new Class<?>[] { String.class }, new Object[] { "response" }, new FutureCallback<MetResponse>() {
                public void completed(MetResponse result) {
                    response.completed(result);
                }

                public void failed(Throwable failure) {
                    response.failed(failure);
                }
            });
            MetResponse result = response.get(2, TimeUnit.SECONDS);
            assertEquals("response", result.getData());
            assertEquals(ProtocolStatus.OK, result.getStatus());
            assertEquals(2, client.localFilters.get());
            assertEquals(2, server.remoteFilters.get());
            assertNull(client.rpc.getRequest(result.getRequestID()));
        }
    }

    @Test
    public void facadeReceivesResponsesAndFailuresCompletedBeforeCallbackRegistration() throws Exception {
        try (Host server = new Host(10000, "normal"); Host client = new Host(10000, "instant")) {
            server.bind();
            BasicFuture<Object> result = new BasicFuture<>();
            client.client(server.address).callbackInvoke(client.service, "echo", new Class<?>[] { String.class }, new Object[] { "value" }, new FutureCallback<Object>() {
                public void completed(Object value) {
                    result.completed(value);
                }

                public void failed(Throwable failure) {
                    result.failed(failure);
                }
            });
            assertEquals("reply", result.get(2, TimeUnit.SECONDS));
            BasicFuture<Throwable> error = new BasicFuture<>();
            client.client(new InterAddress("memory://missing:1/default")).callbackRequest(client.service, "echo", new Class<?>[] { String.class }, new Object[] { "value" }, new FutureCallback<MetResponse>() {
                public void completed(MetResponse value) {
                    fail("Expected connection failure");
                }

                public void failed(Throwable failure) {
                    error.completed(failure);
                }
            });
            assertTrue(error.get(2, TimeUnit.SECONDS) instanceof IOException);
            assertEquals(2, client.cancelledTimers.get());
        }
    }

    @Test
    public void facadeCancellationUsesCancellationCallbackOrFailureFallback() throws Exception {
        try (Host server = new Host(10000, "normal"); Host client = new Host(10000, "hold")) {
            server.bind();
            MetClient facade = client.client(server.address);
            AtomicInteger cancelled = new AtomicInteger();
            facade.callbackRequest(client.service, "echo", new Class<?>[] { String.class }, new Object[] { "value" }, new CancelFutureCallback<MetResponse>() {
                public void completed(MetResponse response) {
                    fail("Expected cancellation");
                }

                public void failed(Throwable failure) {
                    fail("Expected cancellation callback");
                }

                public void cancelled() {
                    cancelled.incrementAndGet();
                }
            });
            RequestPayload first = client.connector().outgoing.sent.remove();
            MetFuture pending = client.rpc.getRequest(first.getRequestID());
            assertTrue(pending.cancel());
            assertFalse(pending.cancel());
            assertEquals(1, cancelled.get());
            BasicFuture<Throwable> failed = new BasicFuture<>();
            facade.callbackRequest(client.service, "echo", new Class<?>[] { String.class }, new Object[] { "value" }, new FutureCallback<MetResponse>() {
                public void completed(MetResponse response) {
                    fail("Expected cancellation failure");
                }

                public void failed(Throwable failure) {
                    failed.completed(failure);
                }
            });
            RequestPayload second = client.connector().outgoing.sent.remove();
            assertTrue(client.rpc.getRequest(second.getRequestID()).cancel());
            assertTrue(failed.get(2, TimeUnit.SECONDS) instanceof CancellationException);
            assertEquals(2, client.cancelledTimers.get());
        }
    }

    @Test
    public void facadeCallbackExceptionsDoNotDependOnResponseTiming() throws Exception {
        for (String mode : Arrays.asList("instant", "hold")) {
            try (Host server = new Host(10000, "normal"); Host client = new Host(10000, mode)) {
                server.bind();
                AtomicInteger callbacks = new AtomicInteger();
                client.client(server.address).callbackRequest(client.service, "echo", new Class<?>[] { String.class }, new Object[] { "value" }, new FutureCallback<MetResponse>() {
                    public void completed(MetResponse response) {
                        callbacks.incrementAndGet();
                        throw new IllegalArgumentException("callback failed");
                    }

                    public void failed(Throwable failure) {
                        fail("Expected response");
                    }
                });
                MemoryChannel channel = client.connector().outgoing;
                RequestPayload request = channel.sent.remove();
                if ("hold".equals(mode)) {
                    client.manager.onResponse(channel, request.getRequestID(), response(request, ProtocolStatus.OK));
                }
                assertEquals(1, callbacks.get());
                assertNull(client.rpc.getRequest(request.getRequestID()));
                MetFuture next = client.invoke(server.address);
                if ("hold".equals(mode)) {
                    assertFalse(next.isDone());
                    next.cancel();
                } else {
                    assertEquals("reply", next.getData(2, TimeUnit.SECONDS));
                }
            }
        }
    }

    @Test
    public void unifiedRequestsPreserveDirectionAddressesAndIdsThroughInvocation() throws Exception {
        try (Host server = new Host(10000, "normal"); Host client = new Host(10000, "normal")) {
            server.bind();
            MetFuture call = client.invoke(server.address);
            assertEquals("hello", call.getData(2, TimeUnit.SECONDS));
            MetRequest local = client.requests.poll(2, TimeUnit.SECONDS);
            MetRequest remote = server.requests.poll(2, TimeUnit.SECONDS);
            assertEquals(MetRequestObject.class, local.getClass());
            assertEquals(local.getClass(), remote.getClass());
            assertSame(call.getRequest(), local);
            assertEquals(local.getRequestID(), remote.getRequestID());
            assertTrue(local.isLocal());
            assertFalse(remote.isLocal());
            assertEquals(client.address, local.getRemoteAddress());
            assertEquals(server.address, local.getTargetAddress());
            assertEquals(client.address, remote.getRemoteAddress());
            assertEquals(server.address, remote.getTargetAddress());
            assertSame(client.service, local.getBindInfo());
            assertSame(server.service, remote.getBindInfo());
            assertEquals("Java", local.getSerializeType());
            assertEquals("Java", remote.getSerializeType());
            assertEquals(10000, local.getTimeout());
            assertTrue(remote.getTimeout() > 0 && remote.getTimeout() <= local.getTimeout());
            assertFalse(local.isMessage());
            assertFalse(remote.isMessage());
            local.getParameterObject()[0] = "changed";
            remote.getParameterTypes()[0] = Object.class;
            assertArrayEquals(new Object[] { "hello" }, local.getParameterObject());
            assertArrayEquals(new Class<?>[] { String.class }, remote.getParameterTypes());
            local.addOption("local-only", "first");
            assertNull(remote.getOption("local-only"));
            local.setAttribute("attribute", "local");
            assertNull(remote.getAttribute("attribute"));
            long before = System.currentTimeMillis();
            assertTrue(local.getReceiveTime() >= before);
            MetFuture next = client.invoke(server.address);
            assertEquals("hello", next.getData(2, TimeUnit.SECONDS));
            assertNotEquals(local.getRequestID(), next.getRequest().getRequestID());
            assertNull(next.getRequest().getOption("local-only"));
        }
    }

    @Test
    public void inboundRequestKeepsPayloadOptionsAndClampsTimeout() throws Exception {
        try (Host server = new Host(10000, "normal"); Host client = new Host(10000, "normal")) {
            server.bind();
            MemoryChannel channel = (MemoryChannel) client.manager.connect(server.address).get();
            RequestPayload payload = request(server, 42, "metadata");
            payload.setFlags(MetFlags.P2PFlag.addTag((short) 0));
            payload.setSerializeType("wire-coder");
            payload.setClientTimeout(20000);
            payload.addOption("trace", "incoming");
            channel.sendData(payload).get();
            assertEquals("metadata", finalResponse(channel.peer).getReturnData());
            MetRequest remote = server.requests.poll(2, TimeUnit.SECONDS);
            assertNotNull(remote);
            assertEquals(42, remote.getRequestID());
            assertTrue(remote.isP2PCalls());
            assertEquals("wire-coder", remote.getSerializeType());
            assertEquals(10000, remote.getTimeout());
            assertEquals(payload.getReceiveTime(), remote.getReceiveTime());
            assertEquals("incoming", remote.getOption("trace"));
            remote.addOption("trace", "filtered");
            assertEquals("filtered", payload.getOption("trace"));
            payload.addOption("reply", "shared");
            assertEquals("shared", remote.getOption("reply"));
            remote.removeOption("reply");
            assertNull(payload.getOption("reply"));
            remote.setAttribute("trace", "private");
            assertEquals("filtered", payload.getOption("trace"));

            RequestPayload shorter = request(server, 43, "shorter");
            shorter.setClientTimeout(5000);
            channel.sendData(shorter).get();
            finalResponse(channel.peer);
            assertEquals(5000, server.requests.poll(2, TimeUnit.SECONDS).getTimeout());
        }
    }

    @Test
    public void inboundMessageTypeComesFromPayloadInsteadOfService() throws Exception {
        try (Host server = new Host(10000, "normal"); Host client = new Host(10000, "normal")) {
            server.bind();
            MemoryChannel channel = (MemoryChannel) client.manager.connect(server.address).get();
            RequestPayload payload = request(server, 44, "message");
            payload.setMessage(true);
            channel.sendData(payload).get();
            MetRequest remote = server.requests.poll(2, TimeUnit.SECONDS);
            assertNotNull(remote);
            assertFalse(server.service.isMessage());
            assertTrue(remote.isMessage());
            assertEquals(44, remote.getRequestID());
        }
    }

    @Test
    public void callerWithoutListenerHandlesRequestsOnAnEstablishedChannel() throws Exception {
        try (Host listener = new Host(10000, "normal"); Host peer = new Host(10000, "normal")) {
            listener.bind();
            MemoryChannel outgoing = (MemoryChannel) peer.manager.connect(listener.address).get();
            assertTrue(peer.connector().getListenList().isEmpty());
            outgoing.peer.sendData(request(peer, 42, "reverse")).get();
            ResponsePayload reply = finalResponse(outgoing);
            assertEquals(42, reply.getRequestID());
            assertEquals("reverse", reply.getReturnData());
            assertEquals(1, peer.remoteFilters.get());
            assertTrue(peer.connector().getListenList().isEmpty());
        }
    }

    @Test
    public void responseDuringSendIsMatchedBeforeWriteFutureCompletes() throws Exception {
        try (Host server = new Host(10000, "normal"); Host client = new Host(10000, "instant")) {
            server.bind();
            assertEquals("reply", client.invoke(server.address).getData(2, TimeUnit.SECONDS));
            assertEquals("reply", client.invoke(server.address).getData(2, TimeUnit.SECONDS));
            assertEquals(2, client.cancelledTimers.get());
        }
    }

    @Test
    public void writeSuccessAndWrongSourceCannotCompleteTheCall() throws Exception {
        try (Host server = new Host(10000, "normal"); Host client = new Host(10000, "hold")) {
            server.bind();
            MetFuture call = client.invoke(server.address);
            MemoryChannel source = client.connector().outgoing;
            RequestPayload request = source.sent.poll(2, TimeUnit.SECONDS);
            assertNotNull(request);
            assertFalse(call.isDone());
            MetChannel other = client.manager.connect(server.address).get();
            client.manager.onResponse(other, request.getRequestID(), response(request, ProtocolStatus.OK));
            client.manager.onFailure(other, request.getRequestID(), new ThrowPayload(new IOException("wrong source")));
            assertFalse(call.isDone());
            client.manager.onResponse(source, request.getRequestID() + 1, response(request, ProtocolStatus.OK));
            assertFalse(call.isDone());
            client.manager.onResponse(source, request.getRequestID(), response(request, ProtocolStatus.Accept));
            assertFalse(call.isDone());
            client.manager.onResponse(source, request.getRequestID(), response(request, ProtocolStatus.OK));
            assertEquals("reply", call.getData(2, TimeUnit.SECONDS));
            assertEquals(1, client.cancelledTimers.get());
        }
    }

    @Test
    public void correctSourceFailurePreservesCauseAndReleasesCapacity() throws Exception {
        try (Host server = new Host(10000, "normal"); Host client = new Host(10000, "hold")) {
            server.bind();
            MetFuture first = client.invoke(server.address);
            MemoryChannel source = client.connector().outgoing;
            IOException failure = new IOException("connection lost", new IllegalArgumentException("original cause"));
            client.manager.onFailure(source, first.getRequest().getRequestID(), new ThrowPayload(failure));
            assertSame(failure, failure(first));
            MetFuture next = client.invoke(server.address);
            assertFalse(next.isDone());
            next.cancel();
            assertEquals(2, client.cancelledTimers.get());
        }
    }

    @Test
    public void messagesCannotCompleteRequestBeforeItsChannelIsAttached() throws Exception {
        try (Host server = new Host(10000, "normal"); Host client = new Host(10000, "delayed")) {
            server.bind();
            MetFuture call = client.invoke(server.address);
            long requestId = call.getRequest().getRequestID();
            MemoryConnector connector = client.connector();
            ResponsePayload response = new ResponsePayload();
            response.setRequestID(requestId);
            response.setStatus(ProtocolStatus.OK);
            response.setReturnData("too early");
            client.manager.onResponse(null, requestId, response);
            client.manager.onFailure(null, requestId, new ThrowPayload(new IOException("no source")));
            client.manager.onResponse(connector.outgoing, requestId, response);
            client.manager.onFailure(connector.outgoing, requestId, new ThrowPayload(new IOException("not attached")));
            assertFalse(call.isDone());
            connector.connecting.completed(connector.outgoing);
            assertEquals("hello", call.getData(2, TimeUnit.SECONDS));
            assertEquals(1, client.cancelledTimers.get());
        }
    }

    @Test
    public void expiredOrCancelledCallIsNotSentWhenConnectionArrivesLate() throws Exception {
        try (Host server = new Host(10000, "normal"); Host client = new Host(100, "delayed")) {
            server.bind();
            MetFuture expired = client.invoke(server.address);
            assertTrue(failure(expired) instanceof MetTimeoutException);
            MemoryConnector connector = client.connector();
            MemoryChannel channel = connector.outgoing;
            connector.connecting.completed(channel);
            assertTrue(channel.sent.isEmpty());
            MetFuture cancelled = client.invoke(server.address);
            channel = connector.outgoing;
            assertTrue(cancelled.cancel());
            connector.connecting.completed(channel);
            assertTrue(channel.sent.isEmpty());
            assertTrue(channel.isActive());
        }
    }

    @Test
    public void timeoutCompletesProtocolLifecycleAfterRequestWasSent() throws Exception {
        try (Host server = new Host(10000, "normal"); Host client = new Host(200, "hold")) {
            server.bind();
            MetFuture call = client.invoke(server.address);
            RequestPayload payload = client.connector().outgoing.sent.poll(2, TimeUnit.SECONDS);
            assertNotNull(payload);
            Throwable cause = failure(call);
            assertTrue(cause instanceof MetTimeoutException);
            assertTrue(payload.completion().isDone());
            assertSame(cause, payload.completion().getCause());
            assertTrue(client.connector().outgoing.isActive());
        }
    }

    @Test
    public void cancelledCallDoesNotCloseItsChannel() throws Exception {
        try (Host server = new Host(10000, "normal"); Host client = new Host(10000, "hold")) {
            server.bind();
            MetFuture call = client.invoke(server.address);
            MemoryChannel channel = client.connector().outgoing;
            assertTrue(call.cancel());
            assertTrue(channel.isActive());
            assertEquals(1, client.cancelledTimers.get());
            RequestPayload request = channel.sent.poll(2, TimeUnit.SECONDS);
            assertTrue(request.completion().isDone());
            assertTrue(request.completion().getCause() instanceof CancellationException);
            client.manager.onResponse(channel, request.getRequestID(), response(request, ProtocolStatus.OK));
            assertTrue(call.isCancelled());
            MetFuture next = client.invoke(server.address);
            assertFalse(next.isDone());
            next.cancel();
        }
    }

    @Test
    public void connectAndWriteFailuresReleaseRequestCapacity() throws Exception {
        try (Host server = new Host(10000, "normal"); Host client = new Host(10000, "fail-write")) {
            server.bind();
            assertTrue(failure(client.invoke(new InterAddress("memory", "missing", 1, "default"))) instanceof IOException);
            assertTrue(failure(client.invoke(server.address)) instanceof IOException);
            assertTrue(failure(client.invoke(server.address)) instanceof IOException);
            assertEquals(3, client.cancelledTimers.get());
        }
    }

    @Test
    public void unknownSchemeFailsThroughRpcFuture() throws Exception {
        try (Host client = new Host(10000, "normal")) {
            Throwable failure = failure(client.invoke(new InterAddress("unknown", "localhost", 1, "default")));
            assertTrue(failure instanceof MetException);
            assertNull(client.manager.find(client.config.name()));
            assertEquals(1, client.cancelledTimers.get());
        }
    }

    @Test
    public void closeStopsListenersThenDrainsWritesBeforeFailingPendingCalls() throws Exception {
        try (Host server = new Host(10000, "normal"); Host client = new Host(10000, "hold")) {
            server.bind();
            MetListen listen = client.bind();
            MetClient remote = client.client(server.address);
            MetFuture pending = client.invoke(server.address);
            MemoryChannel channel = client.connector().outgoing;
            channel.drainGate = new BasicFuture<>();
            FutureTask<Void> closing = new FutureTask<>(client.rpc::close, null);
            Thread thread = new Thread(closing, "rpc-close-test");
            thread.start();
            try {
                assertTrue(channel.draining.await(2, TimeUnit.SECONDS));
                assertFalse(listen.isActive());
                assertFalse(closing.isDone());
                assertFalse(pending.isDone());
                assertTrue(failure(remote.asyncInvoke(client.service, "echo", new Class<?>[] { String.class }, new Object[] { "late" })) instanceof IllegalStateException);
            } finally {
                channel.close();
                channel.drainGate.completed(channel);
                closing.get(3, TimeUnit.SECONDS);
            }
            assertTrue(failure(pending) instanceof IllegalStateException);
            assertFalse(channel.isActive());
            client.rpc.close();
            assertFalse(client.manager.isInitialized());
        }
    }

    @Test
    public void inboundRequestsAreIndependentOfOutboundCapacity() throws Exception {
        try (Host server = new Host(10000, "hold"); Host peer = new Host(10000, "normal")) {
            server.bind();
            peer.bind();
            MetFuture outbound = server.invoke(peer.address);
            assertFalse(outbound.isDone());
            assertTrue(failure(server.invoke(peer.address)) instanceof MetException);
            assertEquals("hello", peer.invoke(server.address).getData(2, TimeUnit.SECONDS));
            assertEquals(1, server.remoteFilters.get());
            assertFalse(outbound.isDone());
            outbound.cancel();
        }
    }

    @Test
    public void sameInboundRequestIdRepliesOnItsOwnChannel() throws Exception {
        try (Host server = new Host(10000, "normal"); Host first = new Host(10000, "normal"); Host second = new Host(10000, "normal")) {
            server.bind();
            MemoryChannel firstChannel = (MemoryChannel) first.manager.connect(server.address).get();
            MemoryChannel secondChannel = (MemoryChannel) second.manager.connect(server.address).get();
            firstChannel.sendData(request(server, 42, "first")).get();
            secondChannel.sendData(request(server, 42, "second")).get();
            ResponsePayload firstReply = finalResponse(firstChannel.peer);
            ResponsePayload secondReply = finalResponse(secondChannel.peer);
            assertEquals(42, firstReply.getRequestID());
            assertEquals(42, secondReply.getRequestID());
            assertEquals("first", firstReply.getReturnData());
            assertEquals("second", secondReply.getReturnData());
            assertEquals(2, server.remoteFilters.get());
        }
    }

    @Test
    public void mismatchedInboundRequestIdDoesNotInvokeService() throws Exception {
        try (Host server = new Host(10000, "normal"); Host client = new Host(10000, "normal")) {
            server.bind();
            MemoryChannel channel = (MemoryChannel) client.manager.connect(server.address).get();
            server.manager.onRequest(channel.peer, 43, request(server, 42, "invalid"));
            channel.sendData(request(server, 44, "valid")).get();
            ResponsePayload response = finalResponse(channel.peer);
            assertEquals(44, response.getRequestID());
            assertEquals("valid", response.getReturnData());
            assertEquals(1, server.remoteFilters.get());
        }
    }

    @Test
    public void responseStillCompletesDuringCloseDrain() throws Exception {
        try (Host server = new Host(10000, "normal"); Host client = new Host(10000, "hold")) {
            server.bind();
            MetFuture pending = client.invoke(server.address);
            MemoryChannel channel = client.connector().outgoing;
            RequestPayload request = channel.sent.poll(2, TimeUnit.SECONDS);
            assertNotNull(request);
            channel.drainGate = new BasicFuture<>();
            FutureTask<Void> closing = new FutureTask<>(client.rpc::close, null);
            Thread thread = new Thread(closing, "rpc-response-during-close");
            thread.start();
            try {
                assertTrue(channel.draining.await(2, TimeUnit.SECONDS));
                client.manager.onResponse(channel, request.getRequestID(), response(request, ProtocolStatus.OK));
                assertEquals("reply", pending.getData(2, TimeUnit.SECONDS));
                assertFalse(closing.isDone());
            } finally {
                channel.close();
                channel.drainGate.completed(channel);
                closing.get(3, TimeUnit.SECONDS);
            }
            assertEquals("reply", pending.getData());
            assertEquals(1, client.cancelledTimers.get());
        }
    }

    private static RequestPayload request(Host server, long requestId, String value) {
        RequestPayload request = new RequestPayload();
        request.setRequestID(requestId);
        request.setServiceGroup(server.service.getBindGroup());
        request.setServiceName(server.service.getBindName());
        request.setServiceVersion(server.service.getBindVersion());
        request.setSerializeType(server.service.getSerializeType());
        request.setClientTimeout(10000);
        request.setReceiveTime(System.currentTimeMillis());
        request.setTargetMethod("echo");
        request.addParameter(String.class.getName(), value);
        return request;
    }

    private static ResponsePayload finalResponse(MemoryChannel channel) throws Exception {
        ResponsePayload response = channel.replies.poll(2, TimeUnit.SECONDS);
        assertNotNull(response);
        if (response.getStatus() == ProtocolStatus.Accept) {
            response = channel.replies.poll(2, TimeUnit.SECONDS);
            assertNotNull(response);
        }
        assertEquals(ProtocolStatus.OK, response.getStatus());
        return response;
    }

    @Test
    public void constructorRequiresInitializedManager() throws Exception {
        try (Host host = new Host(10000, "normal")) {
            try (ConnectorManager uninitialized = new ConnectorManager(host.manager.context(), new MetCallerTest.MemoryFactory())) {
                try {
                    new MetCaller(uninitialized, id -> new Supplier[0]);
                    fail("Manager must be initialized first");
                } catch (IllegalStateException expected) {
                    assertTrue(expected.getMessage().contains("Initialize"));
                }
            }
        }
    }

    @Test
    public void cancelledConnectFailsRpcAndCancelsItsTimeout() throws Exception {
        try (Host server = new Host(10000, "normal"); Host client = new Host(10000, "delayed")) {
            server.bind();
            MetFuture call = client.invoke(server.address);
            client.connector().connecting.cancel();
            assertTrue(failure(call) instanceof CancellationException);
            assertEquals(1, client.cancelledTimers.get());
            MetFuture next = client.invoke(server.address);
            assertFalse(next.isDone());
            next.cancel();
        }
    }

    @Test
    public void cancelledOrFailedAsyncWriteReleasesCapacityWithoutClosingChannel() throws Exception {
        try (Host server = new Host(10000, "normal"); Host client = new Host(10000, "async-write")) {
            server.bind();
            MetFuture cancelled = client.invoke(server.address);
            MemoryChannel first = client.connector().outgoing;
            first.writing.cancel();
            assertTrue(failure(cancelled) instanceof CancellationException);
            assertTrue(first.isActive());
            MetFuture failed = client.invoke(server.address);
            IOException error = new IOException("asynchronous write failure");
            client.connector().outgoing.writing.failed(error);
            assertSame(error, failure(failed));
            assertEquals(2, client.cancelledTimers.get());
            MetFuture next = client.invoke(server.address);
            assertFalse(next.isDone());
            next.cancel();
        }
    }

    @Test
    public void synchronousChannelExceptionBecomesRpcFailure() throws Exception {
        try (Host server = new Host(10000, "normal"); Host client = new Host(10000, "throw-write")) {
            server.bind();
            assertTrue(failure(client.invoke(server.address)) instanceof IllegalStateException);
            assertTrue(failure(client.invoke(server.address)) instanceof IllegalStateException);
            assertEquals(2, client.cancelledTimers.get());
        }
    }

    @Test
    public void closedCallerRejectsLocalAndInboundRequestsBeforeFilters() throws Exception {
        try (Host server = new Host(10000, "normal"); Host client = new Host(10000, "hold")) {
            server.bind();
            MetFuture active = client.invoke(server.address);
            MemoryChannel channel = client.connector().outgoing;
            RequestPayload request = channel.sent.remove();
            client.rpc.close();
            client.rpc.close();
            assertTrue(client.rpc.isClosed());
            assertFalse(client.manager.isInitialized());
            assertTrue(failure(active) instanceof IllegalStateException);
            MetRequestObject rejected = client.rpc.createRequest(new InstanceAddressProvider(server.address), client.service, "echo", new Class<?>[] { String.class }, new Object[] { "rejected" });
            assertTrue(failure(client.rpc.invoke(rejected)) instanceof IllegalStateException);
            client.manager.onRequest(channel, request.getRequestID(), request);
            assertEquals(1, client.localFilters.get());
            assertEquals(0, client.remoteFilters.get());
            assertTrue(channel.replies.isEmpty());
            assertEquals(1, client.cancelledTimers.get());
        }
    }

    @Test
    public void connectionCompletingAfterCloseCannotSendTheRequest() throws Exception {
        try (Host server = new Host(10000, "normal"); Host client = new Host(10000, "delayed")) {
            server.bind();
            MetFuture call = client.invoke(server.address);
            MemoryConnector connector = client.connector();
            client.rpc.close();
            connector.connecting.completed(connector.outgoing);
            assertTrue(failure(call) instanceof IllegalStateException);
            assertTrue(connector.outgoing.sent.isEmpty());
        }
    }

    @Test
    public void subscriptionFailureDoesNotCloseTheProvidedManager() throws Exception {
        try (Host host = new Host(10000, "normal"); ConnectorManager manager = new ConnectorManager(host.manager.context(), new MetCallerTest.MemoryFactory()) {
            @Override
            public void subscribe(ConnectorSubscriber subscriber) {
                throw new IllegalStateException("subscription failed");
            }
        }) {
            manager.init();
            try {
                new MetCaller(manager, id -> new Supplier[0]);
                fail("Subscription failure must abort construction");
            } catch (IllegalStateException expected) {
                assertEquals("subscription failed", expected.getMessage());
            }
            assertTrue(manager.isInitialized());
        }
    }

    @Test
    public void requestCreationUsesAddressProviderAndAllocatesDistinctIds() throws Exception {
        try (Host host = new Host(10000, "normal")) {
            Object[] arguments = { "value" };
            AddressProvider provider = new AddressProvider() {
                public boolean isDistributed() {
                    return true;
                }

                public InterAddress get(String serviceId, String methodName, Object[] args) {
                    assertEquals(host.service.getBindID(), serviceId);
                    assertEquals("echo", methodName);
                    assertSame(arguments, args);
                    return host.address;
                }
            };
            MetRequestObject first = host.rpc.createRequest(provider, host.service, "echo", new Class<?>[] { String.class }, arguments);
            MetRequestObject second = host.rpc.createRequest(provider, host.service, "echo", new Class<?>[] { String.class }, arguments);
            assertNotEquals(first.getRequestID(), second.getRequestID());
            assertFalse(first.isP2PCalls());
            MetRequestObject fixed = host.rpc.createRequest(new InstanceAddressProvider(host.address), host.service, "echo", new Class<?>[] { String.class }, arguments);
            assertTrue(fixed.isP2PCalls());
            assertEquals(host.address, first.getTargetAddress());
            assertNull(host.connector());
        }
    }

    private static ResponsePayload response(RequestPayload request, short status) {
        ResponsePayload response = new ResponsePayload();
        response.setRequestID(request.getRequestID());
        response.setStatus(status);
        response.setReturnData("reply");
        return response;
    }

    private static Throwable failure(MetFuture call) throws Exception {
        try {
            call.getData(3, TimeUnit.SECONDS);
            throw new AssertionError("Expected RPC failure");
        } catch (ExecutionException expected) {
            return expected.getCause();
        }
    }

    private static final class Host implements AutoCloseable {
        private static final AtomicInteger             IDS             = new AtomicInteger();
        private final        InterAddress              address         = new InterAddress("memory", "localhost", IDS.incrementAndGet(), "default");
        private final        ServiceDomain<Echo>       service         = new ServiceDomain<>(Echo.class);
        private final        AtomicInteger             localFilters    = new AtomicInteger();
        private final        AtomicInteger             remoteFilters   = new AtomicInteger();
        private final        AtomicInteger             cancelledTimers = new AtomicInteger();
        private final        BlockingQueue<MetRequest> requests        = new LinkedBlockingQueue<>();
        private final        ConnectorConfig           config;
        private final        ConnectorManager          manager;
        private final        MetCaller                 rpc;

        @SuppressWarnings("unchecked")
        private Host(int timeout, String mode) {
            this.service.setBindName("Echo");
            this.service.setServiceType(MetServiceType.Provider);
            this.service.setSerializeType("Java");
            this.service.setClientTimeout(timeout);
            Map<String, String> options = new HashMap<>();
            options.put("listenType", "rpc-memory");
            options.put("mode", mode);
            this.config = new ConnectorConfig("rpc", this.address, options, Collections.singletonList(new ProtocolConfig("rpc", this.address.getSchema(), options.getOrDefault("protocol", this.address.getSchema()), options)), true);
            MetSettings settings = proxy(MetSettings.class, (p, method, args) -> {
                switch (method.getName()) {
                    case "getConnectorConfigs":
                        return Collections.singletonList(this.config);
                    case "getQueueMaxSize":
                        return 16;
                    case "getQueueMinPoolSize":
                    case "getQueueMaxPoolSize":
                        return 1;
                    case "getQueueKeepAliveTime":
                        return 1000L;
                    case "getDefaultTimeout":
                        return timeout;
                    case "getMaximumRequest":
                        return 1;
                    case "getSendLimitPolicy":
                        return SendLimitPolicy.Reject;
                    case "getRequestOptions":
                    case "getResponseOptions":
                        return new OptionInfo();
                    default:
                        throw new AssertionError("Unexpected setting: " + method);
                }
            });
            JavaSerializeCoder coder = new JavaSerializeCoder();
            MetContext context = proxy(MetContext.class, (p, method, args) -> {
                switch (method.getName()) {
                    case "getSettings":
                        return settings;
                    case "getSerializeCoder":
                        return coder;
                    case "getClassLoader":
                        return getClass().getClassLoader();
                    case "getServiceInfo":
                        return this.service;
                    case "getServiceProvider":
                        return (Supplier<Echo>) () -> value -> value;
                    case "bindAddress":
                        return this.address;
                    default:
                        throw new AssertionError("Unexpected context: " + method);
                }
            });
            this.manager = new ConnectorManager(context, new MetCallerTest.MemoryFactory()) {
                @Override
                public Cancellable schedule(Runnable task, long delay) {
                    Cancellable timer = super.schedule(task, delay);
                    return () -> {
                        Host.this.cancelledTimers.incrementAndGet();
                        return timer.cancel();
                    };
                }
            };
            this.manager.init();
            MetFilter filter = (request, response, chain) -> {
                this.requests.add(request);
                if (request.isLocal()) {
                    this.localFilters.incrementAndGet();
                } else {
                    this.remoteFilters.incrementAndGet();
                }
                chain.doFilter(request, response);
            };
            this.rpc = new MetCaller(this.manager, id -> new Supplier[] { (Supplier<MetFilter>) () -> filter });
        }

        private MetListen bind() throws Exception {
            return this.manager.bind(this.config.name()).get(2, TimeUnit.SECONDS);
        }

        private MemoryConnector connector() {
            return (MemoryConnector) this.manager.find(this.config.name());
        }

        private MetClient client(InterAddress target) {
            return new MetClientImpl(this.rpc, new InstanceAddressProvider(target));
        }

        private MetFuture invoke(InterAddress target) {
            return this.client(target).asyncInvoke(this.service, "echo", new Class<?>[] { String.class }, new Object[] { "hello" });
        }

        @Override
        public void close() {
            this.rpc.close();
        }
    }

    public static final class MemoryFactory implements MetConnectorFactory {
        public Collection<String> listenTypes(ClassLoader loader) {
            return Collections.singletonList("rpc-memory");
        }

        public MetConnector create(ConnectorConfig connectorConfig, ConnectorManager connectorManager) {
            return new MemoryConnector(connectorConfig, connectorManager);
        }
    }

    private static final class MemoryConnector extends AbstractConnector {
        private static final Map<InterAddress, MemoryConnector> LISTENERS = new ConcurrentHashMap<>();
        private              MemoryChannel                      outgoing;
        private              BasicFuture<MetChannel>            connecting;

        private MemoryConnector(ConnectorConfig connectorConfig, ConnectorManager connectorManager) {
            super(connectorConfig, connectorManager);
        }

        protected void initialize() {
        }

        protected void doClose() {
        }

        private InterAddress localAddress() {
            return this.manager.context().bindAddress(this.config().address().getSchema());
        }

        protected Future<MetListen> listen(InterAddress address, ReceivedListener listener) {
            String type = this.config.listenType();
            LISTENERS.put(address, this);
            return new BasicFuture<>(new AbstractMetListen(type, address, listener) {
                private boolean active = true;

                public boolean isActive() {
                    return this.active;
                }

                public void close() {
                    this.active = false;
                    LISTENERS.remove(address, MemoryConnector.this);
                    MemoryConnector.this.onListenClosed(this);
                }
            });
        }

        protected Future<MetChannel> openSession(InterAddress target, ReceivedListener listener) {
            this.connecting = new BasicFuture<>();
            MemoryConnector server = LISTENERS.get(target);
            if (server == null) {
                this.connecting.failed(new IOException("No listener"));
                return this.connecting;
            }
            this.outgoing = new MemoryChannel(this, this.manager.nextConnectionId(), target, listener);
            MemoryChannel incoming = new MemoryChannel(server, server.manager.nextConnectionId(), this.localAddress(), server.manager);
            this.outgoing.peer = incoming;
            incoming.peer = this.outgoing;
            this.fireChannelConnected(this.outgoing);
            server.fireChannelConnected(incoming);
            if (!"delayed".equals(this.config().option("mode", "normal"))) {
                this.connecting.completed(this.outgoing);
            }
            return this.connecting;
        }

        private void closed(MetChannel channel) {
            this.fireChannelClosed(channel);
        }
    }

    private static final class MemoryChannel extends AbstractMetChannel {
        private final    MemoryConnector                owner;
        private final    InterAddress                   remote;
        private final    BlockingQueue<RequestPayload>  sent     = new LinkedBlockingQueue<>();
        private final    BlockingQueue<ResponsePayload> replies  = new LinkedBlockingQueue<>();
        private final    CountDownLatch                 draining = new CountDownLatch(1);
        private volatile boolean                        active   = true;
        private          MemoryChannel                  peer;
        private          BasicFuture<MetChannel>        drainGate;
        private          BasicFuture<MetChannel>        writing;

        private MemoryChannel(MemoryConnector owner, long id, InterAddress remote, ReceivedListener listener) {
            super(owner, id, listener);
            this.owner = owner;
            this.remote = remote;
        }

        public InterAddress getLocal() {
            return this.owner.localAddress();
        }

        public InterAddress getRemote() {
            return this.remote;
        }

        public boolean isActive() {
            return this.active;
        }

        public Future<MetChannel> sendData(Payload payload) {
            BasicFuture<MetChannel> result = new BasicFuture<>();
            if (!this.active || "fail-write".equals(this.owner.config().option("mode", "normal"))) {
                result.failed(new IOException("Write failed"));
                return result;
            }
            String mode = this.owner.config().option("mode", "normal");
            if ("throw-write".equals(mode)) {
                throw new IllegalStateException("write threw");
            }
            if ("async-write".equals(mode)) {
                this.writing = result;
                return result;
            }
            if (payload instanceof RequestPayload) {
                RequestPayload request = (RequestPayload) payload;
                this.sent.add(request);
                if ("instant".equals(this.owner.config().option("mode", "normal"))) {
                    this.listener().onResponse(this, request.getRequestID(), response(request, ProtocolStatus.OK));
                } else if (!"hold".equals(this.owner.config().option("mode", "normal"))) {
                    request.setReceiveTime(System.currentTimeMillis());
                    this.peer.listener().onRequest(this.peer, request.getRequestID(), request);
                }
            } else {
                ResponsePayload response = (ResponsePayload) payload;
                this.replies.add(response);
                this.peer.listener().onResponse(this.peer, response.getRequestID(), response);
            }
            result.completed(this);
            return result;
        }

        public Future<MetChannel> drainAndClose() {
            this.draining.countDown();
            return this.drainGate != null ? this.drainGate : this.close();
        }

        public Future<MetChannel> close() {
            this.active = false;
            this.owner.closed(this);
            return new BasicFuture<>(this);
        }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler));
    }
}
