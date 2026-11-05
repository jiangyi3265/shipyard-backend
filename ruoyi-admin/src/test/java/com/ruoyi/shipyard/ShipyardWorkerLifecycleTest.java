package com.ruoyi.shipyard;

import java.math.BigDecimal;
import java.util.*;
import com.alibaba.fastjson2.JSON;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.exception.ServiceException;
import static com.ruoyi.shipyard.ShipyardService.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ShipyardWorkerLifecycleTest {
    private Map<String,Object> worker() {
        return map("id",3L,"name","原姓名","code","003","team","原班组","rate",new BigDecimal("35.00"),"active",1);
    }
    private Map<String,Object> form() {
        return map("id",3L,"name","新姓名","code","NEW003","team","新班组","rate",50);
    }
    private void invalid(Runnable action) {
        try { action.run();fail("Invalid worker status must be rejected"); }
        catch(ServiceException expected) { assertTrue(expected.getMessage().contains("状态")); }
    }

    @Test public void personnelSnapshotDoesNotChangeWhenMasterChanges() {
        Map<String,Object> content=map(), original=worker();
        ShipyardSnapshots.rememberPerson(content,original);
        ShipyardSnapshots.rememberWork(content,original,"历史工资姓名");
        Map<String,Object> changed=new LinkedHashMap<>(original);changed.putAll(form());
        assertEquals("原姓名",ShipyardSnapshots.rememberPerson(content,changed).get("name"));
        Map<String,Object> snapshot=ShipyardSnapshots.rememberWork(content,changed,"新工资姓名");
        assertEquals("历史工资姓名",snapshot.get("name"));assertEquals("003",snapshot.get("code"));assertEquals("原班组",snapshot.get("team"));
    }

    private static class HistoryDb extends JdbcTemplate {
        final Map<String,Map<String,Object>> records=new LinkedHashMap<>();
        final List<String> events=new ArrayList<>();
        final Map<String,Object> worker;
        HistoryDb(Map<String,Object> worker) {this.worker=worker;}
        @Override public List<Map<String,Object>> queryForList(String sql,Object... args) {
            if(sql.contains("FROM sy_record WHERE id=? FOR UPDATE")) {events.add("record:"+args[0]);return Collections.singletonList(records.get(String.valueOf(args[0])));}
            if(sql.contains("FROM sy_worker WHERE id=? FOR UPDATE")) {events.add("worker");return Collections.singletonList(worker);}
            throw new AssertionError("Unexpected query: "+sql);
        }
        @SuppressWarnings("unchecked")
        @Override public <T> List<T> queryForList(String sql,Class<T> type,Object... args) {
            if(sql.startsWith("SELECT r.id FROM sy_record")) return (List<T>)new ArrayList<>(records.keySet());
            if(sql.startsWith("SELECT worker_name")) return (List<T>)Collections.singletonList("历史工资姓名");
            throw new AssertionError("Unexpected query: "+sql);
        }
        @Override public int update(String sql,Object... args) {
            if(sql.equals("UPDATE sy_record SET body=? WHERE id=?")) {records.get(String.valueOf(args[1])).put("body",args[0]);events.add("freeze:"+args[1]);return 1;}
            if(sql.startsWith("UPDATE sy_worker SET")) {worker.putAll(map("name",args[0],"code",args[1],"team",args[2],"rate",args[3],"active",args[4]));events.add("edit");return 1;}
            throw new AssertionError("Unexpected update: "+sql);
        }
    }
    private ShipyardAdministration admin(HistoryDb db) {return new ShipyardAdministration(db,mock(ShipyardAccess.class),mock(RedisCache.class));}

    @Test public void renameAndDisableFreezeLegacyFineAndWorkBeforeEditingPersonnel() {
        HistoryDb db=new HistoryDb(worker());
        db.records.put("a-fine",map("id","a-fine","kind","罚款","body",JSON.toJSONString(map("person",3,"amount",100,"note","原罚款"))));
        db.records.put("b-work",map("id","b-work","kind","用工","body",JSON.toJSONString(map("people",Arrays.asList(3),"hours",map("3",8),ShipyardIdempotency.META,map("fingerprint","unchanged")))));
        Map<String,Object> update=form();update.put("active",0);
        admin(db).master("person",update);
        Map<String,Object> fine=JSON.parseObject(String.valueOf(db.records.get("a-fine").get("body")));
        Map<String,Object> work=JSON.parseObject(String.valueOf(db.records.get("b-work").get("body")));
        Map<?,?> fined=(Map<?,?>)fine.get("personSnapshot");
        Map<?,?> worked=(Map<?,?>)((Map<?,?>)work.get("workSnapshot")).get("3");
        assertEquals("原姓名",fined.get("name"));assertEquals("003",fined.get("code"));assertEquals("原班组",fined.get("team"));
        assertEquals("历史工资姓名",worked.get("name"));assertEquals("003",worked.get("code"));assertEquals("原班组",worked.get("team"));
        assertEquals("unchanged",((Map<?,?>)work.get(ShipyardIdempotency.META)).get("fingerprint"));
        assertEquals("新姓名",db.worker.get("name"));assertEquals(0L,db.worker.get("active"));
        assertEquals(Arrays.asList("record:a-fine","record:b-work","worker","freeze:a-fine","freeze:b-work","edit"),db.events);
    }

    @Test public void editingAnInactiveWorkerWithoutStatusDoesNotReactivateThem() {
        HistoryDb db=new HistoryDb(worker());db.worker.put("active",0);
        admin(db).master("person",form());assertEquals(0,db.worker.get("active"));
        Map<String,Object> restore=form();restore.put("active","1");
        admin(db).master("person",restore);assertEquals(1L,db.worker.get("active"));
    }

    @Test public void invalidStatusIsRejectedBeforeLocksOrWrites() {
        HistoryDb db=new HistoryDb(worker());Map<String,Object> update=form();update.put("active",2);
        invalid(()->admin(db).master("person",update));assertTrue(db.events.isEmpty());
    }

    @Test public void existingSnapshotsAreNotOverwrittenWhenCandidateChangedWhileWaiting() {
        HistoryDb db=new HistoryDb(worker());Map<String,Object> content=map("person",3,"personSnapshot",map("id",3,"name","更早姓名","code","OLDER","team","更早班组"));
        db.records.put("a-fine",map("id","a-fine","kind","罚款","body",JSON.toJSONString(content)));
        admin(db).master("person",form());
        Map<?,?> snapshot=(Map<?,?>)JSON.parseObject(String.valueOf(db.records.get("a-fine").get("body"))).get("personSnapshot");
        assertEquals("更早姓名",snapshot.get("name"));assertFalse(db.events.contains("freeze:a-fine"));
    }

    @Test public void legacyFineDetailStillContainsIdentityWhenWorkerIsInactive() {
        JdbcTemplate db=mock(JdbcTemplate.class);Map<String,Object> person=worker();person.put("active",0);
        when(db.queryForList("SELECT id,name,code,team FROM sy_worker WHERE id=?",3L)).thenReturn(Collections.singletonList(person));
        ShipyardService service=new ShipyardService(db,mock(ShipyardAccess.class));
        Map<String,Object> record=map("id","fine","kind","罚款","body",JSON.toJSONString(map("person",3)),"created_at","2026-11-04 10:00:00");
        Map<?,?> result=(Map<?,?>)service.view(record).get("personSnapshot");
        assertEquals("原姓名",result.get("name"));assertEquals("原班组",result.get("team"));
    }

    @Test public void fullWorkerArchiveRequiresMasterPermission() {
        for(boolean allowed:Arrays.asList(false,true)) {
            JdbcTemplate db=mock(JdbcTemplate.class);ShipyardAccess access=mock(ShipyardAccess.class);
            when(access.uid()).thenReturn(7L);when(access.roles()).thenReturn(Collections.singletonList("sy_leader"));
            when(access.permissions()).thenReturn(allowed?Collections.singleton("shipyard:master:edit"):Collections.emptySet());
            when(db.queryForList("SELECT user_id AS id,user_name AS username,nick_name AS name FROM sys_user WHERE user_id=?",7L))
                    .thenReturn(Collections.singletonList(map("id",7,"name","用户")));
            when(db.queryForList("SELECT id,name,code,team,rate,active FROM sy_worker ORDER BY active DESC,id"))
                    .thenReturn(Collections.singletonList(worker()));
            Map<String,Object> result=new ShipyardService(db,access).bootstrap();
            assertEquals(allowed?1:0,((List<?>)result.get("workerArchive")).size());
            if(!allowed) verify(db,never()).queryForList("SELECT id,name,code,team,rate,active FROM sy_worker ORDER BY active DESC,id");
        }
    }
}
