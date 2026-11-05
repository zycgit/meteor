package net.hasor.meteor.example;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import net.hasor.boot.Boot;
import net.hasor.boot.BootApplication;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.console.ConsoleMain;
import net.hasor.meteor.console.component.rpc.RpcServer;
import org.h2.jdbcx.JdbcDataSource;
import org.h2.tools.RunScript;
import org.h2.tools.Server;

/** 注册中心入口：启动 H2 注册存储和无头 Console 的 Remote 管理服务。 */
public final class RegistryMain {
    public static void main(String[] arguments) throws Exception {
        String configuration = System.getProperty("config");
        if (StringUtils.isBlank(configuration)) {
            configuration = "example-registry.properties";
        } else {
            configuration = Path.of(configuration).toAbsolutePath().toUri().toString();
        }
        int port = Integer.getInteger("console.example.registry.port", 19192);
        String address = "jdbc:h2:tcp://127.0.0.1:" + port + "/mem:console-debug";

        // 数据库由当前 JVM 持有；业务进程通过 TCP 访问同一份注册数据。
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:console-debug;DB_CLOSE_DELAY=-1");
        source.setUser("sa");
        try (var connection = source.getConnection(); //
             var resource = RegistryMain.class.getResourceAsStream("/META-INF/meteor/schema/h2.sql"); //
             var reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
            RunScript.execute(connection, reader);
        }

        Server server = Server.createTcpServer("-tcpPort", String.valueOf(port), "-tcpDaemon", "-ifExists");
        try {
            server.start();
            Boot boot = new Boot().arguments(arguments)//
                    .hconfigFile(configuration)//
                    .sources(ConsoleMain.class, ConsoleLauncher.class)//
                    .property("hasor.loadPackages", "net.hasor.meteor.example")//
                    .property("storage.address", address);
            try (BootApplication application = boot.start()) {
                System.out.println("H2 注册存储：" + address);
                System.out.println("Remote 管理服务：" + application.getAppContext().getInstance(RpcServer.class).getAddress());
                System.out.println("停止此进程会清空调试注册数据。");
                application.join();
            }
        } finally {
            server.stop();
        }
    }
}
