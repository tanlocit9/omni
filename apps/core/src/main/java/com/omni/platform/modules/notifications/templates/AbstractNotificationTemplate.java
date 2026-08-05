package com.omni.platform.modules.notifications.templates;

import java.util.LinkedHashMap;
import java.util.Map;

public abstract class AbstractNotificationTemplate<E> implements NotificationTemplate<E> {

    protected Map<String, Object> metadata(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(source);
    }

    protected String defaultText(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }
}
