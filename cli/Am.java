///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17
//DEPS info.picocli:picocli:4.7.6
//SOURCES cmd/New.java
//SOURCES cmd/Avatar.java
//SOURCES cmd/Run.java
//SOURCES cmd/Tune.java
//SOURCES cmd/Factory.java
//SOURCES cmd/Publish.java

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
    name = "am",
    description = "agent-maker — factory for portable claude-code agents",
    mixinStandardHelpOptions = true,
    version = "agent-maker 0.1.0",
    subcommands = {
        New.class,
        Avatar.class,
        Run.class,
        Tune.class,
        Factory.class,
        Publish.class
    }
)
public class Am implements Runnable {
    public static void main(String[] args) {
        int exit = new CommandLine(new Am()).execute(args);
        System.exit(exit);
    }

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }
}
