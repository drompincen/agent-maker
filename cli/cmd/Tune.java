import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;

@Command(
    name = "tune",
    description = "Closed-loop tuning with AUT/Grader/Tuner triad. [Chapter 5]",
    mixinStandardHelpOptions = true
)
public class Tune implements Runnable {

    @Parameters(index = "0", description = "Path to the agent bundle (AUT)")
    private Path agentPath;

    @Option(names = "--task", required = true, description = "Path to the task bundle")
    private Path taskPath;

    @Option(names = "--grader", description = "Override the task's default grader")
    private Path graderPath;

    @Option(names = "--tuner", description = "Path to the tuner bundle (default: factory's built-in)")
    private Path tunerPath;

    @Option(names = "--max-iters", description = "Max iterations (default 10)", defaultValue = "10")
    private int maxIters;

    @Option(names = "--target-score", description = "Stop when score >= this (default 0.9)", defaultValue = "0.9")
    private double targetScore;

    @Option(names = "--budget-usd", description = "Hard stop when total spend exceeds this (default 5)", defaultValue = "5.0")
    private double budgetUsd;

    @Option(names = "--allow-edit", description = "Edit scopes (default prompt,skills,scripts)", defaultValue = "prompt,skills,scripts")
    private String allowEdit;

    @Option(names = "--holdout", description = "Held-out split for overfit detection")
    private String holdout;

    @Override
    public void run() {
        System.err.println("am tune: not yet implemented (Chapter 5)");
        System.exit(2);
    }
}
