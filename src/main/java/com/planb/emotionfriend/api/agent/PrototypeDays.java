package com.planb.emotionfriend.api.agent;
import com.planb.emotionfriend.api.common.error.*;
import com.planb.emotionfriend.api.common.security.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
@Transactional
public class PrototypeDays {
    private final JdbcTemplate db;private final PrototypeCalendar calendar;private final ScenarioInputService inputs;private final JsonMapper json;private final Clock clock;
    public PrototypeDays(JdbcTemplate db,PrototypeCalendar calendar,ScenarioInputService inputs,JsonMapper json,Clock clock){this.db=db;this.calendar=calendar;this.inputs=inputs;this.json=json;this.clock=clock;}
    public LocalDate today(){return calendar.today();}
    public Object adultStatus(ActorPrincipal actor,UUID child,LocalDate date){inputs.access(actor,child);return status(child,date==null?today():date);}
    public int nextDay(UUID child,LocalDate date) {
        var same=db.queryForList("select program_day from learning.prototype_day where child_id=? and local_date=?",Integer.class,child,java.sql.Date.valueOf(date));
        if(!same.isEmpty())return same.getFirst();
        int day=db.queryForObject("select coalesce(max(program_day),0)+1 from learning.prototype_day where child_id=?",Integer.class,child);
        if(day>30)throw new ApiException(ErrorCode.RESOURCE_STATE_CONFLICT);return day;
    }
    public void bind(UUID child,LocalDate date,int day,UUID job) {
        if(!date.equals(today())||day!=nextDay(child,date))throw new ApiException(ErrorCode.STALE_DRAFT);
        var previous=db.queryForList("select d.*,j.state from learning.prototype_day d join learning.generation_job j on j.id=d.job_id where d.child_id=? and d.local_date=? for update of d",child,java.sql.Date.valueOf(date));
        if(!previous.isEmpty()) {
            var row=previous.getFirst();if(row.get("job_id").equals(job))return;
            if(row.get("plan_id")!=null||row.get("release_id")!=null||!Set.of("FAILED","CANCELLED","HELD").contains(row.get("state")))throw new ApiException(ErrorCode.RESOURCE_STATE_CONFLICT);
            db.update("update learning.prototype_day set job_id=? where child_id=? and local_date=?",job,child,java.sql.Date.valueOf(date));return;
        }
        db.update("insert into learning.prototype_day(child_id,local_date,program_day,job_id,created_at) values(?,?,?,?,?)",child,java.sql.Date.valueOf(date),day,job,java.sql.Timestamp.from(clock.instant()));
    }
    public Object status(UUID child,LocalDate date) {
        var rows=db.queryForList("select d.*,j.state as job_state from learning.prototype_day d join learning.generation_job j on j.id=d.job_id where d.child_id=? and d.local_date=?",child,java.sql.Date.valueOf(date));
        var out=json.createObjectNode();out.put("schemaVersion","EF_DAY_STATUS_P1").put("childId",child.toString()).put("localDate",date.toString()).put("timezone","Asia/Seoul").put("totalCount",5);
        int count=0;String state="NOT_PREPARED";UUID job=null,release=null,plan=null;boolean generate=date.equals(today()),launch=false;
        int day=nextDay(child,date);
        if(!rows.isEmpty()) {
            var row=rows.getFirst();job=(UUID)row.get("job_id");release=(UUID)row.get("release_id");plan=(UUID)row.get("plan_id");
            String jobState=(String)row.get("job_state");generate=date.equals(today())&&Set.of("FAILED","CANCELLED","HELD").contains(jobState)&&release==null&&plan==null;
            state=Set.of("QUEUED","RUNNING","OUTCOME_UNKNOWN").contains(jobState)?"GENERATING":jobState.equals("RESULT_READY")?"PENDING_APPROVAL":"NOT_PREPARED";
            if(release!=null)state="MEDIA_PREPARING";
            if(plan!=null) {
                count=db.queryForObject("select coalesce(max(completed_count),0) from learning.session where plan_id=?",Integer.class,plan);
                state=count==5?"COMPLETED":count>0?"IN_PROGRESS":"READY";launch=date.equals(today())&&count<5;
            }
        }
        out.put("programDay",day).put("state",state).put("completedCount",count).put("canGenerate",generate).put("canLaunch",launch);
        if(job==null)out.putNull("jobId");else out.put("jobId",job.toString());if(release==null)out.putNull("releaseId");else out.put("releaseId",release.toString());if(plan==null)out.putNull("planId");else out.put("planId",plan.toString());
        var previous=db.queryForList("select d.local_date,coalesce(s.completed_count,0) as completed_count from learning.prototype_day d left join learning.session s on s.plan_id=d.plan_id where d.child_id=? and d.local_date<? order by d.local_date desc limit 1",child,java.sql.Date.valueOf(date));
        if(previous.isEmpty()){out.putNull("previousDayLocalDate");out.putNull("previousDayCompletedCount");}
        else {out.put("previousDayLocalDate",previous.getFirst().get("local_date").toString());out.put("previousDayCompletedCount",((Number)previous.getFirst().get("completed_count")).intValue());}
        return out;
    }
}
