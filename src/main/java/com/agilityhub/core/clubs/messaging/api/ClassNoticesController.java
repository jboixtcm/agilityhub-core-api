package com.agilityhub.core.clubs.messaging.api;

import com.agilityhub.core.clubs.messaging.application.ClassNoticeHistory;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import com.agilityhub.core.shared.domain.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.time.Instant;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ClassNoticesController {
    private final ClassNoticeHistory history;
    public ClassNoticesController(ClassNoticeHistory history) { this.history = history; }
    @Schema(name = "ClassNoticeItem")
    public record Item(String id, Instant createdAt, String code, String classId, String classDate, String classTime,
            String classDescription, String ringName, String title, String body) { }
    @Schema(name = "ClassNoticePage")
    public record Page(List<Item> items, int page, int size, long totalItems) { }

    @GetMapping("/api/v1/class-notices")
    @PreAuthorize(NotificationsController.ADMIN)
    @ContractErrors({ErrorCode.VALIDATION_ERROR})
    @Operation(summary = "classNotices", description = "Roles: ADMIN, never impersonation. Class cancellation and risk notices delivered to administrators, "
            + "one per event, code and class across recipients. Newest first with stable paging; today uses the club timezone and notification creation date. "
            + "Frozen class labels preserve the sent content; no recipient details are exposed. Size 1–100." + NotificationsController.TENANT,
            responses = @ApiResponse(responseCode = "200", description = "ClassNoticePage", useReturnTypeSchema = true))
    public Page classNotices(@RequestParam(defaultValue = "false") boolean today, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var result = history.page(today, page, size);
        return new Page(result.items().stream().map(n -> new Item(n.id(), n.createdAt(), n.code(), n.classId(), n.classDate(), n.classTime(),
                n.classDescription(), n.ringName(), n.title(), n.body())).toList(), result.page(), result.size(), result.totalItems());
    }
}
