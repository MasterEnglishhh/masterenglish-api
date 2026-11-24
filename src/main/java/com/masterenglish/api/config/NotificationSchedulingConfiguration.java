package com.masterenglish.api.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Reminder jobs run only when notification.scheduler.enabled is true (off for local and tests). */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("notification.scheduler.enabled")
@EnableScheduling
public class NotificationSchedulingConfiguration {}
