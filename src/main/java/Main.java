import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class Main {
    public static void main(String[] args) throws Exception {
        // Standard REPL loop for your shell
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        
        while (true) {
            System.out.print("$ ");
            String input = reader.readLine();
            
            if (input == null) break; // Exit on EOF
            input = input.trim();
            if (input.isEmpty()) continue;

            // Check if the command is a pipeline
            if (input.contains("|")) {
                executePipeline(input);
            } else {
                // TODO: Your existing logic for single commands (cd, echo, exit, etc.)
                System.out.println("Executing single command: " + input);
            }
        }
    }

    private static void executePipeline(String input) {
        try {
            // Split the input into separate commands based on the pipe character
            // Note: If you implemented quote handling in previous stages, you should use 
            // your custom parser here instead of simple string splitting to avoid splitting 
            // on pipes inside quotes (e.g. echo "a | b").
            String[] rawCommands = input.split("\\|");
            
            List<ProcessBuilder> builders = new ArrayList<>();

            for (String rawCmd : rawCommands) {
                // Split each command by spaces to get the executable and arguments
                String[] cmdArgs = rawCmd.trim().split(" +");
                ProcessBuilder pb = new ProcessBuilder(cmdArgs);
                builders.add(pb);
            }

            // The output of the final command in the pipeline needs to print to the shell's stdout
            ProcessBuilder lastBuilder = builders.get(builders.size() - 1);
            lastBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            
            // Optionally redirect stderr for all processes so errors show up in the console
            for (ProcessBuilder pb : builders) {
                pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            }

            // Java 9+ native pipeline execution
            // This connects the stdout of builders.get(0) to stdin of builders.get(1), etc.
            List<Process> processes = ProcessBuilder.startPipeline(builders);

            // Wait ONLY for the last process in the pipeline to complete.
            // For example, in `tail -f file | head -n 5`, `head` will finish after 5 lines.
            // Once `head` exits, the OS pipe breaks, which sends a SIGPIPE to `tail` and closes it.
            Process lastProcess = processes.get(processes.size() - 1);
            lastProcess.waitFor();

        } catch (Exception e) {
            System.out.println("Error executing pipeline: " + e.getMessage());
        }
    }
}