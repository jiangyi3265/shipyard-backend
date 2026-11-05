package com.ruoyi.shipyard;

import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import static com.ruoyi.shipyard.ShipyardService.*;

@RestController
@RequestMapping("/shipyard")
public class ShipyardWechatController {
    private final ShipyardWechatService service;
    public ShipyardWechatController(ShipyardWechatService service) { this.service=service; }
    private void limit(HttpServletRequest request) {
        String ip=request.getHeader("X-Real-IP");if(ip==null || ip.isEmpty()) ip=request.getRemoteAddr();service.limit(ip);
    }
    @Anonymous @PostMapping("/auth/wechat")
    public AjaxResult login(@RequestBody Map<String,Object> body,HttpServletRequest request) { limit(request);return AjaxResult.success(service.login(required(body,"code",256))); }
    @Anonymous @PostMapping("/auth/wechat/apply")
    public AjaxResult apply(@RequestBody Map<String,Object> body,HttpServletRequest request) { limit(request);return AjaxResult.success(service.apply(body)); }
    @GetMapping("/wechat/registrations")
    public AjaxResult registrations() { return AjaxResult.success(service.registrations()); }
    @PostMapping("/wechat/registrations/{id}/review")
    @Log(title="微信注册审核",businessType=BusinessType.UPDATE)
    public AjaxResult review(@PathVariable long id,@RequestBody Map<String,Object> body) { service.review(id,body);return AjaxResult.success(); }
}
