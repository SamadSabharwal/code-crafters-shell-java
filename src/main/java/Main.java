import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class Main {
    static class Job {
        int jobNumber;
        long pid;
        String command;
        Process process;
        String status; // "Running", "Done", etc.

        Job(int jobNumber, long pid, String command, Process process) {
            this.jobNumber = jobNumber;
            this.pid = pid;
            this.command = command;
            this.process = process;
            this.status = "Running";
        }
    }

    private static final List<Job> jobs = new ArrayList<>();
    private static int jobCounter = 0;

    public static void main(String[] args) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        while (true) {
            reapFinishedBackgroundJobs();

            System.out.print("$ ");
            System.out.flush();

            String line = reader.readLine();
            if (line == null) {
                break; // EOF
            }
            if (line.trim().isEmpty()) {
                continue;
            }

            List<String> tokens = tokenize(line);
            if (tokens.isEmpty()) {
                continue;
            }

            boolean background = false;
            if (tokens.get(tokens.size() - 1).equals("&")) {
                background = true;
                tokens.remove(tokens.size() - 1);
            }

            if (tokens.isEmpty()) {
                continue;
            }

            // Reconstruct the command string as typed (minus the trailing "&"),
            // used for the `jobs` listing.
            String commandStr = String.join(" ", tokens);
            if (background) {
                commandStr = commandStr + " &";
            }

            executeCommand(tokens, background, commandStr);
        }
    }

    private static void executeCommand(List<String> tokens, boolean background, String commandStr) {
        String cmd = tokens.get(0);

        // Built-ins always run in the foreground (in-process)
        switch (cmd) {
            case "exit":
                System.exit(tokens.size() > 1 ? Integer.parseInt(tokens.get(1)) : 0);
                return;
            case "cd":
                builtinCd(tokens);
                return;
            case "pwd":
                System.out.println(System.getProperty("user.dir"));
                return;
            case "echo":
                System.out.println(String.join(" ", tokens.subList(1, tokens.size())));
                return;
            case "type":
                builtinType(tokens);
                return;
            case "jobs":
                builtinJobs();
                return;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(tokens);
            pb.directory(new File(System.getProperty("user.dir")));

            // Inherit the shell's stdio streams so background jobs can still
            // print to the terminal.
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);

            Process process = pb.start();

            if (background) {
                jobCounter++;
                System.out.println("[" + jobCounter + "] " + process.pid());
                jobs.add(new Job(jobCounter, process.pid(), commandStr, process));
            } else {
                process.waitFor();
            }
        } catch (IOException e) {
            System.out.println(cmd + ": command not found");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void reapFinishedBackgroundJobs() {
        for (Job job : jobs) {
            if (job.process != null && !job.process.isAlive()) {
                job.status = "Done";
            }
        }
        // Keep jobs in the list so a final "Done" status could be reported
        // by `jobs` if needed in later stages; remove here only if your
        // stage requires immediate cleanup. For now we leave them tracked.
    }

    private static void builtinJobs() {
        for (int i = 0; i < jobs.size(); i++) {
            Job job = jobs.get(i);
            boolean isMostRecent = (i == jobs.size() - 1);
            String marker = isMostRecent ? "+" : "-";
            String status = job.status;
            String paddedStatus = String.format("%-24s", status);
            System.out.println("[" + job.jobNumber + "]" + marker + "  " + paddedStatus + job.command);
        }
    }

    private static void builtinCd(List<String> tokens) {
        String target = tokens.size() > 1 ? tokens.get(1) : System.getProperty("user.home");
        if (target.equals("~")) {
            target = System.getProperty("user.home");
        }
        File dir = new File(target);
        if (!dir.isAbsolute()) {
            dir = new File(System.getProperty("user.dir"), target);
        }
        try {
            dir = dir.getCanonicalFile();
        } catch (IOException e) {
            // ignore, fall through to existence check
        }
        if (dir.exists() && dir.isDirectory()) {
            System.setProperty("user.dir", dir.getAbsolutePath());
        } else {
            System.out.println("cd: " + target + ": No such file or directory");
        }
    }

    private static void builtinType(List<String> tokens) {
        if (tokens.size() < 2) return;
        String name = tokens.get(1);
        Set<String> builtins = Set.of("exit", "echo", "type", "pwd", "cd", "jobs");
        if (builtins.contains(name)) {
            System.out.println(name + " is a shell builtin");
            return;
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                File f = new File(dir, name);
                if (f.isFile() && f.canExecute()) {
                    System.out.println(name + " is " + f.getAbsolutePath());
                    return;
                }
            }
        }
        System.out.println(name + ": not found");
    }

    // Minimal tokenizer supporting single quotes, double quotes, and backslash escapes
    private static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false, inDouble = false;
        boolean tokenStarted = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (inSingle) {
                if (c == '\'') {
                    inSingle = false;
                } else {
                    current.append(c);
                }
            } else if (inDouble) {
                if (c == '"') {
                    inDouble = false;
                } else if (c == '\\' && i + 1 < line.length() &&
                        (line.charAt(i + 1) == '"' || line.charAt(i + 1) == '\\' || line.charAt(i + 1) == '$')) {
                    current.append(line.charAt(i + 1));
                    i++;
                } else {
                    current.append(c);
                }
            } else {
                if (c == '\'') {
                    inSingle = true;
                    tokenStarted = true;
                } else if (c == '"') {
                    inDouble = true;
                    tokenStarted = true;
                } else if (c == '\\' && i + 1 < line.length()) {
                    current.append(line.charAt(i + 1));
                    i++;
                    tokenStarted = true;
                } else if (Character.isWhitespace(c)) {
                    if (tokenStarted) {
                        tokens.add(current.toString());
                        current.setLength(0);
                        tokenStarted = false;
                    }
                } else if (c == '&') {
                    if (tokenStarted) {
                        tokens.add(current.toString());
                        current.setLength(0);
                        tokenStarted = false;
                    }
                    tokens.add("&");
                } else {
                    current.append(c);
                    tokenStarted = true;
                }
            }
        }
        if (tokenStarted) {
            tokens.add(current.toString());
        }
        return tokens;
    }
}