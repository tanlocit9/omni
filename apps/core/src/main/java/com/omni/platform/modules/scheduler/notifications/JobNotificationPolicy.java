package com.omni.platform.modules.scheduler.notifications;

import java.util.Optional;

import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;

public interface JobNotificationPolicy {

    JobType getJobType();

    Optional<Object> buildNotification(JobNotificationContext context);
}
