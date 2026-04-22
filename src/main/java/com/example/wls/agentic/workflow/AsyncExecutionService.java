package com.example.wls.agentic.workflow;

import io.helidon.service.registry.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reusable service for executing MCP agent requests and waiting for asynchronous jobs to complete.
 * This service can be injected into any workflow that needs to run agent prompts that may trigger
 * long-running MCP operations (e.g., stop servers, apply patches).
 *
 * Usage example:
 * AsyncExecutionService asyncService = ...;
 * McpAgent domainAgent = domainRuntimeAgent::analyzeRequest;
 * String result = asyncService.executeAndWait("Stop servers in domain X", domainAgent);
 */
@Service.Singleton
public class AsyncExecutionService {

    /**
     * Executes the given prompt using the provided agent and waits for any asynchronous job to complete.
     * If the response contains a job identifier (e.g., "Job ID: 12345"), it polls the agent for status
     * until a terminal state is reached or a timeout occurs.
     *
     * @param prompt The prompt to send to the agent.
     * @param agent The MCP agent wrapper (e.g., domainRuntimeAgent::analyzeRequest).
     * @return The final result string, including status updates or a timeout warning.
     */
    public String executeAndWait(String prompt, McpAgent agent) {
        if (prompt == null || agent == null) {
            return "Invalid input: prompt or agent is null.";
        }

        // Execute the initial request.
        String result = agent.send(prompt);
        if (result == null || result.isBlank()) {
            return "No response from agent.";
        }

        // Detect all job IDs using regex: looks for "Job ID: <id>" (case-insensitive), supports multiple.
        Pattern jobIdPattern = Pattern.compile("(?i)Job ID[:\\s]+(\\S+)");
        Matcher matcher = jobIdPattern.matcher(result);
        List<String> jobIds = new ArrayList<>();
        while (matcher.find()) {
            jobIds.add(matcher.group(1));
        }

        if (jobIds.isEmpty()) {
            // No async jobs detected; return the original result.
            return result;
        }

        long deadline = System.currentTimeMillis() + 120_000L; // 2-minute timeout per job.
        List<String> statuses = new ArrayList<>();
        boolean allComplete = false;

        while (System.currentTimeMillis() < deadline && !allComplete) {
            allComplete = true;
            StringBuilder pollPrompt = new StringBuilder("Check status of jobs: ");
            for (int i = 0; i < jobIds.size(); i++) {
                if (i > 0) pollPrompt.append(", ");
                pollPrompt.append(jobIds.get(i));
            }
            pollPrompt.append(".");

            String rawStatuses = agent.send(pollPrompt.toString());

            if (rawStatuses != null && !rawStatuses.isBlank()) {
                // Parse individual statuses from the response (simple keyword check per job).
                String lowerRaw = rawStatuses.toLowerCase();
                for (String jobId : jobIds) {
                    if (lowerRaw.contains(jobId.toLowerCase() + " completed") || lowerRaw.contains(jobId.toLowerCase() + " success") ||
                        lowerRaw.contains(jobId.toLowerCase() + " finished")) {
                        statuses.add(jobId + ": SUCCESS");
                    } else if (lowerRaw.contains(jobId.toLowerCase() + " failed") || lowerRaw.contains(jobId.toLowerCase() + " error")) {
                        statuses.add(jobId + ": FAILED");
                        allComplete = false; // Continue to check others, but note failure.
                    } else {
                        statuses.add(jobId + ": IN_PROGRESS");
                        allComplete = false;
                    }
                }
            }

            if (!allComplete) {
                try {
                    Thread.sleep(1000L); // Poll every second.
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return result + "\n\n[WARNING] Polling interrupted for jobs: " + String.join(", ", jobIds);
                }
            }
        }

        // Check for any failures.
        boolean hasFailure = statuses.stream().anyMatch(s -> s.contains("FAILED"));
        if (hasFailure) {
            String failureMsg = "One or more jobs failed: " + String.join(", ", statuses) + "\n\n[ERROR] Patching process stopped due to job failure.";
            return result + "\n\n" + failureMsg;
        }

        // If timed out or not all complete, warn.
        if (!allComplete) {
            String warning = "\n\n[WARNING] Some jobs (" + String.join(", ", jobIds) + ") did not reach a terminal state within 2 minutes: " + String.join(", ", statuses);
            return result + warning;
        }

        // All succeeded.
        return result + "\n\nAll jobs completed successfully: " + String.join(", ", statuses);
    }
}
