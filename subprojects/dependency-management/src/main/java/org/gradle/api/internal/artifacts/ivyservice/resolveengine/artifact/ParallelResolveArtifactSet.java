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

package org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact;

import org.gradle.api.Action;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.internal.operations.BuildOperationProcessor;
import org.gradle.internal.operations.BuildOperationQueue;
import org.gradle.internal.operations.RunnableBuildOperation;

import java.io.File;

/**
 * A wrapper that prepares artifacts in parallel when visiting the delegate.
 * This is done by collecting all artifacts to prepare and/or visit in a first step.
 * The collected artifacts are prepared in parallel and subsequently visited in sequence.
 */
public abstract class ParallelResolveArtifactSet {
    private static final EmptySet EMPTY = new EmptySet();

    public abstract void visit(ArtifactVisitor visitor);

    public static ParallelResolveArtifactSet wrap(ResolvedArtifactSet artifacts, BuildOperationProcessor buildOperationProcessor) {
        if (artifacts == ResolvedArtifactSet.EMPTY) {
            return EMPTY;
        }
        return new VisitingSet(artifacts, buildOperationProcessor);
    }

    private static class EmptySet extends ParallelResolveArtifactSet {
        @Override
        public void visit(ArtifactVisitor visitor) {
        }
    }

    private static class VisitingSet extends ParallelResolveArtifactSet {
        private final ResolvedArtifactSet artifacts;
        private final BuildOperationProcessor buildOperationProcessor;

        VisitingSet(ResolvedArtifactSet artifacts, BuildOperationProcessor buildOperationProcessor) {
            this.artifacts = artifacts;
            this.buildOperationProcessor = buildOperationProcessor;
        }

        public void visit(final ArtifactVisitor visitor) {
            // Start preparing the result
            StartVisitAction visitAction = new StartVisitAction(visitor);
            buildOperationProcessor.run(visitAction);

            // Now visit the result in order
            visitAction.result.visit(visitor);
        }

        private static class AsyncArtifactListenerAdapter implements ResolvedArtifactSet.AsyncArtifactListener {
            private final ArtifactVisitor visitor;

            AsyncArtifactListenerAdapter(ArtifactVisitor visitor) {
                this.visitor = visitor;
            }

            @Override
            public void artifactAvailable(ResolvedArtifact artifact) {
                // Don't care, collect the artifacts later (in the correct order)
            }

            @Override
            public boolean requireArtifactFiles() {
                return visitor.requireArtifactFiles();
            }

            @Override
            public boolean includeFileDependencies() {
                return visitor.includeFiles();
            }

            @Override
            public void fileAvailable(File file) {
                // Don't care, collect the files later (in the correct order)
            }
        }

        private class StartVisitAction implements Action<BuildOperationQueue<RunnableBuildOperation>> {
            private final ArtifactVisitor visitor;
            ResolvedArtifactSet.Completion result;

            StartVisitAction(ArtifactVisitor visitor) {
                this.visitor = visitor;
            }

            @Override
            public void execute(BuildOperationQueue<RunnableBuildOperation> buildOperationQueue) {
                result = artifacts.startVisit(buildOperationQueue, new AsyncArtifactListenerAdapter(visitor));
            }
        }
    }
}
