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

    // ✔ track current working directory manually
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

            // ================= CD (ABSOLUTE PATH ONLY) =================
            if (command.equals("cd")) {
                if (parts.length < 2) continue;

                String targetDir = parts[1];

                File dir = new File(targetDir);

                if (dir.exists() && dir.isDirectory()) {
                    currentDir = dir.getAbsolutePath();
                } else {
                    System.out.println("cd: " + targetDir + ": No such file or directory");
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

                for (int i = 1; i < parts.length; i++) {
                    cmd.add(parts[i]);
                }

                ProcessBuilder pb = new ProcessBuilder(cmd);

                pb.environment().put("PATH", System.getenv("PATH"));
                pb.inheritIO();

                Process process = pb.start();
                process.waitFor();

            } catch (Exception e) {
                System.out.println(command + ": not found");
            }
        }

        sc.close();
    }

    // ================= PATH SEARCH =================
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