import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");
            String input = scanner.nextLine();

            // handle empty input (optional but good)
            if (input == null || input.trim().isEmpty()) {
                continue;
            }

            System.out.println(input + ": command not found");
        }
    }
}