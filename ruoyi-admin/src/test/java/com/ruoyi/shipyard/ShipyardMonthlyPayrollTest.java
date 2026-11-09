package com.ruoyi.shipyard;

import java.math.BigDecimal;
import java.util.*;
import org.junit.*;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ruoyi.common.exception.ServiceException;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static com.ruoyi.shipyard.ShipyardService.map;

public class ShipyardMonthlyPayrollTest {
    JdbcTemplate db; ShipyardAccess access; ShipyardService service;
    final List<Map<String,Object>> first=new ArrayList<>(),second=new ArrayList<>();
    @Before public void setup(){
        db=mock(JdbcTemplate.class);access=mock(ShipyardAccess.class);service=new ShipyardService(db,access);
        when(access.uid()).thenReturn(7L);
        when(access.permissions()).thenReturn(Collections.singleton("shipyard:finance:view"));
        when(db.queryForList(startsWith("SELECT id,name,title,price,area,"))).thenReturn(Arrays.asList(map("id",1L,"name","甲船"),map("id",2L,"name","乙船")));
        when(db.queryForList("SELECT user_id AS id,user_name AS username,nick_name AS name FROM sys_user WHERE user_id=?",7L)).thenReturn(Collections.singletonList(map("id",7L,"name","会计")));
        when(db.queryForList(startsWith("SELECT l.id,l.worker_id AS workerId"),eq(1L))).thenReturn(first);
        when(db.queryForList(startsWith("SELECT l.id,l.worker_id AS workerId"),eq(2L))).thenReturn(second);
    }
    Map<String,Object> line(long id,long worker,long project,String type,String month,String hours,String amount,boolean paid){
        return map("id",id,"workerId",worker,"name","历史工人"+worker,"code","W"+worker,"team","打磨一号班组（组长）","label",type,"month",month,"date",month+"-02","projectId",project,"ship",project==1?"甲船":"乙船","hours",new BigDecimal(hours),"amount",new BigDecimal(amount),"paymentId",paid?"paid-batch":null);
    }
    @SuppressWarnings("unchecked") List<Map<String,Object>> reports(){Object value=service.bootstrap().get("monthlyPayroll");assertNotNull("bootstrap must expose authoritative monthly payroll",value);return (List<Map<String,Object>>)value;}
    Map<String,Object> report(List<Map<String,Object>> reports,String month,long project){return reports.stream().filter(r->month.equals(r.get("month"))&&((Number)r.get("projectId")).longValue()==project).findFirst().orElseThrow(()->new AssertionError("Missing month/project report"));}
    @SuppressWarnings("unchecked") List<Map<String,Object>> rows(Map<String,Object> report){return (List<Map<String,Object>>)report.get("rows");}
    void amount(Map<String,Object> row,String field,String expected){assertEquals(field,new BigDecimal(expected),row.get(field));}

    @Test public void monthlyWorkerShowsNormalOvertimeContractAndPartialPayment(){
        first.add(line(1,3,1,"点工","2026-11","8.00","280.00",true));
        first.add(line(2,3,1,"加班","2026-11","2.00","70.00",false));
        first.add(line(3,3,1,"包工","2026-11","0.00","123.45",false));
        Map<String,Object> row=rows(report(reports(),"2026-11",1)).get(0);
        amount(row,"normalHours","8.00");amount(row,"overtimeHours","2.00");amount(row,"totalHours","10.00");
        amount(row,"contractWages","123.45");amount(row,"totalWages","473.45");amount(row,"paidWages","280.00");amount(row,"unpaidWages","193.45");
        assertEquals("部分结算",row.get("status"));assertEquals(3,row.get("lineCount"));
    }
    @Test public void exactDecimalTotalsAndMonthBoundaryAreIndependent(){
        first.add(line(1,3,1,"点工","2026-10","1.00","99.99",false));
        first.add(line(2,3,1,"点工","2026-11","0.10","0.10",false));
        first.add(line(3,3,1,"加班","2026-11","0.20","0.20",false));
        List<Map<String,Object>> result=reports();Map<String,Object> november=rows(report(result,"2026-11",0)).get(0);
        amount(november,"totalHours","0.30");amount(november,"totalWages","0.30");
        amount(rows(report(result,"2026-10",0)).get(0),"totalWages","99.99");
    }
    @Test public void allAuthorizedProjectsCombineByWorkerIdWithoutMergingSameNames(){
        first.add(line(1,3,1,"点工","2026-11","1.00","35.00",false));
        second.add(line(2,3,2,"点工","2026-11","2.00","70.00",true));
        Map<String,Object> twin=line(3,4,2,"包工","2026-11","0.00","50.50",false);twin.put("name","历史工人3");second.add(twin);
        List<Map<String,Object>> result=reports();Map<String,Object> all=report(result,"2026-11",0);
        assertEquals(2,rows(all).size());amount(rows(all).get(0),"totalWages","105.00");
        amount(rows(report(result,"2026-11",1)).get(0),"totalWages","35.00");
        @SuppressWarnings("unchecked") Map<String,Object> totals=(Map<String,Object>)all.get("totals");
        amount(totals,"totalWages","155.50");assertEquals(2,totals.get("workerCount"));
    }
    @Test public void inactiveAndRenamedWorkersUseMonthlySnapshotsAndRemainVisibleAfterPayment(){
        Map<String,Object> early=line(1,3,1,"点工","2026-11","1.00","35.00",true);
        Map<String,Object> late=line(2,3,2,"点工","2026-11","1.00","36.00",true);late.put("date","2026-11-01");late.put("name","更早姓名");late.put("team","油漆一号班组（另一组长）");
        first.add(early);second.add(late);
        Map<String,Object> row=rows(report(reports(),"2026-11",0)).get(0);
        assertEquals("历史工人3",row.get("name"));assertEquals("W3",row.get("code"));assertEquals("已结算",row.get("status"));
        amount(row,"unpaidWages","0.00");assertTrue(String.valueOf(row.get("team")).contains("油漆一号"));assertTrue(String.valueOf(row.get("team")).contains("打磨一号"));
        verify(db).queryForList(argThat(sql->sql.startsWith("SELECT l.id,l.worker_id AS workerId")&&sql.contains("workSnapshot")&&sql.contains("r.status IN ('待结算','已结算')")),eq(1L));
    }
    @Test public void accountsWithoutPayrollReadPermissionGetNoMonthlyData(){
        when(access.permissions()).thenReturn(Collections.singleton("shipyard:fine:handle"));
        first.add(line(1,3,1,"点工","2026-11","8.00","280.00",false));
        assertTrue(reports().isEmpty());verify(db,never()).queryForList(startsWith("SELECT l.id,l.worker_id AS workerId"),anyLong());
    }
    @Test public void emptyMonthHasNoFabricatedWorkersOrWages(){assertTrue(reports().isEmpty());}
    @Test public void inactiveSessionIsRejectedBeforeReadingFinancialData(){
        doThrow(new ServiceException("账号已停用",401)).when(access).member();
        try{service.bootstrap();fail("must reject revoked session");}catch(ServiceException e){assertEquals(Integer.valueOf(401),e.getCode());}
        verifyNoInteractions(db);
    }
}
