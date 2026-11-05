package net.hasor.meteor.example.provider;
import net.hasor.core.InjectSettings;
import net.hasor.meteor.example.api.DebugMessage;
import net.hasor.meteor.example.api.StatusService;

/** 返回状态服务所在的单元和进程。 */
public final class StatusServiceImpl implements StatusService {
    @InjectSettings(value = "meteor.unit", defaultValue = "default")
    private String unit;

    @Override
    public DebugMessage status(DebugMessage request) {
        DebugMessage response = new DebugMessage();
        response.setValue("status:" + this.unit + ":" + ProcessHandle.current().pid() + ":" + request.getValue());
        return response;
    }
}
