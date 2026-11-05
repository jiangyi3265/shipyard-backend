import {credentials} from './test-env.mjs';
import fs from 'node:fs';
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
const root=new URL('../',import.meta.url),base='http://127.0.0.1:8081',tokens={},results=[];
async function api(user,url,method='GET',body){const r=await(await fetch(base+url,{method,headers:{'Content-Type':'application/json',...(tokens[user]?{Authorization:'Bearer '+tokens[user]}:{})},body:body===undefined?undefined:JSON.stringify(body)})).json();assert.equal(r.code,200,url+': '+r.msg);return r.data??r}
async function login(username){const c=await api(null,'/captchaImage'),code=JSON.parse(execFileSync('redis-cli',['-p','6383','-n','1','--raw','GET','captcha_codes:'+c.uuid],{encoding:'utf8',windowsHide:true}));tokens[username]=(await api(null,'/login','POST',{username,password:credentials.password,uuid:c.uuid,code:String(code)})).token}
await login('admin');const all=await api('admin','/shipyard/bootstrap'),accounts=await api('admin','/shipyard/accounts');
for(const username of credentials.accounts){
 if(username!=='admin')await login(username);
 const started=performance.now(),data=await api(username,'/shipyard/bootstrap'),elapsedMs=Math.round(performance.now()-started);
 const account=accounts.users.find(u=>u.username===username);assert.ok(account);
 const elevated=username==='admin'||account.roleIds.some(id=>accounts.roles.some(r=>r.id===id&&['sy_admin','sy_manager','sy_owner'].includes(r.key)));
 const authorized=elevated?all.projects.map(p=>p.id):account.projectIds;
 assert.deepEqual(data.projects.map(p=>p.id).sort(),authorized.slice().sort());
 const can=p=>data.grants.includes('*:*:*')||data.grants.includes('shipyard:'+p);
 const expected=all.records.filter(r=>authorized.includes(r.projectId)&&(elevated||r.createdBy===data.user.id||can('finance:view')||(r.kind==='用工'?can('work:approve')||can('work:complete')||can('payroll:pay'):r.kind==='报销'?can('expense:approve')||can('expense:confirm')||can('expense:pay'):can('fine:approve')||can('fine:handle'))));
 assert.deepEqual(data.records.map(r=>r.id).sort(),expected.map(r=>r.id).sort());
 for(const r of data.records){const original=all.records.find(item=>item.id===r.id);assert.deepEqual(r,original)}
 if(!can('finance:view')&&!can('payroll:pay')){assert.deepEqual([data.payroll,data.attendance],[[],[]]);if(!can('ledger:edit'))assert.deepEqual(data.transactions,[]);else assert.ok(data.transactions.every(t=>t.source==='manual'))}
 results.push({username,records:data.records.length,elapsedMs});console.log('PASS '+username+' 工程/单据/财务数据隔离；'+data.records.length+' 单，'+elapsedMs+' ms');
}
fs.writeFileSync(new URL('.local/bootstrap-verification.json',root),JSON.stringify({completed:true,date:new Date().toISOString(),results},null,2));
