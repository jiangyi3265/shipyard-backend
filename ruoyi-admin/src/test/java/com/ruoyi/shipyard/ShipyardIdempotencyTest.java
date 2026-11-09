package com.ruoyi.shipyard;

import java.math.BigDecimal;
import java.util.*;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.exception.ServiceException;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static com.ruoyi.shipyard.ShipyardService.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ShipyardIdempotencyTest {
    private static final String KEY = "idempotency_test_request_01";
    private static final String DAY = "2026-11-04";

    private Map<String,Object> request(String kind, String type) {
        Map<String,Object> b = map("kind",kind,"type",type,"projectId",1L,"areaId",2L,
                "date",DAY,"note","施工说明","files",new ArrayList<>(),"requestKey",KEY,
                "amount",100,"person",3L,"category","劳保用品");
        if ("用工".equals(kind)) b.putAll(map("people",Arrays.asList(3L,4L),"start",DAY,"end","2026-11-05",
                "hours",map("3",2,"4",3),"duration",2,"allocations",map("3",40,"4",60)));
        return b;
    }

    private Map<String,Object> saved(Map<String,Object> request, boolean withIdentity) {
        Map<String,Object> content = new LinkedHashMap<>(request);
        for (String field : Arrays.asList("kind","type","projectId","areaId","date","amount","requestKey")) content.remove(field);
        if (withIdentity) content.put(ShipyardIdempotency.META,ShipyardIdempotency.identity(request,DAY));
        return map("id","saved-record","kind",request.get("kind"),"type",request.get("type"),"project_id",1L,
                "area_id",2L,"amount",new BigDecimal("100.00"),"business_date",java.sql.Date.valueOf(DAY),
                "created_by",7L,"body",JSON.toJSONString(content),"created_at",DAY+" 10:20:00",
                "status","待结算","version",2);
    }

    private void conflict(Runnable action) {
        try { action.run(); fail("Different business input must not reuse a successful request"); }
        catch (ServiceException expected) { assertEquals(Integer.valueOf(409),expected.getCode()); }
    }

    @Test public void equivalentContractInputAcceptsNumericStringsAndReorderedPeople() {
        Map<String,Object> initial = request("用工","包工");
        initial.put("files",Arrays.asList("file:one","file:two"));
        Map<String,Object> retry = new LinkedHashMap<>(initial);
        retry.putAll(map("projectId","1","areaId","2","people",Arrays.asList("4","3"),
                "allocations",map("4","60.00","3","40.00"),"amount","100.00",
                "files",Arrays.asList("file:two","file:one"),"note"," 施工说明 "));
        for (boolean metadata : Arrays.asList(false,true)) ShipyardIdempotency.submission(retry,saved(initial,metadata));
    }

    @Test public void newAndLegacyRecordsAcceptAllEquivalentBusinessKinds() {
        for (Map<String,Object> initial : Arrays.asList(request("用工","包工"),request("用工","点工"),
                request("用工","加班"),request("报销",""),request("罚款",""))) {
            Map<String,Object> retry = new LinkedHashMap<>(initial);
            retry.put("amount","100.00");
            retry.put("duration","2.00");
            retry.put("hours",map("4","3.00","3","2.00"));
            for (boolean metadata : Arrays.asList(false,true)) ShipyardIdempotency.submission(retry,saved(initial,metadata));
        }
    }

    @Test public void workRetriesIgnoreUntrustedDerivedTotalsAndRates() {
        for (String type : Arrays.asList("点工","加班")) {
            Map<String,Object> initial = request("用工",type);
            Map<String,Object> retry = new LinkedHashMap<>(initial);
            retry.putAll(map("amount","forged","rate",9999,"multiplier",1.5,"hoursTotal",9999));
            for (boolean metadata : Arrays.asList(false,true)) ShipyardIdempotency.submission(retry,saved(initial,metadata));
        }
    }

    @Test public void changedWorkInputConflictsInsteadOfReturningOldApplication() {
        Map<String,Object> initial = request("用工","点工");
        for (Map<String,Object> change : Arrays.asList(map("hours",map("3",3,"4",3)),map("people",Arrays.asList(3L)),
                map("areaId",8),map("projectId",8),map("date","2026-11-05"),map("note","新内容"),
                map("files",Arrays.asList("file:new")),map("type","加班"))) {
            Map<String,Object> retry = new LinkedHashMap<>(initial); retry.putAll(change);
            for (boolean metadata : Arrays.asList(false,true)) conflict(() -> ShipyardIdempotency.submission(retry,saved(initial,metadata)));
        }
        Map<String,Object> overtime = request("用工","加班");
        Map<String,Object> changed = new LinkedHashMap<>(overtime); changed.put("duration",3);
        conflict(() -> ShipyardIdempotency.submission(changed,saved(overtime,true)));
    }

    @Test public void changedContractAllocationOrPeriodConflicts() {
        Map<String,Object> initial = request("用工","包工");
        for (Map<String,Object> change : Arrays.asList(map("amount",120,"allocations",map("3",60,"4",60)),
                map("allocations",map("3",50,"4",50)),map("start","2026-11-03"),map("end","2026-11-06"))) {
            Map<String,Object> retry = new LinkedHashMap<>(initial); retry.putAll(change);
            conflict(() -> ShipyardIdempotency.submission(retry,saved(initial,true)));
        }
    }

    @Test public void changedExpenseAndFineInputConflicts() {
        for (String kind : Arrays.asList("报销","罚款")) {
            Map<String,Object> initial = request(kind,"");
            Map<String,Object> amount = new LinkedHashMap<>(initial); amount.put("amount",101);
            conflict(() -> ShipyardIdempotency.submission(amount,saved(initial,false)));
            Map<String,Object> detail = new LinkedHashMap<>(initial);
            detail.put("报销".equals(kind)?"category":"person","报销".equals(kind)?"材料采购":4);
            conflict(() -> ShipyardIdempotency.submission(detail,saved(initial,true)));
        }
    }

    @Test public void omittedDateUsesFirstAcceptedDateEvenAfterReturnedRecordIsEdited() {
        Map<String,Object> initial = request("用工","点工"); initial.remove("date");
        Map<String,Object> record = saved(initial,true);
        Map<String,Object> content = JSON.parseObject(String.valueOf(record.get("body")));
        content.put("note","退回后修改"); content.put("hours",map("3",5,"4",6));
        record.put("body",JSON.toJSONString(content)); record.put("business_date",java.sql.Date.valueOf("2026-11-06"));
        ShipyardIdempotency.submission(initial,record);
        Map<String,Object> newInput = new LinkedHashMap<>(initial); newInput.put("note","退回后修改");
        conflict(() -> ShipyardIdempotency.submission(newInput,record));
    }

    @Test public void legacyOmittedDateRetryUsesPersistedBusinessDate() {
        Map<String,Object> initial = request("罚款","");
        Map<String,Object> retry = new LinkedHashMap<>(initial); retry.remove("date");
        ShipyardIdempotency.submission(retry,saved(initial,false));
    }

    private Map<String,Object> batch() {
        return map("id","batch-one","project_id",1L,"paid_by",7L,"month","2026-11","amount",new BigDecimal("123.45"));
    }

    @Test public void payrollAcceptsEquivalentSortedLineSet() {
        ShipyardIdempotency.payroll(batch(),1,7,"2026-11",payrollIds(Arrays.asList("5",3)),Arrays.asList(5L,3L));
    }

    @Test public void payrollRejectsDifferentActorMonthProjectOrLineSet() {
        List<Long> lines = Arrays.asList(3L,5L);
        conflict(() -> ShipyardIdempotency.payroll(batch(),1,8,"2026-11",lines,lines));
        conflict(() -> ShipyardIdempotency.payroll(batch(),2,7,"2026-11",lines,lines));
        conflict(() -> ShipyardIdempotency.payroll(batch(),1,7,"2026-10",lines,lines));
        conflict(() -> ShipyardIdempotency.payroll(batch(),1,7,"2026-11",Arrays.asList(3L,6L),lines));
        conflict(() -> ShipyardIdempotency.payroll(batch(),1,7,"2026-11",Arrays.asList(3L),lines));
    }

    private Map<String,Object> entry() {
        return map("project_id",1L,"created_by",7L,"title","材料收入","amount",new BigDecimal("100.00"),
                "business_date",java.sql.Date.valueOf(DAY),"note","已收款");
    }

    @Test public void ledgerRetryComparesStoredValuesAndNumericAmount() {
        ShipyardIdempotency.ledger(entry(),1,7,"材料收入",new BigDecimal("100"),DAY,"已收款");
        conflict(() -> ShipyardIdempotency.ledger(entry(),2,7,"材料收入",new BigDecimal("100"),DAY,"已收款"));
        conflict(() -> ShipyardIdempotency.ledger(entry(),1,8,"材料收入",new BigDecimal("100"),DAY,"已收款"));
        conflict(() -> ShipyardIdempotency.ledger(entry(),1,7,"另一收入",new BigDecimal("100"),DAY,"已收款"));
        conflict(() -> ShipyardIdempotency.ledger(entry(),1,7,"材料收入",new BigDecimal("101"),DAY,"已收款"));
        conflict(() -> ShipyardIdempotency.ledger(entry(),1,7,"材料收入",new BigDecimal("-100"),DAY,"已收款"));
        conflict(() -> ShipyardIdempotency.ledger(entry(),1,7,"材料收入",new BigDecimal("100"),"2026-11-05","已收款"));
        conflict(() -> ShipyardIdempotency.ledger(entry(),1,7,"材料收入",new BigDecimal("100"),DAY,"不同备注"));
    }

    private ShipyardService service(JdbcTemplate db) {
        ShipyardAccess access = mock(ShipyardAccess.class); when(access.uid()).thenReturn(7L);
        return new ShipyardService(db,access) {
            @Override void lockRequestOwner() { }
        };
    }

    @Test public void serviceSubmitRetryReturnsSavedSnapshotWithoutRepricingOrWriting() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        Map<String,Object> initial = request("用工","点工"), record = saved(initial,true);
        when(db.queryForList("SELECT * FROM sy_record WHERE created_by=? AND request_key=?",7L,KEY))
                .thenReturn(Collections.singletonList(record));
        Map<String,Object> result = service(db).submit(initial,null);
        assertEquals(new BigDecimal("100.00"),result.get("amount"));
        assertFalse(result.containsKey(ShipyardIdempotency.META));
        verify(db,never()).update(anyString(),any(Object[].class));
        verify(db,never()).queryForList(eq("SELECT * FROM sy_worker WHERE id=? AND active=1 FOR UPDATE"),any(Object[].class));
        Map<String,Object> changed = new LinkedHashMap<>(initial); changed.put("hours",map("3",8,"4",8));
        conflict(() -> service(db).submit(changed,null));
        verify(db,never()).update(anyString(),any(Object[].class));
    }

    @Test public void servicePayrollRetryReturnsStoredAmountWithoutPaymentOrLedgerWrites() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        when(db.queryForList("SELECT * FROM sy_payroll_batch WHERE request_key=?",KEY)).thenReturn(Collections.singletonList(batch()));
        when(db.queryForList("SELECT id FROM sy_work_line WHERE payment_id=? ORDER BY id",Long.class,"batch-one"))
                .thenReturn(Arrays.asList(3L,5L));
        Map<String,Object> request = map("projectId",1,"month","2026-11","lineIds",Arrays.asList("5","3"),"requestKey",KEY,
                "date",DAY,"method","银行转账","proof","file:0f0e0d0c-0b0a-4900-8000-000000000001");
        assertEquals(new BigDecimal("123.45"),service(db).payroll(request));
        request.put("lineIds",Arrays.asList(3L,6L));
        conflict(() -> service(db).payroll(request));
        verify(db,never()).update(anyString(),any(Object[].class));
    }

    @Test public void serviceLedgerRetryNeverInsertsTwiceAndRejectsEditedAmount() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        when(db.queryForList("SELECT project_id,created_by,title,amount,business_date,note FROM sy_ledger WHERE source_kind='manual' AND source_id=?",KEY))
                .thenReturn(Collections.singletonList(entry()));
        when(db.queryForObject("SELECT COUNT(*) FROM sy_file WHERE id=? AND owner_id=?",Integer.class,"0f0e0d0c-0b0a-4900-8000-000000000002",7L)).thenReturn(1);
        Map<String,Object> request = map("projectId",1,"title","材料收入","date",DAY,"amount","100.00","note","已收款","requestKey",KEY,
                "files",Arrays.asList("file:0f0e0d0c-0b0a-4900-8000-000000000002"));
        service(db).manualLedger(request);
        request.put("amount",101);
        conflict(() -> service(db).manualLedger(request));
        verify(db,never()).update(anyString(),any(Object[].class));
    }
}
