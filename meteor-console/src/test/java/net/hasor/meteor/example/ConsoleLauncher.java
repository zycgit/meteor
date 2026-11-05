package net.hasor.meteor.example;
import java.nio.file.Path;
import net.hasor.boot.Boot;
import net.hasor.boot.BootApplication;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.console.ConsoleMain;
import net.hasor.meteor.console.component.provider.jdbc.JdbcProviderFactory;
import net.hasor.meteor.console.component.provider.nacos.NacosProviderFactory;
import net.hasor.meteor.console.component.provider.remote.RemoteProviderFactory;
import net.hasor.meteor.console.component.provider.zk.ZookeeperProviderFactory;
import net.hasor.meteor.console.web.*;
import net.hasor.web.WebApiBinder;
import net.hasor.web.WebModule;

/** 控制台入口：显式装配正式控制台，示例扫描范围始终限定在本包。 */
public class ConsoleLauncher implements WebModule {
    public static void main(String[] arguments) throws Exception {
        String configuration = System.getProperty("config");
        if (StringUtils.isBlank(configuration)) {
            configuration = "meteor.properties";
        } else {
            configuration = Path.of(configuration).toAbsolutePath().toUri().toString();
        }
        Boot boot = new Boot().arguments(arguments)//
                .hconfigFile(configuration)//
                .sources(ConsoleMain.class, ConsoleLauncher.class)//
                .property("hasor.loadPackages", "net.hasor.meteor.example");
        try (BootApplication application = boot.start()) {
            application.join();
        }
    }

    @Override
    public void loadModule(WebApiBinder binder) {
        // 使用正式组件，显式登记，避免扫描生产包或把示例业务装配进控制台。
        binder.bindType(JdbcProviderFactory.class);
        binder.bindType(NacosProviderFactory.class);
        binder.bindType(ZookeeperProviderFactory.class);
        binder.bindType(RemoteProviderFactory.class);
        binder.loadMappingTo(AuthController.class);
        binder.loadMappingTo(ClusterController.class);
        binder.loadMappingTo(DirectoryController.class);
        binder.loadMappingTo(InstanceController.class);
        binder.loadMappingTo(MetadataController.class);
        binder.loadMappingTo(RuleController.class);
        binder.loadMappingTo(ServiceController.class);
    }
}
