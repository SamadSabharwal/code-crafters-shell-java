import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;

public class Main {

    private static final Map<Integer, Job> jobs = new ConcurrentHashMap<>();

    static class Job {
        final int number;
        final List<Process> processes;
        final String commandLine;

        Job(int number, List<Process> processes, String commandLine) {
            this.number = number;
            this.processes = processes;
            this.commandLine = commandLine;
        }
    }

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");
            if (!scanner.hasNextLine()) {
                break;
            }
            String input = scanner.nextLine();
            if (input.trim().isEmpty()) {
                continue;
            }

            String trimmed = input.trim();
            boolean background = false;
            if (trimmed.endsWith("&")) {
                background = true;
                trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
            }

            List<String> stageStrings = splitOnPipe(trimmed);

            if (background) {
                runPipelineBackground(stageStrings, trimmed);
            } else if (stageStrings.size() == 1) {
                runSingleCommand(stageStrings.get(0));
            } else {
                runPipeline(stageStrings);
            }
        }
    }

    // ---------- Job number recycling ----------

    private static synchronized int nextJobNumber() {
        int n = 1;
        while (jobs.containsKey(n)) {
            n++;
        }
        return n;
    }

    // ---------- Background pipeline execution ----------

    private static void runPipelineBackground(List<String> stageStrings, String commandLine) {
        List<ProcessBuilder> builders = buildPipelineStages(stageStrings);
        if (builders == null) {
            return;
        }

        // Last stage's stdout still goes to the terminal so output is visible.
        builders.get(builders.size() - 1).redirectOutput(ProcessBuilder.Redirect.INHERIT);
        for (ProcessBuilder pb : builders) {
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        }

        try {
            List<Process> processes = ProcessBuilder.startPipeline(builders);

            int jobNum = nextJobNumber();
            long pid = processes.get(processes.size() - 1).pid();
            Job job = new Job(jobNum, processes, commandLine);
            jobs.put(jobNum, job);

            // Bash prints "[N] PID" immediately when a background job starts.
            System.out.println("[" + jobNum + "] " + pid);

            // Watch the job on a separate thread so its number is freed
            // (recycled) as soon as the job finishes, without blocking the shell.
            Thread monitor = new Thread(() -> {
                try {
                    for (Process p : processes) {
                        p.waitFor();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    jobs.remove(jobNum);
                }
            });
            monitor.setDaemon(true);
            monitor.start();

        } catch (IOException e) {
            System.out.println("Error executing pipeline: " + e.getMessage());
        }
    }

    // ---------- Foreground pipeline execution ----------

    private static void runPipeline(List<String> stageStrings) {
        List<ProcessBuilder> builders = buildPipelineStages(stageStrings);
        if (builders == null) {
            return;
        }

        builders.get(0).redirectInput(ProcessBuilder.Redirect.INHERIT);
        builders.get(builders.size() - 1).redirectOutput(ProcessBuilder.Redirect.INHERIT);
        for (ProcessBuilder pb : builders) {
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        }

        try {
            List<Process> processes = ProcessBuilder.startPipeline(builders);
            for (Process p : processes) {
                p.waitFor();
            }
        } catch (IOException e) {
            System.out.println("Error executing pipeline: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<ProcessBuilder> buildPipelineStages(List<String> stageStrings) {
        List<ProcessBuilder> builders = new ArrayList<>();
        for (String stageStr : stageStrings) {
            List<String> tokens = tokenize(stageStr);
            if (tokens.isEmpty()) {
                System.out.println("Invalid pipeline: empty command");
                return null;
            }
            builders.add(new ProcessBuilder(tokens));
        }
        return builders;
    }

    // ---------- Single command execution ----------

    private static void runSingleCommand(String commandStr) {
        List<String> tokens = tokenize(commandStr);
        if (tokens.isEmpty()) {
            return;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(tokens);
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            Process process = pb.start();
            process.waitFor();
        } catch (IOException e) {
            System.out.println(tokens.get(0) + ": command not found");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------- Parsing helpers ----------

    private static List<String> splitOnPipe(String input) {
        List<String> parts = new ArrayList<>();
        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
                current.append(c);
            } else if (c == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes;
                current.append(c);
            } else if (c == '|' && !inSingleQuotes && !inDoubleQuotes) {
                parts.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString().trim());
        return parts;
    }

    private static List<String> tokenize(String commandStr) {
        List<String> tokens = new ArrayList<>();
        for (String t : commandStr.trim().split("\\s+")) {
            if (!t.isEmpty()) {
                tokens.add(t);
            }
        }
        return tokens;
    }
}