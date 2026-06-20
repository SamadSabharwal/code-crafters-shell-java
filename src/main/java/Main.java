import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Scanner;
import java.util.Set;

public class Main {

    // builtin commands
    static Set<String> builtins = new HashSet<>(
            Arrays.asList("echo", "exit", "type")
    );

    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");

            if (!sc.hasNextLine()) {
                break;
            }

            String input = sc.nextLine().trim();
            if (input.isEmpty()) continue;

            String[] parts = input.split("\\s+");
            String command = parts[0];

            // ===== exit builtin =====
            if (command.equals("exit")) {
                System.exit(0);
            }

            // ===== type builtin =====
            if (command.equals("type")) {
                if (parts.length < 2) continue;

                String target = parts[1];

                // 1. check builtin
                if (builtins.contains(target)) {
                    System.out.println(target + " is a shell builtin");
                    continue;
                }

                // 2. check PATH
                String pathEnv = System.getenv("PATH");
                if (pathEnv == null) {
                    System.out.println(target + ": not found");
                    continue;
                }

                String[] paths = pathEnv.split(":");

                boolean found = false;

                for (String dir : paths) {
                    File file = new File(dir, target);

                    if (file.exists() && file.isFile() && file.canExecute()) {
                        System.out.println(target + " is " + file.getAbsolutePath());
                        found = true;
                        break;
                    }
                }

                if (!found) {
                    System.out.println(target + ": not found");
                }

                continue;
            }

            // ===== echo builtin =====
            if (command.equals("echo")) {
                for (int i = 1; i < parts.length; i++) {
                    System.out.print(parts[i]);
                    if (i != parts.length - 1) System.out.print(" ");
                }
                System.out.println();
                continue;
            }

            // ===== unknown command =====
            System.out.println(command + ": not found");
        }

        sc.close();
    }
}