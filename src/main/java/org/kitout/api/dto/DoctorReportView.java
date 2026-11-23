package org.kitout.api.dto;

import java.util.List;

public record DoctorReportView(List<CheckView> results, long errors, long warnings, boolean blocksStart) {
}
