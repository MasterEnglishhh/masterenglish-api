package com.masterenglish.api.modules.notifications.service;

/** Sends pending reminder emails from the delivery outbox (M10.08). */
public interface EmailDeliveryWorker {

    /**
     * @return the number of deliveries processed (sent, retried or failed) in this run
     */
    int deliverDueEmails();
}
