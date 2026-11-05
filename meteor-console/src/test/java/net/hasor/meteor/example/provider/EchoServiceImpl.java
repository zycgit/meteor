package net.hasor.meteor.example.provider;
import net.hasor.core.InjectSettings;
import net.hasor.meteor.example.api.DebugMessage;
import net.hasor.meteor.example.api.EchoService;

/** 在响应中返回提供者单元和 PID，便于观察服务路由。 */
public final class EchoServiceImpl implements EchoService {
    @InjectSettings(value = "meteor.unit", defaultValue = "default")
    private String unit;

    @Override
    public DebugMessage echo(DebugMessage request) {
        DebugMessage response = new DebugMessage();
        response.setValue(this.unit + ":" + ProcessHandle.current().pid() + ":" + request.getValue());
        return response;
    }
}
