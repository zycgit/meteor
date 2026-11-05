package net.hasor.meteor.example.api;
import net.hasor.meteor.annotation.MetMethod;
import net.hasor.meteor.annotation.MetParam;
import net.hasor.meteor.annotation.MetService;

/** 加法服务，用于观察同一应用中不同服务的协议配置。 */
@MetService(id = 0x31234571L, name = "CalculatorService", group = "console-debug", version = "1.0.0")
public interface CalculatorService {
    @MetMethod(id = 1)
    DebugMessage add(@MetParam(id = 1, name = "request") DebugMessage request);
}
