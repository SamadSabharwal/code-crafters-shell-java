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

            String input = sc.nextLine().trim();
            if (input.isEmpty()) continue;

            String[] parts = input.split("\\s+");
            String command = parts[0];

            // ================= EXIT =================
            if (command.equals("exit")) {
                System.exit(0);
            }

            // ================= ECHO =================
            if (command.equals("echo")) {
                for (int i = 1; i < parts.length; i++) {
                    System.out.print(parts[i]);
                    if (i != parts.length - 1) System.out.print(" ");
                }
                System.out.println();
                continue;
            }

            // ================= TYPE =================
            if (command.equals("type")) {
                if (parts.length < 2) continue;

                String target = parts[1];

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
                if (parts.length < 2) continue;

                String target = parts[1];
                String newPath;

                String home = System.getenv("HOME");

                if (target.equals("~")) {
                    newPath = home;
                }
                else if (target.startsWith("~/")) {
                    newPath = home + target.substring(1);
                }
                else if (target.startsWith("/")) {
                    newPath = target;
                }
                else {
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

                // IMPORTANT: use command name (not full path)
                cmd.add(command);

                for (int i = 1; i < parts.length; i++) {
                    cmd.add(parts[i]);
                }

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.inheritIO();

                Process process = pb.start();
                process.waitFor();

            } catch (Exception e) {
                System.out.println(command + ": not found");
            }
        }

        sc.close();
    }

    // ================= PATH RESOLVER =================
    static String resolvePath(String base, String target) {

        String[] baseParts = base.split("/");
        List<String> stack = new ArrayList<>();

        for (String part : baseParts) {
            if (!part.isEmpty()) stack.add(part);
        }

        String[] targetParts = target.split("/");

        for (String part : targetParts) {

            if (part.equals("") || part.equals(".")) continue;

            if (part.equals("..")) {
                if (!stack.isEmpty()) stack.remove(stack.size() - 1);
            } else {
                stack.add(part);
            }
        }

        StringBuilder result = new StringBuilder("/");
        for (int i = 0; i < stack.size(); i++) {
            result.append(stack.get(i));
            if (i != stack.size() - 1) result.append("/");
        }

        return result.toString();
    }

    // ================= EXEC SEARCH =================
    static String findExecutable(String target) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;

        String[] dirs = pathEnv.split(":");

        for (String dir : dirs) {
            File file = new File(dir, target);

            if (file.exists() && file.isFile() && file.canExecute()) {
                return file.getAbsolutePath();
            }
        }

        return null;
    }
}