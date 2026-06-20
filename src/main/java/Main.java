import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");

            if (!scanner.hasNextLine()) {
                break;
            }

            String line = scanner.nextLine().trim();

            if (line.isEmpty()) {
                continue;
            }

            // exit builtin
            if (line.equals("exit") || line.equals("exit 0")) {
                break;
            }

            // pipeline
            if (line.contains("|")) {
                executePipeline(line);
            } else {
                executeCommand(parseCommand(line));
            }
        }
    }

    // Executes a normal external command
    private static void executeCommand(List<String> command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);

            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);

            Process process = pb.start();
            process.waitFor();

        } catch (IOException e) {
            System.out.println(command.get(0) + ": command not found");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Executes cmd1 | cmd2
    private static void executePipeline(String line) {

        String[] commands = line.split("\\|", 2);

        List<String> left = parseCommand(commands[0].trim());
        List<String> right = parseCommand(commands[1].trim());

        try {

            ProcessBuilder pb1 = new ProcessBuilder(left);
            ProcessBuilder pb2 = new ProcessBuilder(right);

            pb1.redirectError(ProcessBuilder.Redirect.INHERIT);
            pb2.redirectError(ProcessBuilder.Redirect.INHERIT);

            Process p1 = pb1.start();
            Process p2 = pb2.start();

            // Pipe stdout of first process to stdin of second process
            Thread pipeThread = new Thread(() -> {
                try (
                        InputStream in = p1.getInputStream();
                        OutputStream out = p2.getOutputStream()) {

                    byte[] buffer = new byte[8192];
                    int len;

                    while ((len = in.read(buffer)) != -1) {
                        out.write(buffer, 0, len);
                        out.flush();
                    }

                    out.close();

                } catch (IOException ignored) {
                }
            });

            // Print stdout of second process
            Thread outputThread = new Thread(() -> {
                try (InputStream in = p2.getInputStream()) {

                    byte[] buffer = new byte[8192];
                    int len;

                    while ((len = in.read(buffer)) != -1) {
                        System.out.write(buffer, 0, len);
                        System.out.flush();
                    }

                } catch (IOException ignored) {
                }
            });

            pipeThread.start();
            outputThread.start();

            p1.waitFor();
            pipeThread.join();

            p2.waitFor();
            outputThread.join();

        } catch (IOException e) {
            System.out.println("command not found");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Parses command line while preserving quoted strings
    private static List<String> parseCommand(String command) {

        List<String> tokens = new ArrayList<>();

        Pattern pattern = Pattern.compile("'([^']*)'|\"([^\"]*)\"|(\\S+)");
        Matcher matcher = pattern.matcher(command);

        while (matcher.find()) {
            if (matcher.group(1) != null) {
                tokens.add(matcher.group(1));
            } else if (matcher.group(2) != null) {
                tokens.add(matcher.group(2));
            } else {
                tokens.add(matcher.group(3));
            }
        }

        return tokens;
    }
}