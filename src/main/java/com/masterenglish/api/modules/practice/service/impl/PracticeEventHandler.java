package com.masterenglish.api.modules.practice.service.impl;

import com.masterenglish.api.common.events.PracticeEvents.PracticeSummaryRequested;
import com.masterenglish.api.modules.practice.service.PracticeService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
class PracticeEventHandler {

    private final PracticeService practice;

    PracticeEventHandler(PracticeService practice) {
        this.practice = practice;
    }

    @EventListener
    void on(PracticeSummaryRequested event) {
        event.reply()
                .complete(
                        practice.getPracticeSummary(
                                event.userId(), event.weaknessSince(), event.timezone()));
    }
}
