/*
 * Copyright 2009 the original author or authors.
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
package org.gradle.api.plugins.quality

import org.gradle.api.GradleException
import org.gradle.api.file.FileCollection
import org.gradle.internal.reflect.Instantiator
import org.gradle.api.internal.project.IsolatedAntBuilder
import org.gradle.api.plugins.quality.internal.CheckstyleReportsImpl
import org.gradle.api.reporting.Reporting
import org.gradle.util.DeprecationLogger
import org.gradle.api.tasks.*

/**
 * Runs Checkstyle against some source files.
 */
class Checkstyle extends SourceTask implements VerificationTask, Reporting<CheckstyleReports> {
    /**
     * The class path containing the Checkstyle library to be used.
     */
    @InputFiles
    FileCollection checkstyleClasspath

    /**
     * The class path containing the compiled classes for the source files to be analyzed.
     */
    @InputFiles
    FileCollection classpath

    /**
     * The Checkstyle configuration file to use.
     */
    @InputFile
    File configFile

    /**
     * The properties available for use in the configuration file. These are substituted into the configuration
     * file.
     */
    @Input
    @Optional
    Map<String, Object> configProperties = [:]

    /**
     * The properties available for use in the configuration file. These are substituted into the configuration
     * file.
     * 
     * @deprecated renamed to <tt>configProperties</tt>
     */
    @Deprecated
    Map<String, Object> getProperties() {
        DeprecationLogger.nagUserOfReplacedProperty("Checkstyle.properties", "configProperties")
        getConfigProperties()
    }

    /**
     * The properties available for use in the configuration file. These are substituted into the configuration
     * file.
     *
     * @deprecated renamed to <tt>configProperties</tt>
     */
    @Deprecated
    void setProperties(Map<String, Object> properties) {
        DeprecationLogger.nagUserOfReplacedProperty("Checkstyle.properties", "configProperties")
        setConfigProperties(properties)
    }

    @Nested
    private final CheckstyleReportsImpl reports = services.get(Instantiator).newInstance(CheckstyleReportsImpl, this)

    /**
     * The reports to be generated by this task.
     *
     * @return The reports container
     */
    CheckstyleReports getReports() {
        reports
    }

    /**
     * Configures the reports to be generated by this task.
     *
     * The contained reports can be configured by name and closures. Example:
     *
     * <pre>
     * checkstyleTask {
     *   reports {
     *     xml {
     *       destination "build/codenarc.xml"
     *     }
     *   }
     * }
     * </pre>
     *
     * @param closure The configuration
     * @return The reports container
     */
    CheckstyleReports reports(Closure closure) {
        reports.configure(closure)
    }

    /**
     * Returns the destination file for the XML report.
     *
     * @deprecated Use {@code reports.xml.destination} instead.
     */
    @Deprecated
    File getResultFile() {
        DeprecationLogger.nagUserOfReplacedProperty("Checkstyle.resultFile", "reports.xml.destination")
        return reports.xml.destination
    }

    /**
     * @deprecated Use {@code reports.xml.destination} instead.
     */
    @Deprecated
    void setResultFile(File file) {
        DeprecationLogger.nagUserOfReplacedProperty("Checkstyle.resultFile", "reports.xml.destination")
        reports.xml.destination = file
    }

    /**
     * Whether or not this task will ignore failures and continue running the build.
     */
    boolean ignoreFailures

    @TaskAction
    public void run() {
        def propertyName = "org.gradle.checkstyle.violations"
        def antBuilder = services.get(IsolatedAntBuilder)
        antBuilder.withClasspath(getCheckstyleClasspath()).execute {
            ant.taskdef(name: 'checkstyle', classname: 'com.puppycrawl.tools.checkstyle.CheckStyleTask')

            ant.checkstyle(config: getConfigFile(), failOnViolation: false, failureProperty: propertyName) {
                getSource().addToAntBuilder(ant, 'fileset', FileCollection.AntType.FileSet)
                getClasspath().addToAntBuilder(ant, 'classpath')
                formatter(type: 'plain', useFile: false)
                if (reports.xml.enabled) {
                    formatter(type: 'xml', toFile: reports.xml.destination)
                }

                getConfigProperties().each { key, value ->
                    property(key: key, value: value.toString())
                }
            }

            if (!getIgnoreFailures() && ant.project.properties[propertyName]) {
                if (reports.xml.enabled) {
                    throw new GradleException("Checkstyle rule violations were found. See the report at ${reports.xml.destination}.")
                } else {
                    throw new GradleException("Checkstyle rule violations were found")
                }
            }
        }
    }
}
