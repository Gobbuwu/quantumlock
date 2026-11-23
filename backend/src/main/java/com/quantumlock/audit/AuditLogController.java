package com.quantumlock.audit;

import com.quantumlock.account.CurrentAccount;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The team's audit log (KAN-32). Read only: nothing can change or delete audit rows through the API. */
@RestController
@RequestMapping("/api/v1/audit-logs")
@Tag(name = "Audit log", description = "Every key operation of your team, newest first")
public class AuditLogController {

    private final AuditLogService auditLogs;
    private final CurrentAccount currentAccount;

    public AuditLogController(AuditLogService auditLogs, CurrentAccount currentAccount) {
        this.auditLogs = auditLogs;
        this.currentAccount = currentAccount;
    }

    @GetMapping
    @Operation(summary = "List your team's audit log, newest first. Pass nextBefore as before to get the next page.")
    public AuditLogPage list(
            @Parameter(description = "Only rows about this key pair") @RequestParam(required = false) String keyPairId,
            @Parameter(description = "Only this action: generate, encapsulate, decapsulate, sign, verify, rotate or revoke")
            @RequestParam(required = false) String action,
            @Parameter(description = "Only success or only failure") @RequestParam(required = false) String status,
            @Parameter(description = "Most rows per page, 1 to 100 (default 50)") @RequestParam(required = false) String limit,
            @Parameter(description = "nextBefore from the previous page") @RequestParam(required = false) String before) {
        AuditLogQuery query = AuditLogQuery.parse(keyPairId, action, status, limit, before);
        return auditLogs.page(currentAccount.accountId(), query);
    }
}
