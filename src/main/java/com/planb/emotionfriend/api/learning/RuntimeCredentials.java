package com.planb.emotionfriend.api.learning;

import static com.planb.emotionfriend.api.learning.RuntimeContracts.*;
import com.planb.emotionfriend.api.auth.AuthContracts.Role;
import com.planb.emotionfriend.api.children.*;
import com.planb.emotionfriend.api.common.error.*;
import com.planb.emotionfriend.api.common.idempotency.*;
import com.planb.emotionfriend.api.common.security.*;
import com.planb.emotionfriend.api.membership.MemberGuard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.sql.Timestamp;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;

/** Opaque purpose-separated runtime credentials; only hashes outside encrypted foundation replay. */
@Service
public class RuntimeCredentials {
    private final JdbcTemplate db;private final MemberGuard guard;private final ChildAccessPolicy access;
    private final Clock clock;private final IdempotencyService idempotency;private final JsonMapper json;
    private final RequestFingerprint fingerprint;private final TransactionTemplate tx;
    private static final SecureRandom RANDOM=new SecureRandom();
    public RuntimeCredentials(JdbcTemplate db,MemberGuard guard,ChildAccessPolicy access,Clock clock,
            IdempotencyService idempotency,JsonMapper json,RequestFingerprint fingerprint,PlatformTransactionManager manager) {
        this.db=db;this.guard=guard;this.access=access;this.clock=clock;this.idempotency=idempotency;this.json=json;this.fingerprint=fingerprint;tx=new TransactionTemplate(manager);
    }
    public StoredResponse grant(ActorPrincipal adult,UUID child,DeviceRequest request,String key) {
        return idempotency.execute(adult,"POST","/api/v1/children/"+child+"/device-grants",key,request,()->{
            access.check(adult,child.toString(),ChildAccessChecker.Capability.MANAGE);guard.recent(adult,Role.GUARDIAN);consent(child);
            // Replays after exchange/expiry/revocation must not recover a grant secret.
            var old=db.queryForList("select g.*,d.generation as current_generation,d.revoked from learning.device_grant g join learning.runtime_device d on d.id=g.device where g.issuer_scope=? and g.request_key=? and d.child_id=? and d.device_id=?",fingerprint.actorScope(adult),fingerprint.key(key),child,request.deviceId());
            if(!old.isEmpty()) {var g=old.getFirst();if(g.get("consumed_at")!=null || Boolean.TRUE.equals(g.get("revoked")) || !g.get("generation").equals(g.get("current_generation")) || !instant(g,"expires_at").isAfter(clock.instant()))deny();}
        },()->{
            var devices=db.queryForList("select * from learning.runtime_device where child_id=? and device_id=?",child,request.deviceId());
            UUID device;
            if(devices.isEmpty()) {device=UUID.randomUUID();db.update("insert into learning.runtime_device(id,child_id,device_id,consent_revision) values(?,?,?,?)",device,child,request.deviceId(),consent(child));}
            else {device=(UUID)devices.getFirst().get("id");if(Boolean.TRUE.equals(devices.getFirst().get("revoked")))db.update("update learning.runtime_device set generation=generation+1,revoked=false,consent_revision=? where id=?",consent(child),device);}
            var d=device(device);String code=secret();UUID id=UUID.randomUUID();Instant until=clock.instant().plus(Duration.ofDays(1));
            db.update("insert into learning.device_grant(id,device,generation,code_hash,expires_at,issuer_scope,request_key,issuer_id,issuer_session_id) values(?,?,?,?,?,?,?,?,?)",id,device,d.get("generation"),LearningCatalog.hash(code),ts(until),fingerprint.actorScope(adult),fingerprint.key(key),UUID.fromString(adult.actorId()),UUID.fromString(adult.sessionId()));
            return response(201,new Grant(id,child,request.deviceId(),code,until));
        });
    }
    public StoredResponse exchange(Exchange request,String key) {
        var grants=db.queryForList("select * from learning.device_grant where code_hash=?",LearningCatalog.hash(request.oneTimeCode()));if(grants.isEmpty())deny();
        var g=grants.getFirst();var actor=scope((UUID)g.get("device"),(UUID)g.get("id"),null);
        return idempotency.execute(actor,"POST","/api/v1/child-sessions/exchange",key,request,()->{
            guard.lockDomain();var d=device((UUID)g.get("device"));matchDevice(d,request.deviceId());
            if(!d.get("generation").equals(g.get("generation")) || !instant(g,"expires_at").isAfter(clock.instant()))deny();
        },()->{
            if(db.update("update learning.device_grant set consumed_at=? where id=? and consumed_at is null",ts(clock.instant()),g.get("id"))!=1)deny();
            return response(200,issue((UUID)g.get("device"),null));
        });
    }
    @Transactional public ActorPrincipal verify(String token) {
        if(token==null || !token.startsWith("rt1_") || token.length()>128)deny();
        var rows=db.queryForList("select * from learning.runtime_credential where token_hash=?",LearningCatalog.hash(token));if(rows.isEmpty())deny();
        var c=rows.getFirst();if(!instant(c,"expires_at").isAfter(clock.instant()))throw new ApiException(ErrorCode.TOKEN_EXPIRED);
        var actor=scope((UUID)c.get("device"),(UUID)c.get("id"),(UUID)c.get("plan_id"));require(actor);return actor;
    }
    public Map<String,Object> require(ActorPrincipal actor) {
        guard.lockDomain();if(actor==null || actor.kind()!=ActorPrincipal.Kind.CHILD || actor.sessionId()==null)deny();
        var credentials=db.queryForList("select * from learning.runtime_credential where id=?",UUID.fromString(actor.sessionId()));if(credentials.isEmpty())deny();
        var c=credentials.getFirst();var d=device((UUID)c.get("device"));
        if(!c.get("generation").equals(d.get("generation")) || !instant(c,"expires_at").isAfter(clock.instant())
                || !actor.actorId().equals(d.get("id").toString()) || !actor.childId().equals(d.get("child_id").toString())
                || !actor.deviceId().equals(d.get("device_id")) || !Objects.equals(actor.planId(),c.get("plan_id")==null?null:c.get("plan_id").toString()))deny();
        return d;
    }
    public StoredResponse renew(ActorPrincipal actor,DeviceRequest request,String key,boolean persist) {
        String path="/api/v1/child-sessions/"+(persist?"persist":"renew");
        return idempotency.execute(actor,"POST",path,key,request,()->{var d=require(actor);matchDevice(d,request.deviceId());if(persist && actor.planId()!=null)deny();},()->{
            UUID device=UUID.fromString(actor.actorId());
            return response(200,persist?persistent(device,clock.instant().plus(Duration.ofDays(30))):issue(device,actor.planId()==null?null:UUID.fromString(actor.planId())));
        });
    }
    public StoredResponse refresh(Refresh request,String key) {
        com.planb.emotionfriend.api.common.validation.RequestPreconditions.idempotencyKey(key);
        // Commit reuse revocation before reporting authentication failure, as adult refresh does.
        Object result=tx.execute(status->{
            guard.lockDomain();var rows=db.queryForList("select * from learning.runtime_refresh where token_hash=?",LearningCatalog.hash(request.refreshToken()));if(rows.isEmpty())return ErrorCode.AUTH_REQUIRED;
            var r=rows.getFirst();UUID device=(UUID)r.get("device");var d=device(device);matchDevice(d,request.deviceId());
            if(!r.get("generation").equals(d.get("generation")) || !instant(r,"expires_at").isAfter(clock.instant()))return ErrorCode.AUTH_REQUIRED;
            if(Boolean.TRUE.equals(r.get("used"))) {
                boolean unused=Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from learning.runtime_refresh where id=? and not used)",Boolean.class,r.get("successor")));
                if(!fingerprint.key(key).equals(r.get("accepted_key")) || !fingerprint.request(request).equals(r.get("body_hash")) || !unused) {
                    invalidate(device);return ErrorCode.AUTH_REQUIRED;
                }
            }
            var actor=scope(device,(UUID)r.get("id"),null);
            return idempotency.execute(actor,"POST","/api/v1/child-sessions/refresh",key,request,()->{device(device);},()->{
                if(Boolean.TRUE.equals(r.get("used")))throw new ApiException(ErrorCode.AUTH_REQUIRED);
                var p=persistent(device,instant(r,"expires_at"));
                UUID successor=db.queryForObject("select id from learning.runtime_refresh where token_hash=?",UUID.class,LearningCatalog.hash(p.refreshToken()));
                db.update("update learning.runtime_refresh set used=true,accepted_key=?,body_hash=?,successor=? where id=?",fingerprint.key(key),fingerprint.request(request),successor,r.get("id"));
                return response(200,p);
            });
        });
        if(result instanceof ErrorCode error)throw new ApiException(error);return (StoredResponse)result;
    }
    public StoredResponse revoke(ActorPrincipal adult,UUID child,String deviceId,String key) {
        return idempotency.execute(adult,"DELETE","/api/v1/children/"+child+"/devices/"+deviceId,key,Map.of(),()->{
            access.check(adult,child.toString(),ChildAccessChecker.Capability.MANAGE);guard.recent(adult,Role.GUARDIAN);
        },()->{for(UUID id:db.queryForList("select id from learning.runtime_device where child_id=? and device_id=?",UUID.class,child,deviceId))invalidate(id);return response(200,Map.of("revoked",true));});
    }
    public Credential issue(UUID device,UUID plan) {
        var d=device(device);UUID id=UUID.randomUUID();String token="rt1_"+secret();Instant until=clock.instant().plusSeconds(600);
        db.update("insert into learning.runtime_credential values(?,?,?,?,?,?)",id,device,d.get("generation"),plan,LearningCatalog.hash(token),ts(until));
        return new Credential(new Token(token,until),(UUID)d.get("child_id"),plan,(String)d.get("device_id"),((Number)d.get("generation")).longValue());
    }
    private Persistent persistent(UUID device,Instant until) {
        var d=device(device);String token=secret();
        db.update("insert into learning.runtime_refresh(id,device,generation,token_hash,expires_at) values(?,?,?,?,?)",UUID.randomUUID(),device,d.get("generation"),LearningCatalog.hash(token),ts(until));
        return new Persistent(issue(device,null),token,until);
    }
    private ActorPrincipal scope(UUID device,UUID credential,UUID plan) {
        var d=db.queryForMap("select * from learning.runtime_device where id=?",device);
        return new ActorPrincipal(device.toString(),ActorPrincipal.Kind.CHILD,d.get("child_id").toString(),(String)d.get("device_id"),plan==null?null:plan.toString(),credential.toString(),false);
    }
    private Map<String,Object> device(UUID id) {
        var rows=db.queryForList("select * from learning.runtime_device where id=?",id);if(rows.isEmpty())deny();var d=rows.getFirst();
        if(Boolean.TRUE.equals(d.get("revoked")) || ((Number)d.get("consent_revision")).longValue()!=consent((UUID)d.get("child_id")))deny();return d;
    }
    private long consent(UUID child) {
        var rows=db.queryForList("select co.revision from care.child c join identity.member g on g.id=c.guardian_id join care.consent co on co.child_id=c.id and co.scope='CORE_PROFILE_LEARNING' where c.id=? and c.deleted_at is null and g.status='ACTIVE' and g.role='GUARDIAN' and g.email_verified_at is not null and co.granted",Long.class,child);
        if(rows.isEmpty())deny();return rows.getFirst();
    }
    private void invalidate(UUID device) {db.update("update learning.runtime_device set generation=generation+1,revoked=true where id=?",device);}
    private void matchDevice(Map<String,Object> d,String device) {if(!d.get("device_id").equals(device))deny();}
    private StoredResponse response(int status,Object value) {return StoredResponse.json(json,status,value);}
    private static Instant instant(Map<String,Object> row,String key) {return ((Timestamp)row.get(key)).toInstant();}
    private static Timestamp ts(Instant t) {return Timestamp.from(t);}
    private static String secret() {byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private static void deny() {throw new ApiException(ErrorCode.AUTH_REQUIRED);}
}
