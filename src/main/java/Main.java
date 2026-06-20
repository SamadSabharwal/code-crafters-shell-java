import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class Main {

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

    private static final Map<Integer, Job> jobs = new ConcurrentHashMap<>();
    private static final LinkedList<Integer> jobStack = new LinkedList<>(); // most recent first
    private static final ConcurrentLinkedQueue<String> pendingNotifications = new ConcurrentLinkedQueue<>();

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            drainNotifications();

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
                runPipelineBackground(stageStrings, normalizeCommand(stageStrings));
            } else if (stageStrings.size() == 1) {
                List<String> tokens = tokenize(stageStrings.get(0));
                if (!tokens.isEmpty() && tokens.get(0).equals("jobs")) {
                    runJobsBuiltin();
                } else {
                    runSingleCommand(stageStrings.get(0));
                }
            } else {
                runPipeline(stageStrings);
            }
        }
    }

    private static void drainNotifications() {
        String line;
        while ((line = pendingNotifications.poll()) != null) {
            System.out.println(line);
        }
    }

    // ---------- "jobs" builtin ----------

    private static void runJobsBuiltin() {
        // Snapshot under lock so the list and sign markers are consistent
        // even if a background job finishes concurrently.
        TreeMap<Integer, Job> sorted;
        synchronized (Main.class) {
            sorted = new TreeMap<>(jobs);
            for (Map.Entry<Integer, Job> entry : sorted.entrySet()) {
                int jobNum = entry.getKey();
                char sign = signFor(jobNum);
                String line = String.format(
                        "[%d]%c  %-21s%s &",
                        jobNum, sign, "Running", entry.getValue().commandLine
                );
                System.out.println(line);
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

    private static synchronized char signFor(int jobNum) {
        if (!jobStack.isEmpty() && jobStack.get(0) == jobNum) {
            return '+';
        }
        if (jobStack.size() >= 2 && jobStack.get(1) == jobNum) {
            return '-';
        }
        return ' ';
    }

    // ---------- Background pipeline execution ----------

    private static void runPipelineBackground(List<String> stageStrings, String commandLine) {
        List<ProcessBuilder> builders = buildPipelineStages(stageStrings);
        if (builders == null) {
            return;
        }

        builders.get(builders.size() - 1).redirectOutput(ProcessBuilder.Redirect.INHERIT);
        for (ProcessBuilder pb : builders) {
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        }

        try {
            List<Process> processes = ProcessBuilder.startPipeline(builders);

            int jobNum = nextJobNumber();
            long pid = processes.get(processes.size() - 1).pid();
            Job job = new Job(jobNum, processes, commandLine);

            synchronized (Main.class) {
                jobs.put(jobNum, job);
                jobStack.addFirst(jobNum);
            }

            System.out.println("[" + jobNum + "] " + pid);

            Thread monitor = new Thread(() -> {
                int exitCode = 0;
                try {
                    for (Process p : processes) {
                        exitCode = p.waitFor();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                char sign = signFor(jobNum);
                String status = (exitCode == 0) ? "Done" : ("Exit " + exitCode);
                String notification = String.format(
                        "[%d]%c  %-21s%s",
                        jobNum, sign, status, commandLine
                );
                pendingNotifications.add(notification);

                synchronized (Main.class) {
                    jobs.remove(jobNum);
                    jobStack.remove(Integer.valueOf(jobNum));
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

    private static String normalizeCommand(List<String> stageStrings) {
        List<String> normalizedStages = new ArrayList<>();
        for (String stage : stageStrings) {
            normalizedStages.add(String.join(" ", tokenize(stage)));
        }
        return String.join(" | ", normalizedStages);
    }
}