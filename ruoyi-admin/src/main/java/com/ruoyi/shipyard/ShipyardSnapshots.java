package com.ruoyi.shipyard;

import java.util.*;
import static com.ruoyi.shipyard.ShipyardService.*;

/** Personnel identity at submission time; changing a master record must not rewrite history. */
final class ShipyardSnapshots {
    private ShipyardSnapshots() { }
    static Map<String,Object> worker(Map<String,Object> worker) {
        return map("id",number(worker.get("id")),"name",str(worker,"name"),"code",str(worker,"code"),"team",str(worker,"team"));
    }
    @SuppressWarnings("unchecked")
    static Map<String,Object> work(Map<String,Object> content) {
        Object value=content.get("workSnapshot");
        if(value instanceof Map) return (Map<String,Object>)value;
        Map<String,Object> snapshots=new LinkedHashMap<>();content.put("workSnapshot",snapshots);return snapshots;
    }
    @SuppressWarnings("unchecked")
    static Map<String,Object> rememberWork(Map<String,Object> content,Map<String,Object> worker,String name) {
        String id=String.valueOf(number(worker.get("id")));
        Map<String,Object> snapshots=work(content);
        if(snapshots.get(id) instanceof Map) return (Map<String,Object>)snapshots.get(id);
        Map<String,Object> snapshot=worker(worker);snapshot.put("name",name);snapshots.put(id,snapshot);return snapshot;
    }
    @SuppressWarnings("unchecked")
    static Map<String,Object> rememberPerson(Map<String,Object> content,Map<String,Object> worker) {
        if(content.get("personSnapshot") instanceof Map) return (Map<String,Object>)content.get("personSnapshot");
        Map<String,Object> snapshot=worker(worker);content.put("personSnapshot",snapshot);return snapshot;
    }
}
