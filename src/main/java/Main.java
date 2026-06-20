import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Scanner;

public class Main {

    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);

        while (true) {
            // prompt
            System.out.print("$ ");

            if (!sc.hasNextLine()) {
                break; // EOF (Ctrl+D)
            }

            String input = sc.nextLine().trim();

            // skip empty input
            if (input.isEmpty()) {
                continue;
            }

            // ===== BUILTIN: exit =====
            if (input.equals("exit")) {
                System.exit(0);
            }

            // ===== split command =====
            String[] cmd = input.split("\\s+");

            try {
                // build process
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);

                Process process = pb.start();

                // print output
                BufferedReader reader =
                        new BufferedReader(new InputStreamReader(process.getInputStream()));

                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                }

                process.waitFor();

            } catch (Exception e) {
                // command not found case
                System.out.println(cmd[0] + ": command not found");
            }
        }

        sc.close();
    }
}