package com.ruoyi.shipyard;

import java.math.BigDecimal;
import java.util.*;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ruoyi.common.exception.ServiceException;
import static com.ruoyi.shipyard.ShipyardService.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Executes real review/payment validation; only SQL reads/writes and actor authorization are isolated. */
public class ShipyardReviewValidationTest {
    private static class Fixture {
        final JdbcTemplate db=mock(JdbcTemplate.class);
        final ShipyardAccess access=mock(ShipyardAccess.class);
        final Map<String,Object> row=map("id","fixture-record","project_id",1L,"area_id",2L,
            "kind","用工","type","点工","status","待审批","version",3,"created_by",10L,
            "body","{}","title","测试单据","amount",new BigDecimal("42.00"));
        final ShipyardService service=new ShipyardService(db,access) {
            @Override Map<String,Object> view(Map<String,Object> record){return record;}
        };
        Fixture() {
            when(access.uid()).thenReturn(20L);
            when(db.queryForList("SELECT * FROM sy_record WHERE id=? FOR UPDATE","fixture-record"))
                .thenAnswer(invocation->Collections.singletonList(row));
            when(db.queryForList("SELECT * FROM sy_record WHERE id=?","fixture-record"))
                .thenAnswer(invocation->Collections.singletonList(row));
        }
        void noWrites(){verify(db,never()).update(anyString(),any(Object[].class));}
    }
    private void rejected(int code,Runnable action) {
        try {action.run();fail("Expected a rejected request without state changes");}
        catch(ServiceException e){assertEquals(Integer.valueOf(code),e.getCode());}
    }
    @Test public void malformedReviewDecisionCannotSilentlyReturnWorkExpenseOrFine() {
        for(String kind:Arrays.asList("用工","报销","罚款")) {
            for(Object invalid:Arrays.asList(null,"true","false",0,1,Collections.emptyList(),Collections.emptyMap())) {
                Fixture f=new Fixture();f.row.put("kind",kind);
                rejected(400,()->f.service.review("fixture-record",map("version",3,"accept",invalid,"opinion","有意见并不代表退回")));
                f.noWrites();
            }
        }
    }
    @Test public void explicitlyBooleanRejectionStillWorks() {
        Fixture f=new Fixture();
        f.service.review("fixture-record",map("version",3,"accept",false,"opinion","核对工时"));
        verify(f.db).update(eq("UPDATE sy_record SET status=?,body=?,version=version+1 WHERE id=?"),eq("已退回"),anyString(),eq("fixture-record"));
    }
    @Test public void explicitlyBooleanApprovalStillWorks() {
        Fixture f=new Fixture();
        f.service.review("fixture-record",map("version",3,"accept",true));
        verify(f.db).update(eq("UPDATE sy_record SET status=?,body=?,version=version+1 WHERE id=?"),eq("待结算"),anyString(),eq("fixture-record"));
    }
    @Test public void malformedVersionsNeverWriteReviewOrPayment() {
        for(Object version:Arrays.asList(null,"",1.5,"3.0",true,Collections.emptyMap())) {
            Fixture review=new Fixture();
            rejected(400,()->review.service.review("fixture-record",map("version",version,"accept",true)));review.noWrites();
            Fixture payment=new Fixture();payment.row.put("kind","报销");payment.row.put("status","待付款");
            rejected(400,()->payment.service.payExpense("fixture-record",map("version",version)));payment.noWrites();
        }
    }
    @Test public void staleAndFutureVersionsNeverWriteReviewOrPayment() {
        for(int version:Arrays.asList(-1,0,2,4,Integer.MAX_VALUE)) {
            Fixture review=new Fixture();
            rejected(409,()->review.service.review("fixture-record",map("version",version,"accept",true)));review.noWrites();
            Fixture payment=new Fixture();payment.row.put("kind","报销");payment.row.put("status","待付款");
            rejected(409,()->payment.service.payExpense("fixture-record",map("version",version)));payment.noWrites();
        }
    }
    @Test public void alreadyPaidRecordRejectsRetryEvenWithCurrentVersion() {
        Fixture f=new Fixture();f.row.put("kind","报销");f.row.put("status","已付款");
        rejected(400,()->f.service.payExpense("fixture-record",map("version",3)));
        f.noWrites();
    }
}
