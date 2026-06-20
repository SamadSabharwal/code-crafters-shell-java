import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");
            if (!scanner.hasNextLine()) {
                break;
            }
            String input = scanner.nextLine();
            if (input.trim().isEmpty()) {
                continue;
            }

            // Split the input into pipeline stages on "|"
            // (naive split — doesn't account for "|" inside quotes;
            // extend with your existing tokenizer if you already
            // handle quoting elsewhere in your shell)
            List<String> stageStrings = splitOnPipe(input);

            if (stageStrings.size() == 1) {
                // No pipe — handle as a normal single command
                // (delegate to your existing single-command execution logic)
                runSingleCommand(stageStrings.get(0));
            } else {
                runPipeline(stageStrings);
            }
        }
    }

    private static List<String> splitOnPipe(String input) {
        List<String> parts = new ArrayList<>();
        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
                current.append(c);
            } else if (c == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes;
                current.append(c);
            } else if (c == '|' && !inSingleQuotes && !inDoubleQuotes) {
                parts.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString().trim());
        return parts;
    }

    private static List<String> tokenize(String commandStr) {
        // Simple whitespace tokenizer. Replace with your existing
        // quote-aware tokenizer if you have one already implemented
        // for earlier stages of the shell.
        List<String> tokens = new ArrayList<>();
        for (String t : commandStr.trim().split("\\s+")) {
            if (!t.isEmpty()) {
                tokens.add(t);
            }
        }
        return tokens;
    }

    private static void runPipeline(List<String> stageStrings) {
        List<ProcessBuilder> builders = new ArrayList<>();

        for (String stageStr : stageStrings) {
            List<String> tokens = tokenize(stageStr);
            if (tokens.isEmpty()) {
                System.out.println("Invalid pipeline: empty command");
                return;
            }
            ProcessBuilder pb = new ProcessBuilder(tokens);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            builders.add(pb);
        }

        // First command reads from this shell's stdin,
        // last command writes to this shell's stdout.
        builders.get(0).redirectInput(ProcessBuilder.Redirect.INHERIT);
        builders.get(builders.size() - 1).redirectOutput(ProcessBuilder.Redirect.INHERIT);

        try {
            List<Process> processes = ProcessBuilder.startPipeline(builders);

            // Wait for all processes in the pipeline to finish
            for (Process p : processes) {
                p.waitFor();
            }
        } catch (IOException e) {
            System.out.println("Error executing pipeline: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void runSingleCommand(String commandStr) {
        List<String> tokens = tokenize(commandStr);
        if (tokens.isEmpty()) {
            return;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(tokens);
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            Process process = pb.start();
            process.waitFor();
        } catch (IOException e) {
            System.out.println(tokens.get(0) + ": command not found");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}