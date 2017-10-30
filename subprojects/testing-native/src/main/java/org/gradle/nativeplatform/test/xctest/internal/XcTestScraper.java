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

package org.gradle.nativeplatform.test.xctest.internal;

import com.google.common.base.Joiner;
import org.gradle.api.internal.tasks.testing.DefaultTestClassDescriptor;
import org.gradle.api.internal.tasks.testing.DefaultTestMethodDescriptor;
import org.gradle.api.internal.tasks.testing.DefaultTestOutputEvent;
import org.gradle.api.internal.tasks.testing.TestCompleteEvent;
import org.gradle.api.internal.tasks.testing.TestDescriptorInternal;
import org.gradle.api.internal.tasks.testing.TestResultProcessor;
import org.gradle.api.internal.tasks.testing.TestStartEvent;
import org.gradle.api.tasks.testing.TestOutputEvent;
import org.gradle.api.tasks.testing.TestResult;
import org.gradle.internal.id.IdGenerator;
import org.gradle.internal.io.TextStream;
import org.gradle.internal.time.Clock;
import org.gradle.util.TextUtil;

import javax.annotation.Nullable;
import java.util.Deque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class XcTestScraper implements TextStream {
    private static final Pattern TEST_SUITE_NAME_PATTERN = Pattern.compile("'(\\p{Alnum}+)'");
    private static final Pattern TEST_CASE_NAME_PATTERN = Pattern.compile("'-\\[\\p{Alnum}+.(\\p{Alnum}+) (\\p{Alnum}+)]'");
    private static final Pattern TEST_FAILURE_PATTERN = Pattern.compile(":\\d+: error: -\\[\\p{Alnum}+.(\\p{Alnum}+) (\\p{Alnum}+)] : (.*)");

    private final TestResultProcessor processor;
    private final TestOutputEvent.Destination destination;
    private final IdGenerator<?> idGenerator;
    private final Clock clock;
    private final Deque<XCTestDescriptor> testDescriptors;
    private TestDescriptorInternal lastDescriptor;

    XcTestScraper(TestOutputEvent.Destination destination, TestResultProcessor processor, IdGenerator<?> idGenerator, Clock clock, Deque<XCTestDescriptor> testDescriptors) {
        this.processor = processor;
        this.destination = destination;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.testDescriptors = testDescriptors;
    }

    @Override
    public void text(String text) {
        synchronized (testDescriptors) {
            if (text.startsWith("Test Suite")) {
                Matcher testSuiteMatcher = TEST_SUITE_NAME_PATTERN.matcher(text);
                if (!testSuiteMatcher.find()) {
                    return;
                }
                String testSuite = testSuiteMatcher.group(1);

                if (text.contains("started at")) {
                    TestDescriptorInternal testDescriptor = new DefaultTestClassDescriptor(idGenerator.generateId(), testSuite);  // Using DefaultTestClassDescriptor to fake JUnit test

                    processor.started(testDescriptor, new TestStartEvent(clock.getCurrentTime()));
                    testDescriptors.push(new XCTestDescriptor(testDescriptor));
                } else {
                    XCTestDescriptor xcTestDescriptor = testDescriptors.pop();
                    lastDescriptor = xcTestDescriptor.getDescriptorInternal();
                    TestDescriptorInternal testDescriptor = xcTestDescriptor.getDescriptorInternal();
                    TestResult.ResultType resultType = TestResult.ResultType.SUCCESS;
                    if (text.contains("failed at")) {
                        resultType = TestResult.ResultType.FAILURE;
                    }

                    processor.completed(testDescriptor.getId(), new TestCompleteEvent(clock.getCurrentTime(), resultType));
                }
            } else if (text.startsWith("Test Case")) {
                Matcher testCaseMatcher = TEST_CASE_NAME_PATTERN.matcher(text);
                testCaseMatcher.find();
                String testSuite = testCaseMatcher.group(1);
                String testCase = testCaseMatcher.group(2);

                if (text.contains("started.")) {
                    TestDescriptorInternal testDescriptor = new DefaultTestMethodDescriptor(idGenerator.generateId(), testSuite, testCase);

                    processor.started(testDescriptor, new TestStartEvent(clock.getCurrentTime()));
                    testDescriptors.push(new XCTestDescriptor(testDescriptor));
                } else {
                    XCTestDescriptor xcTestDescriptor = testDescriptors.pop();
                    lastDescriptor = xcTestDescriptor.getDescriptorInternal();
                    TestDescriptorInternal testDescriptor = xcTestDescriptor.getDescriptorInternal();
                    TestResult.ResultType resultType = TestResult.ResultType.SUCCESS;
                    if (text.contains("failed (")) {
                        resultType = TestResult.ResultType.FAILURE;
                        processor.failure(testDescriptor.getId(), new Throwable(Joiner.on(TextUtil.getPlatformLineSeparator()).join(xcTestDescriptor.getMessages())));
                    }

                    processor.completed(testDescriptor.getId(), new TestCompleteEvent(clock.getCurrentTime(), resultType));
                }
            } else {
                XCTestDescriptor xcTestDescriptor = testDescriptors.peek();
                if (xcTestDescriptor != null) {
                    TestDescriptorInternal testDescriptor = xcTestDescriptor.getDescriptorInternal();

                    processor.output(testDescriptor.getId(), new DefaultTestOutputEvent(destination, text));

                    Matcher failureMessageMatcher = TEST_FAILURE_PATTERN.matcher(text);
                    if (failureMessageMatcher.find()) {
                        String testSuite = failureMessageMatcher.group(1);
                        String testCase = failureMessageMatcher.group(2);
                        String message = failureMessageMatcher.group(3);

                        if (testDescriptor.getClassName().equals(testSuite) && testDescriptor.getName().equals(testCase)) {
                            xcTestDescriptor.getMessages().add(message);
                        }
                    }

                // If no current test can be associated to the output, the last known descriptor is used.
                // See https://bugs.swift.org/browse/SR-1127 for more information.
                } else if (lastDescriptor != null) {
                    processor.output(lastDescriptor.getId(), new DefaultTestOutputEvent(destination, text));
                }
            }
        }
    }

    @Override
    public void endOfStream(@Nullable Throwable failure) {
        if (failure != null) {
            while (!testDescriptors.isEmpty()) {
                processor.failure(testDescriptors.pop(), failure);
            }
        }
    }

}
