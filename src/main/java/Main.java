import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;

public class Main {

    static Set<String> builtins = new HashSet<>(
            Arrays.asList("echo", "exit", "type", "pwd", "cd")
    );

    static String currentDir = System.getProperty("user.dir");

    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");

            if (!sc.hasNextLine()) break;

            String input = sc.nextLine();
            List<String> parts = parse(input);

            if (parts.isEmpty()) continue;

            // ================= REDIRECTION CHECK =================
            int redirectIndex = -1;
            String outputFile = null;

            for (int i = 0; i < parts.size(); i++) {
                if (parts.get(i).equals(">") || parts.get(i).equals("1>")) {
                    redirectIndex = i;
                    outputFile = parts.get(i + 1);
                    break;
                }
            }

            List<String> commandParts;
            if (redirectIndex != -1) {
                commandParts = parts.subList(0, redirectIndex);
            } else {
                commandParts = parts;
            }

            if (commandParts.isEmpty()) continue;

            String rawCommand = commandParts.get(0);
            String command = stripQuotes(rawCommand);

            // ================= EXIT =================
            if (command.equals("exit")) {
                System.exit(0);
            }

            // ================= TYPE =================
            if (command.equals("type")) {
                if (commandParts.size() < 2) continue;

                String target = commandParts.get(1);

                if (builtins.contains(target)) {
                    writeOutput(target + " is a shell builtin", outputFile);
                } else {
                    String path = findExecutable(target);
                    if (path != null) {
                        writeOutput(target + " is " + path, outputFile);
                    } else {
                        writeOutput(target + ": not found", outputFile);
                    }
                }
                continue;
            }

            // ================= PWD =================
            if (command.equals("pwd")) {
                writeOutput(currentDir, outputFile);
                continue;
            }

            // ================= CD =================
            if (command.equals("cd")) {
                if (commandParts.size() < 2) continue;

                String target = commandParts.get(1);
                String newPath;

                String home = System.getenv("HOME");

                if (target.equals("~")) {
                    newPath = home;
                } else if (target.startsWith("~/")) {
                    newPath = home + target.substring(1);
                } else if (target.startsWith("/")) {
                    newPath = target;
                } else {
                    newPath = resolvePath(currentDir, target);
                }

                File dir = new File(newPath);

                if (dir.exists() && dir.isDirectory()) {
                    currentDir = dir.getAbsolutePath();
                } else {
                    writeOutput("cd: " + target + ": No such file or directory", outputFile);
                }
                continue;
            }

            // ================= EXECUTION =================
            String execName = stripQuotes(rawCommand);

            String execPath = findExecutable(execName);

            if (execPath == null) {
                writeOutput(command + ": not found", outputFile);
                continue;
            }

            try {
                List<String> cmd = new ArrayList<>();
                cmd.add(execName);

                for (int i = 1; i < commandParts.size(); i++) {
                    cmd.add(commandParts.get(i));
                }

                ProcessBuilder pb = new ProcessBuilder(cmd);

                Process p = pb.start();

                // READ STDOUT
                BufferedReader br = new BufferedReader(
                        new InputStreamReader(p.getInputStream())
                );

                StringBuilder sb = new StringBuilder();
                String line;

                while ((line = br.readLine()) != null) {
                    sb.append(line).append("\n");
                }

                p.waitFor();

                writeOutput(sb.toString().trim(), outputFile);

            } catch (Exception e) {
                writeOutput(command + ": not found", outputFile);
            }
        }

        sc.close();
    }

    // ================= WRITE OUTPUT (REDIRECTION HANDLER) =================
    static void writeOutput(String text, String file) throws Exception {
        if (file == null) {
            System.out.println(text);
        } else {
            FileWriter fw = new FileWriter(file);
            fw.write(text);
            fw.close();
        }
    }

    // ================= REMOVE QUOTES =================
    static String stripQuotes(String s) {
        if (s == null) return null;

        if (s.length() >= 2) {
            if ((s.startsWith("'") && s.endsWith("'")) ||
                (s.startsWith("\"") && s.endsWith("\""))) {
                return s.substring(1, s.length() - 1);
            }
        }

        return s;
    }

    // ================= PARSER =================
    static List<String> parse(String input) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        boolean inSingle = false;
        boolean inDouble = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                continue;
            }

            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                continue;
            }

            if (c == '\\' && !inSingle && !inDouble) {
                if (i + 1 < input.length()) {
                    i++;
                    current.append(input.charAt(i));
                }
                continue;
            }

            if (inDouble && c == '\\') {
                if (i + 1 < input.length()) {
                    char next = input.charAt(i + 1);
                    if (next == '"' || next == '\\') {
                        current.append(next);
                        i++;
                        continue;
                    } else {
                        current.append('\\');
                        continue;
                    }
                }
                current.append('\\');
                continue;
            }

            if (!inSingle && !inDouble && Character.isWhitespace(c)) {
                if (current.length() > 0) {
                    result.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }

        if (current.length() > 0) {
            result.add(current.toString());
        }

        return result;
    }

    // ================= PATH =================
    static String resolvePath(String base, String target) {
        String[] baseParts = base.split("/");
        List<String> stack = new ArrayList<>();

        for (String p : baseParts) {
            if (!p.isEmpty()) stack.add(p);
        }

        String[] t = target.split("/");

        for (String p : t) {
            if (p.equals("") || p.equals(".")) continue;

            if (p.equals("..")) {
                if (!stack.isEmpty()) stack.remove(stack.size() - 1);
            } else {
                stack.add(p);
            }
        }

        StringBuilder sb = new StringBuilder("/");
        for (int i = 0; i < stack.size(); i++) {
            sb.append(stack.get(i));
            if (i != stack.size() - 1) sb.append("/");
        }

        return sb.toString();
    }

    // ================= EXEC SEARCH =================
    static String findExecutable(String cmd) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;

        String[] dirs = pathEnv.split(":");

        for (String d : dirs) {
            File f = new File(d, cmd);
            if (f.exists() && f.isFile() && f.canExecute()) {
                return f.getAbsolutePath();
            }
        }

        return null;
    }
}