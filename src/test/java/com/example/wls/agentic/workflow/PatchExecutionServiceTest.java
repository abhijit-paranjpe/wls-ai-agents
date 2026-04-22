package com.example.wls.agentic.workflow;

import com.example.wls.agentic.ai.DomainRuntimeAgent;
import com.example.wls.agentic.ai.PatchingAgent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PatchExecutionServiceTest {

    @Mock
    private DomainRuntimeAgent domainRuntimeAgent;

    @Mock
    private PatchingAgent patchingAgent;

    @Mock
    private AsyncExecutionService asyncExecutionService;

    @InjectMocks
    private PatchExecutionService patchExecutionService;

    @Test
    void shouldNotProceedToApplyWhenStopDidNotCompleteSuccessfully() {
        when(asyncExecutionService.executeAndWait(anyString(), any()))
                .thenReturn("[WARNING] Some jobs did not reach a terminal state within 2 minutes");

        PatchExecutionResult result = patchExecutionService.executeRecommendedPatchFlow("my_domain");

        verify(asyncExecutionService, times(1)).executeAndWait(anyString(), any());
        assertTrue(result.applyResult().contains("Skipping patch apply/start/verify"));
        assertTrue(result.startResult().contains("Skipping patch apply/start/verify"));
        assertTrue(result.verifyResult().contains("Skipping patch apply/start/verify"));
    }

    @Test
    void shouldProceedThroughAllStepsWhenStopCompletedSuccessfully() {
        when(asyncExecutionService.executeAndWait(anyString(), any()))
                .thenReturn("Stop completed successfully")
                .thenReturn("Apply completed successfully")
                .thenReturn("Start completed successfully")
                .thenReturn("Verify completed successfully");

        patchExecutionService.executeRecommendedPatchFlow("my_domain");

        verify(asyncExecutionService, times(4)).executeAndWait(anyString(), any());
    }
}
