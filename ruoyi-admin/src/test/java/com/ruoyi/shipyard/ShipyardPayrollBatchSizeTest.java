package com.ruoyi.shipyard;

import java.util.*;
import java.util.stream.*;
import org.junit.Test;
import com.ruoyi.common.exception.ServiceException;
import static org.junit.Assert.*;

public class ShipyardPayrollBatchSizeTest {
    @Test public void monthlyPayrollAcceptsMoreThanTwoHundredLines() {
        List<Long> month=LongStream.rangeClosed(1,220).boxed().collect(Collectors.toList());
        assertEquals(month,ShipyardService.payrollIds(month));
        try { ShipyardService.ids(month);fail("Personnel selection must retain its 200-person limit"); }
        catch(ServiceException expected) { assertTrue(expected.getMessage().contains("200")); }
    }
    @Test public void duplicatePayrollLinesAreRejected() {
        try { ShipyardService.payrollIds(Arrays.asList(1L,2L,1L));fail("Duplicate line must fail"); }
        catch(ServiceException expected) { assertEquals("工资明细不能重复",expected.getMessage()); }
    }
    @Test public void excessivePayrollBatchIsRejectedWithActionableMessage() {
        List<Long> max=LongStream.rangeClosed(1,20000).boxed().collect(Collectors.toList());
        assertEquals(20000,ShipyardService.payrollIds(max).size());
        max.add(20001L);
        try { ShipyardService.payrollIds(max);fail("Oversize batch must fail"); }
        catch(ServiceException expected) { assertEquals("单次最多结算20000条工资明细，请缩小结算范围",expected.getMessage()); }
    }
}
