package com.masterenglish.api.common.events;

import java.util.function.Function;

public interface ModuleEvents {
    <T> T request(Function<EventReply<T>, Object> factory);

    void publish(Object event);
}
