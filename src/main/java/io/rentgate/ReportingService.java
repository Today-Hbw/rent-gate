package io.rentgate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ReportingService {
    private static final ZoneId REPORT_ZONE=ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final AccountService accounts;
    private final DeviceService devices;
    private final Clock clock;
    public ReportingService(JdbcTemplate jdbc,AccountService accounts,DeviceService devices,Clock clock) {
        this.jdbc=jdbc; this.accounts=accounts; this.devices=devices; this.clock=clock;
    }
    public record DailyRevenue(String date,BigDecimal revenue) {}
    public record Statistics(long totalDevices,long onlineDevices,long runningDevices,long trialDevices,long totalOrders,long paidOrders,
                             BigDecimal totalRevenue,BigDecimal todayRevenue,List<DailyRevenue> dailyRevenue) {}
    public record Order(String orderNo,String deviceId,String deviceName,Long userId,String ownerName,Long merchantId,int durationMin,
                        BigDecimal amount,String payChannel,String status,String payTransactionId,long createdTs) {}
    public record Orders(List<Order> items,long total,int page,int pageSize) {}

    public Statistics statistics() {
        var scope=ManagementService.ownerScope(accounts.actor(),"r.user_id");
        var visible=devices.list();
        LocalDate today=LocalDate.ofInstant(clock.instant(),REPORT_ZONE);
        long start=today.atStartOfDay(REPORT_ZONE).toEpochSecond(),end=today.plusDays(1).atStartOfDay(REPORT_ZONE).toEpochSecond();
        var args=new ArrayList<Object>(); args.add(start); args.add(end); args.addAll(Arrays.asList(scope.params()));
        var orderTotals=jdbc.queryForMap("""
                SELECT COUNT(*) AS total_orders,COALESCE(SUM(CASE WHEN status='paid' THEN 1 ELSE 0 END),0) AS paid_orders,
                COALESCE(SUM(CASE WHEN status='paid' THEN amount ELSE 0 END),0) AS total_revenue,
                COALESCE(SUM(CASE WHEN status='paid' AND created_ts>=? AND created_ts<? THEN amount ELSE 0 END),0) AS today_revenue
                FROM rental_order r WHERE
                """+scope.sql(),args.toArray());
        long first=today.minusDays(6).atStartOfDay(REPORT_ZONE).toEpochSecond();
        args=new ArrayList<>(Arrays.asList(scope.params())); args.add(first); args.add(end);
        var daily=new java.util.HashMap<Long,BigDecimal>();
        jdbc.query("SELECT FLOOR((created_ts+28800)/86400.0) AS day_number,SUM(amount) AS revenue FROM rental_order r WHERE "
                +scope.sql()+" AND status='paid' AND created_ts>=? AND created_ts<? GROUP BY day_number",rs->{
                    daily.put(rs.getLong("day_number"),rs.getBigDecimal("revenue"));
                },args.toArray());
        List<DailyRevenue> series=new ArrayList<>();
        for(int offset=6;offset>=0;offset--) {
            LocalDate date=today.minusDays(offset);
            series.add(new DailyRevenue(date.toString(),daily.getOrDefault(date.toEpochDay(),BigDecimal.ZERO)));
        }
        return new Statistics(visible.size(),visible.stream().filter(DeviceService.Device::online).count(),
                visible.stream().filter(d->d.status().equals("on")).count(),visible.stream().filter(d->d.status().equals("on")&&d.runSource().equals("trial")).count(),
                ((Number)orderTotals.get("total_orders")).longValue(),((Number)orderTotals.get("paid_orders")).longValue(),
                (BigDecimal)orderTotals.get("total_revenue"),(BigDecimal)orderTotals.get("today_revenue"),series);
    }

    public Orders orders(int page,int pageSize,String deviceId,String status,LocalDate from,LocalDate to) {
        if(page<1 || page>1000000 || pageSize<1 || pageSize>100) throw Db.bad("页码需大于0，每页1–100条");
        if(deviceId!=null && deviceId.length()>64) throw Db.bad("设备编号过长");
        if(status!=null && !status.isBlank() && !List.of("pending","paid","closed","refunded").contains(status)) throw Db.bad("订单状态无效");
        if(from!=null && to!=null && from.isAfter(to)) throw Db.bad("开始日期不能晚于结束日期");
        var scope=ManagementService.ownerScope(accounts.actor(),"r.user_id");
        StringBuilder where=new StringBuilder(scope.sql()); var params=new ArrayList<>(Arrays.asList(scope.params()));
        if(deviceId!=null && !deviceId.isBlank()) { where.append(" AND r.device_id=?"); params.add(deviceId); }
        if(status!=null && !status.isBlank()) { where.append(" AND r.status=?"); params.add(status); }
        if(from!=null) { where.append(" AND r.created_ts>=?"); params.add(from.atStartOfDay(REPORT_ZONE).toEpochSecond()); }
        if(to!=null) { where.append(" AND r.created_ts<?"); params.add(to.plusDays(1).atStartOfDay(REPORT_ZONE).toEpochSecond()); }
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM rental_order r WHERE "+where,Long.class,params.toArray());
        params.add(pageSize); params.add((page-1L)*pageSize);
        var rows=jdbc.query("SELECT r.*,u.display_name AS owner_name FROM rental_order r LEFT JOIN sys_user u ON u.id=r.user_id WHERE "+where
                +" ORDER BY r.created_ts DESC,r.order_no LIMIT ? OFFSET ?",(rs,row)->new Order(rs.getString("order_no"),rs.getString("device_id"),
                rs.getString("device_name"),rs.getObject("user_id",Long.class),rs.getString("owner_name"),rs.getObject("merchant_id",Long.class),
                rs.getInt("duration_min"),rs.getBigDecimal("amount"),rs.getString("pay_channel"),rs.getString("status"),rs.getString("pay_transaction_id"),rs.getLong("created_ts")),params.toArray());
        return new Orders(rows,total,page,pageSize);
    }
}
