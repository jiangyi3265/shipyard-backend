// Regression for InnoDB snapshot reads preceding worker row locks. Test database only.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import crypto from 'node:crypto';
import {execFileSync} from 'node:child_process';
const root=new URL('../',import.meta.url),base='http://127.0.0.1:8081';
import {credentials} from './test-env.mjs';
const tokens={},results=[];
async function api(user,url,method='GET',body) {
  const response=await fetch(base+url,{method,headers:{'Content-Type':'application/json',...(tokens[user]?{Authorization:'Bearer '+tokens[user]}:{})},body:body===undefined?undefined:JSON.stringify(body)});
  return response.json();
}
for(const user of ['admin','sy_leader']) {
  const c=await api(null,'/captchaImage');
  const code=JSON.parse(execFileSync('redis-cli',['-p','6383','-n','1','--raw','GET','captcha_codes:'+c.uuid],{encoding:'utf8',windowsHide:true}));
  const r=await api(null,'/login','POST',{username:user,password:credentials.password,uuid:c.uuid,code:String(code)});
  assert.equal(r.code,200);tokens[user]=r.token;
}
const initial=(await api('sy_leader','/shipyard/bootstrap')).data,p=initial.projects[0];
const areas=initial.areas.filter(a=>a.projectId===p.id&&!a.completedAt);assert.ok(areas.length);
for(let round=0;round<3;round++) {
  const code='hours_'+Date.now()+'_'+round;
  assert.equal((await api('admin','/shipyard/master/person','PUT',{name:'并发工时回归'+round,code,team:'并发回归',rate:10})).code,200);
  const state=(await api('admin','/shipyard/bootstrap')).data,w=state.people.find(w=>w.code===code);
  // Distinct submitters and areas must still serialize their total for the same worker/date.
  const submitted=await Promise.all(['sy_leader','admin'].map((user,index)=>api(user,'/shipyard/records','POST',{
    requestKey:crypto.randomUUID(),projectId:p.id,areaId:areas[index%areas.length].id,kind:'用工',type:'点工',people:[w.id],hours:{[w.id]:16},note:'并发工时回归',date:state.serverDate
  })));
  assert.deepEqual(submitted.map(r=>r.code).sort(),[200,400],JSON.stringify(submitted.map(r=>({code:r.code,msg:r.msg}))));
  const after=(await api('admin','/shipyard/bootstrap')).data;
  const total=after.records.filter(r=>r.date===state.serverDate&&r.status!=='已退回').flatMap(r=>r.lines).filter(l=>l.workerId===w.id).reduce((s,l)=>s+l.hours,0);
  assert.equal(total,16);
  results.push('跨账号并发登记同一工人：仅一单通过，当日合计16小时（第'+(round+1)+'轮）');
  console.log('PASS '+results.at(-1));
}
fs.writeFileSync(new URL('.local/concurrent-hours-verification.json',root),JSON.stringify({date:new Date().toISOString(),database:'shipyard33_test',results},null,2));
