/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.session;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.connector.ProtocolSessionContext;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.http.channel.HttpBodyStream;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import net.hasor.meteor.domain.payload.RequestPayload;

/** 同一次 RPC 的 HTTP 尝试；仅在尚未提交响应头且请求可重放时重试。所有状态在连接执行器中变更。 */
final class GrpcClientAttemptCoordinator {
    private final HttpExchange                 logical;
    private final NetworkChannel<HttpExchange> connection;
    private final ProtocolSessionContext       context;
    private final GrpcRetryMethodPolicy        policy;
    private final GrpcRetryThrottle            throttle;
    private final BasicFuture<Void>            sent   = new BasicFuture<>();
    private final Set<HttpExchange>            active = new LinkedHashSet<>();
    private final long                         deadline;
    private final GrpcRequestReplayBuffer      replay;
    private final int                          bufferLimit;
    private       Cancellable                  scheduled;
    private       HttpExchange                 committed;
    private       HttpResponse                 lastFailure;
    private       int                          started;
    private       int                          backoffs;
    private       long                         scheduleVersion;
    private       boolean                      stopped;
    private       boolean                      bufferExceeded;
    private       boolean                      hedgingStopped;

    GrpcClientAttemptCoordinator( //
            HttpExchange logical, RequestPayload request, NetworkChannel<HttpExchange> connection, //
            ProtocolSessionContext context, Executor workers, GrpcClientRetryPolicy configuration, //
            GrpcRetryMethodPolicy policy) {
        this.logical = logical;
        this.connection = connection;
        this.context = context;
        this.policy = policy;
        this.bufferLimit = configuration.bufferLimit();
        this.throttle = configuration.throttle(connection.getRemote().toHostSchema());
        this.deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(request.getClientTimeout());
        HttpBodyStream source = logical.request().stream();
        boolean replayable = source != null && policy.attempts() > 1;
        if (replayable) {
            Runnable overflow = () -> connection.execute(this::bufferExceeded);
            this.replay = new GrpcRequestReplayBuffer(source, workers, this.bufferLimit, overflow, failure -> {
                connection.execute(() -> this.fail(failure));
            });
        } else {
            this.replay = null;
        }
    }

    Future<Void> start() {
        this.logical.completion().onFinal(done -> this.connection.execute(() -> this.stop(done.getCause())));
        this.sent.onCancel(done -> this.logical.complete(new CancellationException("gRPC write cancelled")));
        this.attempt();
        if (this.replay != null && !this.stopped) {
            try {
                this.replay.start();
            } catch (RuntimeException failure) {
                this.fail(failure);
            }
        }
        return this.sent;
    }

    private void attempt() {
        this.scheduled = null;
        if (this.stopped || this.committed != null || this.logical.completion().isDone()) {
            return;
        }
        try {
            HttpRequest source = this.logical.request();
            Map<String, List<String>> headers = new LinkedHashMap<>(source.headers());
            if (this.started != 0) {
                headers.put("grpc-previous-rpc-attempts", List.of(Integer.toString(this.started)));
            }
            long remaining = Math.max(1, TimeUnit.NANOSECONDS.toMillis(this.deadline - System.nanoTime()));
            String timeout = remaining <= 99999999 ? remaining + "m" : ((remaining + 999) / 1000) + "S";
            headers.put("grpc-timeout", List.of(timeout));
            HttpRequest request;
            if (source.stream() == null) {
                request = HttpRequest.newBuffered(//
                        source.method(), source.uri(), headers, source.body(), 2, source.trailers());
            } else {
                HttpBodyStream body = this.replay == null ? source.stream() : this.replay.attach();
                request = HttpRequest.newStreaming(source.method(), source.uri(), headers, body, 2);
            }
            HttpExchange attempt = new HttpExchange(request);
            this.active.add(attempt);
            this.started++;
            attempt.response().onFinal(done -> this.connection.execute(() -> {
                if (done.getCause() != null) {
                    this.failed(attempt, done.getCause());
                } else {
                    this.received(attempt, done.getResult());
                }
            }));
            attempt.completion().onFinal(done -> this.connection.execute(() -> {
                if (this.committed == attempt) {
                    this.accountSuccess(attempt, done.getCause());
                    this.logical.complete(done.getCause());
                }
            }));
            this.connection.write(attempt).onFinal(done -> this.connection.execute(() -> {
                if (done.getCause() != null) {
                    this.failed(attempt, done.getCause());
                } else if (done.isCancelled()) {
                    this.failed(attempt, new CancellationException("gRPC attempt write cancelled"));
                } else {
                    this.sent.completed(null);
                }
            }));

            if (this.bufferExceeded || (this.replay == null && source.body().length > this.bufferLimit)) {
                this.commit(attempt);
            } else if (this.policy.hedging() && this.canAttempt()) {
                this.schedule(this.policy.initialDelay());
            }
        } catch (Throwable failure) {
            this.fail(failure);
        }
    }

    private void received(HttpExchange attempt, HttpResponse response) {
        if (this.stopped || !this.active.contains(attempt)) {
            return;
        }
        int status = this.status(response);
        boolean retryable = this.policy.retryable(status);
        boolean permitted = this.throttle == null;
        if (this.throttle != null) {
            if (retryable) {
                permitted = this.throttle.failed();
            }
        }
        // 普通初始响应头会提交调用；只有 trailers-only 错误尚未交付业务响应。
        if (this.committed == attempt || !retryable || response.header("grpc-status") == null) {
            this.commit(attempt);
            this.sent.completed(null);
            this.logical.respond(response);
            return;
        }

        this.lastFailure = HttpResponse.newBuffered(//
                response.status(), response.headers(), new byte[0], response.trailers());
        this.active.remove(attempt);
        this.discard(attempt);
        Long pushback = this.pushback(response);
        if (pushback != null && pushback < 0) {
            this.hedgingStopped = true;
            this.cancelScheduled();
        }
        if (!permitted) {
            this.hedgingStopped = true;
            this.cancelScheduled();
        }
        if (this.canAttempt()) {
            this.cancelScheduled();
            long delay;
            if (pushback != null) {
                this.backoffs = 0;
                delay = pushback;
            } else if (this.policy.hedging()) {
                // 非致命错误不用再等待下一次对冲周期。
                delay = 0;
            } else {
                delay = this.backoff();
            }
            this.schedule(delay);
        } else if (this.active.isEmpty()) {
            this.sent.completed(null);
            this.logical.respond(this.lastFailure);
        }
    }

    private int status(HttpResponse response) {
        String text = response.header("grpc-status");
        if (text == null) {
            return -1;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private void accountSuccess(HttpExchange attempt, Throwable failure) {
        if (this.throttle == null || failure != null) {
            return;
        }
        HttpResponse response = attempt.response().getResult();
        String status = response.header("grpc-status");
        if (status == null) {
            Map<String, List<String>> trailers = response.trailers();
            if (response.stream() != null) {
                trailers = response.stream().trailers();
            }
            List<String> values = trailers.get("grpc-status");
            if (values != null && !values.isEmpty()) {
                status = values.get(0);
            }
        }
        if (StringUtils.equals("0", status)) {
            this.throttle.succeeded();
        }
    }

    private Long pushback(HttpResponse response) {
        String value = response.header("grpc-retry-pushback-ms");
        if (value == null) {
            return null;
        }
        try {
            return (long) Integer.parseInt(value);
        } catch (NumberFormatException invalid) {
            return -1L;
        }
    }

    private void failed(HttpExchange attempt, Throwable cause) {
        if (!this.active.contains(attempt) || this.stopped) {
            return;
        }
        // 没有对端状态时无法确认业务是否执行，保持原始传输失败，不做隐式重放。
        if (this.committed == attempt || !this.policy.hedging() || this.active.size() == 1) {
            this.fail(cause);
        } else {
            this.active.remove(attempt);
            this.discard(attempt);
        }
    }

    private void schedule(long delay) {
        if (this.scheduled == null) {
            long version = ++this.scheduleVersion;
            this.scheduled = this.context.schedule(() -> this.connection.execute(() -> {
                if (version != this.scheduleVersion) {
                    return;
                }
                this.scheduled = null;
                if (this.canAttempt()) {
                    this.attempt();
                } else if (this.active.isEmpty() && this.lastFailure != null) {
                    // 等待期间，其他调用可能耗尽同一目标的令牌；此时返回原错误，不悬挂到 deadline。
                    this.sent.completed(null);
                    this.logical.respond(this.lastFailure);
                }
            }), delay);
        }
    }

    private boolean canAttempt() {
        boolean allowed = this.throttle == null || this.throttle.allowsRetry();
        return !this.hedgingStopped && this.started < this.policy.attempts() && this.committed == null && allowed;
    }

    private long backoff() {
        double delay = this.policy.initialDelay() * Math.pow(this.policy.multiplier(), this.backoffs++);
        delay = Math.min(delay, this.policy.maxDelay());
        return (long) (delay * ThreadLocalRandom.current().nextDouble(0.8, 1.2));
    }

    private void bufferExceeded() {
        this.bufferExceeded = true;
        if (!this.active.isEmpty()) {
            HttpExchange winner = this.active.iterator().next();
            HttpBodyStream leading = this.replay.mostAdvanced();
            for (HttpExchange attempt : this.active) {
                if (attempt.request().stream() == leading) {
                    winner = attempt;
                    break;
                }
            }
            this.commit(winner);
        }
    }

    private void commit(HttpExchange winner) {
        if (this.committed != null) {
            return;
        }
        this.committed = winner;
        this.cancelScheduled();
        for (HttpExchange other : new ArrayList<>(this.active)) {
            if (other != winner) {
                this.active.remove(other);
                this.discard(other);
            }
        }
        if (this.replay != null) {
            this.replay.commit();
        }
    }

    private void discard(HttpExchange attempt) {
        if (this.replay != null) {
            this.replay.detach(attempt.request().stream());
        }
        if (!attempt.completion().isDone()) {
            attempt.complete(new CancellationException("gRPC attempt superseded"));
        }
    }

    private void cancelScheduled() {
        // 定时器已触发但尚在连接队列中的任务，也必须失效。
        this.scheduleVersion++;
        if (this.scheduled != null) {
            this.scheduled.cancel();
            this.scheduled = null;
        }
    }

    private void fail(Throwable cause) {
        this.sent.failed(cause);
        this.logical.complete(cause);
    }

    private void stop(Throwable cause) {
        if (this.stopped) {
            return;
        }
        this.stopped = true;
        this.cancelScheduled();
        for (HttpExchange attempt : new ArrayList<>(this.active)) {
            this.discard(attempt);
        }
        this.active.clear();
        if (this.replay != null) {
            Throwable failure = cause == null ? new IOException("gRPC call completed") : cause;
            this.replay.close(failure);
        }
    }
}
