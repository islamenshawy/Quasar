package com.cms.api;

import com.cms.approval.ApprovalActions;
import com.cms.approval.ApprovalService;
import com.cms.batch.BatchService;
import com.cms.batch.BatchService.JobView;
import com.cms.batch.BatchService.RunView;
import com.cms.common.Page;
import com.cms.security.Operator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Batch jobs: list, history, run now (SUPERVISOR), change schedule (maker-checker BATCH_JOB_UPDATE). */
@RestController
@RequestMapping("/api/admin/batch")
public class BatchController {

    public record JobUpdate(String cron, boolean enabled) {}

    private final BatchService batch;
    private final ApprovalService approvals;

    public BatchController(BatchService batch, ApprovalService approvals) {
        this.batch = batch;
        this.approvals = approvals;
    }

    @GetMapping("/jobs")
    public List<JobView> jobs() {
        return batch.jobs();
    }

    @GetMapping("/jobs/{code}/runs")
    public Page<RunView> runs(@PathVariable String code, @RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "25") int size) {
        return batch.runs(code, page, size);
    }

    @PostMapping("/jobs/{code}/run")
    public RunView run(@PathVariable String code, @Operator String op) {
        return batch.run(code, op);
    }

    @PutMapping("/jobs/{code}")
    public ResponseEntity<Object> update(@PathVariable String code, @RequestBody JobUpdate u, @Operator String op) {
        return approvals.submit("BATCH_JOB_UPDATE", "batch_job", code, "Batch " + code + ": " + u.cron()
                + (u.enabled() ? "" : " (switched off)"), new ApprovalActions.BatchJobUpdate(code, u.cron(), u.enabled()), op)
                .toResponse();
    }
}
