package net.hasor.meteor.example;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import net.hasor.boot.Boot;
import net.hasor.boot.BootApplication;
import net.hasor.cobble.StringUtils;
import net.hasor.core.ApiBinder;
import net.hasor.core.Module;
import net.hasor.meteor.MeteorModule;
import net.hasor.meteor.example.consumer.ServiceConsumer;
import org.h2.jdbcx.JdbcDataSource;

/** 消费者入口：注入 Meteor 代理并持续调用，便于观察控制台治理操作的效果。 */
public class ConsumerMain implements Module {
    @Override
    public void loadModule(ApiBinder binder) throws Throwable {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL(binder.getSettings().getString("example.jdbc.url"));
        source.setUser(binder.getSettings().getString("example.jdbc.username", "sa"));
        source.setPassword(binder.getSettings().getString("example.jdbc.password", ""));
        binder.bindType(DataSource.class).nameWith("registryDataSource").toInstance(source);
        binder.bindType(ServiceConsumer.class);
        binder.installModule(new MeteorModule());
    }

    public static void main(String[] arguments) throws Exception {
        String configuration = System.getProperty("config");
        if (StringUtils.isBlank(configuration)) {
            configuration = "example-consumer.properties";
        } else {
            configuration = Path.of(configuration).toAbsolutePath().toUri().toString();
        }

        Boot boot = new Boot().arguments(arguments)//
                .hconfigFile(configuration)//
                .sources(ConsumerMain.class)//
                .property("hasor.loadPackages", "net.hasor.meteor.example");

        try (BootApplication application = boot.start()) {
            ServiceConsumer consumer = application.getAppContext().getInstance(ServiceConsumer.class);
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    consumer.call();
                } catch (RuntimeException failure) {
                    System.err.println("[CALL FAILED] " + failure.getMessage());
                }
                TimeUnit.SECONDS.sleep(2);
            }
        }
    }
}
