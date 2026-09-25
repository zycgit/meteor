/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.FutureCallback;

/**
 * RSF Future
 * @version : 2014年11月14日
 * @author 赵永春 (zyc@hasor.net)
 */
public class RsfFuture extends BasicFuture<RsfResponse> {
    private RsfRequest rsfRequest = null;

    public RsfFuture(RsfRequest rsfRequest) {
        this.rsfRequest = rsfRequest;
    }

    public RsfFuture(RsfRequest rsfRequest, FutureCallback<RsfResponse> listener) {
        super(listener);
        this.rsfRequest = rsfRequest;
    }

    /** @return 获取发起请求的Request*/
    public RsfRequest getRequest() {
        return this.rsfRequest;
    }

    /**
     * 获取响应的结果。
     * @return 获取响应的结果。
     * @throws InterruptedException wait方法可能引发的异常。
     * @throws ExecutionException 远程方法在调用过程中发生异常。
     */
    public Object getData() throws InterruptedException, ExecutionException {
        return this.get().getData();
    }

    /**
     * 等待执行结果的返回。
     * @param timeout 超时时间
     * @param unit 超时时间单位
     * @return 返回执行结果。
     * @throws InterruptedException wait方法可能引发的异常。
     * @throws ExecutionException 远程方法在调用过程中发生异常。
     * @throws TimeoutException 超时时间到达
     */
    public Object getData(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        return this.get(timeout, unit).getData();
    }
}