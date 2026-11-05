package net.hasor.meteor.console.component.rpc;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ConcurrentModificationException;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetFilter;
import net.hasor.meteor.MetFilterChain;
import net.hasor.meteor.MetRequest;
import net.hasor.meteor.MetResponse;
import net.hasor.meteor.console.global.error.ConsoleOperationException;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.registry.RegistryTokenProvider;

/** Console RPC 的逐次凭据校验和管理操作边界。 */
public final class RpcManagementFilter implements MetFilter {
    private final RegistryTokenProvider tokens;
    private final String[]              secrets;

    public RpcManagementFilter(RegistryTokenProvider tokens, String... secrets) {
        this.tokens = tokens;
        this.secrets = secrets;
    }

    @Override
    public void doFilter(MetRequest request, MetResponse response, MetFilterChain chain) throws Throwable {
        String expected;
        try {
            expected = this.tokens.getToken();
        } catch (RuntimeException failure) {
            response.sendStatus(ProtocolStatus.Unauthorized, "Console management authentication unavailable");
            return;
        }

        String supplied = request.getOption(RpcManagement.CREDENTIAL_OPTION);
        boolean blank = StringUtils.isBlank(expected) || StringUtils.isBlank(supplied);
        if (blank || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            response.sendStatus(ProtocolStatus.Unauthorized, "Console management authentication failed");
            return;
        }

        Object[] arguments = request.getParameterObject();
        if (arguments.length == 0 || !(arguments[0] instanceof RpcTarget target)) {
            response.sendStatus((short) 400, "Remote management requires a registry target");
            return;
        }

        try {
            target.validate();
            chain.doFilter(request, response);
        } catch (Exception failure) {
            Throwable cause = failure;
            String message = failure.getMessage();
            while ((cause instanceof ConsoleOperationException || cause instanceof InvocationTargetException || cause instanceof UndeclaredThrowableException || cause instanceof ExecutionException || cause instanceof CompletionException) && cause.getCause() != null) {
                if (cause instanceof ConsoleOperationException) {
                    message = cause.getMessage();
                }
                cause = cause.getCause();
            }
            if (StringUtils.isBlank(message)) {
                message = cause.getClass().getSimpleName();
            }

            short status = ProtocolStatus.InvokeError;
            if (cause instanceof MetException remote) {
                status = remote.getStatus();
            } else if (cause instanceof IllegalArgumentException) {
                status = 400;
            } else if (cause instanceof SecurityException) {
                status = ProtocolStatus.Forbidden;
            } else if (cause instanceof NoSuchElementException) {
                status = ProtocolStatus.NotFound;
            } else if (cause instanceof UnsupportedOperationException || cause instanceof ConcurrentModificationException) {
                status = 409;
            } else if (cause instanceof TimeoutException || cause instanceof SocketTimeoutException) {
                status = ProtocolStatus.Timeout;
            }
            // 本地 SDK 不经过命令层，RPC 边界仍须隐藏本机存储凭据和当前管理令牌。
            message = message.replace(expected, "***");
            if (this.secrets != null) {
                for (String secret : this.secrets) {
                    if (StringUtils.isNotBlank(secret)) {
                        message = message.replace(secret, "***");
                    }
                }
            }
            response.sendStatus(status, message);
        }
    }
}
