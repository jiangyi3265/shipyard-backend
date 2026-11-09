package com.ruoyi.shipyard;

import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import com.ruoyi.RuoYiApplication;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.framework.web.service.TokenService;
import com.ruoyi.framework.web.service.SysPermissionService;
import com.ruoyi.system.service.ISysUserService;
import static org.junit.Assert.*;
import static com.ruoyi.shipyard.ShipyardService.map;

@RunWith(SpringRunner.class)
@SpringBootTest(classes=RuoYiApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"druid","shipyard","production"})
@TestPropertySource(locations="file:${shipyard.test.config}")
@Import(ShipyardWechatIntegrationTest.IdentityFixture.class)
public class ShipyardWechatIntegrationTest {
    @TestConfiguration static class IdentityFixture {
        @Bean @Primary ShipyardWechatClient fixtureWechatClient() {
            return new ShipyardWechatClient() {
                @Override public String appId(){return "wx0000000000000000";}
                @Override public String openId(String code){
                    if(!code.matches("fixture_[a-z0-9_]+")) throw new ServiceException("微信登录凭证无效",400);
                    return "openid_"+code.substring(8);
                }
            };
        }
    }
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate db;
    @Autowired TokenService tokenService;
    @Autowired ISysUserService users;
    @Autowired SysPermissionService permissions;
    private Map call(String token,String path,HttpMethod method,Object body,int expected) {
        HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);
        if(token!=null)headers.setBearerAuth(token);
        Map result=http.exchange(path,method,new HttpEntity<>(body,headers),Map.class).getBody();
        assertNotNull(path,result);assertEquals(path+": "+result.get("msg"),expected,((Number)result.get("code")).intValue());return result;
    }
    private Map data(Map result){return (Map)result.get("data");}
    private String login(String code){return (String)data(call(null,"/shipyard/auth/wechat",HttpMethod.POST,map("code",code),200)).get("token");}
    private String token(long id){
        org.springframework.mock.web.MockHttpServletRequest request=new org.springframework.mock.web.MockHttpServletRequest();
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(request));
        try { com.ruoyi.common.core.domain.entity.SysUser u=users.selectUserById(id);return tokenService.createToken(new LoginUser(u.getUserId(),u.getDeptId(),u,permissions.getMenuPermission(u))); }
        finally { org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes(); }
    }
    private Map begin(String identity){return data(call(null,"/shipyard/auth/wechat",HttpMethod.POST,map("code","fixture_"+identity,"openid","spoofed","userId",1),200));}
    private long apply(Map session,String name){call(null,"/shipyard/auth/wechat/apply",HttpMethod.POST,map("ticket",session.get("ticket"),"name",name,"phone","13800000000","note","注册流程验收","userId",1,"roleIds",Arrays.asList(41000)),200);return db.queryForObject("SELECT id FROM sy_wechat_registration WHERE real_name=?",Long.class,name);}
    @Test public void registrationApprovalBindingAndIsolation() throws Exception {
        assertEquals("shipyard33_wx_1102",db.queryForObject("SELECT DATABASE()",String.class));
        // Test authentication tokens are issued only inside this test class and isolated database.
        String admin=token(1),leader=token(41001);long project=db.queryForObject("SELECT MIN(id) FROM sy_project",Long.class);
        call(null,"/shipyard/auth/wechat",HttpMethod.POST,map("code","forged"),400);
        Map first=begin("first");assertEquals("new",first.get("status"));assertFalse(first.containsKey("token"));assertFalse(first.containsKey("openid"));
        call((String)first.get("ticket"),"/shipyard/bootstrap",HttpMethod.GET,null,401);
        call(null,"/shipyard/auth/wechat/apply",HttpMethod.POST,map("ticket",String.join("",Collections.nCopies(64,"a")),"name","伪造申请"),400);
        long id=apply(first,"微信流程验收一");apply(first,"微信流程验收一");
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM sy_wechat_registration WHERE id=?",Integer.class,id).intValue());
        assertEquals("pending",begin("first").get("status"));assertNull(login("fixture_first"));
        call(leader,"/shipyard/wechat/registrations",HttpMethod.GET,null,403);
        Map grant=map("version",0,"accept",true,"roleIds",Arrays.asList(41001),"projectIds",Arrays.asList(project),"teamCategory","grinding");
        call(leader,"/shipyard/wechat/registrations/"+id+"/review",HttpMethod.POST,grant,403);
        call(admin,"/shipyard/wechat/registrations/"+id+"/review",HttpMethod.POST,map("version",0,"accept",true,"roleIds",Collections.emptyList(),"projectIds",Arrays.asList(project)),400);
        call(admin,"/shipyard/wechat/registrations/"+id+"/review",HttpMethod.POST,map("version",0,"accept",true,"roleIds",Arrays.asList(41001),"projectIds",Collections.emptyList()),400);
        Map invalidTeam=new HashMap(grant);invalidTeam.put("teamCategory","other");
        call(admin,"/shipyard/wechat/registrations/"+id+"/review",HttpMethod.POST,invalidTeam,400);
        assertEquals("pending",begin("first").get("status"));
        call(admin,"/shipyard/wechat/registrations/"+id+"/review",HttpMethod.POST,grant,200);
        call(admin,"/shipyard/wechat/registrations/"+id+"/review",HttpMethod.POST,grant,409);
        String bound=login("fixture_first");assertNotNull(bound);Map state=data(call(bound,"/shipyard/bootstrap",HttpMethod.GET,null,200));
        long uid=((Number)((Map)state.get("user")).get("id")).longValue();assertTrue(uid!=1);
        assertEquals("grinding",((Map)state.get("user")).get("teamCategory"));
        assertEquals("grinding",db.queryForObject("SELECT team_category FROM sy_user_team WHERE user_id=?",String.class,uid));
        assertEquals(1,((List)state.get("projects")).size());assertEquals(project,((Number)((Map)((List)state.get("projects")).get(0)).get("id")).longValue());
        assertEquals(uid,db.queryForObject("SELECT user_id FROM sy_wechat_registration WHERE id=?",Long.class,id).longValue());
        assertNotNull(login("fixture_first"));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM sy_wechat_registration WHERE user_id=?",Integer.class,uid).intValue());
        call(bound,"/shipyard/wechat/registrations",HttpMethod.GET,null,403);
        db.update("UPDATE sys_user SET status='1' WHERE user_id=?",uid);assertEquals("disabled",begin("first").get("status"));call(bound,"/shipyard/bootstrap",HttpMethod.GET,null,401);db.update("UPDATE sys_user SET status='0' WHERE user_id=?",uid);
        Map second=begin("second");long secondId=apply(second,"微信流程验收二");
        call(admin,"/shipyard/wechat/registrations/"+secondId+"/review",HttpMethod.POST,map("version",0,"accept",false,"reason","请补充所属班组"),200);
        Map rejected=begin("second");assertEquals("rejected",rejected.get("status"));assertEquals("请补充所属班组",rejected.get("reason"));
        apply(rejected,"微信流程验收二");assertEquals("pending",begin("second").get("status"));
        Map existing=map("version",2,"accept",true,"userId",41001,"roleIds",Arrays.asList(41001),"projectIds",Arrays.asList(project));
        db.update("INSERT INTO sy_user_team(user_id,team_category) VALUES(?,?)",41001,"painting");
        call(admin,"/shipyard/wechat/registrations/"+secondId+"/review",HttpMethod.POST,existing,200);
        Map linked=data(call(login("fixture_second"),"/shipyard/bootstrap",HttpMethod.GET,null,200));assertEquals(41001,((Number)((Map)linked.get("user")).get("id")).longValue());
        assertEquals("painting",((Map)linked.get("user")).get("teamCategory"));
        Map third=begin("third");long thirdId=apply(third,"微信流程验收三");
        call(admin,"/shipyard/wechat/registrations/"+thirdId+"/review",HttpMethod.POST,map("version",0,"accept",true,"userId",41001,"roleIds",Arrays.asList(41001),"projectIds",Arrays.asList(project)),400);
        call(admin,"/shipyard/wechat/registrations/"+thirdId+"/review",HttpMethod.POST,map("version",0,"accept",true,"userId",1,"roleIds",Arrays.asList(41000),"projectIds",Collections.emptyList()),400);
        call(admin,"/shipyard/wechat/registrations/"+thirdId+"/review",HttpMethod.POST,map("version",0,"accept",true,"roleIds",Arrays.asList(41000),"projectIds",Collections.emptyList()),200);
        assertNotNull(login("fixture_third"));
        Map listed=call(admin,"/shipyard/wechat/registrations",HttpMethod.GET,null,200);assertFalse(listed.toString().contains("openid_"));assertFalse(listed.toString().contains("session_key"));
        Map fourth=begin("fourth");long fourthId=apply(fourth,"并发审核验收");
        ExecutorService pool=Executors.newFixedThreadPool(2);
        List<Future<Map>> results=pool.invokeAll(Arrays.asList(()->callAny(admin,fourthId,project),()->callAny(admin,fourthId,project)));pool.shutdown();
        List<Integer> codes=new ArrayList<>();for(Future<Map> f:results)codes.add(((Number)f.get().get("code")).intValue());Collections.sort(codes);assertEquals(Arrays.asList(200,409),codes);
        System.out.println("PASS WeChat: verified identity, pending isolation, admin-only approval, role/project validation, persistent binding, rejected resubmission, existing-account binding, disabled blocking, privacy and concurrent approval");
    }
    private Map callAny(String admin,long id,long project){HttpHeaders headers=new HttpHeaders();headers.setBearerAuth(admin);headers.setContentType(MediaType.APPLICATION_JSON);return http.exchange("/shipyard/wechat/registrations/"+id+"/review",HttpMethod.POST,new HttpEntity<>(map("version",0,"accept",true,"roleIds",Arrays.asList(41001),"projectIds",Arrays.asList(project)),headers),Map.class).getBody();}
}
