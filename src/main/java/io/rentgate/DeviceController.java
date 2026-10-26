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
    private final DeviceProvisioningService provisioning;
    public DeviceController(DeviceService devices,DeviceProvisioningService provisioning) { this.devices = devices; this.provisioning=provisioning; }

    public record Create(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String deviceId,
                         @NotBlank @Size(max = 80) String name, Long userId, Long deviceTypeId) {}
    public record Update(@NotBlank @Size(max=80) String name,Long userId,@Min(1) long deviceTypeId,boolean enabled) {}
    public record Release(@NotBlank @Size(max = 64) String deviceId,
                          @Min(1) @Max(1440) int durationMin) {}
    public record Target(@NotBlank @Size(max = 64) String deviceId) {}
    public record PollRequest(@NotBlank @Size(max = 64) String deviceId, Boolean relayOn,
                              @Size(max=36) String configAck,@Size(max=32) String firmwareVersion,
                              Boolean configActive,@Size(max=36) String configCloseAck,@Pattern(regexp="[A-Fa-f0-9]{12}") String hardwareId) {}
    public record PairRequest(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String deviceId,
                              @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String token) {}
    public record PairConfirmation(@NotBlank @Pattern(regexp="[0-9]{6}") String code) {}
    public record EnrollRequest(@NotBlank @Pattern(regexp="[0-9]{8}") String code,
                                @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{43}") String token,
                                @NotBlank @Pattern(regexp="[A-Fa-f0-9]{12}") String hardwareId) {}

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
        return ResponseEntity.status(HttpStatus.CREATED).body(devices.create(request.deviceId, request.name,request.userId,
                request.deviceTypeId==null?1:request.deviceTypeId));
    }

    @PutMapping("/admin/devices/{id}")
    public DeviceService.Device update(@PathVariable String id,@Valid @RequestBody Update request) {
        return devices.update(id,request.name,request.userId,request.deviceTypeId,request.enabled);
    }

    @PostMapping("/admin/release")
    public DeviceService.Device release(@Valid @RequestBody Release request) {
        return devices.release(request.deviceId, request.durationMin);
    }

    @PostMapping("/admin/stop")
    public DeviceService.Device stop(@Valid @RequestBody Target request) { return devices.stop(request.deviceId); }

    @PostMapping("/admin/rotate-key")
    public DeviceService.Credentials rotate(@Valid @RequestBody Target request) { return devices.rotateKey(request.deviceId); }

    @GetMapping("/admin/devices/{id}/credentials")
    public ResponseEntity<DeviceService.Credentials> credentials(@PathVariable String id) {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(devices.credentials(id));
    }
    @PostMapping("/admin/devices/{id}/setup")
    public DeviceProvisioningService.Setup setup(@PathVariable String id) { return provisioning.requestSetup(id); }
    @PostMapping("/admin/devices/{id}/setup/close")
    public DeviceProvisioningService.Setup closeSetup(@PathVariable String id) { return provisioning.closeSetup(id); }
    @PostMapping("/admin/devices/{id}/pairing-code")
    public ResponseEntity<DeviceProvisioningService.PairingCode> pairingCode(@PathVariable String id) {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(provisioning.generateCode(id));
    }
    @DeleteMapping("/admin/devices/{id}/pairing")
    public Map<String,String> cancelPairing(@PathVariable String id) { provisioning.cancelPairing(id);return Map.of("status","cancelled"); }
    @PostMapping("/device/enroll")
    public ResponseEntity<DeviceProvisioningService.Enrollment> enroll(@Valid @RequestBody EnrollRequest input,jakarta.servlet.http.HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(provisioning.enroll(input.code,input.token,input.hardwareId,request.getRemoteAddr()));
    }
    @PostMapping("/admin/devices/{id}/pairing")
    public DeviceProvisioningService.Pairing pairing(@PathVariable String id) { return provisioning.beginPairing(id); }
    @PostMapping("/admin/devices/{id}/pairing/confirm")
    public DeviceProvisioningService.Pairing confirm(@PathVariable String id,@Valid @RequestBody PairConfirmation input) { return provisioning.confirmPairing(id,input.code); }
    @PostMapping("/device/pair")
    public ResponseEntity<DeviceProvisioningService.PairResult> pair(@Valid @RequestBody PairRequest input) {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(provisioning.pollPairing(input.deviceId,input.token));
    }

    @PostMapping("/poll")
    public DeviceService.Poll poll(@RequestHeader(value = "X-Device-Key", required = false) String key,
                                  @Valid @RequestBody PollRequest request) {
        return devices.poll(request.deviceId, key, request.relayOn,request.configAck,request.firmwareVersion,request.configActive,request.configCloseAck,request.hardwareId);
    }
}
