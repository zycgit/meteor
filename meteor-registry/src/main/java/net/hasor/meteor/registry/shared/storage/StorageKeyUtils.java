/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.shared.storage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import net.hasor.meteor.registry.shared.storage.domain.EntityRef;
import net.hasor.meteor.registry.shared.storage.domain.RelationType;

/** 身份编码与关系 ID 的确定性计算。 */
public final class StorageKeyUtils {

    private StorageKeyUtils() {
    }

    public static String entity(EntityRef ref) {
        if (ref == null) {
            throw new IllegalArgumentException("Entity reference is required");
        } else {
            String parent = ref.parent() == null ? "" : entity(ref.parent());
            String id = hash(parent, ref.section().name(), ref.id());
            return "e." + ref.type().name() + "." + id;
        }
    }

    public static String relation(RelationType type, EntityRef source, EntityRef target) {
        if (type == null || source == null || target == null) {
            throw new IllegalArgumentException("Relation type and endpoints are required");
        } else {
            String id = hash(type.name(), entity(source), entity(target));
            return "r." + type.name() + "." + id;
        }
    }

    public static String hash(String... fields) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(bytes);
            for (String field : fields) {
                byte[] value = field.getBytes(StandardCharsets.UTF_8);
                data.writeInt(value.length);
                data.write(value);
            }

            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
