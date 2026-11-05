package net.hasor.meteor.example.api;
import net.hasor.meteor.annotation.MetMethod;
import net.hasor.meteor.annotation.MetParam;
import net.hasor.meteor.annotation.MetService;

/** 状态服务，用于观察提供者所在单元及进程。 */
@MetService(id = 0x31234572L, name = "StatusService", group = "console-debug", version = "1.0.0")
public interface StatusService {
    @MetMethod(id = 1)
    DebugMessage status(@MetParam(id = 1, name = "request") DebugMessage request);
}
