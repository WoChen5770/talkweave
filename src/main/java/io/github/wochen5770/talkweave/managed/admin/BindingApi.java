package io.github.wochen5770.talkweave.managed.admin;

import io.github.wochen5770.talkweave.managed.binding.BindingCoordinator;
import io.github.wochen5770.talkweave.managed.persistence.ManagedUsers;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("managed")
@RequestMapping("/api/admin/users/{userId}/binding-attempts")
public final class BindingApi {
    private final BindingCoordinator coordinator;
    private final io.github.wochen5770.talkweave.managed.runtime.RuntimeManager runtime;
    public BindingApi(BindingCoordinator coordinator, io.github.wochen5770.talkweave.managed.runtime.RuntimeManager runtime) { this.coordinator = coordinator; this.runtime = runtime; }
    public record Create(ManagedUsers.Mode mode, long authEpoch, String previousAttempt, boolean confirmReplacement) { }
    public record Pairing(String code) { @Override public String toString() { return "Pairing[REDACTED]"; } }
    @PostMapping BindingCoordinator.Status create(@PathVariable String userId, @RequestBody Create request) {
        try { return coordinator.create(userId, request.mode(), request.authEpoch(), request.previousAttempt(), request.confirmReplacement()); }
        finally { runtime.reconcile(); }
    }
    @GetMapping("/current") BindingCoordinator.Status current(@PathVariable String userId) { return coordinator.current(userId); }
    @GetMapping("/{id}") BindingCoordinator.Status get(@PathVariable String userId, @PathVariable String id) { return coordinator.get(userId, id); }
    @GetMapping(value = "/{id}/qr", produces = MediaType.IMAGE_PNG_VALUE)
    ResponseEntity<byte[]> image(@PathVariable String userId, @PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache")
                .header("X-Content-Type-Options", "nosniff").body(coordinator.image(userId, id));
    }
    @PostMapping("/{id}/cancel") BindingCoordinator.Status cancel(@PathVariable String userId, @PathVariable String id) { return coordinator.cancel(userId, id); }
    @PostMapping("/{id}/pairing") BindingCoordinator.Status pair(@PathVariable String userId, @PathVariable String id, @RequestBody Pairing request) {
        return coordinator.pair(userId, id, request.code());
    }
}
