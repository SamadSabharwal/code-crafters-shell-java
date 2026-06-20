import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
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

    static class Redirection {
        String stdinFile;
        String stdoutFile;
        boolean stdoutAppend;
        String stderrFile;
        boolean stderrAppend;
    }

    private static final List<Job> jobs = new ArrayList<>();

    // Computes the next job number: 1 if the table is empty, otherwise one
    // more than the current highest job number in the table. This allows
    // numbers to be recycled once jobs finish and are removed.
    private static int nextJobNumber() {
        int max = 0;
        for (Job job : jobs) {
            if (job.jobNumber > max) {
                max = job.jobNumber;
            }
        }
        return max + 1;
    }

    public static void main(String[] args) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        while (true) {
            performReap(false);

            System.out.print("$ ");
            System.out.flush();

            String line = reader.readLine();
            if (line == null) break; // EOF
            if (line.trim().isEmpty()) continue;

            List<String> rawTokens = tokenize(line);
            if (rawTokens.isEmpty()) continue;

            boolean background = false;
            if (rawTokens.get(rawTokens.size() - 1).equals("&")) {
                background = true;
                rawTokens.remove(rawTokens.size() - 1);
            }
            if (rawTokens.isEmpty()) continue;

            // Base command string for job display (no trailing "&" here —
            // that's added conditionally in builtinJobs based on status).
            String commandStr = String.join(" ", rawTokens);

            Redirection redir = new Redirection();
            List<String> tokens = extractRedirection(rawTokens, redir);
            if (tokens.isEmpty()) continue;

            executeCommand(tokens, background, commandStr, redir);
        }
    }

    // Recognizes: <  >  1>  >>  1>>  2>  2>>
    private static List<String> extractRedirection(List<String> rawTokens, Redirection redir) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < rawTokens.size(); i++) {
            String tok = rawTokens.get(i);
            if (tok.equals("<")) {
                if (i + 1 < rawTokens.size()) redir.stdinFile = rawTokens.get(++i);
            } else if (tok.equals(">") || tok.equals("1>")) {
                if (i + 1 < rawTokens.size()) {
                    redir.stdoutFile = rawTokens.get(++i);
                    redir.stdoutAppend = false;
                }
            } else if (tok.equals(">>") || tok.equals("1>>")) {
                if (i + 1 < rawTokens.size()) {
                    redir.stdoutFile = rawTokens.get(++i);
                    redir.stdoutAppend = true;
                }
            } else if (tok.equals("2>")) {
                if (i + 1 < rawTokens.size()) {
                    redir.stderrFile = rawTokens.get(++i);
                    redir.stderrAppend = false;
                }
            } else if (tok.equals("2>>")) {
                if (i + 1 < rawTokens.size()) {
                    redir.stderrFile = rawTokens.get(++i);
                    redir.stderrAppend = true;
                }
            } else {
                result.add(tok);
            }
        }
        return result;
    }

    private static void executeCommand(List<String> tokens, boolean background,
                                        String commandStr, Redirection redir) {
        String cmd = tokens.get(0);

        switch (cmd) {
            case "exit":
                System.exit(tokens.size() > 1 ? Integer.parseInt(tokens.get(1)) : 0);
                return;
            case "cd":
                builtinCd(tokens);
                return;
            case "pwd":
                runBuiltinWithRedirection(redir, () -> System.out.println(System.getProperty("user.dir")));
                return;
            case "echo":
                runBuiltinWithRedirection(redir, () ->
                        System.out.println(String.join(" ", tokens.subList(1, tokens.size()))));
                return;
            case "type":
                runBuiltinWithRedirection(redir, () -> builtinType(tokens));
                return;
            case "jobs":
                runBuiltinWithRedirection(redir, Main::builtinJobs);
                return;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(tokens);
            pb.directory(new File(System.getProperty("user.dir")));

            if (redir.stdinFile != null) {
                pb.redirectInput(new File(redir.stdinFile));
            } else {
                pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            }

            if (redir.stdoutFile != null) {
                File f = new File(redir.stdoutFile);
                pb.redirectOutput(redir.stdoutAppend
                        ? ProcessBuilder.Redirect.appendTo(f)
                        : ProcessBuilder.Redirect.to(f));
            } else {
                pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            }

            if (redir.stderrFile != null) {
                File f = new File(redir.stderrFile);
                pb.redirectError(redir.stderrAppend
                        ? ProcessBuilder.Redirect.appendTo(f)
                        : ProcessBuilder.Redirect.to(f));
            } else {
                pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            }

            Process process = pb.start();

            if (background) {
                int jobNumber = nextJobNumber();
                System.out.println("[" + jobNumber + "] " + process.pid());
                jobs.add(new Job(jobNumber, process.pid(), commandStr, process));
            } else {
                process.waitFor();
            }
        } catch (IOException e) {
            System.out.println(cmd + ": command not found");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void runBuiltinWithRedirection(Redirection redir, Runnable body) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        PrintStream outStream = null;
        PrintStream errStream = null;

        try {
            if (redir.stdoutFile != null) {
                outStream = new PrintStream(new FileOutputStream(redir.stdoutFile, redir.stdoutAppend));
                System.setOut(outStream);
            }
            if (redir.stderrFile != null) {
                errStream = new PrintStream(new FileOutputStream(redir.stderrFile, redir.stderrAppend));
                System.setErr(errStream);
            }
            body.run();
        } catch (FileNotFoundException e) {
            originalOut.println("bash: " +
                    (redir.stdoutFile != null ? redir.stdoutFile : redir.stderrFile) +
                    ": No such file or directory");
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
            if (outStream != null) outStream.close();
            if (errStream != null) errStream.close();
        }
    }

    // Shared reap logic used both before each prompt and by the `jobs`
    // builtin.
    //
    // 1. Refresh status: mark any finished process as "Done".
    // 2. Compute markers (+/-/space) against the FULL current list, so a
    //    job about to be removed still gets the correct marker for this
    //    display.
    // 3. Print: either every job (printAllJobs == true, used by `jobs`),
    //    or only the ones that just became Done (printAllJobs == false,
    //    used for automatic reaping before the prompt).
    // 4. Remove any Done job from the table so it's never shown again.
    private static void performReap(boolean printAllJobs) {
        for (Job job : jobs) {
            if (job.process != null && !job.process.isAlive()) {
                job.status = "Done";
            }
        }

        int lastIndex = jobs.size() - 1;
        int secondLastIndex = jobs.size() - 2;

        for (int i = 0; i < jobs.size(); i++) {
            Job job = jobs.get(i);
            String marker;
            if (i == lastIndex) {
                marker = "+";
            } else if (i == secondLastIndex) {
                marker = "-";
            } else {
                marker = " ";
            }

            boolean isDone = job.status.equals("Done");
            if (printAllJobs || isDone) {
                String paddedStatus = String.format("%-24s", job.status);
                // Running jobs show the trailing "&" (as typed); Done jobs don't.
                String displayCommand = job.status.equals("Running") ? job.command + " &" : job.command;
                System.out.println("[" + job.jobNumber + "]" + marker + "  " + paddedStatus + displayCommand);
            }
        }

        // Reap: remove any job that was just reported as Done so it never
        // appears again, whether displayed here or on a future call.
        jobs.removeIf(job -> job.status.equals("Done"));
    }

    private static void builtinJobs() {
        performReap(true);
    }

    private static void builtinCd(List<String> tokens) {
        // Resolve HOME from the environment, not Java's user.home system
        // property, since the tester sets HOME to a directory that may
        // differ from the OS account's actual home directory.
        String home = System.getenv("HOME");
        if (home == null) {
            home = System.getProperty("user.home");
        }

        String target = tokens.size() > 1 ? tokens.get(1) : home;
        if (target.equals("~")) {
            target = home;
        } else if (target.startsWith("~/")) {
            target = home + target.substring(1);
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

    // Tokenizer supporting single quotes, double quotes, backslash escapes,
    // and redirection operators: < > 1> >> 1>> 2> 2>>
    private static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false, inDouble = false;
        boolean tokenStarted = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (inSingle) {
                if (c == '\'') inSingle = false;
                else current.append(c);
                continue;
            }
            if (inDouble) {
                if (c == '"') {
                    inDouble = false;
                } else if (c == '\\' && i + 1 < line.length() &&
                        (line.charAt(i + 1) == '"' || line.charAt(i + 1) == '\\' || line.charAt(i + 1) == '$')) {
                    current.append(line.charAt(i + 1));
                    i++;
                } else {
                    current.append(c);
                }
                continue;
            }

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
            } else if (c == '<') {
                if (tokenStarted) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    tokenStarted = false;
                }
                tokens.add("<");
            } else if (c == '>') {
                String fdPrefix = "";
                if (tokenStarted && current.toString().matches("[12]")) {
                    fdPrefix = current.toString();
                    current.setLength(0);
                    tokenStarted = false;
                } else if (tokenStarted) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    tokenStarted = false;
                }
                if (i + 1 < line.length() && line.charAt(i + 1) == '>') {
                    tokens.add(fdPrefix + ">>");
                    i++;
                } else {
                    tokens.add(fdPrefix + ">");
                }
            } else {
                current.append(c);
                tokenStarted = true;
            }
        }
        if (tokenStarted) tokens.add(current.toString());
        return tokens;
    }
}