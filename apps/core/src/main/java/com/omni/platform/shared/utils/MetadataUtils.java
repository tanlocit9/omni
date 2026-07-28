package com.omni.platform.shared.utils;

import java.util.Map;

public final class MetadataUtils {

    private MetadataUtils() {
    }

    public static void putIfPresent(Map<String, Object> meta, String key, Object value) {
        if (value != null) {
            meta.put(key, String.valueOf(value));
        }
    }
}
