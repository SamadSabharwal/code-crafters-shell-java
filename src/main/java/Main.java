import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {
    private static Map<Integer, BackgroundJob> backgroundJobs = new LinkedHashMap<>();
    private static final Set<String> BUILTINS = new HashSet<>(Arrays.asList(
        "echo", "type", "exit", "pwd", "cd", "jobs"
    ));

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");
            System.out.flush();

            if (!scanner.hasNextLine()) {
                break;
            }

            String line = scanner.nextLine().trim();

            if (line.isEmpty()) {
                continue;
            }

            // exit builtin
            if (line.equals("exit") || line.equals("exit 0")) {
                break;
            }

            // Check for background job
            boolean isBackground = line.endsWith("&");
            if (isBackground) {
                line = line.substring(0, line.length() - 1).trim();
            }

            // pipeline
            if (line.contains("|")) {
                executePipeline(line, isBackground);
            } else {
                List<String> command = parseCommand(line);
                
                // Handle builtins
                if (!command.isEmpty() && isBuiltin(command.get(0))) {
                    handleBuiltin(command);
                } else {
                    executeCommand(command, isBackground);
                }
            }

            // Check for completed background jobs
            printCompletedJobs();
        }
    }

    private static boolean isBuiltin(String command) {
        return BUILTINS.contains(command);
    }

    private static void handleBuiltin(List<String> command) {
        String cmd = command.get(0);

        switch (cmd) {
            case "echo":
                handleEcho(command);
                break;
            case "type":
                handleType(command);
                break;
            case "pwd":
                handlePwd();
                break;
            case "cd":
                handleCd(command);
                break;
            case "jobs":
                handleJobs();
                break;
            case "exit":
                System.exit(0);
                break;
        }
    }

    private static void handleEcho(List<String> command) {
        // Skip the "echo" command itself
        List<String> args = command.subList(1, command.size());
        System.out.println(String.join(" ", args));
    }

    private static void handleType(List<String> command) {
        if (command.size() < 2) {
            System.err.println("type: missing argument");
            return;
        }

        String cmdName = command.get(1);

        if (BUILTINS.contains(cmdName)) {
            System.out.println(cmdName + " is a shell builtin");
        } else {
            // Check if it's an external command in PATH
            String pathEnv = System.getenv("PATH");
            if (pathEnv != null) {
                String[] paths = pathEnv.split(":");
                for (String path : paths) {
                    File file = new File(path, cmdName);
                    if (file.exists() && file.isFile() && file.canExecute()) {
                        System.out.println(cmdName + " is " + file.getAbsolutePath());
                        return;
                    }
                }
            }
            System.out.println(cmdName + ": not found");
        }
    }

    private static void handlePwd() {
        System.out.println(System.getProperty("user.dir"));
    }

    private static void handleCd(List<String> command) {
        String targetDir;
        if (command.size() < 2) {
            targetDir = System.getProperty("user.home");
        } else {
            targetDir = command.get(1);
        }

        File dir = new File(targetDir);
        if (!dir.exists() || !dir.isDirectory()) {
            System.err.println("cd: " + targetDir + ": No such file or directory");
            return;
        }

        try {
            System.setProperty("user.dir", dir.getCanonicalPath());
        } catch (IOException e) {
            System.err.println("cd: " + targetDir + ": " + e.getMessage());
        }
    }

    private static void handleJobs() {
        // Print all background jobs (both running and done)
        for (BackgroundJob job : backgroundJobs.values()) {
            String marker = job.isLatest ? "+" : " ";
            System.out.printf("[%d]%s  %-20s %s &%n", job.jobNumber, marker, job.status, job.command);
        }
    }

    // Get the next available job number (recycle numbers)
    private static int getNextJobNumber() {
        int jobNum = 1;
        while (backgroundJobs.containsKey(jobNum)) {
            jobNum++;
        }
        return jobNum;
    }

    // Mark all jobs as not latest, then mark the given job as latest
    private static void updateLatestJob(int jobNumber) {
        for (BackgroundJob job : backgroundJobs.values()) {
            job.isLatest = false;
        }
        if (backgroundJobs.containsKey(jobNumber)) {
            backgroundJobs.get(jobNumber).isLatest = true;
        }
    }

    // Executes a normal external command
    private static void executeCommand(List<String> command, boolean isBackground) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);

            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);

            Process process = pb.start();

            if (isBackground) {
                long pid = process.pid();
                int jobNumber = getNextJobNumber();
                BackgroundJob job = new BackgroundJob(jobNumber, (int) pid, String.join(" ", command));
                backgroundJobs.put(jobNumber, job);
                updateLatestJob(jobNumber);
                System.out.println("[" + jobNumber + "] " + pid);
                System.out.flush();

                // Monitor the process in a separate thread
                new Thread(() -> {
                    try {
                        process.waitFor();
                        synchronized (Main.class) {
                            job.status = "Done";
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }).start();
            } else {
                process.waitFor();
            }

        } catch (IOException e) {
            System.out.println(command.get(0) + ": command not found");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Executes cmd1 | cmd2
    private static void executePipeline(String line, boolean isBackground) {

        String[] commands = line.split("\\|", 2);

        List<String> left = parseCommand(commands[0].trim());
        List<String> right = parseCommand(commands[1].trim());

        try {

            ProcessBuilder pb1 = new ProcessBuilder(left);
            ProcessBuilder pb2 = new ProcessBuilder(right);

            pb1.redirectError(ProcessBuilder.Redirect.INHERIT);
            pb2.redirectError(ProcessBuilder.Redirect.INHERIT);

            Process p1 = pb1.start();
            Process p2 = pb2.start();

            // Pipe stdout of first process to stdin of second process
            Thread pipeThread = new Thread(() -> {
                try (
                        InputStream in = p1.getInputStream();
                        OutputStream out = p2.getOutputStream()) {

                    byte[] buffer = new byte[8192];
                    int len;

                    while ((len = in.read(buffer)) != -1) {
                        out.write(buffer, 0, len);
                        out.flush();
                    }

                    out.close();

                } catch (IOException ignored) {
                }
            });

            // Print stdout of second process
            Thread outputThread = new Thread(() -> {
                try (InputStream in = p2.getInputStream()) {

                    byte[] buffer = new byte[8192];
                    int len;

                    while ((len = in.read(buffer)) != -1) {
                        System.out.write(buffer, 0, len);
                        System.out.flush();
                    }

                } catch (IOException ignored) {
                }
            });

            pipeThread.start();
            outputThread.start();

            if (isBackground) {
                long pid = p2.pid();
                int jobNumber = getNextJobNumber();
                BackgroundJob job = new BackgroundJob(jobNumber, (int) pid, line);
                backgroundJobs.put(jobNumber, job);
                updateLatestJob(jobNumber);
                System.out.println("[" + jobNumber + "] " + pid);
                System.out.flush();

                // Monitor both processes in a separate thread
                new Thread(() -> {
                    try {
                        p1.waitFor();
                        pipeThread.join();
                        p2.waitFor();
                        outputThread.join();
                        synchronized (Main.class) {
                            job.status = "Done";
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }).start();
            } else {
                p1.waitFor();
                pipeThread.join();

                p2.waitFor();
                outputThread.join();
            }

        } catch (IOException e) {
            System.out.println("command not found");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Prints completed background jobs
    private static synchronized void printCompletedJobs() {
        List<Integer> completedJobNumbers = new ArrayList<>();

        for (Map.Entry<Integer, BackgroundJob> entry : backgroundJobs.entrySet()) {
            BackgroundJob job = entry.getValue();
            if ("Done".equals(job.status) && !job.notified) {
                String marker = job.isLatest ? "+" : " ";
                System.out.printf("[%d]%s  Done                 %s%n", job.jobNumber, marker, job.command);
                System.out.flush();
                job.notified = true;
                completedJobNumbers.add(entry.getKey());
            }
        }

        // Remove notified completed jobs
        for (Integer jobNumber : completedJobNumbers) {
            backgroundJobs.remove(jobNumber);
        }
    }

    // Parses command line while preserving quoted strings
    private static List<String> parseCommand(String command) {

        List<String> tokens = new ArrayList<>();

        Pattern pattern = Pattern.compile("'([^']*)'|\"([^\"]*)\"|(\\S+)");
        Matcher matcher = pattern.matcher(command);

        while (matcher.find()) {
            if (matcher.group(1) != null) {
                tokens.add(matcher.group(1));
            } else if (matcher.group(2) != null) {
                tokens.add(matcher.group(2));
            } else {
                tokens.add(matcher.group(3));
            }
        }

        return tokens;
    }

    static class BackgroundJob {
        int jobNumber;
        int pid;
        String command;
        String status;
        boolean notified;
        boolean isLatest;

        BackgroundJob(int jobNumber, int pid, String command) {
            this.jobNumber = jobNumber;
            this.pid = pid;
            this.command = command;
            this.status = "Running";
            this.notified = false;
            this.isLatest = false;
        }
    }
}
