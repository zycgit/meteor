package net.hasor.meteor.example.api;
import net.hasor.meteor.annotation.MetMethod;
import net.hasor.meteor.annotation.MetParam;
import net.hasor.meteor.annotation.MetService;

/** 显示实际响应的提供者，便于观察路由和实例上下线。 */
@MetService(id = 0x31234570L, name = "EchoService", group = "console-debug", version = "1.0.0")
public interface EchoService {
    @MetMethod(id = 1)
    DebugMessage echo(@MetParam(id = 1, name = "request") DebugMessage request);
}
