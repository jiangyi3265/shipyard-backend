package com.ruoyi.shipyard;

import java.util.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import com.alibaba.fastjson2.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static com.ruoyi.shipyard.ShipyardService.*;

/** Subscription credits are only a local hint; WeChat independently enforces user consent. */
@Service
@EnableScheduling
public class ShipyardWechatNotice {
    private final JdbcTemplate db; private final ShipyardAccess access; private final TransactionTemplate tx;
    @Value("${shipyard.wechat.app-id}") private String appId;
    @Value("${shipyard.wechat.app-secret:}") private String secret;
    @Value("${shipyard.wechat.notice-template:}") private String template;
    @Value("${shipyard.wechat.notice-fields:}") private String fields;
    @Value("${shipyard.wechat.notice-state:formal}") private String state;
    private String token; private long tokenExpires;
    public ShipyardWechatNotice(JdbcTemplate db,ShipyardAccess access,PlatformTransactionManager tm){this.db=db;this.access=access;this.tx=new TransactionTemplate(tm);}
    boolean configured(){
        if(template.isEmpty() || secret.isEmpty() || !Arrays.asList("formal","trial","developer").contains(state))return false;
        try { JSONObject f=JSON.parseObject(fields);return f!=null&&!f.isEmpty()&&f.entrySet().stream().allMatch(e->e.getKey().matches("(thing|phrase|time|character_string|number)[0-9]+")&&Arrays.asList("title","status","time","project","kind","id").contains(e.getValue())); }
        catch(Exception e){return false;}
    }
    public Map<String,Object> config(){access.member();boolean bound=db.queryForObject("SELECT COUNT(*) FROM sy_wechat_registration WHERE user_id=? AND app_id=? AND status='approved'",Integer.class,access.uid(),appId)>0;return map("enabled",configured()&&bound,"templateId",configured()&&bound?template:"","message",!configured()?"审批通知尚未配置，请联系管理员":"请在微信小程序内订阅审批进度通知");}
    public void subscribe(Map<String,Object> body){
        access.member();check(Boolean.TRUE.equals(config().get("enabled")),"审批通知暂未开通");check(template.equals(str(body,"templateId")),"通知模板已更新，请刷新后重试");
        String key=required(body,"requestKey",64);check(key.matches("[a-zA-Z0-9_-]{16,64}"),"订阅请求无效");
        tx.execute(s->{
            db.queryForObject("SELECT user_id FROM sys_user WHERE user_id=? FOR UPDATE",Long.class,access.uid());
            List<Map<String,Object>> prior=db.queryForList("SELECT request_key FROM sy_wechat_subscription WHERE user_id=? AND template_id=?",access.uid(),template);
            if(!prior.isEmpty()&&key.equals(prior.get(0).get("request_key")))return null;
            db.update("INSERT INTO sy_wechat_subscription(user_id,template_id,credits,request_key) VALUES(?,?,1,?) ON DUPLICATE KEY UPDATE authorized_at=IF(credits=0,NOW(3),authorized_at),credits=LEAST(credits+1,20),request_key=VALUES(request_key)",access.uid(),template,key);return null;
        });
    }
    @Scheduled(fixedDelay=15000,initialDelay=30000)
    public synchronized void deliver(){
        if(!configured())return;
        // Only send the applicant's own subsequent progress, never other leaders' work or historic events.
        List<Map<String,Object>> jobs=db.queryForList("SELECT a.id AS auditId,r.created_by AS userId,r.id,r.title,r.kind,a.to_status AS status,DATE_FORMAT(a.created_at,'%Y-%m-%d %H:%i') AS time,p.name AS project,w.open_id FROM sy_audit a JOIN sy_record r ON r.id=a.record_id JOIN sy_project p ON p.id=r.project_id JOIN sy_wechat_registration w ON w.user_id=r.created_by AND w.status='approved' AND w.app_id=? JOIN sys_user u ON u.user_id=w.user_id AND u.status='0' AND u.del_flag='0' JOIN sy_wechat_subscription s ON s.user_id=u.user_id AND s.template_id=? AND s.credits>0 LEFT JOIN sy_wechat_delivery d ON d.audit_id=a.id AND d.user_id=u.user_id WHERE d.audit_id IS NULL AND a.actor_id<>r.created_by AND a.created_at>=s.authorized_at AND a.created_at>DATE_SUB(NOW(),INTERVAL 1 DAY) AND EXISTS(SELECT 1 FROM sys_user_role ur JOIN sys_role ro ON ro.role_id=ur.role_id WHERE ur.user_id=u.user_id AND ro.status='0' AND ro.del_flag='0' AND LEFT(ro.role_key,3)='sy_') AND (EXISTS(SELECT 1 FROM sys_user_role ur JOIN sys_role ro ON ro.role_id=ur.role_id WHERE ur.user_id=u.user_id AND ro.status='0' AND ro.del_flag='0' AND ro.role_key IN ('sy_admin','sy_manager','sy_owner')) OR EXISTS(SELECT 1 FROM sy_project_member pm WHERE pm.user_id=u.user_id AND pm.project_id=r.project_id)) ORDER BY a.id LIMIT 20",appId,template);
        for(Map<String,Object> job:jobs){
            Boolean claimed=tx.execute(s->{
                int taken=db.update("UPDATE sy_wechat_subscription SET credits=credits-1 WHERE user_id=? AND template_id=? AND credits>0",job.get("userId"),template);
                if(taken==0)return false;
                if(db.update("INSERT IGNORE INTO sy_wechat_delivery(audit_id,user_id,state) VALUES(?,?,'sending')",job.get("auditId"),job.get("userId"))==0){s.setRollbackOnly();return false;}return true;
            });
            if(!Boolean.TRUE.equals(claimed))continue;
            try {
                JSONObject data=new JSONObject();JSONObject mapping=JSON.parseObject(fields);
                for(String key:mapping.keySet()) {String value=String.valueOf(job.get(mapping.getString(key)));int max=key.startsWith("phrase")?5:key.startsWith("thing")?20:32;data.put(key,map("value",truncate(value,max)));}
                String path="用工".equals(job.get("kind"))?"work-approval":"报销".equals(job.get("kind"))?"expense-approval":"fine-detail";
                Map<String,Object> payload=map("touser",job.get("open_id"),"template_id",template,"page","pages/"+path+"/index?id="+job.get("id"),"miniprogram_state",state,"data",data);
                int code=send(payload);
                db.update("UPDATE sy_wechat_delivery SET state=?,error_code=? WHERE audit_id=? AND user_id=?",code==0?"sent":"failed",code,job.get("auditId"),job.get("userId"));
                if(code==43101)db.update("UPDATE sy_wechat_subscription SET credits=0 WHERE user_id=? AND template_id=?",job.get("userId"),template);
            } catch(Exception e) { // Timeout is ambiguous: do not retry and risk duplicate push.
                db.update("UPDATE sy_wechat_delivery SET state='unknown' WHERE audit_id=? AND user_id=?",job.get("auditId"),job.get("userId"));
            }
        }
    }
    static String truncate(String value,int max){return value.substring(0,value.offsetByCodePoints(0,Math.min(max,value.codePointCount(0,value.length()))));}
    static int responseCode(JSONObject response)throws IOException{
        Object code=response==null?null:response.get("errcode");
        if(!(code instanceof Integer))throw new IOException("Invalid WeChat response");
        return (Integer)code;
    }
    int send(Map<String,Object> payload)throws Exception {
        if(token==null||System.currentTimeMillis()>=tokenExpires){
            JSONObject result=post("https://api.weixin.qq.com/cgi-bin/stable_token",map("grant_type","client_credential","appid",appId,"secret",secret));
            token=result.getString("access_token");if(token==null)throw new IOException("WeChat access token unavailable");tokenExpires=System.currentTimeMillis()+Math.max(60,result.getIntValue("expires_in")-300)*1000L;
        }
        int code=responseCode(post("https://api.weixin.qq.com/cgi-bin/message/subscribe/send?access_token="+URLEncoder.encode(token,"UTF-8"),payload));
        if(code==40001||code==42001)token=null;
        return code;
    }
    private JSONObject post(String url,Map<String,Object> payload)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        try{c.setRequestMethod("POST");c.setDoOutput(true);c.setConnectTimeout(5000);c.setReadTimeout(10000);c.setInstanceFollowRedirects(false);c.setRequestProperty("Content-Type","application/json");
            try(OutputStream out=c.getOutputStream()){out.write(JSON.toJSONBytes(payload));}
            if(c.getResponseCode()!=200)throw new IOException("WeChat request failed");
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[2048];int n;while((n=in.read(b))!=-1){if(out.size()+n>16384)throw new IOException("Response too large");out.write(b,0,n);}return JSON.parseObject(new String(out.toByteArray(),StandardCharsets.UTF_8));}
        }finally{c.disconnect();}
    }
}
