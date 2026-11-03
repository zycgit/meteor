package net.hasor.meteor.test.nxn;
import java.util.Arrays;

/** Command entry for planning, executing, and reporting the Docker RPC matrix. */
public final class NxnMain {
    private NxnMain() {
    }

    public static void main(String[] arguments) {
        int status;
        try {
            status = run(arguments);
        } catch (Exception failure) {
            System.err.println("Test orchestration failed: " + failure);
            failure.printStackTrace(System.err);
            status = 1;
        }
        if (status != 0) {
            System.exit(status);
        }
    }

    static int run(String[] arguments) throws Exception {
        if (arguments.length == 0) {
            throw new IllegalArgumentException("Expected export, run, report, or types command");
        }
        String[] options = Arrays.copyOfRange(arguments, 1, arguments.length);
        return switch (arguments[0]) {
            case "export" -> NxnRuntime.run(options);
            case "run" -> NxnRunner.run(options);
            case "report" -> NxnReport.run(options);
            case "types" -> NxnTypeReport.run(options);
            default -> throw new IllegalArgumentException("Unknown command: " + arguments[0]);
        };
    }
}
