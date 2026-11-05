/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.dao;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import com.fasterxml.jackson.core.type.TypeReference;
import net.hasor.core.Init;
import net.hasor.core.Inject;
import net.hasor.meteor.console.global.MeteorConfig;
import net.hasor.meteor.console.model.fo.ClusterProfile;
import net.hasor.meteor.console.utils.JsonUtils;

/** 集群连接定义的本地文件 DAO；不建立注册中心连接，不处理 HTTP。 */
public final class ClusterDao {
    @Inject
    private MeteorConfig                config;
    private Path                        file;
    private Map<String, ClusterProfile> profiles = new LinkedHashMap<>();

    @Init
    public void initialize() throws IOException {
        Path directory = Path.of(this.config.getDataDirectory()).toAbsolutePath().normalize();
        Files.createDirectories(directory);
        this.file = directory.resolve("clusters.json");
        if (Files.exists(this.file)) {
            List<ClusterProfile> stored = JsonUtils.readValue(this.file.toFile(), new TypeReference<>() {
            });

            for (ClusterProfile profile : stored) {
                profile.validate();
                this.profiles.put(profile.getId(), profile);
            }
        }
    }

    public synchronized List<ClusterProfile> list() {
        return new ArrayList<>(this.profiles.values());
    }

    public synchronized ClusterProfile get(String id) {
        ClusterProfile profile = this.profiles.get(id);
        if (profile == null) {
            throw new NoSuchElementException("集群连接不存在或已被移除");
        }
        return profile;
    }

    public synchronized void save(ClusterProfile profile) throws IOException {
        Map<String, ClusterProfile> next = new LinkedHashMap<>(this.profiles);
        next.put(profile.getId(), profile);
        this.persist(next);
        this.profiles = next;
    }

    public synchronized void remove(String id) throws IOException {
        Map<String, ClusterProfile> next = new LinkedHashMap<>(this.profiles);
        if (next.remove(id) == null) {
            throw new NoSuchElementException("集群连接不存在");
        }

        this.persist(next);
        this.profiles = next;
    }

    private void persist(Map<String, ClusterProfile> next) throws IOException {
        Path temporary = Files.createTempFile(this.file.getParent(), "clusters-", ".tmp");
        try {
            if (Files.getFileStore(temporary).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            }
            JsonUtils.writePrettyValue(temporary.toFile(), next.values());
            Files.move(temporary, this.file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
