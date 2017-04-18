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

package org.gradle.api.internal.changedetection.state;

import com.google.common.hash.HashCode;
import org.gradle.api.file.RelativePath;
import org.gradle.api.internal.changedetection.resources.SnapshottableDirectoryResource;
import org.gradle.internal.nativeintegration.filesystem.FileType;

import java.io.IOException;
import java.util.Collections;

class DirectoryFileSnapshot implements FileSnapshot, SnapshottableDirectoryResource {
    final String path;
    private final RelativePath relativePath;
    private final boolean root;

    DirectoryFileSnapshot(String path, RelativePath relativePath, boolean root) {
        this.path = path;
        this.relativePath = relativePath;
        this.root = root;
    }

    @Override
    public String toString() {
        return getType() + " " + path;
    }

    @Override
    public String getPath() {
        return path;
    }

    @Override
    public String getName() {
        return relativePath.getLastName();
    }

    @Override
    public boolean isRoot() {
        return root;
    }

    @Override
    public RelativePath getRelativePath() {
        return relativePath;
    }

    @Override
    public FileContentSnapshot getContent() {
        return DirContentSnapshot.getInstance();
    }

    @Override
    public FileType getType() {
        return FileType.Directory;
    }

    @Override
    public FileSnapshot withContentHash(HashCode contentHash) {
        throw new UnsupportedOperationException("Cannot change the content of a directory");
    }

    @Override
    public FileSnapshot getRoot() {
        return this;
    }

    @Override
    public Iterable<? extends FileSnapshot> getElements() {
        return Collections.singleton(this);
    }

    @Override
    public String getDisplayName() {
        return getPath();
    }

    @Override
    public void close() throws IOException {
    }
}
