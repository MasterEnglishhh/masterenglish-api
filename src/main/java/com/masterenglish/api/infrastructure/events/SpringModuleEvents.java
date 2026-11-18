package com.masterenglish.api.infrastructure.events;

import com.masterenglish.api.common.events.EventReply;
import com.masterenglish.api.common.events.ModuleEvents;
import java.util.function.Function;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
class SpringModuleEvents implements ModuleEvents {
    private final ApplicationEventPublisher publisher;

    SpringModuleEvents(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public <T> T request(Function<EventReply<T>, Object> factory) {
        var reply = new EventReply<T>();
        Object event = factory.apply(reply);
        publish(event);
        return reply.get(event.getClass().getSimpleName());
    }

    @Override
    public void publish(Object event) {
        publisher.publishEvent(event);
    }
}
