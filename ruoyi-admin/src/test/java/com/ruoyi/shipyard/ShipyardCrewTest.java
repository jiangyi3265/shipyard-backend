package com.ruoyi.shipyard;

import java.util.*;
import org.junit.*;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.exception.ServiceException;
import static com.ruoyi.shipyard.ShipyardService.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ShipyardCrewTest {
    JdbcTemplate db;ShipyardAccess access;ShipyardAdministration admin;
    @Before public void setup(){
        db=mock(JdbcTemplate.class,invocation->"update".equals(invocation.getMethod().getName())?1:RETURNS_DEFAULTS.answer(invocation));
        access=mock(ShipyardAccess.class);admin=new ShipyardAdministration(db,access,mock(RedisCache.class));
        when(db.queryForList(contains("FROM sy_crew WHERE id=?"),eq(7L))).thenReturn(Collections.singletonList(map("id",7L,"name","打磨一号班组","category","grinding","leader_id",12L,"version",2)));
        when(db.queryForList(contains("FROM sys_user u"),eq(12L))).thenReturn(Collections.singletonList(map("id",12L,"name","张师傅")));
        when(db.queryForList(contains("FROM sy_worker WHERE id=? FOR UPDATE"),eq(3L))).thenReturn(Collections.singletonList(map("id",3L,"active",1,"team","原班组")));
    }
    Map<String,Object> crew(){return map("id",7L,"name","打磨一号班组","category","grinding","leaderId",12L,"version",2);}
    Map<String,Object> worker(){return map("id",3L,"name","测试工人","code","W03","team","原班组","rate",35);}
    void rejected(int code,String part,Runnable action){try{action.run();fail("Should reject invalid crew operation");}catch(ServiceException e){assertEquals(Integer.valueOf(code),e.getCode());assertTrue(e.getMessage(),e.getMessage().contains(part));}}
    @Test public void crewEditStoresNameCategoryAndRealLeaderWithVersion(){admin.master("crew",crew());verify(db).update("UPDATE sy_crew SET name=?,category=?,leader_id=?,leader_name=?,version=version+1 WHERE id=? AND version=?","打磨一号班组","grinding",12L,"张师傅",7L,2L);verify(db,never()).update(contains("sys_user_role"),any(Object[].class));}
    @Test public void invalidCrewCategoryNeverWrites(){for(Object value:Arrays.asList("", "other",1,true)){Map<String,Object>b=crew();b.put("category",value);rejected(400,"班组类型",()->admin.master("crew",b));}verify(db,never()).update(anyString(),any(Object[].class));}
    @Test public void leaderMustBeAnActiveLeaderAccount(){Map<String,Object>b=crew();b.put("leaderId",99);rejected(400,"组长",()->admin.master("crew",b));verify(db,never()).update(anyString(),any(Object[].class));}
    @Test public void crewNotFoundAndStaleEditDoNotWrite(){Map<String,Object>b=crew();b.put("id",99);rejected(404,"班组不存在",()->admin.master("crew",b));b.put("id",7);b.put("version",1);rejected(409,"更新",()->admin.master("crew",b));verify(db,never()).update(anyString(),any(Object[].class));}
    @Test public void duplicateNameWithinCategoryIsRejected(){when(db.queryForObject(contains("SELECT COUNT(*) FROM sy_crew"),eq(Integer.class),eq("grinding"),eq("打磨一号班组"),eq(7L))).thenReturn(1);rejected(409,"同名",()->admin.master("crew",crew()));verify(db,never()).update(anyString(),any(Object[].class));}
    @Test public void crewStillRequiresMasterEdit(){doThrow(new ServiceException("没有该操作权限",403)).when(access).require("master:edit");rejected(403,"权限",()->admin.master("crew",crew()));verify(db,never()).update(anyString(),any(Object[].class));}
    @Test public void invalidWorkerCrewIsRejectedBeforeWrites(){for(Object value:Arrays.asList(99,-1,1.5,true,Arrays.asList(7))){Map<String,Object>b=worker();b.put("crewId",value);rejected(value.equals(99)?404:400,"班组",()->admin.master("person",b));}verify(db,never()).update(anyString(),any(Object[].class));}
    @Test public void assigningCrewKeepsLegacyTeamAndDoesNotChangeAccess(){Map<String,Object>b=worker();b.put("crewId",7);admin.master("person",b);verify(db).update("UPDATE sy_worker SET crew_id=? WHERE id=?",7L,3L);verify(db).update(startsWith("UPDATE sy_worker SET name="),eq("测试工人"),eq("W03"),eq("原班组"),any(),eq(1),eq(3L));verify(db,never()).update(contains("sys_user_role"),any(Object[].class));verify(db,never()).update(contains("sy_project_member"),any(Object[].class));}
    @Test public void oldClientsPreserveCrewAndExplicitEmptyClears(){admin.master("person",worker());verify(db,never()).update(startsWith("UPDATE sy_worker SET crew_id"),any(Object[].class));Map<String,Object>b=worker();b.put("crewId",null);admin.master("person",b);verify(db).update("UPDATE sy_worker SET crew_id=? WHERE id=?",null,3L);}
    @Test public void unassignedLegacyWorkersStillLoadAndSnapshot(){Map<String,Object> w=worker();assertSame(w,ShipyardCrews.snapshot(db,w));assertEquals("",ShipyardCrews.decorate(Arrays.asList(w),Collections.emptyList()).get(0).get("crewLabel"));assertEquals("原班组",w.get("team"));}
    @Test public void newSnapshotUsesCrewLeaderAndRenamesDoNotRewriteExistingSnapshot(){
        Map<String,Object> crew=map("id",7L,"name","打磨一号班组","category","grinding","leaderName","张师傅");
        when(db.queryForList(contains("FROM sy_crew c"),eq(7L))).thenReturn(Arrays.asList(crew));
        Map<String,Object>w=worker(),body=new HashMap<>();w.put("crew_id",7L);
        ShipyardSnapshots.rememberWork(body,ShipyardCrews.snapshot(db,w),"测试工人");
        crew.put("name","打磨二号班组");
        assertEquals("打磨一号班组（张师傅）",ShipyardSnapshots.rememberWork(body,ShipyardCrews.snapshot(db,w),"测试工人").get("team"));
        assertEquals("原班组",w.get("team"));
    }
}
