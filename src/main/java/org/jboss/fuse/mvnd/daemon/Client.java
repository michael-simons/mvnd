/*
 * Copyright 2019 the original author or authors.
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
package org.jboss.fuse.mvnd.daemon;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jboss.fuse.mvnd.daemon.Message.BuildEvent;
import org.jboss.fuse.mvnd.daemon.Message.BuildMessage;
import org.jboss.fuse.mvnd.daemon.Message.MessageSerializer;
import org.jboss.fuse.mvnd.jpm.Process;
import org.jboss.fuse.mvnd.jpm.ScriptUtils;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.jline.utils.Display;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Client {

    private static final Logger LOGGER = LoggerFactory.getLogger(Client.class);
    public static final String DAEMON_DEBUG = "daemon.debug";

    public static void main(String[] args) throws Exception {
        LOGGER.debug("Starting client");

        Path javaHome = Layout.javaHome();
        DaemonRegistry registry = DaemonRegistry.getDefault();
        DaemonConnector connector = new DaemonConnector(registry, Client::startDaemon, new MessageSerializer());
        List<String> opts = new ArrayList<>();
        DaemonClientConnection daemon = connector.connect(new DaemonCompatibilitySpec(javaHome.toString(), opts));

        daemon.dispatch(new Message.BuildRequest(
                Arrays.asList(args),
                Layout.getProperty("user.dir"),
                Layout.getProperty("maven.multiModuleProjectDirectory")));

        List<String> log = new ArrayList<>();
        LinkedHashMap<String, String> projects = new LinkedHashMap<>();
        Terminal terminal = TerminalBuilder.terminal();
        Display display = new Display(terminal, false);
        boolean exit = false;
        while (!exit) {
            Message m = daemon.receive();
            if (m instanceof BuildEvent) {
                BuildEvent be = (BuildEvent) m;
                switch (be.getType()) {
                    case BuildStarted:
                        break;
                    case BuildStopped:
                        exit = true;
                        break;
                    case ProjectStarted:
                    case MojoStarted:
                    case MojoStopped:
                        projects.put(be.projectId, be.display);
                        break;
                    case ProjectStopped:
                        projects.remove(be.projectId);
                }
                Size size = terminal.getSize();
                display.resize(size.getRows(), size.getColumns());
                List<AttributedString> lines = new ArrayList<>();
                lines.add(new AttributedString("Building..."));
                projects.values().stream().map(AttributedString::fromAnsi).forEachOrdered(lines::add);
                display.update(lines, -1);
            } else if (m instanceof BuildMessage) {
                BuildMessage bm = (BuildMessage) m;
                log.add(bm.getMessage());
            }
        }
        display.update(Collections.emptyList(), 0);

        LOGGER.debug("Done receiving, printing log");

        log.forEach(terminal.writer()::println);
        terminal.flush();

        LOGGER.debug("Done !");
    }

    public static String startDaemon() {
//        DaemonParameters parms = new DaemonParameters();
//        for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
//
//        }
//            List<String> args = new ArrayList<>();
//            args.add(javaHome.resolve(java).toString());
//            args.addAll(parms.getEffectiveJvmArgs());
//            args.add("-cp");
//            args.add(classpath);

        String uid = UUID.randomUUID().toString();
        Path mavenHome = Layout.mavenHome();
        Path javaHome = Layout.javaHome();
        Path workingDir = Layout.userDir();
        String command = "";
        try {
            String classpath =
                    Stream.concat(
                            Stream.concat(Files.list(mavenHome.resolve("lib/ext")),
                                    Files.list(mavenHome.resolve("lib")))
                                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                                    .filter(Files::isRegularFile),
                            Stream.of(mavenHome.resolve("conf"), mavenHome.resolve("conf/logging")))
                            .map(Path::normalize)
                            .map(Path::toString)
                            .collect(Collectors.joining(":"));
            String java = ScriptUtils.isWindows() ? "bin\\java.exe" : "bin/java";
            List<String> args = new ArrayList<>();
            args.add("\"" + javaHome.resolve(java) + "\"");
            args.add("-classpath");
            args.add("\"" + classpath + "\"");
            if (Boolean.getBoolean(DAEMON_DEBUG)) {
                args.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=8000");
            }
            args.add("-Dmaven.home=\"" + mavenHome + "\"");
            args.add("-Dlogback.configurationFile=logback.xml");
            args.add("-Ddaemon.uid=" + uid);
            args.add("-Xmx4g");
            if (System.getProperty(Server.DAEMON_IDLE_TIMEOUT) != null) {
                args.add("-D" + Server.DAEMON_IDLE_TIMEOUT + "=" + System.getProperty(Server.DAEMON_IDLE_TIMEOUT));
            }

            args.add(Server.class.getName());
            command = String.join(" ", args);

            LOGGER.debug("Starting daemon process: uid = {}, workingDir = {}, daemonArgs: {}", uid, workingDir, command);
            Process.create(workingDir.toFile(), command);
            return uid;
        } catch (Exception e) {
            throw new DaemonException.StartException(
                    String.format("Error starting daemon: uid = %s, workingDir = %s, daemonArgs: %s",
                            uid, workingDir, command), e);
        }
    }

}