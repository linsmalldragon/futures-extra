/*-
 * -\-\-
 * futures-extra
 * --
 * Copyright (C) 2016 - 2023 Spotify AB
 * --
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * -/-/-
 */

package com.spotify.futures;

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;

import java.util.concurrent.*;

/**
 * A ConcurrencyLimiter can be used for efficiently queueing up asynchronous work to only run up to
 * a specific limit of work concurrently.
 *
 * <p>This is a threadsafe class.
 */
public final class ConcurrencyLimiter<T> implements FutureJobInvoker<T> {

    private final Executor executor;
    private final BlockingQueue<Job<T>> queue;
    private final Semaphore limit;
    private final int maxQueueSize;

    private final int maxConcurrency;

    private ConcurrencyLimiter(final Executor executor, int maxConcurrency, int maxQueueSize) {
        this.executor = executor;
        this.maxConcurrency = maxConcurrency;
        this.maxQueueSize = maxQueueSize;
        Preconditions.checkArgument(maxConcurrency > 0);
        if (maxQueueSize == 0) {
            this.queue = new LinkedBlockingQueue<>();
        } else {
            this.queue = new ArrayBlockingQueue<>(maxQueueSize);
        }
        this.limit = new Semaphore(maxConcurrency);
    }

    /**
     * Create a {@code ConcurrencyLimiter} instance.
     *
     * @param maxConcurrency maximum number of futures in progress,
     * @param maxQueueSize   maximum number of jobs in queue. This is a soft bound and may be
     *                       temporarily exceeded if add() is called concurrently.
     * @return a new concurrency limiter
     */
    public static <T> ConcurrencyLimiter<T> create(int maxConcurrency, int maxQueueSize) {
        return new ConcurrencyLimiter<>(MoreExecutors.directExecutor(), maxConcurrency, maxQueueSize);
    }

    public static <T> ConcurrencyLimiter<T> create(int maxConcurrency) {
        return new ConcurrencyLimiter<>(MoreExecutors.directExecutor(), maxConcurrency, 0);
    }

    /**
     * Create a {@code ConcurrencyLimiter} instance.
     *
     * @param executor       the executor to run callables on.
     * @param maxConcurrency maximum number of futures in progress,
     * @param maxQueueSize   maximum number of jobs in queue. This is a soft bound and may be
     *                       temporarily exceeded if add() is called concurrently.
     * @return a new concurrency limiter
     */
    public static <T> ConcurrencyLimiter<T> create(
            final Executor executor, int maxConcurrency, int maxQueueSize) {
        return new ConcurrencyLimiter<>(executor, maxConcurrency, maxQueueSize);
    }

    public static <T> ConcurrencyLimiter<T> create(
            final Executor executor, int maxConcurrency) {
        return new ConcurrencyLimiter<>(executor, maxConcurrency, 0);
    }

    /**
     * the callable function will run as soon as the currently active set of futures is less than the
     * maxConcurrency limit.
     *
     * @param callable - a function that creates a future.
     * @return a proxy future that completes with the future created by the input function. This
     * future will be immediately failed with {@link CapacityReachedException} if the soft queue
     * size limit is exceeded.
     * @throws NullPointerException if callable is null
     */
    @Override
    public ListenableFuture<T> add(Callable<? extends ListenableFuture<T>> callable) {
        Preconditions.checkNotNull(callable);
        final SettableFuture<T> response = SettableFuture.create();
        final Job<T> job = new Job<>(callable, response);
        if (!queue.offer(job)) {
            final String message = "Queue size has reached capacity: " + maxQueueSize;
            return Futures.immediateFailedFuture(new CapacityReachedException(message));
        }
        executor.execute(this::pump);
        return response;
    }

    /**
     * Return the number of callables that are queued up and haven't started yet.
     *
     * @return the number of callables that are queued up and haven't started yet.
     */
    public int numQueued() {
        return queue.size();
    }

    /**
     * Return the number of currently active futures that have not yet completed.
     *
     * @return the number of currently active futures that have not yet completed.
     */
    public int numActive() {
        return maxConcurrency - limit.availablePermits();
    }

    /**
     * Return the number of additional callables that can be queued before failing.
     *
     * @return the number of additional callables that can be queued before failing.
     */
    public int remainingQueueCapacity() {
        return queue.remainingCapacity();
    }

    /**
     * Return the number of additional callables that can be run without queueing.
     *
     * @return the number of additional callables that can be run without queueing.
     */
    public int remainingActiveCapacity() {
        return limit.availablePermits();
    }

    /**
     * Return a {@code Job} with acquired permit, {@code null} otherwise.
     *
     * <p>Does one of two things: 1) return a job and acquire a permit from the semaphore 2) return
     * null and does not acquire a permit from the semaphore
     */
    private Job<T> grabJob() {
        if (!limit.tryAcquire()) {
            return null;
        }

        final Job<T> job = queue.poll();
        if (job != null) {
            return job;
        }

        limit.release();
        return null;
    }

    private void pump() {
        Job<T> job;
        while ((job = grabJob()) != null) {
            final SettableFuture<T> response = job.response;

            if (response.isCancelled()) {
                limit.release();
            } else {
                invoke(response, job.callable);
            }
        }
    }

    private void invoke(
            final SettableFuture<T> response, Callable<? extends ListenableFuture<T>> callable) {
        final ListenableFuture<T> future;
        try {
            future = callable.call();
            if (future == null) {
                limit.release();
                response.setException(new NullPointerException());
                return;
            }
        } catch (Throwable e) {
            limit.release();
            response.setException(e);
            return;
        }

        Futures.addCallback(
                future,
                new FutureCallback<T>() {
                    @Override
                    public void onSuccess(T result) {
                        limit.release();
                        response.set(result);
                        pump();
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        limit.release();
                        response.setException(t);
                        pump();
                    }
                },
                executor);
    }

    private static class Job<T> {
        private final Callable<? extends ListenableFuture<T>> callable;
        private final SettableFuture<T> response;

        Job(Callable<? extends ListenableFuture<T>> callable, SettableFuture<T> response) {
            this.callable = callable;
            this.response = response;
        }
    }

    public static class CapacityReachedException extends RuntimeException {

        public CapacityReachedException(String errorMessage) {
            super(errorMessage);
        }
    }
}
