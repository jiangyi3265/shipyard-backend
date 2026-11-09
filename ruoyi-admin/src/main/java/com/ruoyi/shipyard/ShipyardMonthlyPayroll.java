package com.ruoyi.shipyard;

import java.math.BigDecimal;
import java.util.*;
import static com.ruoyi.shipyard.ShipyardService.*;

/** Read-only reports from the same authorized, approved wage lines used for settlement. */
final class ShipyardMonthlyPayroll {
    private ShipyardMonthlyPayroll() { }
    private static final List<String> FIELDS=Arrays.asList("normalHours","overtimeHours","totalHours",
            "normalWages","overtimeWages","contractWages","totalWages","paidWages","unpaidWages");
    private static Map<String,Object> amounts(){
        Map<String,Object> values=new LinkedHashMap<>();
        for(String field:FIELDS)values.put(field,new BigDecimal("0.00"));
        return values;
    }
    private static BigDecimal decimal(Object value){return new BigDecimal(String.valueOf(value)).setScale(2);}
    private static void add(Map<String,Object> target,String field,BigDecimal value){target.put(field,((BigDecimal)target.get(field)).add(value));}

    static List<Map<String,Object>> build(List<Map<String,Object>> authorizedLines){
        // Sorting a copy also makes the displayed identity the latest snapshot in that month,
        // even when the source was loaded project by project.
        List<Map<String,Object>> lines=new ArrayList<>(authorizedLines);
        lines.sort(Comparator.comparing((Map<String,Object> l)->str(l,"month"))
                .thenComparing(l->str(l,"date")).thenComparingLong(l->number(l.get("id"))));
        Map<String,Group> groups=new LinkedHashMap<>();
        for(Map<String,Object> line:lines){
            String month=str(line,"month");long project=number(line.get("projectId"));
            for(long scope:new long[]{0,project}){
                String key=month+":"+scope;
                groups.computeIfAbsent(key,k->new Group(month,scope)).add(line);
            }
        }
        List<Map<String,Object>> result=new ArrayList<>();
        for(Group group:groups.values())result.add(group.finish());
        return result;
    }

    private static final class Group {
        final String month;final long project;
        final SortedMap<Long,Worker> workers=new TreeMap<>();
        Group(String month,long project){this.month=month;this.project=project;}
        void add(Map<String,Object> line){workers.computeIfAbsent(number(line.get("workerId")),Worker::new).add(line);}
        Map<String,Object> finish(){
            Map<String,Object> totals=amounts();List<Map<String,Object>> rows=new ArrayList<>();
            for(Worker worker:workers.values()){
                Map<String,Object> row=worker.finish();rows.add(row);
                for(String field:FIELDS)ShipyardMonthlyPayroll.add(totals,field,(BigDecimal)row.get(field));
            }
            totals.put("workerCount",rows.size());
            return map("month",month,"projectId",project,"rows",rows,"totals",totals);
        }
    }
    private static final class Worker {
        final Map<String,Object> row=amounts();final Set<String> teams=new LinkedHashSet<>();int lineCount,paidCount;
        Worker(long id){row.put("workerId",id);}
        void add(Map<String,Object> line){
            row.put("name",str(line,"name"));row.put("code",str(line,"code"));
            if(!str(line,"team").isEmpty())teams.add(str(line,"team"));
            String type=str(line,"label");BigDecimal wage=decimal(line.get("amount"));
            if("点工".equals(type)||"加班".equals(type)){
                BigDecimal hours=decimal(line.get("hours"));
                ShipyardMonthlyPayroll.add(row,"点工".equals(type)?"normalHours":"overtimeHours",hours);
                ShipyardMonthlyPayroll.add(row,"totalHours",hours);
            }
            ShipyardMonthlyPayroll.add(row,"包工".equals(type)?"contractWages":"加班".equals(type)?"overtimeWages":"normalWages",wage);
            ShipyardMonthlyPayroll.add(row,"totalWages",wage);
            boolean paid=line.get("paymentId")!=null;
            ShipyardMonthlyPayroll.add(row,paid?"paidWages":"unpaidWages",wage);
            lineCount++;if(paid)paidCount++;
        }
        Map<String,Object> finish(){
            row.put("team",String.join("、",teams));row.put("lineCount",lineCount);
            row.put("status",paidCount==lineCount?"已结算":paidCount==0?"待结算":"部分结算");
            return row;
        }
    }
}
