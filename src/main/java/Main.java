import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Main {
    // 1. Helper class to store the process AND the command that started it
    static class Job {
        Process process;
        String command;

        Job(Process process, String command) {
            this.process = process;
            this.command = command;
        }
    }

    // Map now stores our Job wrapper instead of just the Process
    private static final Map<Integer, Job> activeJobs = new HashMap<>();

    public static void main(String[] args) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        
        while (true) {
            // 2. Poll background jobs before prompting the user
            checkCompletedJobs();

            System.out.print("$ ");
            String input = reader.readLine();
            
            if (input == null) break;
            input = input.trim();
            if (input.isEmpty()) continue;

            boolean isBackground = false;
            if (input.endsWith("&")) {
                isBackground = true;
                // Strip the ampersand so 'input' becomes the clean command string we save
                input = input.substring(0, input.length() - 1).trim();
            }

            if (input.contains("|")) {
                executePipeline(input, isBackground);
            } else {
                executeSingle(input, isBackground);
            }
        }
    }

    private static void checkCompletedJobs() {
        List<Integer> doneJobs = new ArrayList<>();
        // Sort keys to ensure we print completions in numerical Job ID order
        List<Integer> jobIds = new ArrayList<>(activeJobs.keySet());
        Collections.sort(jobIds);

        for (int id : jobIds) {
            Job job = activeJobs.get(id);
            if (!job.process.isAlive()) {
                // 3. Print exactly as the tester expects (note the spacing)
                System.out.println("[" + id + "]+  Done                 " + job.command);
                doneJobs.add(id);
            }
        }

        // Remove finished jobs so their IDs are recycled
        for (int id : doneJobs) {
            activeJobs.remove(id);
        }
    }

    private static void executeSingle(String input, boolean isBackground) {
        // 1. Intercept the 'jobs' built-in command
        if (input.equals("jobs")) {
            List<Integer> jobIds = new ArrayList<>(activeJobs.keySet());
            Collections.sort(jobIds);
            
            for (int id : jobIds) {
                Job job = activeJobs.get(id);
                if (job.process.isAlive()) {
                    // Standard formatting for running background jobs
                    System.out.println("[" + id + "]+  Running                 " + job.command + " &");
                }
            }
            return; // Return immediately to prevent ProcessBuilder from running
        }

        // 2. Existing external command logic
        try {
            String[] cmdArgs = input.split(" +");
            ProcessBuilder pb = new ProcessBuilder(cmdArgs);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);

            Process process = pb.start();

            if (isBackground) {
                int jobId = getNextJobId();
                activeJobs.put(jobId, new Job(process, input));
                System.out.println("[" + jobId + "] " + process.pid());
            } else {
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
                // Store the full pipeline string for the Done message
                activeJobs.put(jobId, new Job(lastProcess, input));
                System.out.println("[" + jobId + "] " + lastProcess.pid());
            } else {
                lastProcess.waitFor();
            }
        } catch (Exception e) {
            System.out.println("Error executing pipeline: " + e.getMessage());
        }
    }

    private static int getNextJobId() {
        int id = 1;
        while (activeJobs.containsKey(id) && activeJobs.get(id).process.isAlive()) {
            id++;
        }
        return id;
    }
}