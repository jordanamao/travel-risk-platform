package com.travelrisk.platform.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on {@code @Scheduled} jobs, such as the alert outbox relay's safety-net poll. */
@Configuration
@EnableScheduling
public class SchedulingConfig {}
