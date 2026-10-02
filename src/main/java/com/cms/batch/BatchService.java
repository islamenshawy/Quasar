package com.cms.batch;

import com.cms.card.IssuanceException;
import com.cms.common.AuditLog;
import com.cms.common.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Batch jobs (CMS-082): cron schedule per job in batch_job, run history in batch_run.
 *
 * A run first claims the job row (running_since), so one job never runs twice at the same time,
 * also across CMS instances; a claim older than two hours is treated as a crashed run.
 * The scheduler checks every minute whether a job's next cron time since its last scheduled run
 * has passed. Supervisors can also start a run from the console.
 */
@Service
public class BatchService {

    private static final Logger log = LoggerFactory.getLogger(BatchService.class);
    public static final String SCHEDULER = "SCHEDULER";

    public record Result(int items, String message) {}

    public record JobView(String code, String name, String description, String cron, boolean enabled,
                          boolean running, OffsetDateTime nextRun, RunView lastRun, String updatedBy) {}

    public record RunView(long id, String jobCode, OffsetDateTime startedAt, OffsetDateTime finishedAt, String status,
                          int items, String message, String triggeredBy) {}

    private final JdbcTemplate jdbc;
    private final AuditLog audit;
    private final boolean schedulerEnabled;
    private final Map<String, Function<String, Result>> jobs = new ConcurrentHashMap<>();
    private final Instant started = Instant.now();

    public BatchService(JdbcTemplate jdbc, AuditLog audit,
                        @Value("${cms.batch.scheduler-enabled:true}") boolean schedulerEnabled) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.schedulerEnabled = schedulerEnabled;
    }

    public void register(String code, Function<String, Result> job) {
        jobs.put(code, job);
    }

    // ---------------- scheduler ----------------

    @Scheduled(initialDelay = 30_000, fixedDelay = 60_000)
    public void tick() {
        if (!schedulerEnabled) return;
        for (JobView j : jobs()) {
            if (!j.enabled() || j.running() || !jobs.containsKey(j.code())) continue;
            if (j.nextRun() != null && !j.nextRun().toInstant().isAfter(Instant.now())) {
                try {
                    run(j.code(), SCHEDULER);
                } catch (IssuanceException e) {
                    log.info("Batch {} not started: {}", j.code(), e.getMessage());
                }
            }
        }
    }

    /** Next cron time after the last scheduled run (or after CMS start when it never ran). */
    private OffsetDateTime nextRun(String code, String cron) {
        Instant base = jdbc.query("""
                SELECT max(started_at) FROM batch_run WHERE job_code = ? AND triggered_by = ?
                """, rs -> rs.next() && rs.getObject(1) != null ? rs.getObject(1, OffsetDateTime.class).toInstant() : null,
                code, SCHEDULER);
        if (base == null || base.isBefore(started)) base = started;
        ZonedDateTime next = CronExpression.parse(cron).next(base.atZone(ZoneId.systemDefault()));
        return next == null ? null : next.toOffsetDateTime();
    }

    // ---------------- run ----------------

    public RunView run(String code, String actor) {
        Function<String, Result> job = jobs.get(code);
        if (job == null) throw new IssuanceException("NOT_FOUND", "Unknown batch job " + code);
        int claimed = jdbc.update("""
                UPDATE batch_job SET running_since = now()
                 WHERE code = ? AND (running_since IS NULL OR running_since < now() - interval '2 hours')
                """, code);
        if (claimed == 0) throw new IssuanceException("ALREADY_RUNNING", "Batch " + code + " is already running");
        long runId = jdbc.queryForObject("INSERT INTO batch_run (job_code, triggered_by) VALUES (?, ?) RETURNING id",
                Long.class, code, actor);
        log.info("Batch {} started by {} (run {})", code, actor, runId);
        try {
            Result r = job.apply(actor);
            jdbc.update("UPDATE batch_run SET status = 'SUCCESS', finished_at = now(), items = ?, message = ? WHERE id = ?",
                    r.items(), clip(r.message()), runId);
            log.info("Batch {} finished: {} item(s) {}", code, r.items(), r.message() == null ? "" : r.message());
        } catch (RuntimeException e) {
            log.error("Batch {} failed", code, e);
            jdbc.update("UPDATE batch_run SET status = 'FAILED', finished_at = now(), message = ? WHERE id = ?",
                    clip(e.getClass().getSimpleName() + ": " + e.getMessage()), runId);
        } finally {
            jdbc.update("UPDATE batch_job SET running_since = NULL, last_run_id = ? WHERE code = ?", runId, code);
        }
        if (!SCHEDULER.equals(actor)) audit.record(actor, "BATCH_RUN", "batch_job", runId, Map.of("job", code));
        return runView(runId);
    }

    // ---------------- read / maintain ----------------

    public List<JobView> jobs() {
        return jdbc.query("""
                SELECT code, name, description, cron, enabled, running_since IS NOT NULL, last_run_id, updated_by
                  FROM batch_job ORDER BY code
                """, (rs, i) -> {
                    Long last = (Long) rs.getObject(7);
                    String code = rs.getString(1);
                    boolean enabled = rs.getBoolean(5);
                    return new JobView(code, rs.getString(2), rs.getString(3), rs.getString(4), enabled,
                            rs.getBoolean(6), enabled ? nextRun(code, rs.getString(4)) : null,
                            last == null ? null : runView(last), rs.getString(8));
                });
    }

    public Page<RunView> runs(String code, int page, int size) {
        size = Page.size(size);
        long total = jdbc.queryForObject("SELECT count(*) FROM batch_run WHERE job_code = ?", Long.class, code);
        return new Page<>(jdbc.query(RUN_SELECT + " WHERE job_code = ? ORDER BY id DESC LIMIT ? OFFSET ?",
                (rs, i) -> mapRun(rs), code, size, Page.offset(page, size)), total, page, size);
    }

    public JobView update(String code, String cron, boolean enabled, String actor) {
        try {
            CronExpression.parse(cron);
        } catch (IllegalArgumentException e) {
            throw new IssuanceException("INVALID_REQUEST", "Invalid cron expression: " + e.getMessage());
        }
        int n = jdbc.update("UPDATE batch_job SET cron = ?, enabled = ?, updated_at = now(), updated_by = ? WHERE code = ?",
                cron.trim(), enabled, actor, code);
        if (n == 0) throw new IssuanceException("NOT_FOUND", "Unknown batch job " + code);
        audit.record(actor, "BATCH_JOB_UPDATE", "batch_job", null, Map.of("job", code, "cron", cron, "enabled", enabled));
        return jobs().stream().filter(j -> j.code().equals(code)).findFirst().orElseThrow();
    }

    private static final String RUN_SELECT = """
            SELECT id, job_code, started_at, finished_at, status, items, message, triggered_by FROM batch_run
            """;

    private RunView runView(long id) {
        return jdbc.queryForObject(RUN_SELECT + " WHERE id = ?", (rs, i) -> mapRun(rs), id);
    }

    private static RunView mapRun(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RunView(rs.getLong(1), rs.getString(2), rs.getObject(3, OffsetDateTime.class),
                rs.getObject(4, OffsetDateTime.class), rs.getString(5), rs.getInt(6), rs.getString(7), rs.getString(8));
    }

    private static String clip(String s) {
        return s == null || s.length() <= 512 ? s : s.substring(0, 512);
    }
}
