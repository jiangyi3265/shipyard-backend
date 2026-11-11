// Real API regression, restricted to the isolated local test database.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import crypto from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {credentials} from './test-env.mjs';
const base='http://127.0.0.1:8081',tokens={},run=Date.now().toString().slice(-9),passed=[];
async function api(user,url,method='GET',body,expected=200){const r=await(await fetch(base+url,{method,headers:{'Content-Type':'application/json',...(user?{Authorization:'Bearer '+tokens[user]}:{})},body:body===undefined?undefined:JSON.stringify(body)})).json();assert.equal(r.code,expected,url+': '+r.msg);return r.data??r;}
const boot=user=>api(user,'/shipyard/bootstrap');
try {
 await api(null,'/shipyard/bootstrap','GET',undefined,401);
 for(const user of ['admin','sy_leader','sy_manager','sy_accountant']){
  const c=await api(null,'/captchaImage');const code=JSON.parse(execFileSync('redis-cli',['-p','6383','-n','1','--raw','GET','captcha_codes:'+c.uuid],{encoding:'utf8',windowsHide:true}));
  tokens[user]=(await api(null,'/login','POST',{username:user,password:credentials.password,uuid:c.uuid,code:String(code)})).token;
 }
 const start=await boot('admin'),project=start.projects[0],day=start.serverDate;
 const names=['地点'+run+'主甲板','地点'+run+'货舱'];
 for(const name of names) await api('admin','/shipyard/master/area','PUT',{projectId:project.id,name,area:100,rate:30});
 await api('admin','/shipyard/master/person','PUT',{name:'地点工人'+run,code:'PL'+run,team:'测试班组',rate:30});
 const state=await boot('admin'),areas=names.map(n=>state.areas.find(a=>a.name===n)),worker=state.people.find(p=>p.code==='PL'+run),records=[];
 for(const [type,index] of [['点工',0],['加班',0],['包工',1]]){
  const body={requestKey:crypto.randomUUID(),projectId:project.id,areaId:areas[index].id,kind:'用工',type,people:[worker.id],date:day,note:'工资地点隔离回归',...(type==='包工'?{start:day,end:day,amount:100,allocations:{[worker.id]:100}}:{hours:{[worker.id]:type==='点工'?8:2}})};
  if(type==='加班')body.duration=2;
  const r=await api('sy_leader','/shipyard/records','POST',body);
  await api('sy_manager','/shipyard/records/'+r.id+'/review','POST',{version:r.version,accept:true});records.push({id:r.id,area:areas[index]});
 }
 let lines=(await boot('sy_accountant')).payroll.filter(l=>records.some(r=>r.id===l.recordId));assert.equal(lines.length,3);
 for(const line of lines){const r=records.find(r=>r.id===line.recordId);assert.equal(String(line.areaId),String(r.area.id),'Each wage line must retain its work area ID');assert.equal(line.area,r.area.name);assert.equal(line.ship,project.name);}
 passed.push('同人点工/加班/包工逐条返回真实船号和工作区域，不混用当前选中区域');
 await api('admin','/shipyard/master/area','PUT',{id:areas[0].id,projectId:project.id,name:names[0]+'改名',area:100,rate:30});
 lines=(await boot('sy_accountant')).payroll.filter(l=>records.some(r=>r.id===l.recordId));
 assert.ok(lines.filter(l=>l.recordId!==records[2].id).every(l=>l.area===names[0]));passed.push('区域改名后已有工资明细保留用工登记地点快照');
 const config=fs.readFileSync(new URL('../.local/button-audit/api.properties',import.meta.url),'utf8');
 assert.match(config,/jdbc:mysql:\/\/127\.0\.0\.1:13337\/shipyard33_test\?/);
 const dbPassword=config.match(/^spring\.datasource\.druid\.master\.password=(.*)$/m)[1].trim();
 assert.match(records[1].id,/^[A-Za-z0-9_-]+$/);
 execFileSync('mysql',['--protocol=TCP','-h','127.0.0.1','-P','13337','-u','root','shipyard33_test'],{env:{...process.env,MYSQL_PWD:dbPassword},input:"UPDATE sy_record SET body=JSON_REMOVE(body,'$.area') WHERE id='"+records[1].id+"';",stdio:'pipe',windowsHide:true});
 lines=(await boot('sy_accountant')).payroll.filter(l=>records.some(r=>r.id===l.recordId));
 assert.equal(lines.find(l=>l.recordId===records[1].id).area,names[0]+'改名');passed.push('无地点快照的旧单按原areaId关联工作区域');
 const line=lines.find(l=>l.label==='包工'),form=new FormData();form.append('file',new Blob([fs.readFileSync(new URL('../../船厂/static/test/receipt-placeholder.png',import.meta.url))],{type:'image/png'}),'receipt.png');
 const upload=await(await fetch(base+'/shipyard/files',{method:'POST',headers:{Authorization:'Bearer '+tokens.sy_accountant},body:form})).json();assert.equal(upload.code,200);
 await api('sy_accountant','/shipyard/payroll/settle','POST',{requestKey:crypto.randomUUID(),projectId:project.id,month:line.month,lineIds:[line.id],date:day,method:'现金',proof:upload.data.id});
 const paid=(await boot('sy_accountant')).payroll.find(l=>l.id===line.id);assert.equal(paid.paid,true);assert.equal(paid.area,names[1]);assert.equal(paid.amount,100);passed.push('已结算工资仍显示原地点，金额不变');
 assert.equal((await boot('sy_leader')).payroll.length,0);passed.push('未登录和无财务权限仍不能获取工资明细');
 fs.writeFileSync(new URL('../.local/payroll-location-result.json',import.meta.url),JSON.stringify({completed:true,at:new Date().toISOString(),passed},null,2));console.log(JSON.stringify({completed:true,passed}));
}catch(e){console.error(String(e.message).split(credentials.password).join('[redacted]'));process.exitCode=1;}
