package com.masterenglish.api.common.events;

/** One reply from the synchronous listener responsible for a request. */
public final class EventReply<T> {
    private boolean completed;
    private T value;

    public EventReply() {}

    public void complete(T result) {
        if (completed) throw new IllegalStateException("Module request already answered.");
        value = result;
        completed = true;
    }

    public T get(String eventName) {
        if (!completed) {
            throw new IllegalStateException("No synchronous reply for " + eventName);
        }
        return value;
    }
}
