/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.dbms.diagnostics.profile;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.neo4j.cli.AbstractAdminCommand;
import org.neo4j.cli.CommandFailedException;
import org.neo4j.cli.Converters;
import org.neo4j.cli.ExecutionContext;
import org.neo4j.configuration.Config;
import org.neo4j.configuration.SettingValueParsers;
import org.neo4j.dbms.diagnostics.jmx.JMXDumper;
import org.neo4j.dbms.diagnostics.jmx.JmxDump;
import org.neo4j.internal.helpers.collection.Iterators;
import org.neo4j.io.fs.FileHandle;
import org.neo4j.kernel.diagnostics.NonInteractiveProgress;
import org.neo4j.time.Clocks;
import org.neo4j.time.Stopwatch;
import org.neo4j.time.SystemNanoClock;
import picocli.CommandLine;

@CommandLine.Command(
        name = "profile",
        header = "Profile a running neo4j process",
        description = "Runs various profilers against a running neo4j VM",
        hidden = true)
public class ProfileCommand extends AbstractAdminCommand {

    @CommandLine.Parameters(description = "Output directory of profiles")
    private Path output;

    @CommandLine.Parameters(
            description = "Duration, how long the profilers should run",
            converter = Converters.DurationConverter.class)
    private Duration duration;

    @CommandLine.Parameters(description = "The selected profilers to run. Valid values: ${COMPLETION-CANDIDATES}")
    private Set<ProfilerSource> profilers = Set.of(ProfilerSource.values());

    public enum ProfilerSource {
        JFR,
        THREADS,
    }

    public ProfileCommand(ExecutionContext ctx) {
        super(ctx);
    }

    @Override
    protected void execute() throws Exception {
        if (duration.isNegative() || duration.isZero()) {
            ctx.out().println("Duration needs to be positive");
            return;
        }
        if (profilers.isEmpty()) {
            profilers = Set.of(ProfilerSource.values());
        }
        Config config = createPrefilledConfigBuilder().build();
        JmxDump jmxDump = new JMXDumper(config, ctx.fs(), ctx.out(), ctx.err(), verbose)
                .getJMXDump()
                .orElseThrow(() ->
                        new CommandFailedException("Can not connect to running Neo4j. Profiling can not be done"));
        // TODO improve output from dumper, its designed only for report command
        SystemNanoClock clock = Clocks.nanoClock();

        ctx.out()
                .printf(
                        "Profilers %s selected. Duration %s. Output directory %s%n",
                        profilers, SettingValueParsers.DURATION.valueToString(duration), output.toAbsolutePath());
        try (ProfileTool tool = new ProfileTool()) {

            if (profilers.contains(ProfilerSource.JFR)) {
                addProfiler(tool, new JfrProfiler(jmxDump, ctx.fs(), output.resolve("jfr"), duration, clock));
            }
            if (profilers.contains(ProfilerSource.THREADS)) {
                addProfiler(
                        tool,
                        new JstackProfiler(jmxDump, ctx.fs(), output.resolve("threads"), Duration.ofSeconds(1), clock));
            }

            tool.start();
            Stopwatch stopwatch = Stopwatch.start();
            NonInteractiveProgress progress = new NonInteractiveProgress(ctx.out(), true);
            while (!stopwatch.hasTimedOut(duration) && tool.hasRunningProfilers()) {
                Thread.sleep(10);
                progress.percentChanged((int) (stopwatch.elapsed(TimeUnit.MILLISECONDS) * 100 / duration.toMillis()));
            }
            tool.stop();
            progress.finished();
            List<Profiler> profilers = Iterators.asList(tool.profilers().iterator());
            if (!stopwatch.hasTimedOut(duration)) {
                ctx.out().println("Profiler stopped before expected duration.");
            }
            if (!profilers.isEmpty()) {
                profilers.forEach(this::printFailedProfiler);
                if (profilers.stream().allMatch(profiler -> profiler.failure() != null)) {
                    ctx.out().println("All profilers failed.");
                } else {
                    ctx.out().println("Profiler results:");
                    for (Path path : ctx.fs().listFiles(output)) {
                        try (var fileStream = ctx.fs().streamFilesRecursive(path, false)) {
                            List<Path> files =
                                    fileStream.map(FileHandle::getRelativePath).toList();
                            ctx.out()
                                    .printf(
                                            "%s/ [%d %s]%n",
                                            output.relativize(path), files.size(), files.size() > 1 ? "files" : "file");
                            int numFilesToPrint = 3;
                            for (int i = 0; i < files.size() && i <= numFilesToPrint; i++) {
                                if (i < numFilesToPrint) {
                                    ctx.out().printf("\t%s%n", files.get(i));
                                } else {
                                    ctx.out().printf("\t...%n");
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private void addProfiler(ProfileTool tool, Profiler profiler) {
        if (!tool.add(profiler)) {
            ctx.out().println(profiler.getClass().getSimpleName() + " is not available and will not be used");
        }
    }

    private void printFailedProfiler(Profiler profiler) {
        if (profiler.failure() != null) {
            ctx.out()
                    .println(profiler.getClass().getSimpleName() + " failed with: "
                            + profiler.failure().getMessage());
            if (verbose) {
                profiler.failure().printStackTrace(ctx.err());
            }
        }
    }
}