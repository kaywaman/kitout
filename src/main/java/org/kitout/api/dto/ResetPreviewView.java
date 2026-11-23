package org.kitout.api.dto;

import java.util.List;

/** What a reset would remove: shown in the dialog before anything happens. */
public record ResetPreviewView(List<String> runningServices, int logFiles, long logBytes, int buildLogs, int builds,
                               List<String> buildOutputs, List<String> images, String configFile, int secrets) {
}
