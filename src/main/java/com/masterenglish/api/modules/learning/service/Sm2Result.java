package com.masterenglish.api.modules.learning.service;

import java.math.BigDecimal;
import java.time.Instant;

public record Sm2Result(int repetitions, int intervalDays, BigDecimal easeFactor, Instant dueAt) {}
