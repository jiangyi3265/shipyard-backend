package com.ruoyi.shipyard;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.mockito.Mockito.*;
import static org.junit.Assert.*;
import java.util.*;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.mockito.ArgumentMatchers.*;
import static com.ruoyi.shipyard.ShipyardService.map;
public class ShipyardWechatNoticeTest {
 @Test public void malformedWechatResponseMustNotBeReportedAsSent()throws Exception{
  assertEquals(0,ShipyardWechatNotice.responseCode(com.alibaba.fastjson2.JSON.parseObject("{\"errcode\":0}")));
  assertEquals(43101,ShipyardWechatNotice.responseCode(com.alibaba.fastjson2.JSON.parseObject("{\"errcode\":43101}")));
  for(String json:Arrays.asList("null","{}","{\"errmsg\":\"unexpected\"}","{\"errcode\":null}","{\"errcode\":\"0\"}","{\"errcode\":0.5}")){
   try{ShipyardWechatNotice.responseCode(com.alibaba.fastjson2.JSON.parseObject(json));fail("Must reject malformed response: "+json);}catch(java.io.IOException expected){}
  }
 }
 @Test public void templateTruncationNeverSplitsAUnicodeCharacter(){
  assertEquals("一二三四🚢",ShipyardWechatNotice.truncate("一二三四🚢六",5));
  assertEquals("海盛308",ShipyardWechatNotice.truncate("海盛308",20));
 }
 @Test public void notificationsStayOffUntilValidTemplateAndFieldMappingExist(){
  JdbcTemplate db=mock(JdbcTemplate.class);
  ShipyardWechatNotice n=new ShipyardWechatNotice(db,mock(ShipyardAccess.class),mock(PlatformTransactionManager.class));
  ReflectionTestUtils.setField(n,"template","");ReflectionTestUtils.setField(n,"secret","fixture");ReflectionTestUtils.setField(n,"state","formal");ReflectionTestUtils.setField(n,"fields","");
  assertFalse(n.configured());n.deliver();verifyNoInteractions(db);
  ReflectionTestUtils.setField(n,"template","fixture_template");ReflectionTestUtils.setField(n,"fields","{\"thing1\":\"title\",\"phrase2\":\"status\",\"time3\":\"time\"}");assertTrue(n.configured());
  ReflectionTestUtils.setField(n,"fields","{\"thing1\":\"open_id\"}");assertFalse(n.configured());
  ReflectionTestUtils.setField(n,"fields","bad-json");assertFalse(n.configured());
 }
 @Test public void deliveryUsesBoundRecipientAndHandlesRevokedConsent()throws Exception{
  for(int result:new int[]{0,43101}){
   JdbcTemplate db=mock(JdbcTemplate.class);PlatformTransactionManager tm=mock(PlatformTransactionManager.class);
   when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
   List<Map<String,Object>> sent=new ArrayList<>();
   ShipyardWechatNotice n=new ShipyardWechatNotice(db,mock(ShipyardAccess.class),tm){@Override int send(Map<String,Object> payload){sent.add(payload);return result;}};
   ReflectionTestUtils.setField(n,"template","template_fixture");ReflectionTestUtils.setField(n,"appId","app_fixture");ReflectionTestUtils.setField(n,"secret","fixture");ReflectionTestUtils.setField(n,"state","trial");ReflectionTestUtils.setField(n,"fields","{\"thing1\":\"title\",\"phrase2\":\"status\"}");
   Map<String,Object> job=map("auditId",7L,"userId",10L,"id","record_fixture","title","申请审批","kind","用工","status","待结算","open_id","bound_fixture");
   when(db.queryForList(anyString(),eq("app_fixture"),eq("template_fixture"))).thenReturn(Arrays.asList(job));
   when(db.update(startsWith("UPDATE sy_wechat_subscription SET credits=credits-1"),eq(10L),eq("template_fixture"))).thenReturn(1);
   when(db.update(startsWith("INSERT IGNORE INTO sy_wechat_delivery"),eq(7L),eq(10L))).thenReturn(1);
   n.deliver();assertEquals(1,sent.size());assertEquals("bound_fixture",sent.get(0).get("touser"));assertEquals("pages/work-approval/index?id=record_fixture",sent.get(0).get("page"));
   verify(db).update(startsWith("UPDATE sy_wechat_delivery SET state=?"),eq(result==0?"sent":"failed"),eq(result),eq(7L),eq(10L));
   if(result==43101)verify(db).update(startsWith("UPDATE sy_wechat_subscription SET credits=0"),eq(10L),eq("template_fixture"));
   // A concurrent worker cannot send without claiming a remaining consent credit.
   when(db.update(startsWith("UPDATE sy_wechat_subscription SET credits=credits-1"),eq(10L),eq("template_fixture"))).thenReturn(0);
   n.deliver();assertEquals(1,sent.size());
  }
 }
}
