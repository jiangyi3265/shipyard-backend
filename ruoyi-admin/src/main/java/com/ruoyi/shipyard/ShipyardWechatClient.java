package com.ruoyi.shipyard;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Only the server exchanges WeChat codes. Never return or log the secret or session key. */
@Component
public class ShipyardWechatClient {
    private static final Logger log=LoggerFactory.getLogger(ShipyardWechatClient.class);
    @Value("${shipyard.wechat.app-id:}") private String appId;
    @Value("${shipyard.wechat.app-secret:}") private String secret;
    public String appId() { return appId; }
    public String openId(String code) {
        if(appId==null || appId.isEmpty() || secret==null || secret.isEmpty()) throw new ServiceException("微信登录暂未配置，请联系管理员",503);
        if(code==null || !code.matches("[A-Za-z0-9_-]{8,256}")) throw new ServiceException("微信登录凭证无效，请重试",400);
        HttpURLConnection connection=null;
        try {
            String query="?appid="+encode(appId)+"&secret="+encode(secret)+"&js_code="+encode(code)+"&grant_type=authorization_code";
            connection=(HttpURLConnection)new URL("https://api.weixin.qq.com/sns/jscode2session"+query).openConnection();
            connection.setConnectTimeout(5000);connection.setReadTimeout(10000);connection.setInstanceFollowRedirects(false);
            if(connection.getResponseCode()!=200) throw new ServiceException("微信服务暂时不可用，请稍后重试",503);
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            try(InputStream input=connection.getInputStream()) {
                byte[] buffer=new byte[2048];int n;
                while((n=input.read(buffer))!=-1) { if(out.size()+n>16384) throw new ServiceException("微信服务响应异常，请重试",503);out.write(buffer,0,n); }
            }
            JSONObject response=JSON.parseObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
            int error=response.getIntValue("errcode");
            if(error!=0) {
                log.warn("WeChat code2Session failed, error code {}",error);
                if(error==40029 || error==40163) throw new ServiceException("微信登录凭证已过期，请重新点击微信登录",400);
                if(error==40013 || error==40125 || error==40001) throw new ServiceException("微信登录配置异常，请联系管理员",503);
                throw new ServiceException("微信登录暂不可用，请稍后重试",503);
            }
            String openId=response.getString("openid");
            if(openId==null || !openId.matches("[A-Za-z0-9_-]{8,128}")) throw new ServiceException("微信身份验证失败，请重试",400);
            return openId;
        } catch(ServiceException e) { throw e; }
          catch(Exception e) { throw new ServiceException("无法连接微信服务，请稍后重试",503); }
        finally { if(connection!=null) connection.disconnect(); }
    }
    private String encode(String value) throws Exception { return URLEncoder.encode(value,"UTF-8"); }
}
