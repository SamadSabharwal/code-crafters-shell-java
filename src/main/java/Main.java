import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Main {
    // Keep track of background processes to recycle job IDs
    private static final Map<Integer, Process> activeJobs = new HashMap<>();

    public static void main(String[] args) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        
        while (true) {
            System.out.print("$ ");
            String input = reader.readLine();
            
            if (input == null) break;
            input = input.trim();
            if (input.isEmpty()) continue;

            // 1. Check for background job operator '&'
            boolean isBackground = false;
            if (input.endsWith("&")) {
                isBackground = true;
                // Remove the '&' and any trailing spaces from the command string
                input = input.substring(0, input.length() - 1).trim();
            }

            // 2. Route to appropriate executor
            if (input.contains("|")) {
                executePipeline(input, isBackground);
            } else {
                executeSingle(input, isBackground);
            }
        }
    }

    private static void executeSingle(String input, boolean isBackground) {
        // TODO: Handle your built-in commands (cd, exit, echo, type) here before ProcessBuilder
        // if (input.startsWith("cd ")) { ... return; }
        
        try {
            // Split by spaces (assuming you aren't using a custom quote tokenizer yet)
            String[] cmdArgs = input.split(" +");
            ProcessBuilder pb = new ProcessBuilder(cmdArgs);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);

            Process process = pb.start();

            if (isBackground) {
                int jobId = getNextJobId();
                activeJobs.put(jobId, process);
                System.out.println("[" + jobId + "] " + process.pid());
            } else {
                // Only wait if it's NOT a background job
                process.waitFor();
            }
        } catch (Exception e) {
            System.out.println("Error executing command: " + e.getMessage());
        }
    }

    private static void executePipeline(String input, boolean isBackground) {
        try {
            String[] rawCommands = input.split("\\|");
            List<ProcessBuilder> builders = new ArrayList<>();

            for (String rawCmd : rawCommands) {
                String[] cmdArgs = rawCmd.trim().split(" +");
                ProcessBuilder pb = new ProcessBuilder(cmdArgs);
                pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                builders.add(pb);
            }

            builders.get(builders.size() - 1).redirectOutput(ProcessBuilder.Redirect.INHERIT);
            
            List<Process> processes = ProcessBuilder.startPipeline(builders);
            Process lastProcess = processes.get(processes.size() - 1);

            if (isBackground) {
                int jobId = getNextJobId();
                // For pipelines, we track the final process in the chain
                activeJobs.put(jobId, lastProcess);
                System.out.println("[" + jobId + "] " + lastProcess.pid());
            } else {
                lastProcess.waitFor();
            }

        } catch (Exception e) {
            System.out.println("Error executing pipeline: " + e.getMessage());
        }
    }

    /**
     * Finds the lowest available integer for a job ID.
     * If a process mapped to an ID is no longer alive, its ID is recycled.
     */
    private static int getNextJobId() {
        int id = 1;
        while (activeJobs.containsKey(id) && activeJobs.get(id).isAlive()) {
            id++;
        }
        return id;
    }
}