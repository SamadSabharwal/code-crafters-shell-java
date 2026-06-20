import java.util.Arrays;
import java.util.HashSet;
import java.util.Scanner;
import java.util.Set;

public class Main {

    // list of builtins
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

            if (input.isEmpty()) {
                continue;
            }

            String[] parts = input.split("\\s+");
            String command = parts[0];

            // ===== BUILTIN: exit =====
            if (command.equals("exit")) {
                System.exit(0);
            }

            // ===== BUILTIN: type =====
            if (command.equals("type")) {
                if (parts.length < 2) {
                    // no argument provided
                    continue;
                }

                String target = parts[1];

                if (builtins.contains(target)) {
                    System.out.println(target + " is a shell builtin");
                } else {
                    System.out.println(target + ": not found");
                }
                continue;
            }

            // ===== BUILTIN: echo =====
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