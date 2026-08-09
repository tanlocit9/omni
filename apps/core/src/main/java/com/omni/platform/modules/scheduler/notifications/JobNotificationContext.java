package com.omni.platform.modules.scheduler.notifications;

import java.util.List;

import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;

public record JobNotificationContext(
        JobExecutionHistory execution,
        List<JobExecutionHistory> children,
        long total,
        long success,
        long failed) {

    public boolean hasChildren() {
        return children != null && !children.isEmpty();
    }
}
