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
package org.neo4j.commandline.dbms;

import static java.lang.String.format;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.neo4j.cli.CommandTestUtils.withSuppressedOutput;
import static org.neo4j.commandline.dbms.DiagnosticsReportCommand.DEFAULT_CLASSIFIERS;
import static org.neo4j.commandline.dbms.DiagnosticsReportCommand.describeClassifier;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Optional;
import org.apache.commons.io.output.NullPrintStream;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.neo4j.cli.CommandFailedException;
import org.neo4j.cli.CommandTestUtils;
import org.neo4j.cli.ContextInjectingFactory;
import org.neo4j.configuration.BootloaderSettings;
import org.neo4j.configuration.Config;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.dbms.diagnostics.jmx.JMXDumper;
import org.neo4j.dbms.diagnostics.jmx.JmxDump;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.fs.FileSystemUtils;
import org.neo4j.io.fs.FileUtils;
import org.neo4j.memory.EmptyMemoryTracker;
import org.neo4j.test.extension.Inject;
import org.neo4j.test.extension.testdirectory.TestDirectoryExtension;
import org.neo4j.test.utils.TestDirectory;
import picocli.CommandLine;

@TestDirectoryExtension
class DiagnosticsReportCommandIT {
    @Inject
    private TestDirectory testDirectory;

    @Inject
    private FileSystemAbstraction fs;

    private Path homeDir;
    private Path configDir;
    private String originalUserDir;

    @BeforeEach
    void setUp() throws Exception {
        homeDir = testDirectory.directory("home-dir");
        configDir = testDirectory.directory("config-dir");

        // Touch config
        Files.createFile(configDir.resolve("neo4j.conf"));

        // To make sure files are resolved from the working directory
        originalUserDir =
                System.setProperty("user.dir", testDirectory.absolutePath().toString());
    }

    @AfterEach
    void tearDown() {
        // Restore directory
        System.setProperty("user.dir", originalUserDir);
    }

    @Test
    void shouldBeAbleToAttachToPidAndRunThreadDump() throws IOException {
        long pid = getPID();
        assertThat(pid).isNotEqualTo(0);

        // Write config file
        Files.createFile(testDirectory.file("neo4j.conf"));

        // write neo4j.pid file
        Path run = testDirectory.directory("run");
        Files.write(run.resolve("neo4j.pid"), String.valueOf(pid).getBytes());

        // Run command, should detect running instance
        String[] args = {"threads", "--to-path=" + testDirectory.absolutePath() + "/reports"};
        Path homeDir = testDirectory.homePath();
        var signalToIgnoreThisTest = new MutableBoolean();
        withSuppressedOutput(homeDir, homeDir, fs, ctx -> {
            try {
                DiagnosticsReportCommand diagnosticsReportCommand = populateCommand(ctx, args);
                diagnosticsReportCommand.execute();
            } catch (CommandFailedException e) {
                if (e.getMessage().equals("Unknown classifier: threads")) {
                    signalToIgnoreThisTest.setTrue();
                } else {
                    throw e;
                }
            }
        });

        // If we get attach API is not available for example in some IBM jdk installs, ignore this test
        if (signalToIgnoreThisTest.isTrue()) {
            return;
        }

        // Verify that we took a thread dump
        Path reports = testDirectory.directory("reports");
        Path[] files = FileUtils.listPaths(reports);
        assertThat(files).isNotNull();
        assertThat(files.length).isEqualTo(1);

        Path report = files[0];
        final URI uri = URI.create("jar:file:" + report.toUri().getRawPath());

        try (FileSystem fs = FileSystems.newFileSystem(uri, Collections.emptyMap())) {
            String threadDump = Files.readString(fs.getPath("threaddump.txt"));
            assertThat(threadDump).contains(DiagnosticsReportCommandIT.class.getCanonicalName());
        }
    }

    @Test
    void shouldBeAbleToAttachToPidAndRunHeapDump() throws IOException {
        long pid = getPID();
        assertThat(pid).isNotEqualTo(0);

        // Write config file
        Files.createFile(testDirectory.file("neo4j.conf"));

        // write neo4j.pid file
        Path run = testDirectory.directory("run");
        Files.write(run.resolve("neo4j.pid"), String.valueOf(pid).getBytes());

        // Run command, should detect running instance
        String[] args = {"heap", "--to-path=" + testDirectory.absolutePath() + "/reports"};
        Path homeDir = testDirectory.homePath();
        var signalToIgnoreThisTest = new MutableBoolean();
        withSuppressedOutput(homeDir, homeDir, fs, ctx -> {
            try {
                DiagnosticsReportCommand diagnosticsReportCommand = populateCommand(ctx, args);
                diagnosticsReportCommand.execute();
            } catch (CommandFailedException e) {
                if (e.getMessage().equals("Unknown classifier: heap")) {
                    signalToIgnoreThisTest.setTrue();
                } else {
                    throw e;
                }
            }
        });

        // If we get attach API is not available for example in some IBM jdk installs, ignore this test
        if (signalToIgnoreThisTest.isTrue()) {
            return;
        }

        // Verify that we took a heap dump
        Path reports = testDirectory.directory("reports");
        Path[] files = FileUtils.listPaths(reports);
        assertThat(files).isNotNull();
        assertThat(files.length).isEqualTo(1);

        try (FileSystem fs = FileSystems.newFileSystem(files[0])) {
            assertTrue(Files.exists(fs.getPath("heapdump.hprof")));
        }
    }

    @Test
    void includeAllLogFiles() throws IOException {
        // Write config file and specify a custom name for the neo4j.log file.
        Path confFile = testDirectory.createFile("neo4j.conf");
        Files.write(confFile, singletonList(GraphDatabaseSettings.logs_directory.name() + "=customLogDir/"));

        // Create some log files that should be found.
        testDirectory.directory("customLogDir");
        testDirectory.createFile("customLogDir/debug.log");
        testDirectory.createFile("customLogDir/debug.log.01.zip");
        testDirectory.createFile("customLogDir/neo4j.log");
        testDirectory.createFile("customLogDir/neo4j.log.01");

        String[] args = {"logs", "--to-path=" + testDirectory.absolutePath() + "/reports"};
        Path homeDir = testDirectory.homePath();
        withSuppressedOutput(homeDir, homeDir, fs, ctx -> {
            DiagnosticsReportCommand diagnosticsReportCommand = populateCommand(ctx, args);
            diagnosticsReportCommand.execute();
        });

        Path reports = testDirectory.directory("reports");
        Path[] files = FileUtils.listPaths(reports);
        assertThat(files.length).isEqualTo(1);

        try (FileSystem fileSystem = FileSystems.newFileSystem(files[0])) {
            Path logsDir = fileSystem.getPath("logs");
            assertTrue(Files.exists(logsDir.resolve("debug.log")));
            assertTrue(Files.exists(logsDir.resolve("debug.log.01.zip")));
            assertTrue(Files.exists(logsDir.resolve("neo4j.log")));
            assertTrue(Files.exists(logsDir.resolve("neo4j.log.01")));
        }
    }

    @Test
    void includeAllAdminConfigFiles() throws IOException {
        // Create some config files that should be found. neo4j.conf has already been created during setup.
        Files.createFile(configDir.resolve("neo4j-admin.conf"));
        Files.createFile(configDir.resolve("neo4j-admin-database-check.conf"));

        String[] args = {"config", "--to-path=" + testDirectory.absolutePath() + "/reports"};
        withSuppressedOutput(homeDir, configDir, fs, ctx -> {
            DiagnosticsReportCommand diagnosticsReportCommand = populateCommand(ctx, args);
            diagnosticsReportCommand.execute();
        });

        Path[] files = FileUtils.listPaths(testDirectory.homePath().resolve("reports"));
        assertThat(files.length).isEqualTo(1);

        try (FileSystem fileSystem = FileSystems.newFileSystem(files[0])) {
            Path confDir = fileSystem.getPath("config");
            assertTrue(Files.exists(confDir.resolve("neo4j.conf")));
            assertTrue(Files.exists(confDir.resolve("neo4j-admin.conf")));
            assertTrue(Files.exists(confDir.resolve("neo4j-admin-database-check.conf")));
        }
    }

    @Test
    void includeLog4jConfigs() throws IOException {
        // Special location for one of the logging configuration files.
        String neo4jConfContents = GraphDatabaseSettings.server_logging_config_path.name() + "=customLogDir/name.xml";
        Files.write(configDir.resolve("neo4j.conf"), singletonList(neo4jConfContents));
        Files.createDirectories(homeDir.resolve("customLogDir"));
        Files.write(homeDir.resolve("customLogDir/name.xml"), singletonList("Config1"));
        // Default for the logging config is in a conf folder under neo4j-home
        Files.createDirectories(homeDir.resolve("conf"));
        Files.write(homeDir.resolve("conf/user-logs.xml"), singletonList("Config2"));

        String[] args = {"config", "--to-path=" + testDirectory.absolutePath() + "/reports"};
        withSuppressedOutput(homeDir, configDir, fs, ctx -> {
            DiagnosticsReportCommand diagnosticsReportCommand = populateCommand(ctx, args);
            diagnosticsReportCommand.execute();
        });

        Path[] files = FileUtils.listPaths(testDirectory.homePath().resolve("reports"));
        assertThat(files.length).isEqualTo(1);

        // Should find neo4j.conf (created in setup), and the two logging configuration files in different places
        try (FileSystem fileSystem = FileSystems.newFileSystem(files[0])) {
            Path confDir = fileSystem.getPath("config");
            Path neo4jConf = confDir.resolve("neo4j.conf");
            assertTrue(Files.exists(neo4jConf));
            assertThat(Files.readAllLines(neo4jConf)).containsExactly(neo4jConfContents);

            Path serverLogConf = confDir.resolve("server-logs.xml");
            assertTrue(Files.exists(serverLogConf));
            assertThat(Files.readAllLines(serverLogConf)).containsExactly("Config1");

            Path userLogConf = confDir.resolve("user-logs.xml");
            assertTrue(Files.exists(userLogConf));
            assertThat(Files.readAllLines(userLogConf)).containsExactly("Config2");
        }
    }

    @Test
    void allHasToBeOnlyClassifier() {
        withSuppressedOutput(homeDir, configDir, fs, ctx -> {
            String[] args = {"all", "logs", "tx"};
            DiagnosticsReportCommand diagnosticsReportCommand = populateCommand(ctx, args);

            CommandFailedException incorrectUsage =
                    assertThrows(CommandFailedException.class, diagnosticsReportCommand::execute);
            assertEquals(
                    "If you specify 'all' this has to be the only classifier. Found ['logs','tx'] as well.",
                    incorrectUsage.getMessage());
        });
    }

    @Test
    void printUnrecognizedClassifiers() {
        String[] args = {"logs", "tx", "invalid"};
        withSuppressedOutput(homeDir, configDir, fs, ctx -> {
            DiagnosticsReportCommand diagnosticsReportCommand = populateCommand(ctx, args);
            CommandFailedException incorrectUsage =
                    assertThrows(CommandFailedException.class, diagnosticsReportCommand::execute);
            assertEquals("Unknown classifier: invalid", incorrectUsage.getMessage());
        });
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    @Test
    void defaultValuesShouldBeValidClassifiers() {
        for (String classifier : DEFAULT_CLASSIFIERS) {
            describeClassifier(classifier);
        }

        // Make sure the above actually catches bad classifiers
        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> describeClassifier("invalid"));
        assertEquals("Unknown classifier: invalid", exception.getMessage());
    }

    @Test
    void listShouldDisplayAllClassifiers() {
        String[] args = {"--list"};

        withSuppressedOutput(homeDir, configDir, fs, ctx -> {
            DiagnosticsReportCommand diagnosticsReportCommand = populateCommand(ctx, args);
            diagnosticsReportCommand.execute();

            assertThat(ctx.outAsString())
                    .isEqualTo(String.format("Finding running instance of neo4j%n"
                            + "No running instance of neo4j was found. Online reports will be omitted.%n"
                            + "All available classifiers:%n"
                            + "  config     include configuration files%n"
                            + "  logs       include log files%n"
                            + "  plugins    include a view of the plugin directory%n"
                            + "  ps         include a list of running processes%n"
                            + "  tree       include a view of the tree structure of the data directory%n"
                            + "  tx         include transaction logs%n"
                            + "  version    include version of neo4j%n"));
        });
    }

    @Test
    void overrideDestination() throws Exception {
        String toArgument = "--to-path=" + System.getProperty("user.dir") + "/other/";
        String[] args = {toArgument, "all"};

        withSuppressedOutput(homeDir, configDir, fs, ctx -> {
            DiagnosticsReportCommand diagnosticsReportCommand = populateCommand(ctx, args);
            diagnosticsReportCommand.execute();
        });

        Path other = testDirectory.directory("other");
        assertThat(fs.fileExists(other)).isEqualTo(true);
        assertThat(fs.listFiles(other).length).isEqualTo(1);

        // Default should be empty
        Path reports = testDirectory.homePath().resolve("reports");
        assertThat(fs.fileExists(reports)).isEqualTo(false);
    }

    @Test
    void shouldNotListProfileCommand() {
        String[] args = {"--list"};

        withSuppressedOutput(homeDir, configDir, fs, ctx -> {
            DiagnosticsReportCommand diagnosticsReportCommand = populateCommand(ctx, args);
            diagnosticsReportCommand.execute();

            assertThat(ctx.outAsString()).doesNotContain("profile");
        });
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void shouldRunProfileAsASubCommand() throws IOException {
        Config config = Config.defaults(GraphDatabaseSettings.neo4j_home, homeDir);
        Path pidFile = config.get(BootloaderSettings.pid_file);
        fs.mkdirs(pidFile.getParent());
        FileSystemUtils.writeString(fs, pidFile, format("%s%n", getPID()), EmptyMemoryTracker.INSTANCE);

        JMXDumper jmxDumper =
                new JMXDumper(config, fs, NullPrintStream.NULL_PRINT_STREAM, NullPrintStream.NULL_PRINT_STREAM, true);
        Optional<JmxDump> maybeDump = jmxDumper.getJMXDump();
        assumeThat(maybeDump).isPresent(); // IF not, then no point in running tests
        maybeDump.get().close();

        Path output = homeDir.resolve("profile");
        String[] args = {"profile", output.toString(), "3s", "--skip-compression"};
        withSuppressedOutput(homeDir, configDir, fs, ctx -> {
            CommandLine commandLine =
                    new CommandLine(new DiagnosticsReportCommand(ctx), new ContextInjectingFactory(ctx));
            commandLine.execute(args);
            assertThat(ctx.outAsString()).contains("jfr/ [1 file]").contains("threads");
        });
        assertThat(fs.listFiles(
                        output.resolve("jfr"),
                        path -> path.getFileName().toString().endsWith(".jfr")))
                .hasSize(1);
    }

    private DiagnosticsReportCommand populateCommand(CommandTestUtils.CapturingExecutionContext ctx, String... args) {
        DiagnosticsReportCommand diagnosticsReportCommand = new DiagnosticsReportCommand(ctx);
        CommandLine commandLine = new CommandLine(diagnosticsReportCommand, new ContextInjectingFactory(ctx));
        commandLine.parseArgs(args);
        return diagnosticsReportCommand;
    }

    private static long getPID() {
        return ProcessHandle.current().pid();
    }
}
