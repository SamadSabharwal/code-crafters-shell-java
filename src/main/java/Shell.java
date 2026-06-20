import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class Shell {
    public static void main(String[] args) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String line;

        while ((line = reader.readLine()) != null) {
            if (line.trim().isEmpty()) {
                continue;
            }
            executeCommand(line);
        }
    }

    private static void executeCommand(String commandLine) {
        try {
            // Parse the command line
            CommandParsed parsed = parseCommand(commandLine);

            if (parsed == null) {
                return;
            }

            // Create ProcessBuilder
            ProcessBuilder pb = new ProcessBuilder(parsed.command);

            // Handle stdout redirection (> or >>)
            if (parsed.stdoutFile != null) {
                File outFile = new File(parsed.stdoutFile);
                ensureParentDirectory(outFile);
                if (parsed.stdoutAppend) {
                    pb.redirectOutput(ProcessBuilder.Redirect.appendTo(outFile));
                } else {
                    pb.redirectOutput(ProcessBuilder.Redirect.to(outFile));
                }
            }

            // Handle stderr redirection (2> or 2>>)
            if (parsed.stderrFile != null) {
                File errFile = new File(parsed.stderrFile);
                ensureParentDirectory(errFile);
                if (parsed.stderrAppend) {
                    pb.redirectError(ProcessBuilder.Redirect.appendTo(errFile));
                } else {
                    pb.redirectError(ProcessBuilder.Redirect.to(errFile));
                }
            }

            // Start the process
            Process process = pb.start();
            process.waitFor();

        } catch (Exception e) {
            System.err.println("Error executing command: " + e.getMessage());
        }
    }

    private static void ensureParentDirectory(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            Files.createDirectories(parent.toPath());
        }
    }

    private static CommandParsed parseCommand(String commandLine) {
        CommandParsed parsed = new CommandParsed();

        // Pattern to match: command args [> file | >> file] [2> file | 2>> file]
        // Handle 2>> first (must be before 2>)
        Pattern stderr2AppendPattern = Pattern.compile("2>>\\s+([^\\s]+)(?:\\s|$)");
        Matcher stderrMatcher = stderr2AppendPattern.matcher(commandLine);
        if (stderrMatcher.find()) {
            parsed.stderrFile = stderrMatcher.group(1);
            parsed.stderrAppend = true;
            commandLine = commandLine.substring(0, stderrMatcher.start()) + 
                         commandLine.substring(stderrMatcher.end());
        }

        // Handle 2> (redirect stderr, overwrite)
        Pattern stderr1Pattern = Pattern.compile("2>\\s+([^\\s]+)(?:\\s|$)");
        stderrMatcher = stderr1Pattern.matcher(commandLine);
        if (stderrMatcher.find()) {
            parsed.stderrFile = stderrMatcher.group(1);
            parsed.stderrAppend = false;
            commandLine = commandLine.substring(0, stderrMatcher.start()) + 
                         commandLine.substring(stderrMatcher.end());
        }

        // Handle >> (stdout append)
        Pattern stdoutAppendPattern = Pattern.compile(">>\\s+([^\\s]+)(?:\\s|$)");
        Matcher stdoutMatcher = stdoutAppendPattern.matcher(commandLine);
        if (stdoutMatcher.find()) {
            parsed.stdoutFile = stdoutMatcher.group(1);
            parsed.stdoutAppend = true;
            commandLine = commandLine.substring(0, stdoutMatcher.start()) + 
                         commandLine.substring(stdoutMatcher.end());
        }

        // Handle > (stdout overwrite)
        Pattern stdoutPattern = Pattern.compile(">\\s+([^\\s]+)(?:\\s|$)");
        stdoutMatcher = stdoutPattern.matcher(commandLine);
        if (stdoutMatcher.find()) {
            parsed.stdoutFile = stdoutMatcher.group(1);
            parsed.stdoutAppend = false;
            commandLine = commandLine.substring(0, stdoutMatcher.start()) + 
                         commandLine.substring(stdoutMatcher.end());
        }

        // Parse the remaining command
        commandLine = commandLine.trim();
        if (commandLine.isEmpty()) {
            return null;
        }

        parsed.command = parseCommandArguments(commandLine);
        return parsed;
    }

    private static List<String> parseCommandArguments(String commandLine) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < commandLine.length(); i++) {
            char c = commandLine.charAt(i);

            if (c == '"' || c == '\'') {
                inQuotes = !inQuotes;
            } else if (c == ' ' && !inQuotes) {
                if (current.length() > 0) {
                    args.add(current.toString());
                    current = new StringBuilder();
                }
            } else {
                current.append(c);
            }
        }

        if (current.length() > 0) {
            args.add(current.toString());
        }

        return args;
    }

    static class CommandParsed {
        List<String> command;
        String stdoutFile;
        boolean stdoutAppend;
        String stderrFile;
        boolean stderrAppend;

        CommandParsed() {
            this.stdoutAppend = false;
            this.stderrAppend = false;
        }
    }
}
