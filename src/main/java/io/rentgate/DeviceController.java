package io.rentgate;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
public class DeviceController {
    private final DeviceService devices;
    public DeviceController(DeviceService devices) { this.devices = devices; }

    public record Create(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String deviceId,
                         @NotBlank @Size(max = 80) String name) {}
    public record Release(@NotBlank @Size(max = 64) String deviceId,
                          @Min(1) @Max(1440) int durationMin) {}
    public record Target(@NotBlank @Size(max = 64) String deviceId) {}
    public record PollRequest(@NotBlank @Size(max = 64) String deviceId, Boolean relayOn) {}

    @GetMapping("/health")
    public Map<String, String> health() { return Map.of("status", "ok"); }

    @GetMapping("/admin/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "header_name", token.getHeaderName());
    }

    @GetMapping("/admin/devices")
    public List<DeviceService.Device> list() { return devices.list(); }

    @PostMapping("/admin/devices")
    public ResponseEntity<DeviceService.Credentials> create(@Valid @RequestBody Create request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(devices.create(request.deviceId, request.name));
    }

    @PostMapping("/admin/release")
    public DeviceService.Device release(@Valid @RequestBody Release request) {
        return devices.release(request.deviceId, request.durationMin);
    }

    @PostMapping("/admin/stop")
    public DeviceService.Device stop(@Valid @RequestBody Target request) { return devices.stop(request.deviceId); }

    @PostMapping("/admin/rotate-key")
    public DeviceService.Credentials rotate(@Valid @RequestBody Target request) { return devices.rotateKey(request.deviceId); }

    @PostMapping("/poll")
    public DeviceService.Poll poll(@RequestHeader(value = "X-Device-Key", required = false) String key,
                                  @Valid @RequestBody PollRequest request) {
        return devices.poll(request.deviceId, key, request.relayOn);
    }
}
