package com.ruoyi.shipyard;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ruoyi.common.exception.ServiceException;
import static com.ruoyi.shipyard.ShipyardService.*;

/** Named crews organize personnel only; they never alter account authorization. */
final class ShipyardCrews {
    private ShipyardCrews() { }
    static long id(Object value) {
        if(value==null || "".equals(value)) return 0;
        String text=String.valueOf(value);
        check(text.matches("[0-9]{1,18}"),"班组标识或版本格式无效");return Long.parseLong(text);
    }
    static Map<String,Object> find(JdbcTemplate db,long id) {
        List<Map<String,Object>> rows=db.queryForList("SELECT * FROM sy_crew WHERE id=?",id);
        if(rows.isEmpty())throw new ServiceException("班组不存在，请刷新后重选",404);return rows.get(0);
    }
    private static final String SELECT="SELECT c.id,c.name,c.category,c.leader_id AS leaderId,COALESCE(u.nick_name,c.leader_name) AS leaderName,c.version,EXISTS(SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.role_id=ur.role_id WHERE ur.user_id=u.user_id AND r.role_key='sy_leader' AND r.status='0' AND r.del_flag='0' AND u.status='0' AND u.del_flag='0') AS leaderActive FROM sy_crew c LEFT JOIN sys_user u ON u.user_id=c.leader_id";
    static List<Map<String,Object>> list(JdbcTemplate db){return db.queryForList(SELECT+" ORDER BY c.category,c.name,c.id");}
    static List<Map<String,Object>> leaders(JdbcTemplate db){return db.queryForList("SELECT DISTINCT u.user_id AS id,u.nick_name AS name FROM sys_user u JOIN sys_user_role ur ON ur.user_id=u.user_id JOIN sys_role r ON r.role_id=ur.role_id WHERE u.status='0' AND u.del_flag='0' AND r.role_key='sy_leader' AND r.status='0' AND r.del_flag='0' ORDER BY u.user_id");}
    static String label(Map<String,Object> crew){return str(crew,"name")+"（"+str(crew,"leaderName")+"）";}
    static List<Map<String,Object>> decorate(List<Map<String,Object>> workers,List<Map<String,Object>> crews){
        Map<Long,Map<String,Object>> byId=new HashMap<>();for(Map<String,Object> crew:crews)byId.put(number(crew.get("id")),crew);
        for(Map<String,Object> worker:workers){Map<String,Object> crew=byId.get(id(worker.get("crewId")));if(crew!=null){worker.put("crewLabel",label(crew));worker.put("teamCategory",crew.get("category"));}else worker.put("crewLabel","");}return workers;
    }
    /** Freeze the current crew label into new work/fine records; old snapshots stay unchanged. */
    static Map<String,Object> snapshot(JdbcTemplate db,Map<String,Object> worker){
        long crewId=id(worker.get("crew_id"));if(crewId==0)return worker;
        List<Map<String,Object>> rows=db.queryForList(SELECT+" WHERE c.id=?",crewId);
        if(rows.isEmpty())return worker;
        Map<String,Object> copy=new LinkedHashMap<>(worker);copy.put("team",label(rows.get(0)));return copy;
    }
}
