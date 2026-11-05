package com.ruoyi.shipyard;

import java.util.*;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import static com.ruoyi.shipyard.ShipyardService.*;

@RestController
@RequestMapping("/shipyard")
public class ShipyardController {
    private final ShipyardService service;
    private final ShipyardAdministration admin;
    private final ShipyardAccess access;
    private final JdbcTemplate db;
    public ShipyardController(ShipyardService service,ShipyardAdministration admin,ShipyardAccess access,JdbcTemplate db) { this.service=service; this.admin=admin; this.access=access; this.db=db; }
    @GetMapping("/bootstrap") public AjaxResult bootstrap() { return AjaxResult.success(service.bootstrap()); }
    @GetMapping("/records/{id}") public AjaxResult detail(@PathVariable String id) { return AjaxResult.success(service.detail(id)); }
    @PostMapping("/records") public AjaxResult submit(@RequestBody Map<String,Object> body) { return AjaxResult.success(service.submit(body,null)); }
    @PutMapping("/records/{id}") public AjaxResult resubmit(@PathVariable String id,@RequestBody Map<String,Object> body) { return AjaxResult.success(service.submit(body,id)); }
    @PostMapping("/records/{id}/review") public AjaxResult review(@PathVariable String id,@RequestBody Map<String,Object> body) { return AjaxResult.success(service.review(id,body)); }
    @PostMapping("/records/{id}/payment") public AjaxResult payment(@PathVariable String id,@RequestBody Map<String,Object> body) { return AjaxResult.success(service.payExpense(id,body)); }
    @PostMapping("/records/{id}/fine-receipt") public AjaxResult fine(@PathVariable String id,@RequestBody Map<String,Object> body) { return AjaxResult.success(service.handleFine(id,body)); }
    @PostMapping("/payroll/settle") public AjaxResult payroll(@RequestBody Map<String,Object> body) { return AjaxResult.success(service.payroll(body)); }
    @PostMapping("/ledger") public AjaxResult ledger(@RequestBody Map<String,Object> body) { service.manualLedger(body); return AjaxResult.success(); }
    @PostMapping("/areas/{id}/complete") public AjaxResult complete(@PathVariable long id) { service.complete(id); return AjaxResult.success(); }
    @PostMapping("/areas/{id}/completion-request") public AjaxResult requestCompletion(@PathVariable long id) { service.requestCompletion(id); return AjaxResult.success(); }
    @PostMapping("/areas/{id}/completion-reject") public AjaxResult rejectCompletion(@PathVariable long id,@RequestBody Map<String,Object> body) { service.rejectCompletion(id,body); return AjaxResult.success(); }
    @PostMapping("/notices/{id}/read") public AjaxResult read(@PathVariable long id) { service.readNotice(id); return AjaxResult.success(); }
    @PutMapping("/master/{type}") public AjaxResult master(@PathVariable String type,@RequestBody Map<String,Object> body) { admin.master(type,body); return AjaxResult.success(); }
    @GetMapping("/accounts") public AjaxResult accounts() { return AjaxResult.success(admin.accounts()); }
    @PutMapping("/accounts") public AjaxResult account(@RequestBody Map<String,Object> body) { admin.account(body); return AjaxResult.success(); }
    @PutMapping("/roles/{id}/permissions") public AjaxResult permissions(@PathVariable long id,@RequestBody Map<String,Object> body) { admin.permissions(id,body); return AjaxResult.success(); }
    @PostMapping("/files") public AjaxResult upload(@RequestParam("file") MultipartFile file) throws Exception {
        access.active();
        check(!access.permissions().isEmpty(),"没有上传权限");
        check(file.getSize()>0&&file.getSize()<=5*1024*1024,"图片必须小于5MB");
        byte[] data=file.getBytes();
        boolean png=data.length>8 && data[0]==(byte)137 && data[1]==80 && data[2]==78 && data[3]==71;
        boolean jpg=data.length>3 && data[0]==(byte)255 && data[1]==(byte)216 && data[2]==(byte)255;
        check(png||jpg,"仅支持PNG或JPEG图片");
        try(javax.imageio.stream.ImageInputStream input=ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<javax.imageio.ImageReader> readers=ImageIO.getImageReaders(input); check(readers.hasNext(),"无法识别图片");
            javax.imageio.ImageReader reader=readers.next();
            try { reader.setInput(input); check((long)reader.getWidth(0)*reader.getHeight(0)<=25000000,"图片尺寸过大"); } finally { reader.dispose(); }
        }
        String id=UUID.randomUUID().toString(),mime=png?"image/png":"image/jpeg";
        db.update("INSERT INTO sy_file(id,owner_id,mime,content) VALUES(?,?,?,?)",id,access.uid(),mime,data);
        return AjaxResult.success("上传成功",map("id","file:"+id));
    }
    @GetMapping("/files/{id}") public AjaxResult file(@PathVariable String id) {
        access.member(); Map<String,Object> f=service.one("SELECT * FROM sy_file WHERE id=?",id);
        boolean allowed=number(f.get("owner_id"))==access.uid();
        if(!allowed) for(Map<String,Object> r:db.queryForList("SELECT r.* FROM sy_record r JOIN sy_record_file f ON f.record_id=r.id WHERE f.file_id=?",id)) {
            try { if(access.canRead(r)) { allowed=true; break; } } catch(ServiceException ignored) { }
        }
        if(!allowed) throw new ServiceException("无权查看此凭证",403);
        return AjaxResult.success("读取成功","data:"+f.get("mime")+";base64,"+Base64.getEncoder().encodeToString((byte[])f.get("content")));
    }
}
