package com.github.highcumontoa.iotdevicegatewayjava.capacity;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

/**
 * 容量与降级可观测计数器。所有计数仅增不减，供测试与运维断言背压/丢弃是否发生。
 */
@Component
public class CapacityMetrics {

    private final AtomicLong capacityRejections = new AtomicLong();
    private final AtomicLong commandQueueRejections = new AtomicLong();
    private final AtomicLong eventBacklogDiscards = new AtomicLong();
    private final AtomicLong eventBacklogRejections = new AtomicLong();
    private final AtomicLong eventRuleErrors = new AtomicLong();
    private final AtomicLong eventsProcessed = new AtomicLong();
    private final AtomicLong commandsExpired = new AtomicLong();

    public long capacityRejections() { return capacityRejections.get(); }
    public long commandQueueRejections() { return commandQueueRejections.get(); }
    public long eventBacklogDiscards() { return eventBacklogDiscards.get(); }
    public long eventBacklogRejections() { return eventBacklogRejections.get(); }
    public long eventRuleErrors() { return eventRuleErrors.get(); }
    public long eventsProcessed() { return eventsProcessed.get(); }
    public long commandsExpired() { return commandsExpired.get(); }

    public void incrementCapacityRejections() { capacityRejections.incrementAndGet(); }
    public void incrementCommandQueueRejections() { commandQueueRejections.incrementAndGet(); }
    public void incrementEventBacklogDiscards() { eventBacklogDiscards.incrementAndGet(); }
    public void incrementEventBacklogRejections() { eventBacklogRejections.incrementAndGet(); }
    public void incrementEventRuleErrors() { eventRuleErrors.incrementAndGet(); }
    public void incrementEventsProcessed() { eventsProcessed.incrementAndGet(); }
    public void incrementCommandsExpired() { commandsExpired.incrementAndGet(); }
}
