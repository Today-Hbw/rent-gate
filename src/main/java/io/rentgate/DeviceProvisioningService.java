package io.rentgate;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DeviceProvisioningService {
    private final JdbcTemplate jdbc;
    private final DeviceService devices;
    private final Clock clock;
    private final DeviceKeyVault vault;
    private final SecureRandom random = new SecureRandom();
    public DeviceProvisioningService(JdbcTemplate jdbc,DeviceService devices,Clock clock,DeviceKeyVault vault) { this.jdbc=jdbc; this.devices=devices; this.clock=clock; this.vault=vault; }
    public record Setup(String requestId,long expiresTs) {}
    public record Pairing(String status,long expiresTs) {}
    public record PairResult(String status,String pairingCode,String deviceKey,long expiresTs) {}
    private record Session(String tokenHash,String code,long expires,boolean approved,int attempts) {}
    private long now() { return clock.instant().getEpochSecond(); }

    private DeviceService.Device lockAuthorized(String id) {
        devices.device(id);
        jdbc.queryForObject("SELECT device_id FROM device WHERE device_id=? FOR UPDATE",String.class,id);
        return devices.device(id);
    }
    private Session session(String id) {
        var rows=jdbc.query("SELECT * FROM device_pairing WHERE device_id=?",(rs,row)->new Session(rs.getString("token_hash"),rs.getString("pairing_code"),rs.getLong("expires_ts"),rs.getBoolean("approved"),rs.getInt("failed_attempts")),id);
        return rows.isEmpty()?null:rows.get(0);
    }
    private void idle(DeviceService.Device device) {
        if (!device.enabled()) throw Db.conflict("设备已停用");
        if (device.status().equals("on")) throw Db.conflict("请先停止设备，再进入配网或配对");
    }
    @Transactional public Setup requestSetup(String id) {
        var device=lockAuthorized(id); idle(device);
        if (device.setupPending()) return jdbc.queryForObject("SELECT setup_request_id,setup_requested_ts FROM device WHERE device_id=?",
                (rs,row)->new Setup(rs.getString(1),rs.getLong(2)+300),id);
        if (!device.online()) throw Db.conflict("设备离线；连接失败后会自动打开配网热点");
        if (device.firmwareVersion().isBlank()) throw Db.conflict("请先烧录 v0.2.0 或更新的固件，再使用后台配网");
        String request=UUID.randomUUID().toString();
        jdbc.update("UPDATE device SET setup_request_id=?,setup_requested_ts=? WHERE device_id=?",request,now(),id);
        return new Setup(request,now()+300);
    }
    @Transactional public Pairing beginPairing(String id) {
        idle(lockAuthorized(id)); Session existing=session(id);
        if (existing!=null && !existing.approved && existing.expires>now() && existing.attempts<5) return new Pairing("waiting",existing.expires);
        jdbc.update("DELETE FROM device_pairing WHERE device_id=?",id);
        long expires=now()+300;
        jdbc.update("INSERT INTO device_pairing(device_id,expires_ts) VALUES(?,?)",id,expires);
        return new Pairing("waiting",expires);
    }
    @Transactional(noRollbackFor=ResponseStatusException.class)
    public Pairing confirmPairing(String id,String code) {
        idle(lockAuthorized(id)); Session pairing=session(id);
        if (pairing==null || pairing.expires<=now() || pairing.attempts>=5) throw Db.conflict("配对已过期或尝试次数过多，请重新开始配对");
        if (pairing.code==null) throw Db.conflict("尚未收到设备请求，请先在设备配网页留空密钥并保存");
        if (!MessageDigest.isEqual(pairing.code.getBytes(StandardCharsets.UTF_8),code.getBytes(StandardCharsets.UTF_8))) {
            jdbc.update("UPDATE device_pairing SET failed_attempts=failed_attempts+1 WHERE device_id=?",id);
            throw Db.bad("配对码不正确，请核对设备配网页显示的 6 位数字");
        }
        if (!pairing.approved) {
            devices.replaceKey(id,devices.newKey());
            jdbc.update("UPDATE device_pairing SET approved=TRUE WHERE device_id=?",id);
        }
        return new Pairing("approved",pairing.expires);
    }
    @Transactional public PairResult pollPairing(String id,String token) {
        var found=jdbc.queryForList("SELECT device_id FROM device WHERE device_id=? AND enabled=TRUE FOR UPDATE",String.class,id);
        if (found.isEmpty()) return new PairResult("waiting",null,null,0);
        Session pairing=session(id);
        if (pairing==null || pairing.expires<=now() || pairing.attempts>=5) return new PairResult("waiting",null,null,0);
        String hashed=DeviceService.hash(token);
        if (pairing.tokenHash==null) {
            String code=String.format(java.util.Locale.ROOT,"%06d",random.nextInt(1000000));
            jdbc.update("UPDATE device_pairing SET token_hash=?,pairing_code=? WHERE device_id=?",hashed,code,id);
            return new PairResult("pending",code,null,pairing.expires);
        }
        if (!MessageDigest.isEqual(pairing.tokenHash.getBytes(StandardCharsets.UTF_8),hashed.getBytes(StandardCharsets.UTF_8))) {
            return new PairResult("busy",null,null,pairing.expires);
        }
        // The token is a per-device secret; never expose it in admin/public lists.
        String key=pairing.approved?devicesKey(id):null;
        return new PairResult(pairing.approved?"approved":"pending",pairing.code,key,pairing.expires);
    }
    private String devicesKey(String id) {
        // Internal device response bypasses user sessions only after token verification.
        return vault.decrypt(id,jdbc.queryForObject("SELECT key_cipher FROM device WHERE device_id=?",String.class,id));
    }
}
