// Leader completion request -> manager return/confirm, area status and worker names. Test database only (8081).
import assert from 'node:assert/strict';
import fs from 'node:fs';
import crypto from 'node:crypto';
import {execFileSync} from 'node:child_process';
const root=new URL('../',import.meta.url);
import {credentials} from './test-env.mjs';
const base='http://127.0.0.1:8081',tokens={},results=[],key=()=>crypto.randomUUID();
async function api(user,path,method='GET',body,code=200){
 const response=await fetch(base+path,{method,headers:{'Content-Type':'application/json',...(tokens[user]?{Authorization:'Bearer '+tokens[user]}:{})},body:body===undefined?undefined:JSON.stringify(body)});
 const data=await response.json();assert.equal(data.code,code,path+': '+data.msg);return data.data??data;
}
async function login(user){
 const captcha=await api(null,'/captchaImage');
 const code=JSON.parse(execFileSync('redis-cli',['-p','6383','-n','1','--raw','GET','captcha_codes:'+captcha.uuid],{encoding:'utf8',windowsHide:true}));
 tokens[user]=(await api(null,'/login','POST',{username:user,password:credentials.password,uuid:captcha.uuid,code:String(code)})).token;
}
const report=name=>{results.push(name);console.log('PASS '+name);};
for(const name of credentials.accounts)await login(name);
const run=Date.now().toString().slice(-8);
let state=await api('admin','/shipyard/bootstrap');const project=state.projects[0];
await api('admin','/shipyard/master/area','PUT',{projectId:project.id,name:'完工申请'+run,area:100,rate:10});
await api('admin','/shipyard/master/person','PUT',{name:'完工验证'+run,code:'CA'+run,team:'完工验证',rate:30});
const area=async user=>(await api(user,'/shipyard/bootstrap')).areas.find(a=>a.name==='完工申请'+run);
const notices=async user=>(await api(user,'/shipyard/bootstrap')).notifications;
state=await api('admin','/shipyard/bootstrap');
const worker=state.people.find(p=>p.code==='CA'+run),areaId=(await area('admin')).id,date=state.serverDate;
let a=await area('admin');
assert.equal(a.status,'未开工');assert.equal(a.people,0);assert.deepEqual(a.workers,[]);
report('未提交用工的区域显示未开工，不计入进行中');

const work={projectId:project.id,areaId,kind:'用工',type:'点工',people:[worker.id],hours:{[worker.id]:4},note:'完工流程验证',date};
let record=await api('sy_leader','/shipyard/records','POST',{...work,requestKey:key()});
assert.equal((await area('admin')).status,'进行中');
await api('sy_leader','/shipyard/areas/'+areaId+'/completion-request','POST',{},400);
record=await api('sy_manager','/shipyard/records/'+record.id+'/review','POST',{version:record.version,accept:true,opinion:'通过'});
a=await area('sy_leader');assert.equal(a.people,1);assert.deepEqual(a.workers.map(w=>w.name),['完工验证'+run]);
assert.deepEqual((await area('sy_manager')).workers.map(w=>w.name),['完工验证'+run]);
report('提交用工后变为进行中，组长和经理都能看到施工人员姓名；待审批单据阻止提交完工');

await api('sy_manager','/shipyard/areas/'+areaId+'/completion-request','POST',{},403);
await api('admin','/shipyard/areas/'+areaId+'/completion-request','POST',{},400);
await api('sy_leader','/shipyard/areas/'+areaId+'/completion-request','POST',{});
a=await area('sy_manager');assert.equal(a.status,'待确认完工');assert.equal(a.completionRequester,'组长');assert.ok(a.completionRequestedAt);
assert.ok((await notices('sy_manager')).some(n=>n.title==='提交完工'&&n.recordId===record.id));
await api('sy_leader','/shipyard/areas/'+areaId+'/completion-request','POST',{},400);
await api('sy_leader','/shipyard/records','POST',{...work,requestKey:key()},400);
await api('sy_leader','/shipyard/areas/'+areaId+'/complete','POST',{},403);
await api('sy_leader','/shipyard/areas/'+areaId+'/completion-reject','POST',{reason:'x'},403);
report('仅有本人已审批用工的组长可提交完工；重复提交、提交后新增用工及组长自行确认均被拒绝；经理收到消息');

await api('sy_manager','/shipyard/areas/'+areaId+'/completion-reject','POST',{reason:''},400);
await api('sy_manager','/shipyard/areas/'+areaId+'/completion-reject','POST',{reason:'甲板边角未完成'});
a=await area('sy_leader');assert.equal(a.status,'进行中');assert.equal(a.completionRequestedAt,null);
assert.ok((await notices('sy_leader')).some(n=>n.title==='完工退回'&&n.detail==='甲板边角未完成'));
report('经理退回完工须填原因，区域恢复进行中，组长收到退回原因');

await api('sy_leader','/shipyard/areas/'+areaId+'/completion-request','POST',{});
await api('sy_manager','/shipyard/areas/'+areaId+'/complete','POST',{});
a=await area('sy_leader');assert.equal(a.status,'已完工');assert.ok(a.completedAt);
assert.ok((await notices('sy_leader')).some(n=>n.title==='施工完工'&&n.recordId===record.id));
await api('sy_manager','/shipyard/areas/'+areaId+'/completion-reject','POST',{reason:'x'},400);
report('组长重新提交后经理确认完工，区域归档并通知组长');

fs.writeFileSync(new URL('.local/area-completion-result.json',root),JSON.stringify({completed:true,date:new Date().toISOString(),database:'shipyard33_test',results},null,2));
console.log('All '+results.length+' area completion checks passed; database: shipyard33_test');
