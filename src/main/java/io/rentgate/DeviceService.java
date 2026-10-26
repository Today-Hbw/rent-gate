package io.rentgate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DeviceService {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AccountService accounts;
    private final ManagementService management;
    private final DeviceKeyVault vault;
    private final SecureRandom random=new SecureRandom();
    public DeviceService(JdbcTemplate jdbc,Clock clock,AccountService accounts,ManagementService management,DeviceKeyVault vault) {
        this.jdbc=jdbc; this.clock=clock; this.accounts=accounts; this.management=management; this.vault=vault;
    }
    public record Device(String deviceId,String name,String status,long expireTs,Long lastSeenTs,Boolean relayOn,boolean online,long remainingSeconds,
                         Long userId,String ownerName,Long providerId,String providerName,Long merchantId,long deviceTypeId,String typeName,boolean enabled,
                         String runSource,Long trialUsedAt,long cooldownUntil,String publicToken,String firmwareVersion,boolean setupPending,String setupState,String hardwareId) {}
    public record Credentials(String deviceId,String deviceKey,String publicToken) {}
    public record Poll(String action,long expireTs,long serverTs,long remainingSeconds,String configRequestId,String configCloseId,String deviceName) {}
    public record GuestDevice(String name,String merchantName,String ownerName,String typeName,String status,boolean online,long serverTs,long expireTs,long remainingSeconds,
                              int trialMinutes,int cooldownMinutes,long cooldownUntil,long cooldownRemainingSeconds,boolean trialAvailable,String trialUnavailableReason,
                              boolean paymentEnabled,List<ManagementService.PublicPlan> plans,String siteName,String guestNotice) {}
    private record Stored(String id,String name,String hash,long expiry,Long seen,Boolean relay,Long ownerId,Long merchantId,long typeId,String token,
                          boolean enabled,String source,Long trialAt,long cooldown) {}

    @Scheduled(fixedDelay=1000) public void expireLeases() { jdbc.update("UPDATE device SET status='off' WHERE status='on' AND expire_ts<=?",now()); }
    private long now() { return clock.instant().getEpochSecond(); }

    private Stored read(String value,boolean byToken,boolean lock) {
        String column=byToken?"public_token":"device_id";
        var found=jdbc.query("SELECT * FROM device WHERE "+column+"=?"+(lock?" FOR UPDATE":""),
                (rs,row)->new Stored(rs.getString("device_id"),rs.getString("name"),rs.getString("key_hash"),rs.getLong("expire_ts"),rs.getObject("last_seen_ts",Long.class),
                        rs.getObject("relay_on",Boolean.class),rs.getObject("user_id",Long.class),rs.getObject("merchant_id",Long.class),rs.getLong("device_type_id"),
                        rs.getString("public_token"),rs.getBoolean("enabled"),rs.getString("run_source"),rs.getObject("trial_used_at",Long.class),rs.getLong("cooldown_until")),value);
        if (found.isEmpty()) throw Db.missing(); return found.get(0);
    }

    private void authorize(Stored device) {
        var actor=accounts.actor(); if (actor.admin()) return;
        if (device.ownerId==null) throw Db.missing(); accounts.requireOwner(actor,device.ownerId,false);
    }

    public List<Device> list() { var scope=ManagementService.ownerScope(accounts.actor(),"d.user_id"); return queryDevices(scope.sql(),scope.params()); }
    public Device device(String id) { Stored stored=read(id,false,false); authorize(stored); return queryDevices("d.device_id=?",id).get(0); }

    private List<Device> queryDevices(String where,Object... params) {
        long now=now(); int timeout=management.settings().onlineTimeoutSeconds();
        return jdbc.query("SELECT d.*,o.display_name AS owner_name,o.provider_id,p.display_name AS provider_name,t.name AS type_name FROM device d JOIN device_type t ON t.id=d.device_type_id LEFT JOIN sys_user o ON o.id=d.user_id LEFT JOIN sys_user p ON p.id=o.provider_id WHERE "
                +where+" ORDER BY d.created_ts DESC,d.device_id",(rs,row)-> {
            long expiry=rs.getLong("expire_ts"); Long seen=rs.getObject("last_seen_ts",Long.class); boolean on=rs.getBoolean("enabled") && expiry>now;
            boolean online=seen!=null && now-seen<=timeout;
            boolean opening=rs.getString("setup_request_id")!=null && rs.getLong("setup_requested_ts")>now-300;
            boolean closing=rs.getString("setup_close_id")!=null && rs.getLong("setup_close_ts")>now-300;
            String setupState=closing?"closing":rs.getBoolean("setup_active") && online?"active":opening?"opening":"idle";
            return new Device(rs.getString("device_id"),rs.getString("name"),on?"on":"off",expiry,seen,rs.getObject("relay_on",Boolean.class),seen!=null && now-seen<=timeout,on?expiry-now:0,
                    rs.getObject("user_id",Long.class),rs.getString("owner_name"),rs.getObject("provider_id",Long.class),rs.getString("provider_name"),rs.getObject("merchant_id",Long.class),
                    rs.getLong("device_type_id"),rs.getString("type_name"),rs.getBoolean("enabled"),rs.getString("run_source"),rs.getObject("trial_used_at",Long.class),rs.getLong("cooldown_until"),rs.getString("public_token"),
                    rs.getString("firmware_version"),!setupState.equals("idle"),setupState,rs.getString("hardware_id"));
        },params);
    }

    @Transactional public Credentials create(String id,String name) { return create(id,name,null,1); }
    @Transactional public Credentials create(String id,String name,Long ownerId,long typeId) {
        var actor=accounts.actor(); ownerId=resolveOwner(actor,ownerId);
        var owner=ownerId==null?null:accounts.requireOwner(actor,ownerId,true); management.requireTypeForOwner(typeId,owner,true);
        String key=newKey(),token=UUID.randomUUID().toString();
        Long merchant=ownerId==null?null:jdbc.queryForObject("SELECT id FROM merchant WHERE user_id=?",Long.class,ownerId);
        try {
            jdbc.update("INSERT INTO device(device_id,name,key_hash,key_cipher,created_ts,user_id,merchant_id,device_type_id,public_token) VALUES(?,?,?,?,?,?,?,?,?)",id,name.strip(),hash(key),vault.encrypt(id,key),now(),ownerId,merchant,typeId,token);
        } catch (DuplicateKeyException error) { throw Db.conflict("设备编号已存在"); }
        return new Credentials(id,key,token);
    }

    private Long resolveOwner(AccountService.Actor actor,Long ownerId) {
        if (actor.owner()) { if (ownerId!=null && ownerId!=actor.id()) throw Db.forbidden(); return actor.id(); }
        if (actor.provider() && ownerId==null) throw Db.bad("请将设备分配给名下老板"); return ownerId;
    }

    @Transactional public Device update(String id,String name,Long ownerId,long typeId,boolean enabled) {
        Stored stored=read(id,false,true); authorize(stored); var actor=accounts.actor(); ownerId=resolveOwner(actor,ownerId);
        var owner=ownerId==null?null:accounts.requireOwner(actor,ownerId,enabled); management.requireTypeForOwner(typeId,owner,enabled);
        if (stored.expiry>now() && (!Objects.equals(stored.ownerId,ownerId) || stored.typeId!=typeId)) throw Db.conflict("运行中的设备不能变更归属或类型，请先停止");
        Long merchant=ownerId==null?null:jdbc.queryForObject("SELECT id FROM merchant WHERE user_id=?",Long.class,ownerId);
        jdbc.update("UPDATE device SET name=?,user_id=?,merchant_id=?,device_type_id=?,enabled=? WHERE device_id=?",name.strip(),ownerId,merchant,typeId,enabled,id);
        if (!enabled) jdbc.update("UPDATE device SET status='off',expire_ts=0 WHERE device_id=?",id);
        return device(id);
    }

    @Transactional public Device release(String id,int minutes) {
        Stored stored=read(id,false,true); authorize(stored); if (!stored.enabled) throw Db.conflict("设备已停用");
        ensureNotProvisioning(id);
        jdbc.update("UPDATE device SET status='on',expire_ts=?,run_source='manual' WHERE device_id=?",now()+minutes*60L,id); return device(id);
    }
    @Transactional public Device stop(String id) {
        Stored stored=read(id,false,true); authorize(stored);
        jdbc.update("UPDATE device SET status='off',expire_ts=0 WHERE device_id=?",id); return device(id);
    }
    @Transactional public Credentials rotateKey(String id) {
        Stored stored=read(id,false,true); authorize(stored); String key=newKey(); replaceKey(id,key);
        jdbc.update("DELETE FROM device_pairing WHERE device_id=?",id);
        return new Credentials(id,key,stored.token);
    }

    public Credentials credentials(String id) {
        Stored stored=read(id,false,false); authorize(stored);
        return new Credentials(id,vault.decrypt(id,jdbc.queryForObject("SELECT key_cipher FROM device WHERE device_id=?",String.class,id)),stored.token);
    }

    void replaceKey(String id,String key) {
        jdbc.update("UPDATE device SET key_hash=?,key_cipher=? WHERE device_id=?",hash(key),vault.encrypt(id,key),id);
    }

    @Transactional public Poll poll(String id,String key,Boolean relayOn) {
        return poll(id,key,relayOn,null,null);
    }

    @Transactional public Poll poll(String id,String key,Boolean relayOn,String configAck,String firmwareVersion) {
        return poll(id,key,relayOn,configAck,firmwareVersion,null,null);
    }
    @Transactional public Poll poll(String id,String key,Boolean relayOn,String configAck,String firmwareVersion,Boolean configActive,String configCloseAck) {
        return poll(id,key,relayOn,configAck,firmwareVersion,configActive,configCloseAck,null);
    }
    @Transactional public Poll poll(String id,String key,Boolean relayOn,String configAck,String firmwareVersion,Boolean configActive,String configCloseAck,String hardwareId) {
        Stored stored;
        try { stored=read(id,false,true); } catch (ResponseStatusException error) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"设备认证失败"); }
        if (key==null || key.length()>128 || !MessageDigest.isEqual(stored.hash.getBytes(StandardCharsets.UTF_8),hash(key).getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"设备认证失败");
        }
        long now=now(); boolean on=stored.enabled && stored.expiry>now;
        // Recover viewable legacy keys only after successful authentication.
        jdbc.update("UPDATE device SET key_cipher=? WHERE device_id=? AND key_cipher IS NULL",vault.encrypt(id,key),id);
        if (firmwareVersion!=null) jdbc.update("UPDATE device SET firmware_version=? WHERE device_id=?",firmwareVersion,id);
        if (hardwareId!=null) jdbc.update("UPDATE device SET hardware_id=? WHERE device_id=?",hardwareId.toUpperCase(java.util.Locale.ROOT),id);
        if (configAck!=null) jdbc.update("UPDATE device SET setup_request_id=NULL,setup_requested_ts=NULL WHERE device_id=? AND setup_request_id=?",id,configAck);
        if (configActive!=null) jdbc.update("UPDATE device SET setup_active=? WHERE device_id=?",configActive,id);
        if (configCloseAck!=null) jdbc.update("UPDATE device SET setup_close_id=NULL,setup_close_ts=NULL WHERE device_id=? AND setup_close_id=?",id,configCloseAck);
        jdbc.update("UPDATE device SET status=?,last_seen_ts=?,relay_on=? WHERE device_id=?",on?"on":"off",now,relayOn,id);
        String command=jdbc.queryForObject("SELECT CASE WHEN setup_requested_ts>? THEN setup_request_id ELSE NULL END FROM device WHERE device_id=?",String.class,now-300,id);
        String close=jdbc.queryForObject("SELECT CASE WHEN setup_close_ts>? THEN setup_close_id ELSE NULL END FROM device WHERE device_id=?",String.class,now-300,id);
        // Legacy firmware has a smaller JSON buffer; metadata is for the new firmware.
        String metadataName=firmwareVersion==null || firmwareVersion.isBlank() || firmwareVersion.equals("0.2.0") || firmwareVersion.equals("0.3.0")?null:stored.name;
        return new Poll(on?"on":"off",stored.expiry,now,on?stored.expiry-now:0,command,close,metadataName);
    }

    public GuestDevice guest(String token) { return guestView(read(token,true,false)); }
    private GuestDevice guestView(Stored stored) {
        if (!stored.enabled) throw Db.missing();
        long now=now(); var type=management.type(stored.typeId); var settings=management.settings();
        boolean online=stored.seen!=null && now-stored.seen<=settings.onlineTimeoutSeconds();
        String unavailable="",merchantName="设备待分配",ownerName=""; boolean ownerEnabled=false;
        if (stored.ownerId!=null) {
            var owner=accounts.account(stored.ownerId); ownerEnabled=accounts.principal(owner.username()).enabled();
            ownerName=owner.displayName();
            merchantName=jdbc.queryForObject("SELECT CASE WHEN name_explicit THEN name ELSE ? END FROM merchant WHERE user_id=?",String.class,ownerName,stored.ownerId);
        }
        if (!ownerEnabled) unavailable="设备尚未分配或所属商户已停用";
        else if (!type.enabled() || type.trialMinutes()==0) unavailable="该类型设备暂未开放试用";
        else if (provisioningActive(stored.id)) unavailable="设备正在配网或配对，请稍后重试";
        else if (!online) unavailable="设备离线，请稍后重试或联系老板";
        else if (stored.expiry>now) unavailable="设备正在使用中";
        else if (stored.cooldown>now) unavailable="设备正在试用冷却期内";
        String status=stored.expiry>now?(stored.source.equals("trial")?"trial":"running"):stored.cooldown>now?"cooldown":"standby";
        return new GuestDevice(stored.name,merchantName,ownerName,type.name(),status,online,now,stored.expiry,Math.max(0,stored.expiry-now),type.trialMinutes(),type.cooldownMinutes(),
                stored.cooldown,Math.max(0,stored.cooldown-now),unavailable.isEmpty(),unavailable,false,stored.ownerId==null?List.of():management.publicPlans(stored.ownerId,stored.typeId),settings.siteName(),settings.guestNotice());
    }

    @Transactional public GuestDevice trial(String token) {
        Stored stored=read(token,true,true); GuestDevice before=guestView(stored);
        if (!before.trialAvailable) throw Db.conflict(before.trialUnavailableReason);
        ensureNotProvisioning(stored.id);
        long now=now(),expiry=now+before.trialMinutes*60L,cooldown=expiry+before.cooldownMinutes*60L;
        jdbc.update("UPDATE device SET status='on',expire_ts=?,run_source='trial',trial_used_at=?,cooldown_until=? WHERE device_id=?",expiry,now,cooldown,stored.id);
        return guestView(read(token,true,false));
    }

    private void ensureNotProvisioning(String id) {
        if (provisioningActive(id)) throw Db.conflict("设备正在配网或配对，请完成后再运行");
    }
    private boolean provisioningActive(String id) {
        int active=jdbc.queryForObject("SELECT COUNT(*) FROM device WHERE device_id=? AND ((setup_request_id IS NOT NULL AND setup_requested_ts>?) OR (setup_active=TRUE AND last_seen_ts>?) OR EXISTS (SELECT 1 FROM device_pairing p WHERE p.device_id=device.device_id AND p.expires_ts>? AND p.approved=FALSE))",Integer.class,id,now()-300,now()-management.settings().onlineTimeoutSeconds(),now());
        return active>0;
    }
    String newKey() { byte[] bytes=new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    static String hash(String key) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
