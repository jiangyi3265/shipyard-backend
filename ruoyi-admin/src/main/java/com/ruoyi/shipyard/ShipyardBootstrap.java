package com.ruoyi.shipyard;

import java.util.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Explicit local first-run provisioning; never resets existing passwords or grants. */
@Component
@ConditionalOnProperty(name="shipyard.bootstrap.enabled",havingValue="true")
public class ShipyardBootstrap implements ApplicationRunner {
    private final JdbcTemplate db;
    @Value("${shipyard.bootstrap.password}") private String password;
    public ShipyardBootstrap(JdbcTemplate db) { this.db=db; }
    public static final String[][] PERMISSIONS={
        {"基础资料管理","master:edit"},{"提交施工用工","work:submit"},{"审批用工申请","work:approve"},
        {"确认施工完工","work:complete"},{"提交报销","expense:submit"},{"经理报销签字","expense:approve"},
        {"负责人报销签字","expense:confirm"},{"报销付款登记","expense:pay"},{"提交安全罚款","fine:submit"},
        {"审批安全罚款","fine:approve"},{"罚款收款登记","fine:handle"},{"工资结算登记","payroll:pay"},
        {"经营收支查看","finance:view"},{"收支台账登记","ledger:edit"},{"账号权限管理","account:edit"}
    };
    @Override @Transactional public void run(ApplicationArguments args) {
        if(db.queryForObject("SELECT COUNT(*) FROM sys_role WHERE role_key='sy_leader'",Integer.class)>0) return;
        if(password.length()<12) throw new IllegalArgumentException("首次初始化须提供至少12位随机密码");
        String hash=new BCryptPasswordEncoder().encode(password);
        db.update("UPDATE sys_user SET password=?,pwd_update_date=NOW() WHERE user_id=1",hash);
        for(int i=0;i<PERMISSIONS.length;i++) db.update("INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,menu_type,visible,status,perms,create_by,create_time) VALUES(?, ?,0,?,'F','0','0',?,'shipyard',NOW())",40000+i,PERMISSIONS[i][0],i,"shipyard:"+PERMISSIONS[i][1]);
        String[][] roles={
            {"sy_admin","管理员","master:edit,account:edit,finance:view"},
            {"sy_leader","组长","work:submit,expense:submit"},
            {"sy_manager","项目经理","work:approve,work:complete,expense:submit,expense:approve,fine:approve"},
            {"sy_owner","负责人","expense:confirm,finance:view"},
            {"sy_accountant","会计","expense:pay,fine:handle,payroll:pay,finance:view,ledger:edit"},
            {"sy_safety","安全员","fine:submit,expense:submit"}
        };
        for(int i=0;i<roles.length;i++) {
            long roleId=41000+i;
            db.update("INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag,create_by,create_time) VALUES(?,?,?,?,'0','0','shipyard',NOW())",roleId,roles[i][1],roles[i][0],i);
            for(String permission:roles[i][2].split(",")) db.update("INSERT INTO sys_role_menu(role_id,menu_id) SELECT ?,menu_id FROM sys_menu WHERE perms=?",roleId,"shipyard:"+permission);
            if(i==0) continue;
            db.update("INSERT INTO sys_user(user_id,dept_id,user_name,nick_name,password,status,del_flag,pwd_update_date,create_by,create_time) VALUES(?,101,?,?,?,'0','0',NOW(),'shipyard',NOW())",41000+i,roles[i][0],roles[i][1],hash);
            db.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)",41000+i,roleId);
        }
        // Basic reference records only. No fabricated approvals, wages, payments or balances.
        db.update("INSERT INTO sy_project(name,title,price,area) VALUES('海盛 308','船体涂装工程',756000,12600),('远洋 216','甲板维护工程',480000,8000)");
        db.update("INSERT INTO sy_project_member(project_id,user_id) SELECT p.id,u.user_id FROM sy_project p CROSS JOIN sys_user u WHERE u.user_id BETWEEN 41001 AND 41005");
        long p=db.queryForObject("SELECT id FROM sy_project WHERE name='海盛 308'",Long.class);
        db.update("INSERT INTO sy_area(project_id,name,area,rate) VALUES(?,'A 区 · 主甲板',1200,60),(?,'B 区 · 货舱',2400,60),(?,'C 区 · 船艉',860,60),(?,'D 区 · 机舱通道',640,60)",p,p,p,p);
        long second=db.queryForObject("SELECT id FROM sy_project WHERE name='远洋 216'",Long.class);
        db.update("INSERT INTO sy_area(project_id,name,area,rate) VALUES(?,'A 区 · 甲板',8000,60)",second);
        String[] names={"张建国","李志强","王海波","赵文斌","周建平","刘海涛","陈伟","孙强"};
        for(int i=0;i<names.length;i++) db.update("INSERT INTO sy_worker(name,code,team,rate) VALUES(?,?,'涂装组',?)",names[i],String.format("%04d",33+i),i<2?35:32);
    }
}
