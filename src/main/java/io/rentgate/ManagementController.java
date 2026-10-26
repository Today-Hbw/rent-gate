package io.rentgate;

import java.math.BigDecimal;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class ManagementController {
    private final ManagementService management;
    public ManagementController(ManagementService management) { this.management=management; }
    public record Type(@NotBlank @Size(max=80) String name,Long providerId,@Min(0) @Max(1440) int trialMinutes,@Min(0) @Max(10080) int cooldownMinutes,boolean enabled) {}
    public record Merchant(@NotBlank @Size(max=80) String name,@NotNull @Pattern(regexp="[0-9]{0,32}") String wechatMchId,
                           @NotNull @Pattern(regexp="[0-9]{0,32}") String wechatSubMchId,@NotNull @Pattern(regexp="[0-9]{0,32}") String alipayAppId) {}
    public record Plan(Long userId,@Min(1) long deviceTypeId,@NotBlank @Size(max=80) String name,@Min(1) @Max(1440) int durationMin,
                       @NotNull @DecimalMin("0") @DecimalMax("99999.99") @Digits(integer=5,fraction=2) BigDecimal price,boolean enabled) {}
    public record Settings(@NotBlank @Size(max=80) String siteName,@NotNull @Size(max=240) String guestNotice,@Min(6) @Max(300) int onlineTimeoutSeconds) {}
    @GetMapping("/admin/device-types") public List<ManagementService.DeviceType> types() { return management.types(); }
    @PostMapping("/admin/device-types") public ManagementService.DeviceType createType(@Valid @RequestBody Type input) { return saveType(null,input); }
    @PutMapping("/admin/device-types/{id}") public ManagementService.DeviceType updateType(@PathVariable long id,@Valid @RequestBody Type input) { return saveType(id,input); }
    @DeleteMapping("/admin/device-types/{id}") public java.util.Map<String,String> deleteType(@PathVariable long id) { management.deleteType(id);return java.util.Map.of("status","deleted"); }
    private ManagementService.DeviceType saveType(Long id,Type input) { return management.saveType(id,input.name,input.providerId,input.trialMinutes,input.cooldownMinutes,input.enabled); }
    @GetMapping("/admin/merchants") public List<ManagementService.Merchant> merchants() { return management.merchants(); }
    @PutMapping("/admin/merchants/{id}") public ManagementService.Merchant saveMerchant(@PathVariable long id,@Valid @RequestBody Merchant input) { return management.saveMerchant(id,input.name,input.wechatMchId,input.wechatSubMchId,input.alipayAppId); }
    @GetMapping("/admin/plans") public List<ManagementService.Plan> plans() { return management.plans(); }
    @PostMapping("/admin/plans") public ManagementService.Plan createPlan(@Valid @RequestBody Plan input) { return savePlan(null,input); }
    @PutMapping("/admin/plans/{id}") public ManagementService.Plan updatePlan(@PathVariable long id,@Valid @RequestBody Plan input) { return savePlan(id,input); }
    private ManagementService.Plan savePlan(Long id,Plan input) { return management.savePlan(id,input.userId,input.deviceTypeId,input.name,input.durationMin,input.price,input.enabled); }
    @GetMapping("/admin/settings") public ManagementService.Settings settings() { return management.settings(); }
    @PutMapping("/admin/settings") public ManagementService.Settings saveSettings(@Valid @RequestBody Settings input) { return management.saveSettings(input.siteName,input.guestNotice,input.onlineTimeoutSeconds); }
}
