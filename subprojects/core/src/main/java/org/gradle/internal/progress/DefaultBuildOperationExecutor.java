/*
 * Copyright 2017 the original author or authors.
 *
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
 */

package org.gradle.internal.progress;

import com.google.common.collect.Lists;
import org.apache.commons.lang.StringUtils;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Nullable;
import org.gradle.api.Transformer;
import org.gradle.internal.Cast;
import org.gradle.internal.SystemProperties;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.concurrent.ExecutorFactory;
import org.gradle.internal.concurrent.GradleThread;
import org.gradle.internal.concurrent.StoppableExecutor;
import org.gradle.internal.exceptions.DefaultMultiCauseException;
import org.gradle.internal.logging.events.OperationIdentifier;
import org.gradle.internal.logging.progress.ProgressLogger;
import org.gradle.internal.logging.progress.ProgressLoggerFactory;
import org.gradle.internal.operations.BuildOperation;
import org.gradle.internal.operations.BuildOperationContext;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.operations.BuildOperationQueue;
import org.gradle.internal.operations.BuildOperationQueueFactory;
import org.gradle.internal.operations.BuildOperationQueueFailure;
import org.gradle.internal.operations.BuildOperationState;
import org.gradle.internal.operations.BuildOperationWorker;
import org.gradle.internal.operations.CallableBuildOperation;
import org.gradle.internal.operations.MultipleBuildOperationFailures;
import org.gradle.internal.operations.RunnableBuildOperation;
import org.gradle.internal.time.TimeProvider;
import org.gradle.util.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

//TODO move to base-services once the ProgressLogger dependency is removed
public class DefaultBuildOperationExecutor implements BuildOperationExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultBuildOperationExecutor.class);
    private static final String LINE_SEPARATOR = SystemProperties.getInstance().getLineSeparator();

    private final BuildOperationListener listener;
    private final TimeProvider timeProvider;
    private final ProgressLoggerFactory progressLoggerFactory;
    private final BuildOperationQueueFactory buildOperationQueueFactory;
    private final StoppableExecutor fixedSizePool;

    private final AtomicLong nextId = new AtomicLong();
    private final ThreadLocal<BuildOperationState> currentOperation = new ThreadLocal<BuildOperationState>();

    public DefaultBuildOperationExecutor(BuildOperationListener listener, TimeProvider timeProvider, ProgressLoggerFactory progressLoggerFactory,
                                         BuildOperationQueueFactory buildOperationQueueFactory, ExecutorFactory executorFactory, int maxWorkerCount) {
        this.listener = listener;
        this.timeProvider = timeProvider;
        this.progressLoggerFactory = progressLoggerFactory;
        this.buildOperationQueueFactory = buildOperationQueueFactory;
        this.fixedSizePool = executorFactory.create("build operations", maxWorkerCount);
    }


    @Override
    public BuildOperationState getCurrentOperation() {
        BuildOperationState current = currentOperation.get();
        if (current == null) {
            throw new IllegalStateException("No operation is currently running.");
        }
        return current;
    }

    @Override
    public void setRootOperationOfCurrentThread(BuildOperationState rootOperation) {
        currentOperation.set(rootOperation);
    }

    @Override
    public void run(RunnableBuildOperation buildOperation) {
        maybeStartUnmanagedThreadOperation();
        execute(buildOperation, currentOperation.get());
        maybeStopUnmanagedThreadOperation();
    }

    @Override
    public <T> T call(CallableBuildOperation<T> buildOperation) {
        maybeStartUnmanagedThreadOperation();
        T result = execute(buildOperation, currentOperation.get());
        maybeStopUnmanagedThreadOperation();
        return result;
    }

    @Override
    public <O extends RunnableBuildOperation> void runAll(Action<BuildOperationQueue<O>> schedulingAction) {
        maybeStartUnmanagedThreadOperation();
        executeInParallel(new ParentBuildOperationAwareWorker<O>(currentOperation.get()), schedulingAction);
        maybeStopUnmanagedThreadOperation();
    }

    @Override
    public <O extends BuildOperation> void runAll(BuildOperationWorker<O> worker, Action<BuildOperationQueue<O>> schedulingAction) {
        maybeStartUnmanagedThreadOperation();
        executeInParallel(worker, schedulingAction);
        maybeStopUnmanagedThreadOperation();
    }

    private <O extends BuildOperation> void executeInParallel(BuildOperationWorker<O> worker, Action<BuildOperationQueue<O>> queueAction) {
        BuildOperationQueue<O> queue = buildOperationQueueFactory.create(fixedSizePool, worker);

        List<GradleException> failures = Lists.newArrayList();
        try {
            queueAction.execute(queue);
        } catch (Exception e) {
            failures.add(new BuildOperationQueueFailure("There was a failure while populating the build operation queue: " + e.getMessage(), e));
            queue.cancel();
        }

        try {
            queue.waitForCompletion();
        } catch (MultipleBuildOperationFailures e) {
            failures.add(e);
        }

        if (failures.size() == 1) {
            throw failures.get(0);
        } else if (failures.size() > 1) {
            throw new DefaultMultiCauseException(formatMultipleFailureMessage(failures), failures);
        }
    }

    private <T> T execute(BuildOperation buildOperation, BuildOperationState parent) {
        BuildOperationState operationBefore = currentOperation.get();
        BuildOperationDescriptor.Builder descriptorBuilder = createDescriptorBuilder(buildOperation, parent);

        BuildOperationState currentOperation = new BuildOperationState(descriptorBuilder.build(), parent, timeProvider.getCurrentTime());
        BuildOperationDescriptor description = currentOperation.getDescription();

        if (parent != null && !parent.isRunning()) {
            throw new IllegalStateException(String.format("Cannot start operation (%s) as parent operation (%s) has already completed.",
                description.getDisplayName(), parent.getDescription().getDisplayName()));
        }

        currentOperation.setRunning(true);
        this.currentOperation.set(currentOperation);

        try {
            listener.started((BuildOperationInternal) description, new OperationStartEvent(currentOperation.getStartTime()));

            T result = null;
            Throwable failure = null;
            DefaultBuildOperationContext context = new DefaultBuildOperationContext();
            try {
                ProgressLogger progressLogger = maybeStartProgressLogging(currentOperation);

                LOGGER.debug("Build operation '{}' started", description.getDisplayName());
                try {
                    if (buildOperation instanceof RunnableBuildOperation) {
                        ((RunnableBuildOperation) buildOperation).run(context);
                    }
                    if (buildOperation instanceof CallableBuildOperation) {
                        result = Cast.uncheckedCast(((CallableBuildOperation) buildOperation).call(context));
                    }
                } finally {
                    if (progressLogger != null) {
                        progressLogger.completed();
                    }
                }
                if (parent != null && !parent.isRunning()) {
                    throw new IllegalStateException(String.format("Parent operation (%s) completed before this operation (%s).",
                        parent.getDescription().getDisplayName(), description.getDisplayName()));
                }
            } catch (Throwable t) {
                context.failed(t);
                failure = t;
            }

            long endTime = timeProvider.getCurrentTime();
            listener.finished((BuildOperationInternal) description, new OperationResult(currentOperation.getStartTime(), endTime, context.failure, context.result));

            if (failure != null) {
                throw UncheckedException.throwAsUncheckedException(failure, true);
            }

            LOGGER.debug("Build operation '{}' completed", description.getDisplayName());
            return result;
        } finally {
            this.currentOperation.set(operationBefore);
            currentOperation.setRunning(false);
        }
    }

    private BuildOperationDescriptor.Builder createDescriptorBuilder(BuildOperation buildOperation, BuildOperationState parent) {
        OperationIdentifier id = new OperationIdentifier(nextId.getAndIncrement());
        BuildOperationDescriptor.Builder descriptorBuilder = buildOperation.description();
        descriptorBuilder.id(id);
        maybeFillOperationParentId(descriptorBuilder, parent);
        return descriptorBuilder;
    }

    private void maybeFillOperationParentId(BuildOperationDescriptor.Builder descriptorBuilder, BuildOperationState parent) {
        if (parent != null) {
            descriptorBuilder.parentId(parent.getDescription().getId());
        }
    }

    @Nullable
    private ProgressLogger maybeStartProgressLogging(BuildOperationState currentOperation) {
        if (providesProgressLogging(currentOperation)) {
            ProgressLogger progressLogger = progressLoggerFactory.newOperation(DefaultBuildOperationExecutor.class);
            progressLogger.setDescription(currentOperation.getDescription().getDisplayName());
            progressLogger.setShortDescription(currentOperation.getDescription().getProgressDisplayName());
            progressLogger.started();
            return progressLogger;
        } else {
            return null;
        }
    }

    private boolean providesProgressLogging(BuildOperationState currentOperation) {
        return currentOperation.getDescription().getProgressDisplayName() != null;
    }

    private void maybeStartUnmanagedThreadOperation() {
        BuildOperationState current = currentOperation.get();
        if (current == null && !GradleThread.isManaged()) {
            UnmanagedThreadOperation operation = UnmanagedThreadOperation.create(timeProvider);
            operation.setRunning(true);
            currentOperation.set(operation);
            listener.started((BuildOperationInternal) operation.getDescription(), new OperationStartEvent(operation.getStartTime()));
        }
    }

    private void maybeStopUnmanagedThreadOperation() {
        BuildOperationState current = currentOperation.get();
        if (current instanceof UnmanagedThreadOperation) {
            try {
                listener.finished((BuildOperationInternal) current.getDescription(), new OperationResult(current.getStartTime(), timeProvider.getCurrentTime(), null, null));
            } finally {
                currentOperation.set(null);
                current.setRunning(false);
            }
        }
    }

    /**
     * Artificially create a running root operation.
     * Main use case is ProjectBuilder, useful for some of our test fixtures too.
     */
    protected void createRunningRootOperation(String displayName) {
        assert currentOperation.get() == null;
        BuildOperationState operation = new BuildOperationState(BuildOperationDescriptor.displayName(displayName).id(new OperationIdentifier(0)).build(), null, timeProvider.getCurrentTime());
        operation.setRunning(true);
        currentOperation.set(operation);
    }

    private static String formatMultipleFailureMessage(List<GradleException> failures) {
        return StringUtils.join(CollectionUtils.collect(failures, new Transformer<String, GradleException>() {
            @Override
            public String transform(GradleException e) {
                return e.getMessage();
            }
        }), LINE_SEPARATOR + "AND" + LINE_SEPARATOR);
    }

    public void stop() {
        fixedSizePool.stop();
    }

    private static class DefaultBuildOperationContext implements BuildOperationContext {
        Throwable failure;
        Object result;

        @Override
        public void failed(Throwable t) {
            failure = t;
        }

        @Override
        public void setResult(Object result) {
            this.result = result;
        }
    }

    private class ParentBuildOperationAwareWorker<O extends RunnableBuildOperation> implements BuildOperationWorker<O> {

        private BuildOperationState parent;

        private ParentBuildOperationAwareWorker(BuildOperationState parent) {
            this.parent = parent;
        }

        @Override
        public String getDisplayName() {
            return "runnable worker";
        }

        @Override
        public void execute(O buildOperation) {
            DefaultBuildOperationExecutor.this.execute(buildOperation, parent);
        }
    }

    private static class UnmanagedThreadOperation extends BuildOperationState {

        private static final AtomicLong UNMANAGED_THREAD_OPERATION_COUNTER = new AtomicLong(-1);

        private static UnmanagedThreadOperation create(TimeProvider timeProvider) {
            // TODO:pm Move this to WARN level once we fixed maven-publish, see gradle/gradle#1662
            LOGGER.debug("WARNING No operation is currently running in unmanaged thread: {}", Thread.currentThread().getName());
            OperationIdentifier id = new OperationIdentifier(UNMANAGED_THREAD_OPERATION_COUNTER.getAndDecrement());
            String displayName = "Unmanaged thread operation #" + id + " (" + Thread.currentThread().getName() + ')';
            return new UnmanagedThreadOperation(BuildOperationDescriptor.displayName(displayName).id(id).build(), null, timeProvider.getCurrentTime());
        }

        private UnmanagedThreadOperation(BuildOperationDescriptor descriptor, BuildOperationState parent, long startTime) {
            super(descriptor, parent, startTime);
        }
    }
}
