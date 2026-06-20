import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {
    private static Map<Integer, BackgroundJob> backgroundJobs = new LinkedHashMap<>();
    private static int jobCounter = 1;
    private static final Set<String> BUILTINS = new HashSet<>(Arrays.asList(
        "echo", "type", "exit", "pwd", "cd", "jobs"
    ));

    public static void main(String[] args) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String line;

        System.out.print("$ ");
        System.out.flush();

        while ((line = reader.readLine()) != null) {
            if (line.trim().isEmpty()) {
                System.out.print("$ ");
                System.out.flush();
                continue;
            }
            executeCommand(line);
            
            // Check for completed jobs and print their status
            printCompletedJobs();
            
            System.out.print("$ ");
            System.out.flush();
        }
    }

    private static synchronized void printCompletedJobs() {
        List<Integer> completedJobNumbers = new ArrayList<>();
        
        for (Map.Entry<Integer, BackgroundJob> entry : backgroundJobs.entrySet()) {
            BackgroundJob job = entry.getValue();
            if ("Done".equals(job.status) && !job.notified) {
                System.out.println("[" + job.jobNumber + "]+  Done                 " + job.command);
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

    private static void executeCommand(String commandLine) {
        try {
            // Check for pipeline
            if (commandLine.contains("|")) {
                executePipeline(commandLine);
                return;
            }

            // Parse the command line
            CommandParsed parsed = parseCommand(commandLine);

            if (parsed == null) {
                return;
            }

            // Check if command should run in background
            boolean runInBackground = parsed.runInBackground;

            // Handle builtins
            if (!parsed.command.isEmpty()) {
                String firstCommand = parsed.command.get(0);

                if ("echo".equals(firstCommand)) {
                    handleEcho(parsed.command);
                    return;
                } else if ("type".equals(firstCommand)) {
                    handleType(parsed.command);
                    return;
                } else if ("exit".equals(firstCommand)) {
                    handleExit(parsed.command);
                    return;
                } else if ("pwd".equals(firstCommand)) {
                    handlePwd();
                    return;
                } else if ("cd".equals(firstCommand)) {
                    handleCd(parsed.command);
                    return;
                } else if ("jobs".equals(firstCommand)) {
                    handleJobs();
                    return;
                }
            }

            // Create ProcessBuilder for external commands
            ProcessBuilder pb = new ProcessBuilder(parsed.command);

            // Handle stdout redirection (> or >>)
            if (parsed.stdoutFile != null) {
                File outFile = new File(parsed.stdoutFile);
                ensureParentDirectory(outFile);
                if (parsed.stdoutAppend) {
                    pb.redirectOutput(ProcessBuilder.Redirect.appendTo(outFile));
                } else {
                    pb.redirectOutput(ProcessBuilder.Redirect.to(outFile));
                }
            }

            // Handle stderr redirection (2> or 2>>)
            if (parsed.stderrFile != null) {
                File errFile = new File(parsed.stderrFile);
                ensureParentDirectory(errFile);
                if (parsed.stderrAppend) {
                    pb.redirectError(ProcessBuilder.Redirect.appendTo(errFile));
                } else {
                    pb.redirectError(ProcessBuilder.Redirect.to(errFile));
                }
            }

            // Start the process
            Process process = pb.start();

            if (runInBackground) {
                // Register background job
                long pid = process.pid();
                int jobNumber = jobCounter++;
                BackgroundJob job = new BackgroundJob(jobNumber, (int) pid, String.join(" ", parsed.command));
                backgroundJobs.put(jobNumber, job);
                System.out.println("[" + jobNumber + "] " + pid);
                System.out.flush();

                // Start a thread to monitor the process
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
                // Wait for foreground process to complete
                process.waitFor();
            }

        } catch (Exception e) {
            System.err.println("Error executing command: " + e.getMessage());
        }
    }

    private static void executePipeline(String commandLine) {
        try {
            // Split by pipe
            String[] commands = commandLine.split("\\|");
            
            if (commands.length < 2) {
                System.err.println("Invalid pipeline");
                return;
            }

            // Parse each command in the pipeline
            List<CommandParsed> parsedCommands = new ArrayList<>();
            for (String cmd : commands) {
                CommandParsed parsed = parseCommand(cmd.trim());
                if (parsed != null) {
                    parsedCommands.add(parsed);
                }
            }

            if (parsedCommands.isEmpty()) {
                return;
            }

            // Create processes for each command
            List<Process> processes = new ArrayList<>();
            
            for (int i = 0; i < parsedCommands.size(); i++) {
                CommandParsed parsed = parsedCommands.get(i);
                ProcessBuilder pb = new ProcessBuilder(parsed.command);

                // Set up input redirection for non-first commands
                if (i > 0) {
                    pb.redirectInput(ProcessBuilder.Redirect.PIPE);
                }

                // Set up output redirection
                if (i < parsedCommands.size() - 1) {
                    // Not the last command - use PIPE for output
                    pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
                } else {
                    // Last command - handle output redirection if specified, otherwise inherit stdout
                    if (parsed.stdoutFile != null) {
                        File outFile = new File(parsed.stdoutFile);
                        ensureParentDirectory(outFile);
                        if (parsed.stdoutAppend) {
                            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(outFile));
                        } else {
                            pb.redirectOutput(ProcessBuilder.Redirect.to(outFile));
                        }
                    }
                    // If no redirection, output goes to parent's stdout (default behavior)
                }

                // Handle stderr redirection
                if (parsed.stderrFile != null) {
                    File errFile = new File(parsed.stderrFile);
                    ensureParentDirectory(errFile);
                    if (parsed.stderrAppend) {
                        pb.redirectError(ProcessBuilder.Redirect.appendTo(errFile));
                    } else {
                        pb.redirectError(ProcessBuilder.Redirect.to(errFile));
                    }
                }

                Process process = pb.start();
                processes.add(process);
            }

            // Connect the processes using pipes
            List<Thread> pipeThreads = new ArrayList<>();
            
            for (int i = 0; i < processes.size() - 1; i++) {
                Process currentProcess = processes.get(i);
                Process nextProcess = processes.get(i + 1);

                // Create a thread to copy output from current process to input of next process
                Thread pipeThread = new Thread(() -> {
                    try {
                        InputStream input = currentProcess.getInputStream();
                        OutputStream output = nextProcess.getOutputStream();
                        byte[] buffer = new byte[1024];
                        int bytesRead;
                        while ((bytesRead = input.read(buffer)) != -1) {
                            output.write(buffer, 0, bytesRead);
                            output.flush();
                        }
                        output.close();
                    } catch (IOException e) {
                        // Pipe closed, process ended
                    }
                });
                pipeThread.start();
                pipeThreads.add(pipeThread);
            }

            // For the last process, copy its output to our stdout
            Process lastProcess = processes.get(processes.size() - 1);
            Thread lastOutputThread = new Thread(() -> {
                try {
                    InputStream input = lastProcess.getInputStream();
                    OutputStream output = System.out;
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    while ((bytesRead = input.read(buffer)) != -1) {
                        output.write(buffer, 0, bytesRead);
                        output.flush();
                    }
                } catch (IOException e) {
                    // Stream closed
                }
            });
            lastOutputThread.start();

            // Wait for all processes to complete
            for (Process process : processes) {
                process.waitFor();
            }

            // Wait for all pipe threads to complete
            for (Thread thread : pipeThreads) {
                thread.join();
            }

            // Wait for the last output thread to complete
            lastOutputThread.join();

        } catch (Exception e) {
            System.err.println("Error executing pipeline: " + e.getMessage());
        }
    }

    private static void handleEcho(List<String> args) {
        // Skip the "echo" command itself
        List<String> echoArgs = args.subList(1, args.size());
        System.out.println(String.join(" ", echoArgs));
    }

    private static void handleType(List<String> args) {
        if (args.size() < 2) {
            System.err.println("type: missing argument");
            return;
        }

        String command = args.get(1);

        if (BUILTINS.contains(command)) {
            System.out.println(command + " is a shell builtin");
        } else {
            // Check if it's an external command in PATH
            String pathEnv = System.getenv("PATH");
            if (pathEnv != null) {
                String[] paths = pathEnv.split(":");
                for (String path : paths) {
                    File file = new File(path, command);
                    if (file.exists() && file.isFile() && file.canExecute()) {
                        System.out.println(command + " is " + file.getAbsolutePath());
                        return;
                    }
                }
            }
            System.out.println(command + ": not found");
        }
    }

    private static void handleExit(List<String> args) {
        int exitCode = 0;
        if (args.size() > 1) {
            try {
                exitCode = Integer.parseInt(args.get(1));
            } catch (NumberFormatException e) {
                exitCode = 1;
            }
        }
        System.exit(exitCode);
    }

    private static void handlePwd() {
        System.out.println(System.getProperty("user.dir"));
    }

    private static void handleCd(List<String> args) {
        String targetDir;
        if (args.size() < 2) {
            targetDir = System.getProperty("user.home");
        } else {
            targetDir = args.get(1);
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
        // Clean up completed jobs
        backgroundJobs.values().removeIf(job -> "Done".equals(job.status));

        // Print remaining jobs
        for (BackgroundJob job : backgroundJobs.values()) {
            System.out.println("[" + job.jobNumber + "]   " + job.status + "   " + job.command);
        }
    }

    private static void ensureParentDirectory(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            Files.createDirectories(parent.toPath());
        }
    }

    private static CommandParsed parseCommand(String commandLine) {
        CommandParsed parsed = new CommandParsed();

        // Check for background job (&)
        Pattern backgroundPattern = Pattern.compile("&\\s*$");
        Matcher backgroundMatcher = backgroundPattern.matcher(commandLine);
        if (backgroundMatcher.find()) {
            parsed.runInBackground = true;
            commandLine = commandLine.substring(0, backgroundMatcher.start()).trim();
        }

        // Handle 2>> first (must be before 2>)
        Pattern stderr2AppendPattern = Pattern.compile("2>>\\s+([^\\s]+)(?:\\s|$)");
        Matcher stderrMatcher = stderr2AppendPattern.matcher(commandLine);
        if (stderrMatcher.find()) {
            parsed.stderrFile = stderrMatcher.group(1);
            parsed.stderrAppend = true;
            commandLine = commandLine.substring(0, stderrMatcher.start()) + 
                         commandLine.substring(stderrMatcher.end());
        }

        // Handle 2> (redirect stderr, overwrite)
        Pattern stderr1Pattern = Pattern.compile("2>\\s+([^\\s]+)(?:\\s|$)");
        stderrMatcher = stderr1Pattern.matcher(commandLine);
        if (stderrMatcher.find()) {
            parsed.stderrFile = stderrMatcher.group(1);
            parsed.stderrAppend = false;
            commandLine = commandLine.substring(0, stderrMatcher.start()) + 
                         commandLine.substring(stderrMatcher.end());
        }

        // Handle >> (stdout append)
        Pattern stdoutAppendPattern = Pattern.compile(">>\\s+([^\\s]+)(?:\\s|$)");
        Matcher stdoutMatcher = stdoutAppendPattern.matcher(commandLine);
        if (stdoutMatcher.find()) {
            parsed.stdoutFile = stdoutMatcher.group(1);
            parsed.stdoutAppend = true;
            commandLine = commandLine.substring(0, stdoutMatcher.start()) + 
                         commandLine.substring(stdoutMatcher.end());
        }

        // Handle > (stdout overwrite)
        Pattern stdoutPattern = Pattern.compile(">\\s+([^\\s]+)(?:\\s|$)");
        stdoutMatcher = stdoutPattern.matcher(commandLine);
        if (stdoutMatcher.find()) {
            parsed.stdoutFile = stdoutMatcher.group(1);
            parsed.stdoutAppend = false;
            commandLine = commandLine.substring(0, stdoutMatcher.start()) + 
                         commandLine.substring(stdoutMatcher.end());
        }

        // Parse the remaining command
        commandLine = commandLine.trim();
        if (commandLine.isEmpty()) {
            return null;
        }

        parsed.command = parseCommandArguments(commandLine);
        return parsed;
    }

    private static List<String> parseCommandArguments(String commandLine) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < commandLine.length(); i++) {
            char c = commandLine.charAt(i);

            if (c == '"' || c == '\'') {
                inQuotes = !inQuotes;
            } else if (c == ' ' && !inQuotes) {
                if (current.length() > 0) {
                    args.add(current.toString());
                    current = new StringBuilder();
                }
            } else {
                current.append(c);
            }
        }

        if (current.length() > 0) {
            args.add(current.toString());
        }

        return args;
    }

    static class CommandParsed {
        List<String> command;
        String stdoutFile;
        boolean stdoutAppend;
        String stderrFile;
        boolean stderrAppend;
        boolean runInBackground;

        CommandParsed() {
            this.stdoutAppend = false;
            this.stderrAppend = false;
            this.runInBackground = false;
        }
    }

    static class BackgroundJob {
        int jobNumber;
        int pid;
        String command;
        String status;
        boolean notified;

        BackgroundJob(int jobNumber, int pid, String command) {
            this.jobNumber = jobNumber;
            this.pid = pid;
            this.command = command;
            this.status = "Running";
            this.notified = false;
        }
    }
}
