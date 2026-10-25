/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */
package net.hasor.meteor.test.nxn;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.hasor.cobble.StringUtils;

/** Java owns JSON evidence; the native executor owns one Linux process tree. */
public final class NxnProcess {
    public static final String EXECUTABLE = "/opt/meteor-test/peer-process";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> NUMBERS = Set.of("wrapperPid", "wrapperStartTicks", "pid", "pgid",
            "pidStartTicks", "exitCode", "rawExitCode");
    private static final Set<String> BOOLEANS = Set.of("processGroupEmpty", "temporaryDirectoryRemoved");

    private NxnProcess() {
    }

    /** Write the auditable launch and its bounded, NUL-delimited native input. */
    public static void prepareLaunch(Path launchFile, Map<String, Object> launch) throws IOException {
        Path path = absolute(launchFile);
        Files.createDirectories(path.getParent());
        List<String> values = new ArrayList<>();
        // The native layout has a fixed header followed by counted argv, env and unset sections.
        values.add("meteor-peer-v1");
        for (String key : List.of("processId", "runId", "groupId", "workingDirectory", "temporaryDirectory")) {
            Object value = launch.get(key);
            if (value == null) {
                throw new IOException("Missing peer launch field: " + key);
            }
            values.add(value.toString());
        }
        Object command = launch.get("command");
        if (!(command instanceof List<?>) || ((List<?>) command).isEmpty()) {
            throw new IOException("Peer process command must not be empty");
        }
        appendList(values, (List<?>) command);
        Map<?, ?> environment = launch.get("environment") instanceof Map<?, ?>
                ? (Map<?, ?>) launch.get("environment") : Map.of();
        values.add(Integer.toString(environment.size()));
        for (Map.Entry<?, ?> entry : environment.entrySet()) {
            String name = entry.getKey().toString();
            if (name.isEmpty() || name.contains("=")) {
                throw new IOException("Invalid environment name: " + name);
            }
            values.add(name + "=" + entry.getValue());
        }
        Object unset = launch.get("unsetEnvironment");
        appendList(values, unset instanceof List<?> ? (List<?>) unset : List.of());
        byte[] encoded = encode(values);
        writeAtomic(path, JSON.writeValueAsBytes(launch));
        writeAtomic(path.resolveSibling("launch.native"), encoded);
    }

    /** Return the executor arguments; the caller owns the attached local process. */
    public static List<String> runCommand(Path launchFile) throws IOException {
        return List.of(EXECUTABLE, "--run", absolute(launchFile).toString());
    }

    /** Prepare an auditable cancellation without weakening an earlier forced request. */
    public static synchronized List<String> stopCommand(Path launchFile, boolean force) throws IOException {
        Path launch = absolute(launchFile);
        JsonNode configuration = JSON.readTree(Files.readAllBytes(launch));
        String processId = configuration.path("processId").asText();
        if (processId.isEmpty()) {
            throw new IOException("Missing peer launch field: processId");
        }
        Path directory = launch.getParent();
        // Java owns the JSON audit; native controls separately merge atomic requests under flock.
        try (FileChannel lock = FileChannel.open(directory.resolve("cancel-json.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock ignored = lock.lock()) {
            Path cancellation = directory.resolve("cancel.json");
            String previousError = "";
            if (Files.exists(cancellation)) {
                try {
                    JsonNode previous = JSON.readTree(Files.readAllBytes(cancellation));
                    if (previous == null || !StringUtils.equals(processId, previous.path("processId").asText())) {
                        throw new IOException("Cancellation processId does not match launch");
                    }
                    force |= previous.path("force").asBoolean();
                    previousError = previous.path("previousError").asText("");
                } catch (IOException failure) {
                    previousError = "Invalid cancellation file: " + failure.getMessage();
                    force = true;
                }
            }
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("processId", processId);
            request.put("force", force);
            request.put("requestedAt", Instant.now().toString());
            if (!previousError.isEmpty()) {
                request.put("previousError", previousError);
            }
            writeAtomic(cancellation, JSON.writeValueAsBytes(request));
            writeAtomic(directory.resolve("cancel.request"),
                    encode(List.of("meteor-cancel-v1", processId, force ? "1" : "0", previousError)));
        }
        List<String> command = new ArrayList<>(List.of(EXECUTABLE, "--stop", launch.toString()));
        if (force) {
            command.add("--force");
        }
        return command;
    }

    /** Read native state and publish the compatible JSON snapshot for reports and audit. */
    public static JsonNode readStatus(Path launchFile) throws IOException {
        Path directory = absolute(launchFile).getParent();
        Path nativeState = directory.resolve("status.native");
        if (!Files.exists(nativeState)) {
            return null;
        }
        List<String> values = decode(Files.readAllBytes(nativeState));
        if (values.isEmpty() || !StringUtils.equals("meteor-status-v1", values.get(0)) || values.size() % 2 != 1) {
            throw new IOException("Invalid native peer status: " + nativeState);
        }
        ObjectNode status = JSON.createObjectNode();
        for (int index = 1; index < values.size(); index += 2) {
            String key = values.get(index);
            String value = values.get(index + 1);
            boolean failureField = StringUtils.equals("error", key) || StringUtils.equals("cleanupFailure", key);
            if (NUMBERS.contains(key)) {
                if (value.isEmpty()) {
                    status.putNull(key);
                } else {
                    try {
                        status.put(key, Long.parseLong(value));
                    } catch (NumberFormatException invalid) {
                        throw new IOException("Invalid native peer number: " + key, invalid);
                    }
                }
            } else if (BOOLEANS.contains(key)) {
                status.put(key, StringUtils.equals("1", value));
            } else if (StringUtils.equals("remainingPids", key)) {
                var pids = status.putArray(key);
                if (!value.isEmpty()) {
                    for (String pid : value.split(",")) {
                        try {
                            pids.add(Long.parseLong(pid));
                        } catch (NumberFormatException invalid) {
                            throw new IOException("Invalid native peer PID: " + pid, invalid);
                        }
                    }
                }
            } else if (StringUtils.equals("finishedAt", key) && value.isEmpty()) {
                status.putNull(key);
            } else if (failureField && value.isEmpty()) {
                // Successful state does not contain synthetic failure members.
            } else {
                status.put(key, value);
            }
        }
        writeAtomic(directory.resolve("status.json"), JSON.writeValueAsBytes(status));
        return status;
    }

    private static Path absolute(Path path) throws IOException {
        if (!path.isAbsolute()) {
            throw new IOException("launch.json must have an absolute path");
        }
        return path.normalize();
    }

    private static void appendList(List<String> target, List<?> values) throws IOException {
        target.add(Integer.toString(values.size()));
        for (Object value : values) {
            if (value == null) {
                throw new IOException("Peer native input must not contain null");
            }
            target.add(value.toString());
        }
    }

    private static byte[] encode(List<String> values) throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        for (String value : values) {
            if (value.indexOf('\0') >= 0) {
                throw new IOException("Peer native input must not contain NUL");
            }
            encoded.write(value.getBytes(StandardCharsets.UTF_8));
            encoded.write(0);
        }
        return encoded.toByteArray();
    }

    private static List<String> decode(byte[] data) throws IOException {
        List<String> values = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < data.length; index++) {
            if (data[index] == 0) {
                values.add(new String(data, start, index - start, StandardCharsets.UTF_8));
                start = index + 1;
            }
        }
        if (start != data.length) {
            throw new IOException("Truncated native peer state");
        }
        return values;
    }

    private static void writeAtomic(Path path, byte[] content) throws IOException {
        Path temporary = path.resolveSibling(path.getFileName() + "." + UUID.randomUUID() + ".tmp");
        Files.write(temporary, content);
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
