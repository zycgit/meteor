/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hasor.tconsole.launcher;
import io.netty.buffer.ByteBufAllocator;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.NameThreadFactory;
import net.hasor.cobble.logging.Logger;
import net.hasor.cobble.logging.LoggerFactory;
import net.hasor.tconsole.TelContext;
import net.hasor.tconsole.TelExecutor;
import net.hasor.tconsole.commands.GetSetExecutor;
import net.hasor.tconsole.commands.HelpExecutor;
import net.hasor.tconsole.commands.QuitExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Supplier;

/**
 * tConsole 服务基类
 * @version : 2016年09月20日
 * @author 赵永春 (zyc@hasor.net)
 */
public abstract class AbstractTelService implements TelContext {
    public static final String                                       CMD            = "tConsole>";
    protected static    Logger                                       logger         = LoggerFactory.getLogger(AbstractTelService.class);
    private final       Map<String, Supplier<? extends TelExecutor>> telExecutorMap = new ConcurrentHashMap<>();
    private             ScheduledExecutorService                     executor       = null;

    public AbstractTelService() {
        // .触发SPI
        logger.info("tConsole -> trigger TelStartContextListener.onStart");
        //
        logger.info("tConsole -> applyCommand.");
        this.applyCommand();
        this.addCommand(new String[] { "get", "set" }, new GetSetExecutor());
        this.addCommand(new String[] { "quit", "exit" }, new QuitExecutor());
        this.addCommand(new String[] { "help" }, new HelpExecutor());
        //
        // .执行线程池
        String shortName = "tConsole-Work";
        int workSize = 2;
        this.executor = Executors.newScheduledThreadPool(workSize, new NameThreadFactory(shortName, this.classLoader));
        ThreadPoolExecutor threadPool = (ThreadPoolExecutor) this.executor;
        threadPool.setCorePoolSize(workSize);
        threadPool.setMaximumPoolSize(workSize);
        logger.info("tConsole -> create TelnetHandler , threadShortName={} , workThreadSize = {}.", shortName, workSize);
    }

    protected void applyCommand() {
        //
    }

    @Override
    protected void doClose() {
        if (this.executor != null) {
            logger.info("tConsole -> executor shutdownNow.");
            this.executor.shutdownNow();
            this.executor = null;
        }
        this.telExecutorMap.clear();
        // .触发SPI
        logger.info("tConsole -> trigger TelStopContextListener.onStop");
    }

    public void asyncExecute(Runnable runnable) {
        if (!this.isInit()) {
            throw new IllegalStateException("the Container need init.");
        }
        this.executor.execute(runnable);
    }

    /** 添加命令 */
    public void addCommand(String cmdName, TelExecutor telExecutor) {
        this.addCommand(new String[] { cmdName }, () -> telExecutor);
    }

    /** 添加命令 */
    public void addCommand(String[] cmdName, TelExecutor telExecutor) {
        this.addCommand(cmdName, () -> telExecutor);
    }

    /** 添加命令 */
    public void addCommand(String cmdName, Supplier<? extends TelExecutor> provider) {
        this.addCommand(new String[] { cmdName }, provider);
    }

    /** 添加命令 */
    public void addCommand(String[] cmdName, Supplier<? extends TelExecutor> provider) {
        for (String name : cmdName) {
            if (StringUtils.isNotBlank(name)) {
                this.telExecutorMap.put(name, provider);
            }
        }
    }

    @Override
    public TelExecutor findCommand(String cmdName) {
        Supplier<? extends TelExecutor> supplier = this.telExecutorMap.get(cmdName);
        if (supplier != null) {
            return supplier.get();
        }
        return null;
    }

    @Override
    public List<String> getCommandNames() {
        return new ArrayList<>(this.telExecutorMap.keySet());
    }

    public abstract ByteBufAllocator getByteBufAllocator();
}