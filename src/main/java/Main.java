import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class Main {
    private static final Set<String> BUILTINS = Set.of("exit", "echo", "type", "pwd", "cd");

    public static void main(String[] args) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        while (true) {
            System.out.print("$ ");
            String line = reader.readLine();
            if (line == null) break; // EOF (Ctrl+D)

            if (line.isBlank()) continue;

            List<String> tokens;
            try {
                tokens = tokenize(line);
            } catch (IllegalArgumentException e) {
                System.out.println(e.getMessage());
                continue;
            }
            if (tokens.isEmpty()) continue;

            ParsedCommand cmd;
            try {
                cmd = parseRedirection(tokens);
            } catch (IllegalArgumentException e) {
                System.out.println(e.getMessage());
                continue;
            }
            if (cmd.args.isEmpty()) continue;

            try {
                execute(cmd);
            } catch (Exception e) {
                System.err.println(cmd.args.get(0) + ": " + e.getMessage());
            }
        }
    }

    // =========================================================
    // Tokenizer — handles single quotes, double quotes, backslash
    // escapes, and splits out the redirection operators > / 1> / 2>
    // =========================================================
    private static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;
        boolean hasToken = false;

        int i = 0;
        int n = line.length();

        while (i < n) {
            char c = line.charAt(i);

            if (inSingleQuotes) {
                if (c == '\'') {
                    inSingleQuotes = false;
                } else {
                    current.append(c);
                }
                i++;
                continue;
            }

            if (inDoubleQuotes) {
                if (c == '"') {
                    inDoubleQuotes = false;
                } else if (c == '\\' && i + 1 < n && isDoubleQuoteEscapable(line.charAt(i + 1))) {
                    current.append(line.charAt(i + 1));
                    i++;
                } else {
                    current.append(c);
                }
                i++;
                continue;
            }

            switch (c) {
                case '\'' -> {
                    inSingleQuotes = true;
                    hasToken = true;
                    i++;
                }
                case '"' -> {
                    inDoubleQuotes = true;
                    hasToken = true;
                    i++;
                }
                case '\\' -> {
                    if (i + 1 < n) {
                        current.append(line.charAt(i + 1));
                        hasToken = true;
                        i += 2;
                    } else {
                        i++;
                    }
                }
                case ' ', '\t' -> {
                    if (hasToken) {
                        tokens.add(current.toString());
                        current.setLength(0);
                        hasToken = false;
                    }
                    i++;
                }
                case '>' -> {
                    // Distinguish "1>"/"2>" (no space before '>') from a
                    // literal argument "1"/"2" followed by a separate ">".
                    String currentStr = current.toString();
                    if (hasToken && (currentStr.equals("1") || currentStr.equals("2"))) {
                        current.setLength(0);
                        hasToken = false;
                        tokens.add(currentStr + ">");
                    } else {
                        if (hasToken) {
                            tokens.add(current.toString());
                            current.setLength(0);
                            hasToken = false;
                        }
                        tokens.add(">");
                    }
                    i++;
                }
                default -> {
                    current.append(c);
                    hasToken = true;
                    i++;
                }
            }
        }

        if (inSingleQuotes || inDoubleQuotes) {
            throw new IllegalArgumentException("syntax error: unterminated quote");
        }
        if (hasToken) {
            tokens.add(current.toString());
        }

        return tokens;
    }

    private static boolean isDoubleQuoteEscapable(char c) {
        return c == '\\' || c == '"' || c == '$' || c == '`' || c == '\n';
    }

    // =========================================================
    // Redirection parsing
    // =========================================================
    private static class ParsedCommand {
        List<String> args;
        String stdoutFile;
        String stderrFile;

        ParsedCommand(List<String> args, String stdoutFile, String stderrFile) {
            this.args = args;
            this.stdoutFile = stdoutFile;
            this.stderrFile = stderrFile;
        }
    }

    private static ParsedCommand parseRedirection(List<String> tokens) {
        List<String> args = new ArrayList<>();
        String stdoutFile = null;
        String stderrFile = null;

        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (token.equals(">") || token.equals("1>")) {
                if (i + 1 >= tokens.size()) {
                    throw new IllegalArgumentException("syntax error near unexpected token `newline'");
                }
                stdoutFile = tokens.get(i + 1);
                i++; // consume the filename token too
            } else if (token.equals("2>")) {
                if (i + 1 >= tokens.size()) {
                    throw new IllegalArgumentException("syntax error near unexpected token `newline'");
                }
                stderrFile = tokens.get(i + 1);
                i++;
            } else {
                args.add(token);
            }
        }

        return new ParsedCommand(args, stdoutFile, stderrFile);
    }

    // =========================================================
    // Execution
    // =========================================================
    private static void execute(ParsedCommand cmd) throws Exception {
        String name = cmd.args.get(0);
        if (BUILTINS.contains(name)) {
            runBuiltin(cmd);
        } else {
            runExternal(cmd);
        }
    }

    private static void runBuiltin(ParsedCommand cmd) throws Exception {
        PrintStream out = System.out;
        PrintStream err = System.err;
        FileOutputStream outFos = null;
        FileOutputStream errFos = null;

        try {
            if (cmd.stdoutFile != null) {
                outFos = new FileOutputStream(resolvePath(cmd.stdoutFile)); // create/truncate, like '>'
                out = new PrintStream(outFos);
            }
            if (cmd.stderrFile != null) {
                errFos = new FileOutputStream(resolvePath(cmd.stderrFile)); // create/truncate, like '2>'
                err = new PrintStream(errFos);
            }

            String name = cmd.args.get(0);
            switch (name) {
                case "exit" -> {
                    int code = cmd.args.size() > 1 ? Integer.parseInt(cmd.args.get(1)) : 0;
                    System.exit(code);
                }
                case "echo" -> out.println(String.join(" ", cmd.args.subList(1, cmd.args.size())));
                case "pwd" -> out.println(System.getProperty("user.dir"));
                case "cd" -> handleCd(cmd.args.size() > 1 ? cmd.args.get(1) : System.getenv("HOME"), err);
                case "type" -> handleType(cmd.args.size() > 1 ? cmd.args.get(1) : "", out, err);
            }
        } finally {
            if (outFos != null) outFos.close();
            if (errFos != null) errFos.close();
        }
    }

    private static void handleCd(String path, PrintStream err) {
        if (path == null || path.equals("~")) {
            path = System.getenv("HOME");
        }

        File dir = new File(path);
        if (!dir.isAbsolute()) {
            dir = new File(System.getProperty("user.dir"), path);
        }

        try {
            dir = dir.getCanonicalFile();
        } catch (IOException e) {
            err.println("cd: " + path + ": No such file or directory");
            return;
        }

        if (!dir.exists() || !dir.isDirectory()) {
            err.println("cd: " + path + ": No such file or directory");
            return;
        }

        System.setProperty("user.dir", dir.getAbsolutePath());
    }

    private static void handleType(String name, PrintStream out, PrintStream err) {
        if (BUILTINS.contains(name)) {
            out.println(name + " is a shell builtin");
            return;
        }

        String path = findExecutable(name);
        if (path != null) {
            out.println(name + " is " + path);
        } else {
            err.println(name + ": not found");
        }
    }

    private static String findExecutable(String name) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;

        for (String dir : pathEnv.split(File.pathSeparator)) {
            File candidate = new File(dir, name);
            if (candidate.isFile() && candidate.canExecute()) {
                return candidate.getAbsolutePath();
            }
        }
        return null;
    }

    private static File resolvePath(String path) {
        File f = new File(path);
        if (!f.isAbsolute()) {
            f = new File(System.getProperty("user.dir"), path);
        }
        return f;
    }

    private static void runExternal(ParsedCommand cmd) throws IOException, InterruptedException {
        String executable = findExecutable(cmd.args.get(0));
        if (executable == null) {
            PrintStream err = System.err;
            FileOutputStream errFos = null;
            try {
                if (cmd.stderrFile != null) {
                    errFos = new FileOutputStream(resolvePath(cmd.stderrFile));
                    err = new PrintStream(errFos);
                }
                err.println(cmd.args.get(0) + ": command not found");
            } finally {
                if (errFos != null) errFos.close();
            }
            return;
        }

        ProcessBuilder pb = new ProcessBuilder(cmd.args);
        pb.directory(new File(System.getProperty("user.dir")));
        pb.redirectInput(ProcessBuilder.Redirect.INHERIT);

        if (cmd.stdoutFile != null) {
            pb.redirectOutput(resolvePath(cmd.stdoutFile)); // create/truncate, like '>'
        } else {
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        }

        if (cmd.stderrFile != null) {
            pb.redirectError(resolvePath(cmd.stderrFile)); // create/truncate, like '2>'
        } else {
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        }

        Process process = pb.start();
        process.waitFor();
    }
}