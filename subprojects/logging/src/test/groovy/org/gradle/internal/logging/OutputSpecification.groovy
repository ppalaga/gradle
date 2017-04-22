/*
 * Copyright 2016 the original author or authors.
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
package org.gradle.internal.logging

import org.gradle.api.logging.LogLevel
import org.gradle.internal.logging.events.LogEvent
import org.gradle.internal.logging.events.ProgressCompleteEvent
import org.gradle.internal.logging.events.ProgressEvent
import org.gradle.internal.logging.events.ProgressStartEvent
import org.gradle.internal.operations.OperationIdentifier
import org.gradle.util.TextUtil
import spock.lang.Specification

import java.text.SimpleDateFormat

abstract class OutputSpecification extends Specification {

    public static final String CATEGORY = 'category'
    private Long counter = 1

    protected String toNative(String value) {
        return TextUtil.toPlatformLineSeparators(value)
    }

    /**
     * Returns timestamp representing 10AM today in local time.
     */
    long getTenAm() {
        return getTime('10:00:00.000')
    }

    long getTime(String time) {
        String today = new SimpleDateFormat("yyyyMMdd").format(new Date())
        return new SimpleDateFormat('yyyyMMdd HH:mm:ss.SSS').parse(today + ' ' + time).getTime()
    }

    LogEvent event(String text) {
        return new LogEvent.Builder(tenAm, CATEGORY, LogLevel.INFO, text)
            .forOperation(new OperationIdentifier(1L))
            .build()
    }

    LogEvent event(String text, LogLevel logLevel) {
        return new LogEvent.Builder(tenAm, CATEGORY, logLevel, text)
            .forOperation(new OperationIdentifier(1L))
            .build()
    }

    LogEvent event(long timestamp, String text, LogLevel logLevel) {
        return new LogEvent.Builder(timestamp, CATEGORY, logLevel, text)
            .forOperation(new OperationIdentifier(1L))
            .build()
    }

    LogEvent event(long timestamp, String text) {
        return new LogEvent.Builder(timestamp, CATEGORY, LogLevel.INFO, text)
            .forOperation(new OperationIdentifier(1L))
            .build()
    }

    LogEvent event(String text, Throwable throwable) {
        return new LogEvent.Builder(tenAm, CATEGORY, LogLevel.INFO, text)
            .forOperation(new OperationIdentifier(1L))
            .withThrowable(throwable)
            .build()
    }

    ProgressStartEvent start(String description) {
        start(description: description)
    }

    ProgressStartEvent start(Map args) {
        OperationIdentifier parentId = args.containsKey("parentId") ? args.parentId : new OperationIdentifier(counter)
        OperationIdentifier buildOperationId = args.containsKey("buildOperationId") ? args.buildOperationId : new OperationIdentifier(counter)
        long id = ++counter
        String category = args.containsKey("category") ? args.category : CATEGORY
        return new ProgressStartEvent(new OperationIdentifier(id), parentId, tenAm, category, args.description, args.shortDescription, args.loggingHeader, args.status, buildOperationId)
    }

    ProgressEvent progress(String status) {
        long id = counter
        return new ProgressEvent(new OperationIdentifier(id), tenAm, CATEGORY, status)
    }

    ProgressCompleteEvent complete(String status) {
        long id = counter--
        return new ProgressCompleteEvent(new OperationIdentifier(id), tenAm, CATEGORY, 'description', status)
    }
}
