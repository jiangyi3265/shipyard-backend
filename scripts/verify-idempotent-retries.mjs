import {credentials} from './test-env.mjs';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import crypto from 'node:crypto';
import {execFileSync} from 'node:child_process';
const root=new URL('../',import.meta.url),base='http://127.0.0.1:8081',tokens={},results=[];
async function request(user,url,method='GET',body){return(await fetch(base+url,{method,headers:{'Content-Type':'application/json',...(tokens[user]?{Authorization:'Bearer '+tokens[user]}:{})},body:body===undefined?undefined:JSON.stringify(body)})).json()}
async function upload(user){const form=new FormData();form.append('file',new Blob([fs.readFileSync(new URL('../../船厂/static/test/receipt-placeholder.png',import.meta.url))],{type:'image/png'}),'receipt.png');const r=await(await fetch(base+'/shipyard/files',{method:'POST',headers:{Authorization:'Bearer '+tokens[user]},body:form})).json();assert.equal(r.code,200,r.msg);return r.data.id}
async function api(...args){const j=await request(...args);assert.equal(j.code,200,j.msg);return j.data??j}
for(const user of ['sy_leader','sy_manager','sy_accountant']){const c=await api(null,'/captchaImage'),code=String(JSON.parse(execFileSync('redis-cli',['-p','6383','-n','1','--raw','GET','captcha_codes:'+c.uuid],{encoding:'utf8',windowsHide:true})));tokens[user]=(await api(null,'/login','POST',{username:user,password:credentials.password,code,uuid:c.uuid})).token}
const state=await api('sy_leader','/shipyard/bootstrap'),p=state.projects[0],a=state.areas.find(a=>a.projectId===p.id&&!a.completedAt),w=state.people[0];
const form={requestKey:crypto.randomUUID(),projectId:p.id,areaId:a.id,kind:'用工',type:'包工',start:state.serverDate,end:state.serverDate,people:[w.id],amount:12.34,allocations:{[w.id]:12.34},note:'同一请求并发重试验收'};
const submitted=await Promise.all([0,1,2].map(()=>request('sy_leader','/shipyard/records','POST',form)));
assert.ok(submitted.every(r=>r.code===200),'并发提交应全部返回已保存结果，实际 code: '+submitted.map(r=>r.code));assert.equal(new Set(submitted.map(r=>r.data.id)).size,1);results.push('同一用工请求并发重试返回同一单据');
const work=await api('sy_manager','/shipyard/records/'+submitted[0].data.id+'/review','POST',{version:0,accept:true});
const settle={requestKey:crypto.randomUUID(),projectId:p.id,month:state.serverDate.slice(0,7),lineIds:work.lines.map(l=>l.id),date:state.serverDate,method:'银行转账',proof:await upload('sy_accountant')};
const settled=await Promise.all([0,1,2].map(()=>request('sy_accountant','/shipyard/payroll/settle','POST',settle)));
assert.ok(settled.every(r=>r.code===200),'同一结算重试应返回相同成功结果，实际 code: '+settled.map(r=>r.code));assert.ok(settled.every(r=>r.data===12.34));results.push('同一工资结算请求并发重试只记一次支出');
const ledger={requestKey:crypto.randomUUID(),projectId:p.id,date:state.serverDate,title:'并发重试台账',amount:18.76,note:'只登记一次',files:[await upload('sy_accountant')]};
const saved=await Promise.all([0,1,2].map(()=>request('sy_accountant','/shipyard/ledger','POST',ledger)));
assert.ok(saved.every(r=>r.code===200),'收支重试应返回成功，实际 code: '+saved.map(r=>r.code));
const final=await api('sy_accountant','/shipyard/bootstrap');assert.equal(final.transactions.filter(t=>t.sourceId===ledger.requestKey).length,1);const batch=final.payroll.find(l=>l.id===work.lines[0].id).paymentId;assert.equal(final.transactions.filter(t=>t.sourceId===batch).length,1);results.push('同一收支请求并发重试只生成一条台账');
fs.writeFileSync(new URL('.local/idempotent-retries-verification.json',root),JSON.stringify({date:new Date().toISOString(),results},null,2));console.log(JSON.stringify(results,null,2));
