package com.planb.emotionfriend.api.agent;
import com.planb.emotionfriend.api.common.error.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Only the learning date can move. JWT/lease/proof/provider clocks always remain real UTC. */
@Service
@Transactional
public class PrototypeCalendar {
    private final JdbcTemplate db;private final Clock clock;private final boolean test;
    public PrototypeCalendar(JdbcTemplate db,Clock clock,Environment environment,
        @Value("${emotion-friend.prototype.test-clock-enabled:false}") boolean test) {
        this.db=db;this.clock=clock;this.test=test;
        if(test&&environment.acceptsProfiles(Profiles.of("prod","production")))throw new IllegalArgumentException("Test calendar cannot be enabled in production");
    }
    public LocalDate today() {
        if(test){var rows=db.queryForList("select effective_date from learning.prototype_calendar where singleton",java.sql.Date.class);if(!rows.isEmpty())return rows.getFirst().toLocalDate();}
        return clock.instant().atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
    }
    public Object status(){if(!test)throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);var rows=db.queryForList("select version from learning.prototype_calendar where singleton",Long.class);return Map.of("localDate",today().toString(),"version",rows.isEmpty()?0:rows.getFirst());}
    public Object advance(UUID request,long expectedVersion) {
        if(!test)throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        // Separate advisory lock also covers the initial no-row state.
        db.query("select pg_advisory_xact_lock(hashtextextended('prototype-calendar',0))",rs->{});
        var prior=db.queryForList("select * from learning.prototype_calendar_command where id=?",request);
        if(!prior.isEmpty()) {
            var p=prior.getFirst();if(((Number)p.get("expected_version")).longValue()!=expectedVersion)throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT);
            return Map.of("localDate",p.get("result_date").toString(),"version",p.get("result_version"));
        }
        var rows=db.queryForList("select version from learning.prototype_calendar where singleton for update",Long.class);
        long version=rows.isEmpty()?0:rows.getFirst();if(expectedVersion!=version)throw new ApiException(ErrorCode.STALE_DRAFT);
        LocalDate date=today().plusDays(1);
        db.update("insert into learning.prototype_calendar(singleton,effective_date,version) values(true,?,?) on conflict(singleton) do update set effective_date=excluded.effective_date,version=excluded.version",java.sql.Date.valueOf(date),version+1);
        db.update("insert into learning.prototype_calendar_command(id,expected_version,result_date,result_version) values(?,?,?,?)",request,expectedVersion,java.sql.Date.valueOf(date),version+1);
        return Map.of("localDate",date.toString(),"version",version+1);
    }
}
