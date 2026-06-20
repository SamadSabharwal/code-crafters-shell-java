import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;

public class Main {

    // ---------- JOB TRACKING ----------
    static class Job {
        int number;
        long pid;
        String command;
        Process process;
        Job(int number, long pid, String command, Process process) {
            this.number = number;
            this.pid = pid;
            this.command = command;
            this.process = process;
        }
    }
    static List<Job> jobList = new ArrayList<>();

    private static final Set<String> BUILTINS = new HashSet<>(Arrays.asList("echo", "exit", "type", "jobs", "pwd", "cd"));

    private static String findExecutable(String cmd) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(File.pathSeparator)) {
            File f = new File(dir, cmd);
            if (f.exists() && f.canExecute()) return f.getAbsolutePath();
        }
        return null;
    }

    private static List<String> parse(String input) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean sq = false, dq = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (sq) {
                if (c == '\'') sq = false;
                else cur.append(c);
                continue;
            }
            if (dq) {
                if (c == '"') { dq = false; continue; }
                if (c == '\\' && i + 1 < input.length()) {
                    char n = input.charAt(i + 1);
                    if (n == '"' || n == '\\' || n == '$' || n == '`') {
                        cur.append(n); i++; continue;
                    }
                }
                cur.append(c);
                continue;
            }

            if (c == '\'') { sq = true; continue; }
            if (c == '"')  { dq = true; continue; }
            if (c == '\\' && i + 1 < input.length()) {
                cur.append(input.charAt(i + 1)); i++; continue;
            }
            if (Character.isWhitespace(c)) {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
                continue;
            }
            cur.append(c);
        }

        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static List<List<String>> splitPipe(List<String> parts) {
        List<List<String>> segments = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        for (String t : parts) {
            if (t.equals("|")) {
                segments.add(cur);
                cur = new ArrayList<>();
            } else {
                cur.add(t);
            }
        }
        segments.add(cur);
        return segments;
    }

    static class R {
        String outFile = null, errFile = null;
        boolean appendOut = false, appendErr = false;
        boolean background = false;
        List<String> cmd = new ArrayList<>();
    }

    private static R extract(List<String> parts) {
        R r = new R();

        if (!parts.isEmpty() && parts.get(parts.size() - 1).equals("&")) {
            r.background = true;
            parts = new ArrayList<>(parts);
            parts.remove(parts.size() - 1);
        }

        for (int i = 0; i < parts.size(); i++) {
            String t = parts.get(i);
            switch (t) {
                case ">":  case "1>":  r.outFile = parts.get(++i); r.appendOut = false; break;
                case ">>": case "1>>": r.outFile = parts.get(++i); r.appendOut = true;  break;
                case "2>":             r.errFile = parts.get(++i); r.appendErr = false; break;
                case "2>>":            r.errFile = parts.get(++i); r.appendErr = true;  break;
                default: r.cmd.add(t);
            }
        }
        return r;
    }

    private static void ensureDir(String filePath) {
        File parent = new File(filePath).getParentFile();
        if (parent != null) parent.mkdirs();
    }

    private static void touchFile(String filePath, boolean append) throws Exception {
        ensureDir(filePath);
        try (FileOutputStream fos = new FileOutputStream(filePath, append)) {}
    }

    private static void writeOutput(R r, String text) throws Exception {
        if (r.outFile == null) {
            System.out.print(text);
        } else {
            ensureDir(r.outFile);
            try (FileOutputStream fos = new FileOutputStream(r.outFile, r.appendOut)) {
                fos.write(text.getBytes());
            }
        }
    }

    private static void writeError(R r, String text) throws Exception {
        if (r.errFile == null) {
            System.err.print(text);
        } else {
            ensureDir(r.errFile);
            try (FileOutputStream fos = new FileOutputStream(r.errFile, r.appendErr)) {
                fos.write(text.getBytes());
            }
        }
    }

    private static void reapJobs() {
        List<Job> toRemove = new ArrayList<>();
        for (int i = 0; i < jobList.size(); i++) {
            Job j = jobList.get(i);
            if (!j.process.isAlive()) {
                String marker;
                if (i == jobList.size() - 1)      marker = "+";
                else if (i == jobList.size() - 2)  marker = "-";
                else                               marker = " ";
                String cmdNoBg = j.command.endsWith(" &")
                    ? j.command.substring(0, j.command.length() - 2)
                    : j.command;
                String status = String.format("%-24s", "Done");
                System.out.print("[" + j.number + "]" + marker + "  " + status + cmdNoBg + "\n");
                System.out.flush();
                toRemove.add(j);
            }
        }
        jobList.removeAll(toRemove);
    }

    private static int nextJobNumber() {
        Set<Integer> used = new HashSet<>();
        for (Job j : jobList) used.add(j.number);
        int n = 1;
        while (used.contains(n)) n++;
        return n;
    }

    private static String homeDir() {
        String home = System.getenv("HOME");
        return (home != null) ? home : System.getProperty("user.home");
    }

    private static void runBuiltin(R r, InputStream in, OutputStream out) throws Exception {
        PrintStream ps = new PrintStream(out, true);
        String c = r.cmd.get(0);

        if (c.equals("echo")) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < r.cmd.size(); i++) {
                if (i > 1) sb.append(" ");
                sb.append(r.cmd.get(i));
            }
            ps.println(sb.toString());
        } else if (c.equals("type")) {
            String t = r.cmd.size() > 1 ? r.cmd.get(1) : "";
            String res = BUILTINS.contains(t)
                ? t + " is a shell builtin"
                : (findExecutable(t) != null
                    ? t + " is " + findExecutable(t)
                    : t + ": not found");
            ps.println(res);
        } else if (c.equals("pwd")) {
            ps.println(System.getProperty("user.dir"));
        } else if (c.equals("jobs")) {
            for (int i = 0; i < jobList.size(); i++) {
                Job j = jobList.get(i);
                String marker;
                if (i == jobList.size() - 1)      marker = "+";
                else if (i == jobList.size() - 2)  marker = "-";
                else                               marker = " ";
                String status = String.format("%-24s", "Running");
                ps.println("[" + j.number + "]" + marker + "  " + status + j.command);
            }
        } else if (c.equals("cd")) {
            String dir = r.cmd.size() > 1 ? r.cmd.get(1) : homeDir();
            if (dir.equals("~")) dir = homeDir();
            File f = new File(dir);
            if (!f.isAbsolute()) f = new File(System.getProperty("user.dir"), dir);
            if (f.exists() && f.isDirectory()) {
                System.setProperty("user.dir", f.getCanonicalPath());
            } else {
                System.err.print("cd: " + dir + ": No such file or directory\n");
            }
        }
    }

    private static void runPipeline(List<List<String>> segments) throws Exception {
        int n = segments.size();

        R[] rs = new R[n];
        for (int i = 0; i < n; i++) {
            rs[i] = extract(new ArrayList<>(segments.get(i)));
        }

        InputStream currentInput = null;
        List<Thread> threads = new ArrayList<>();
        List<Process> processes = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            R r = rs[i];
            boolean isLast = (i == n - 1);

            if (r.cmd.isEmpty()) continue;
            String cmd = r.cmd.get(0);
            boolean isBuiltin = BUILTINS.contains(cmd);

            if (isBuiltin) {
                PipedOutputStream pipedOut = null;
                PipedInputStream pipedIn = null;

                OutputStream outStream;
                if (isLast) {
                    if (r.outFile != null) {
                        ensureDir(r.outFile);
                        outStream = new FileOutputStream(r.outFile, r.appendOut);
                    } else {
                        outStream = System.out;
                    }
                } else {
                    pipedOut = new PipedOutputStream();
                    pipedIn = new PipedInputStream(pipedOut);
                    outStream = pipedOut;
                }

                final InputStream stdinForBuiltin = currentInput;
                final OutputStream stdoutForBuiltin = outStream;
                final R rFinal = r;
                final PipedOutputStream pipedOutFinal = pipedOut;

                Thread t = new Thread(() -> {
                    try {
                        runBuiltin(rFinal, stdinForBuiltin, stdoutForBuiltin);
                        if (pipedOutFinal != null) pipedOutFinal.close();
                        else if (stdoutForBuiltin != System.out) stdoutForBuiltin.close();
                    } catch (Exception e) {
                        // ignore
                    }
                });
                t.start();
                threads.add(t);

                currentInput = pipedIn;

            } else {
                String exe = findExecutable(cmd);
                if (exe == null) {
                    System.err.print(cmd + ": command not found\n");
                    return;
                }

                ProcessBuilder pb = new ProcessBuilder(new ArrayList<>(r.cmd));

                if (currentInput == null) {
                    pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                } else {
                    pb.redirectInput(ProcessBuilder.Redirect.PIPE);
                }

                if (isLast) {
                    if (r.outFile != null) {
                        ensureDir(r.outFile);
                        pb.redirectOutput(r.appendOut
                            ? ProcessBuilder.Redirect.appendTo(new File(r.outFile))
                            : ProcessBuilder.Redirect.to(new File(r.outFile)));
                    } else {
                        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                    }
                } else {
                    pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
                }

                if (isLast && r.errFile != null) {
                    ensureDir(r.errFile);
                    pb.redirectError(r.appendErr
                        ? ProcessBuilder.Redirect.appendTo(new File(r.errFile))
                        : ProcessBuilder.Redirect.to(new File(r.errFile)));
                } else {
                    pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                }

                Process p = pb.start();
                processes.add(p);

                if (currentInput != null) {
                    final InputStream src = currentInput;
                    final OutputStream dst = p.getOutputStream();
                    Thread feeder = new Thread(() -> {
                        try {
                            byte[] buf = new byte[4096];
                            int len;
                            while ((len = src.read(buf)) != -1) {
                                dst.write(buf, 0, len);
                                dst.flush();
                            }
                            dst.close();
                        } catch (IOException e) {
                            // pipe broken
                        }
                    });
                    feeder.setDaemon(true);
                    feeder.start();
                }

                currentInput = isLast ? null : p.getInputStream();
            }
        }

        for (Thread t : threads) t.join();
        for (Process p : processes) p.waitFor();
    }

    private static void runExternal(R r) throws Exception {
        String exe = findExecutable(r.cmd.get(0));
        if (exe == null) {
            writeError(r, r.cmd.get(0) + ": command not found\n");
            return;
        }

        ProcessBuilder pb = new ProcessBuilder(new ArrayList<>(r.cmd));
        pb.redirectInput(ProcessBuilder.Redirect.INHERIT);

        if (r.outFile != null) {
            ensureDir(r.outFile);
            pb.redirectOutput(r.appendOut
                ? ProcessBuilder.Redirect.appendTo(new File(r.outFile))
                : ProcessBuilder.Redirect.to(new File(r.outFile)));
        } else {
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        }

        if (r.errFile != null) {
            ensureDir(r.errFile);
            pb.redirectError(r.appendErr
                ? ProcessBuilder.Redirect.appendTo(new File(r.errFile))
                : ProcessBuilder.Redirect.to(new File(r.errFile)));
        } else {
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        }

        Process p = pb.start();

        if (r.background) {
            int jobNum = nextJobNumber();
            String cmdStr = String.join(" ", r.cmd) + " &";
            jobList.add(new Job(jobNum, p.pid(), cmdStr, p));
            System.out.println("[" + jobNum + "] " + p.pid());
            System.out.flush();
        } else {
            p.waitFor();
        }
    }

    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);

        while (true) {
            reapJobs();
            System.out.print("$ ");
            System.out.flush();

            if (!sc.hasNextLine()) break;
            String input = sc.nextLine();

            List<String> parts = parse(input);
            if (parts.isEmpty()) continue;

            if (parts.contains("|")) {
                List<List<String>> segments = splitPipe(parts);
                runPipeline(segments);
                continue;
            }

            R r = extract(parts);
            if (r.cmd.isEmpty()) continue;

            String c = r.cmd.get(0);

            if (c.equals("exit")) break;

            if (c.equals("echo")) {
                if (r.errFile != null) touchFile(r.errFile, r.appendErr);
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i < r.cmd.size(); i++) {
                    if (i > 1) sb.append(" ");
                    sb.append(r.cmd.get(i));
                }
                writeOutput(r, sb.toString() + "\n");
                continue;
            }

            if (c.equals("type")) {
                if (r.errFile != null) touchFile(r.errFile, r.appendErr);
                String t = r.cmd.size() > 1 ? r.cmd.get(1) : "";
                String res = BUILTINS.contains(t)
                    ? t + " is a shell builtin\n"
                    : (findExecutable(t) != null
                        ? t + " is " + findExecutable(t) + "\n"
                        : t + ": not found\n");
                writeOutput(r, res);
                continue;
            }

            if (c.equals("pwd")) {
                if (r.errFile != null) touchFile(r.errFile, r.appendErr);
                writeOutput(r, System.getProperty("user.dir") + "\n");
                continue;
            }

            if (c.equals("cd")) {
                String dir = r.cmd.size() > 1 ? r.cmd.get(1) : homeDir();
                if (dir.equals("~")) dir = homeDir();
                File f = new File(dir);
                if (!f.isAbsolute()) f = new File(System.getProperty("user.dir"), dir);
                if (f.exists() && f.isDirectory()) {
                    System.setProperty("user.dir", f.getCanonicalPath());
                } else {
                    System.err.print("cd: " + dir + ": No such file or directory\n");
                }
                continue;
            }

            if (.equals("jobs")) {
                if (r.errFile != null) touchFile(r.errFile, r.appendErr);
                if (r.outFile != null) touchFile(r.outFile, r.appendOut);

                List<Job> toRemove = new ArrayList<>();
                for (int i = 0; i < jobList.size(); i++) {
                    Job j = jobList.get(i);
                    String marker;
                    if (i == jobList.size() - 1)      marker = "+";
                    else if (i == jobList.size() - 2)  marker = "-";
                    else                               marker = " ";

                    if (!j.process.isAlive()) {
                        String cmdNoBg = j.command.endsWith(" &")
                            ? j.command.substring(0, j.command.length() - 2)
                            : j.command;
                        String status = String.format("%-24s", "Done");
                        writeOutput(r, "[" + j.number + "]" + marker + "  " + status + cmdNoBg + "\n");
                        toRemove.add(j);
                    } else {
                        String status = String.format("%-24s", "Running");
                        writeOutput(r, "[" + j.number + "]" + marker + "  " + status + j.command + "\n");
                    }
                }
                jobList.removeAll(toRemove);
                continue;
            }

            runExternal(r);
        }

        sc.close();
    }
}