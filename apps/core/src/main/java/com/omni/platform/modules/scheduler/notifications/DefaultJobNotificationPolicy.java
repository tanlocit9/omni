package com.omni.platform.modules.scheduler.notifications;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.omni.platform.modules.notifications.templates.JobNotificationTemplate;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory.JobStatus;

@Component
public class DefaultJobNotificationPolicy implements JobNotificationPolicy {

    private final JobNotificationTemplate jobNotificationTemplate;

    public DefaultJobNotificationPolicy(JobNotificationTemplate jobNotificationTemplate) {
        this.jobNotificationTemplate = jobNotificationTemplate;
    }

    @Override
    public JobType getJobType() {
        return null;
    }

    @Override
    public Optional<Object> buildNotification(JobNotificationContext context) {
        if (context.hasChildren()) {
            return Optional.of(context.execution().getStatus() == JobStatus.SUCCESS
                    ? jobNotificationTemplate.parentSucceeded(context.execution(), context.total(), context.success(), context.failed())
                    : jobNotificationTemplate.parentFailed(context.execution(), context.total(), context.success(), context.failed()));
        }

        return Optional.of(context.execution().getStatus() == JobStatus.SUCCESS
                ? jobNotificationTemplate.standaloneSucceeded(context.execution())
                : jobNotificationTemplate.standaloneFailed(context.execution()));
    }
}
