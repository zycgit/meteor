package net.hasor.meteor.example;
import java.nio.file.Path;
import javax.sql.DataSource;
import net.hasor.boot.Boot;
import net.hasor.boot.BootApplication;
import net.hasor.cobble.StringUtils;
import net.hasor.core.ApiBinder;
import net.hasor.core.Module;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MeteorModule;
import net.hasor.meteor.example.provider.CalculatorServiceImpl;
import net.hasor.meteor.example.provider.EchoServiceImpl;
import net.hasor.meteor.example.provider.StatusServiceImpl;
import org.h2.jdbcx.JdbcDataSource;

/** 提供者入口：由 Hasor 管理业务实现，Meteor 插件自动发布 api 下的服务。 */
public class ProviderMain implements Module {
    @Override
    public void loadModule(ApiBinder binder) throws Throwable {
        // JDBC 数据源由应用提供，注册发现及连接器由 meteor-hasor 读取配置并安装。
        JdbcDataSource source = new JdbcDataSource();
        source.setURL(binder.getSettings().getString("example.jdbc.url"));
        source.setUser(binder.getSettings().getString("example.jdbc.username", "sa"));
        source.setPassword(binder.getSettings().getString("example.jdbc.password", ""));
        binder.bindType(DataSource.class).nameWith("registryDataSource").toInstance(source);
        binder.bindType(EchoServiceImpl.class);
        binder.bindType(CalculatorServiceImpl.class);
        binder.bindType(StatusServiceImpl.class);
        binder.installModule(new MeteorModule());
    }

    public static void main(String[] arguments) throws Exception {
        String configuration = System.getProperty("config");
        if (StringUtils.isBlank(configuration)) {
            configuration = "example-provider.properties";
        } else {
            configuration = Path.of(configuration).toAbsolutePath().toUri().toString();
        }

        Boot boot = new Boot().arguments(arguments)//
                .hconfigFile(configuration)//
                .sources(ProviderMain.class)//
                .property("hasor.loadPackages", "net.hasor.meteor.example");
        try (BootApplication application = boot.start()) {
            MetContext meteor = application.getAppContext().getInstance(MetContext.class);
            System.out.println("提供者已启动：app=" + meteor.getApplicationName());
            System.out.println("实例管理地址：" + meteor.getListenAddress("rsf"));
            application.join();
        }
    }

}
