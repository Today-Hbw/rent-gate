package io.rentgate;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

@RestController
public class ReportingController {
    private final ReportingService reporting;
    public ReportingController(ReportingService reporting) { this.reporting=reporting; }
    @GetMapping("/admin/statistics") public ReportingService.Statistics statistics() { return reporting.statistics(); }
    @GetMapping("/admin/orders") public ReportingService.Orders orders(@RequestParam(defaultValue="1") int page,@RequestParam(name="page_size",defaultValue="20") int pageSize,
            @RequestParam(name="device_id",required=false) String deviceId,@RequestParam(required=false) String status,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to) {
        return reporting.orders(page,pageSize,deviceId,status,from,to);
    }
}
