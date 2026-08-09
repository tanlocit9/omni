package com.omni.platform.modules.scheduler.notifications;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;

@Component
public class JobNotificationPolicyRegistry {

    private final Map<JobType, JobNotificationPolicy> policies;
    private final DefaultJobNotificationPolicy defaultPolicy;

    public JobNotificationPolicyRegistry(
            List<JobNotificationPolicy> registeredPolicies,
            DefaultJobNotificationPolicy defaultPolicy) {
        this.defaultPolicy = defaultPolicy;
        EnumMap<JobType, JobNotificationPolicy> byType = new EnumMap<>(JobType.class);
        for (JobNotificationPolicy policy : registeredPolicies) {
            JobType jobType = policy.getJobType();
            if (jobType == null) {
                continue;
            }
            JobNotificationPolicy previous = byType.putIfAbsent(jobType, policy);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate JobNotificationPolicy registration for jobType " + jobType
                                + ": " + previous.getClass().getSimpleName()
                                + " and " + policy.getClass().getSimpleName());
            }
        }
        this.policies = Map.copyOf(byType);
    }

    public Optional<Object> buildNotification(JobNotificationContext context) {
        JobDefinition job = context.execution().getJob();
        JobNotificationPolicy policy = job == null ? defaultPolicy : policies.getOrDefault(job.getJobType(), defaultPolicy);
        return policy.buildNotification(context);
    }
}
