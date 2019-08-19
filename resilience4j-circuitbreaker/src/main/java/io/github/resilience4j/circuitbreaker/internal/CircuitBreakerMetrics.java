/*
 *
 *  Copyright 2016 Robert Winkler and Bohdan Storozhuk
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */
package io.github.resilience4j.circuitbreaker.internal;


import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.metrics.FixedSizeSlidingWindowMetrics;
import io.github.resilience4j.core.metrics.Metrics;
import io.github.resilience4j.core.metrics.SlidingTimeWindowMetrics;
import io.github.resilience4j.core.metrics.Snapshot;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import static io.github.resilience4j.core.metrics.Metrics.Outcome;

class CircuitBreakerMetrics implements CircuitBreaker.Metrics {

    private final Metrics metrics;
    private final float failureRateThreshold;
    private final float slowCallRateThreshold;
    private final long slowCallDurationThresholdInNanos;
    private int minimumNumberOfCalls;
    private final LongAdder numberOfNotPermittedCalls;

    CircuitBreakerMetrics(int slidingWindowSize, CircuitBreakerConfig circuitBreakerConfig) {
        if(circuitBreakerConfig.getSlidingWindowType() == CircuitBreakerConfig.SlidingWindow.COUNT_BASED){
            this.metrics = new FixedSizeSlidingWindowMetrics(slidingWindowSize);
            this.minimumNumberOfCalls = Math.min(circuitBreakerConfig.getMinimumNumberOfCalls(), slidingWindowSize);
        }else{
            this.metrics = new SlidingTimeWindowMetrics(slidingWindowSize);
            this.minimumNumberOfCalls = circuitBreakerConfig.getMinimumNumberOfCalls();
        }
        this.failureRateThreshold = circuitBreakerConfig.getFailureRateThreshold();
        this.slowCallRateThreshold = circuitBreakerConfig.getSlowCallRateThreshold();
        this.slowCallDurationThresholdInNanos = circuitBreakerConfig.getSlowCallDurationThreshold().toNanos();
        this.numberOfNotPermittedCalls = new LongAdder();
    }

    /**
     * Records a call which was not permitted, because the CircuitBreaker state is OPEN.
     */
    void onCallNotPermitted() {
        numberOfNotPermittedCalls.increment();
    }

    /**
     * Records a successful call and checks if the thresholds are exceeded.
     *
     * @return the result of the check
     */
    public Result onSuccess(long duration, TimeUnit durationUnit) {
        Snapshot snapshot;
        if(durationUnit.toNanos(duration) > slowCallDurationThresholdInNanos){
            snapshot = metrics.record(duration, durationUnit, Outcome.SLOW_SUCCESS);
        }else{
            snapshot = metrics.record(duration, durationUnit, Outcome.SUCCESS);
        }
        return checkIfThresholdsExceeded(snapshot);
    }

    /**
     * Records a failed call and checks if the thresholds are exceeded.
     *
     * @return the result of the check
     */
    public Result onError(long duration, TimeUnit durationUnit) {
        Snapshot snapshot;
        if(durationUnit.toNanos(duration) > slowCallDurationThresholdInNanos){
            snapshot = metrics.record(duration, durationUnit, Outcome.SLOW_ERROR);
        }else{
            snapshot = metrics.record(duration, durationUnit, Outcome.ERROR);
        }
        return checkIfThresholdsExceeded(snapshot);
    }

    /**
     * Checks if the failure rate is above the threshold or
     * if the slow calls percentage is above the threshold.
     *
     * @param snapshot a metrics snapshot
     * @return false, if the thresholds haven't been exceeded.
     */
    private Result checkIfThresholdsExceeded(Snapshot snapshot) {
        float failureRateInPercentage = getFailureRate(snapshot);
        if(failureRateInPercentage == -1 ){
            return Result.BELOW_MINIMUM_CALLS_THRESHOLD;
        }
        if(failureRateInPercentage >= failureRateThreshold){
            return Result.ABOVE_THRESHOLDS;
        }
        float slowCallsInPercentage = getSlowCallRate(snapshot);
        if(slowCallsInPercentage >= slowCallRateThreshold){
            return Result.ABOVE_THRESHOLDS;
        }
        return Result.BELOW_THRESHOLDS;
    }

    private float getSlowCallRate(Snapshot snapshot){
        int bufferedCalls = snapshot.getTotalNumberOfCalls();
        if (bufferedCalls == 0 || bufferedCalls < minimumNumberOfCalls) {
            return -1.0f;
        }
        return snapshot.getSlowCallRate();
    }

    private float getFailureRate(Snapshot snapshot) {
        int bufferedCalls = snapshot.getTotalNumberOfCalls();
        if (bufferedCalls == 0 || bufferedCalls < minimumNumberOfCalls) {
            return -1.0f;
        }
        return snapshot.getFailureRate();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public float getFailureRate() {
        return getFailureRate(metrics.getSnapshot());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public float getSlowCallRate() {
        return getSlowCallRate(metrics.getSnapshot());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getNumberOfSuccessfulCalls() {
        return this.metrics.getSnapshot().getNumberOfSuccessfulCalls();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getNumberOfBufferedCalls() {
        return this.metrics.getSnapshot().getTotalNumberOfCalls();
    }

    @Override
    public int getNumberOfFailedCalls() {
        return this.metrics.getSnapshot().getNumberOfFailedCalls();
    }

    @Override
    public int getNumberOfSlowCalls() {
        return this.metrics.getSnapshot().getNumberOfSlowCalls();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getNumberOfNotPermittedCalls() {
        return this.numberOfNotPermittedCalls.sum();
    }

    enum Result {
        BELOW_THRESHOLDS,
        ABOVE_THRESHOLDS,
        BELOW_MINIMUM_CALLS_THRESHOLD
    }
}