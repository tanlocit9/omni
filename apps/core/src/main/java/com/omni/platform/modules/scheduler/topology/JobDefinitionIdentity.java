package com.omni.platform.modules.scheduler.topology;

import java.util.Objects;

import com.omni.platform.modules.scheduler.constants.JobDefinitionConfig.JobDefinitionSeed;
import com.omni.platform.modules.scheduler.entities.JobDefinition.DataSource;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;

/** Stable seed/database identity used to map definitions to logical topology nodes. */
public record JobDefinitionIdentity(DataSource source, JobType jobType, String cronExpression)
        implements Comparable<JobDefinitionIdentity> {

    public JobDefinitionIdentity {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(jobType, "jobType");
        Objects.requireNonNull(cronExpression, "cronExpression");
        cronExpression = cronExpression.strip();
        if (cronExpression.isEmpty()) {
            throw new IllegalArgumentException("cronExpression must not be blank");
        }
    }

    public static JobDefinitionIdentity from(JobDefinitionSeed seed) {
        Objects.requireNonNull(seed, "seed");
        return new JobDefinitionIdentity(seed.source(), seed.jobType(), seed.cronExpr());
    }

    @Override
    public int compareTo(JobDefinitionIdentity other) {
        int sourceOrder = source.compareTo(other.source);
        if (sourceOrder != 0) {
            return sourceOrder;
        }
        int typeOrder = jobType.compareTo(other.jobType);
        return typeOrder != 0 ? typeOrder : cronExpression.compareTo(other.cronExpression);
    }
}
