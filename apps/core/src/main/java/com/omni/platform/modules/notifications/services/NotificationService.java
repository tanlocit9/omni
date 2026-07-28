package com.omni.platform.modules.notifications.services;

import com.omni.platform.modules.notifications.dtos.NotificationRequest;

public interface NotificationService {

    void send(NotificationRequest request);
}
