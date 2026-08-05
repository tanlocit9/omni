package com.omni.platform.modules.notifications.templates;

import com.omni.platform.modules.notifications.dtos.NotificationRequest;

public interface NotificationTemplate<E> {

    NotificationRequest render(E event);
}
