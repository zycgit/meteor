package net.hasor.meteor.example.provider;
import net.hasor.meteor.example.api.CalculatorService;
import net.hasor.meteor.example.api.DebugMessage;

/** 提供者的加法服务实现，由 Hasor 容器创建。 */
public final class CalculatorServiceImpl implements CalculatorService {
    @Override
    public DebugMessage add(DebugMessage request) {
        DebugMessage response = new DebugMessage();
        response.setResult(request.getLeft() + request.getRight());
        return response;
    }
}
