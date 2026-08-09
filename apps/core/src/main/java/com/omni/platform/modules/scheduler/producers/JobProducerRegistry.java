package com.omni.platform.modules.scheduler.producers;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;

@Component
public class JobProducerRegistry {

    private final Map<JobType, JobProducer> producers;

    public JobProducerRegistry(List<JobProducer> registeredProducers) {
        EnumMap<JobType, JobProducer> byType = new EnumMap<>(JobType.class);
        for (JobProducer producer : registeredProducers) {
            JobType jobType = producer.getJobType();
            JobProducer previous = byType.putIfAbsent(jobType, producer);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate JobProducer registration for jobType " + jobType
                                + ": " + previous.getClass().getSimpleName()
                                + " and " + producer.getClass().getSimpleName());
            }
        }
        this.producers = Map.copyOf(byType);
    }

    public JobProducer getProducer(JobType jobType) {
        JobProducer producer = producers.get(jobType);
        if (producer == null) {
            throw new IllegalStateException("No JobProducer registered for jobType " + jobType);
        }
        return producer;
    }
}
