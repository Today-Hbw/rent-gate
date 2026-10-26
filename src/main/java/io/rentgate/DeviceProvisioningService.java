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
    private final PairingAttemptLimiter limiter;
    private final SecureRandom random = new SecureRandom();
    public DeviceProvisioningService(JdbcTemplate jdbc,DeviceService devices,Clock clock,DeviceKeyVault vault,PairingAttemptLimiter limiter) { this.jdbc=jdbc; this.devices=devices; this.clock=clock; this.vault=vault; this.limiter=limiter; }
    public record Setup(String requestId,long expiresTs) {}
    public record Pairing(String status,long expiresTs) {}
    public record PairResult(String status,String pairingCode,String deviceKey,long expiresTs) {}
    public record PairingCode(String code,long expiresTs) {}
    public record Enrollment(String deviceId,String deviceKey,String deviceName) {}
    private record Session(String tokenHash,String code,long expires,boolean approved,int attempts,String enrollmentHash,String hardwareId) {}
    private long now() { return clock.instant().getEpochSecond(); }

    private DeviceService.Device lockAuthorized(String id) {
        devices.device(id);
        jdbc.queryForObject("SELECT device_id FROM device WHERE device_id=? FOR UPDATE",String.class,id);
        return devices.device(id);
    }
    private Session session(String id) {
        var rows=jdbc.query("SELECT * FROM device_pairing WHERE device_id=?",(rs,row)->new Session(rs.getString("token_hash"),rs.getString("pairing_code"),rs.getLong("expires_ts"),rs.getBoolean("approved"),rs.getInt("failed_attempts"),rs.getString("enrollment_code_hash"),rs.getString("hardware_id")),id);
        return rows.isEmpty()?null:rows.get(0);
    }
    private void idle(DeviceService.Device device) {
        if (!device.enabled()) throw Db.conflict("设备已停用");
        if (device.status().equals("on")) throw Db.conflict("请先停止设备，再进入配网或配对");
    }
    @Transactional public Setup requestSetup(String id) {
        var device=lockAuthorized(id); idle(device);
        if (device.setupState().equals("closing")) throw Db.conflict("正在结束配网，请等待设备确认后再进入");
        if (device.setupPending()) return jdbc.queryForObject("SELECT CASE WHEN setup_requested_ts>? THEN setup_request_id END AS request_id,setup_requested_ts FROM device WHERE device_id=?",
                (rs,row)->new Setup(rs.getString(1),rs.getString(1)==null?now()+180:rs.getLong(2)+300),now()-300,id);
        if (!device.online()) throw Db.conflict("设备离线；连接失败后会自动打开配网热点");
        if (device.firmwareVersion().isBlank()) throw Db.conflict("请先烧录 v0.2.0 或更新的固件，再使用后台配网");
        String request=UUID.randomUUID().toString();
        jdbc.update("UPDATE device SET setup_request_id=?,setup_requested_ts=?,setup_close_id=NULL,setup_close_ts=NULL WHERE device_id=?",request,now(),id);
        return new Setup(request,now()+300);
    }
    @Transactional public Setup closeSetup(String id) {
        var device=lockAuthorized(id);
        if (device.setupState().equals("closing")) return jdbc.queryForObject("SELECT setup_close_id,setup_close_ts FROM device WHERE device_id=?",
                (rs,row)->new Setup(rs.getString(1),rs.getLong(2)+300),id);
        String[] version=device.firmwareVersion().split("\\.");
        boolean supported=false;
        try { supported=version.length>=2 && (Integer.parseInt(version[0])>0 || Integer.parseInt(version[1])>=3); } catch (NumberFormatException ignored) { }
        if (!supported) throw Db.conflict("后台结束配网需要 v0.3.0 或更新固件；旧固件请在现场保存配置退出");
        String request=UUID.randomUUID().toString();
        jdbc.update("DELETE FROM device_pairing WHERE device_id=? AND approved=FALSE",id);
        jdbc.update("UPDATE device SET setup_request_id=NULL,setup_requested_ts=NULL,setup_close_id=?,setup_close_ts=? WHERE device_id=?",request,now(),id);
        return new Setup(request,now()+300);
    }
    @Transactional public PairingCode generateCode(String id) {
        idle(lockAuthorized(id));
        jdbc.update("DELETE FROM device_pairing WHERE device_id=?",id);
        String code;
        do { code=String.format(java.util.Locale.ROOT,"%08d",random.nextInt(100000000)); }
        while (jdbc.queryForObject("SELECT COUNT(*) FROM device_pairing WHERE enrollment_code_hash=?",Integer.class,DeviceService.hash(code))>0);
        long expires=now()+300;
        jdbc.update("INSERT INTO device_pairing(device_id,expires_ts,enrollment_code_hash) VALUES(?,?,?)",id,expires,DeviceService.hash(code));
        return new PairingCode(code,expires);
    }
    @Transactional public void cancelPairing(String id) { lockAuthorized(id);jdbc.update("DELETE FROM device_pairing WHERE device_id=?",id); }

    @Transactional public Enrollment enroll(String code,String token,String hardwareId,String address) {
        limiter.check(address);
        String codeHash=DeviceService.hash(code);
        var ids=jdbc.queryForList("SELECT device_id FROM device_pairing WHERE enrollment_code_hash=?",String.class,codeHash);
        if (ids.isEmpty()) throw invalidCode(address);
        String id=ids.get(0);
        jdbc.queryForObject("SELECT device_id FROM device WHERE device_id=? FOR UPDATE",String.class,id);
        Session pairing=session(id);
        if (pairing==null || !codeHash.equals(pairing.enrollmentHash) || pairing.expires<=now()
                || !Boolean.TRUE.equals(jdbc.queryForObject("SELECT enabled FROM device WHERE device_id=?",Boolean.class,id))) throw invalidCode(address);
        String tokenHash=DeviceService.hash(token), hardware=hardwareId.toUpperCase(java.util.Locale.ROOT);
        if (pairing.tokenHash!=null && (!pairing.tokenHash.equals(tokenHash) || !hardware.equals(pairing.hardwareId))) throw invalidCode(address);
        if (!pairing.approved) {
            devices.replaceKey(id,devices.newKey());
            jdbc.update("UPDATE device SET hardware_id=? WHERE device_id=?",hardware,id);
            jdbc.update("UPDATE device_pairing SET token_hash=?,hardware_id=?,approved=TRUE WHERE device_id=?",tokenHash,hardware,id);
        }
        return new Enrollment(id,devicesKey(id),jdbc.queryForObject("SELECT name FROM device WHERE device_id=?",String.class,id));
    }
    private ResponseStatusException invalidCode(String address) {
        limiter.failed(address);
        return new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED,"验证码无效、已过期或已使用，请在后台重新生成");
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
        if (pairing!=null && pairing.enrollmentHash!=null) throw Db.conflict("请将后台生成的验证码填到设备配网页保存");
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
        if (pairing!=null && pairing.enrollmentHash!=null) return new PairResult("waiting",null,null,0);
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
