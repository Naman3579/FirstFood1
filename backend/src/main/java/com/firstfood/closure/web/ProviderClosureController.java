package com.firstfood.closure.web;

import com.firstfood.closure.ClosurePreview;
import com.firstfood.closure.ClosureService;
import com.firstfood.closure.ClosureView;
import com.firstfood.closure.DeclareClosureResult;
import com.firstfood.closure.dto.DeclareClosureRequest;
import com.firstfood.identity.security.AuthenticatedAccount;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provider staff's side of closures. Thin HTTP layer - the acting account is always the JWT principal and every
 * authorization decision is in {@link ClosureService}.
 */
@RestController
@RequestMapping({"/api/v1/providers/{providerId}/closures", "/api/v1/staff/providers/{providerId}/closures"})
public class ProviderClosureController {

    private final ClosureService closureService;

    public ProviderClosureController(ClosureService closureService) {
        this.closureService = closureService;
    }

    @GetMapping
    public List<ClosureView> list(@AuthenticationPrincipal AuthenticatedAccount principal, @PathVariable UUID providerId) {
        return closureService.list(principal.accountId(), providerId);
    }

    /** Dry run: who would be extended, and to which date. Writes nothing. */
    @GetMapping("/preview")
    public ClosurePreview preview(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID providerId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return closureService.preview(principal.accountId(), providerId, from, to);
    }

    @GetMapping("/{closureId}")
    public ClosureView get(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID providerId,
            @PathVariable UUID closureId) {
        return closureService.get(principal.accountId(), providerId, closureId);
    }

    /** 201 when the closure was recorded now; 200 when the same declaration already existed (a retry). */
    @PostMapping
    public ResponseEntity<ClosureView> declare(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID providerId,
            @Valid @RequestBody DeclareClosureRequest request) {
        DeclareClosureResult result = closureService.declare(principal.accountId(), providerId, request);
        if (!result.created()) {
            return ResponseEntity.ok(result.closure());
        }
        return ResponseEntity.created(URI.create("/api/v1/providers/" + providerId + "/closures/" + result.closure().id()))
                .body(result.closure());
    }

    @PostMapping("/{closureId}/cancel")
    public ResponseEntity<ClosureView> cancel(
            @AuthenticationPrincipal AuthenticatedAccount principal,
            @PathVariable UUID providerId,
            @PathVariable UUID closureId) {
        return ResponseEntity.status(HttpStatus.OK).body(closureService.cancel(principal.accountId(), providerId, closureId));
    }
}
