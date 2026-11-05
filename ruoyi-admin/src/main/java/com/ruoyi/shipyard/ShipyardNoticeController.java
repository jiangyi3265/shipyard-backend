package com.ruoyi.shipyard;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.core.domain.AjaxResult;
@RestController
@RequestMapping("/shipyard/wechat/notifications")
public class ShipyardNoticeController {
 private final ShipyardWechatNotice service;
 public ShipyardNoticeController(ShipyardWechatNotice service){this.service=service;}
 @GetMapping public AjaxResult config(){return AjaxResult.success(service.config());}
 @PostMapping public AjaxResult subscribe(@RequestBody Map<String,Object> body){service.subscribe(body);return AjaxResult.success();}
}
