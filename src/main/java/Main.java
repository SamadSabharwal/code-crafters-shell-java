import java.io.File;
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

            String command = parts.get(0);

            // ================= EXIT =================
            if (command.equals("exit")) {
                System.exit(0);
            }

            // ================= ECHO =================
            if (command.equals("echo")) {
                for (int i = 1; i < parts.size(); i++) {
                    System.out.print(parts.get(i));
                    if (i != parts.size() - 1) System.out.print(" ");
                }
                System.out.println();
                continue;
            }

            // ================= TYPE =================
            if (command.equals("type")) {
                if (parts.size() < 2) continue;

                String target = parts.get(1);

                if (builtins.contains(target)) {
                    System.out.println(target + " is a shell builtin");
                } else {
                    String path = findExecutable(target);
                    if (path != null) {
                        System.out.println(target + " is " + path);
                    } else {
                        System.out.println(target + ": not found");
                    }
                }
                continue;
            }

            // ================= PWD =================
            if (command.equals("pwd")) {
                System.out.println(currentDir);
                continue;
            }

            // ================= CD =================
            if (command.equals("cd")) {
                if (parts.size() < 2) continue;

                String target = parts.get(1);
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
                    System.out.println("cd: " + target + ": No such file or directory");
                }
                continue;
            }

            // ================= EXECUTION =================
            String execPath = findExecutable(command);

            if (execPath == null) {
                System.out.println(command + ": not found");
                continue;
            }

            try {
                List<String> cmd = new ArrayList<>();
                cmd.add(command);

                for (int i = 1; i < parts.size(); i++) {
                    cmd.add(parts.get(i));
                }

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.inheritIO();

                Process p = pb.start();
                p.waitFor();

            } catch (Exception e) {
                System.out.println(command + ": not found");
            }
        }

        sc.close();
    }

    // ================= PARSER (FULL QUOTES + ESCAPES) =================
    static List<String> parse(String input) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        boolean inSingle = false;
        boolean inDouble = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            // -------- SINGLE QUOTES --------
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                continue;
            }

            // -------- DOUBLE QUOTES --------
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                continue;
            }

            // -------- ESCAPE OUTSIDE QUOTES --------
            if (c == '\\' && !inSingle && !inDouble) {
                if (i + 1 < input.length()) {
                    i++;
                    current.append(input.charAt(i));
                }
                continue;
            }

            // -------- ESCAPE INSIDE DOUBLE QUOTES --------
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

            // -------- SPLIT OUTSIDE QUOTES --------
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

    // ================= PATH RESOLUTION =================
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