// Deterministic lock-order regression. Writes only to the isolated localhost test database.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import crypto from 'node:crypto';
import {execFileSync,spawn} from 'node:child_process';
import {credentials} from './test-env.mjs';

if(process.env.SHIPYARD_DB_PASSWORD===undefined) throw new Error('Set SHIPYARD_DB_PASSWORD for the isolated local MySQL instance.');
const database='shipyard33_test',base='http://127.0.0.1:8081',tokens={},results=[],fixtures=[];
const mysqlArgs=['--host=127.0.0.1','--port=3306',`--user=${process.env.SHIPYARD_DB_USER||'root'}`,'--connect-timeout=5','--default-character-set=utf8mb4','--batch','--skip-column-names','--unbuffered',database];
const mysqlEnv={...process.env,MYSQL_PWD:process.env.SHIPYARD_DB_PASSWORD};
const sql=query=>execFileSync('mysql',mysqlArgs,{input:query,env:mysqlEnv,encoding:'utf8',windowsHide:true}).trim();
const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function response(user,path,method='GET',body) {
 const r=await fetch(base+path,{method,headers:{'Content-Type':'application/json',...(tokens[user]?{Authorization:'Bearer '+tokens[user]}:{})},body:body===undefined?undefined:JSON.stringify(body)});
 return r.json();
}
async function api(...args){const j=await response(...args);assert.equal(j.code,200,j.msg);return j.data??j;}
async function login(username){
 const c=await api(null,'/captchaImage');
 const code=JSON.parse(execFileSync('redis-cli',['-p','6383','-n','1','--raw','GET','captcha_codes:'+c.uuid],{encoding:'utf8',windowsHide:true}));
 tokens[username]=(await api(null,'/login','POST',{username,password:credentials.password,uuid:c.uuid,code:String(code)})).token;
}
async function waitFor(predicate,label){
 for(let i=0;i<80;i++){if(predicate())return;await pause(100);}
 throw new Error('Timeout: '+label);
}
async function holdArea(id){
 const child=spawn('mysql',mysqlArgs,{env:mysqlEnv,windowsHide:true,stdio:['pipe','pipe','pipe']});
 let output='',failure='';child.stdout.on('data',b=>output+=b);child.stderr.on('data',b=>failure+=b);
 child.stdin.write(`START TRANSACTION; SELECT id FROM sy_area WHERE id=${Number(id)} FOR UPDATE; SELECT 'AREA_LOCKED';\n`);
 try{await waitFor(()=>output.includes('AREA_LOCKED'),'external area row lock');}
 catch(error){child.stdin.end('ROLLBACK;\n');throw error;}
 return async()=>{child.stdin.end('COMMIT;\n');await new Promise(resolve=>child.on('close',resolve));assert.equal(failure,'');};
}
function areaWaiters(id){
 return Number(sql(`SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID WHERE l.OBJECT_SCHEMA='${database}' AND l.OBJECT_NAME='sy_area' AND l.INDEX_NAME='PRIMARY' AND l.LOCK_DATA='${Number(id)}';`));
}
async function fixture(suffix,withReturned=true){
 assert.match(suffix,/^[0-9a-f]{16}$/);fixtures.push(suffix);
 const initial=await api('admin','/shipyard/bootstrap'),p=initial.projects[0];
 await api('admin','/shipyard/master/area','PUT',{projectId:p.id,name:'Completion race '+suffix,area:10,rate:10});
 await api('admin','/shipyard/master/person','PUT',{name:'Race '+suffix,code:'CR'+suffix,team:'Completion regression',rate:10});
 const state=await api('admin','/shipyard/bootstrap'),area=state.areas.find(a=>a.name==='Completion race '+suffix),worker=state.people.find(w=>w.code==='CR'+suffix);
 const form={projectId:p.id,areaId:area.id,kind:'用工',type:'点工',people:[worker.id],hours:{[worker.id]:1},note:'Isolated completion concurrency regression',date:state.serverDate};
 let approved=await api('sy_leader','/shipyard/records','POST',{...form,requestKey:crypto.randomUUID()});
 approved=await api('sy_manager','/shipyard/records/'+approved.id+'/review','POST',{version:approved.version,accept:true});
 let returned;
 if(withReturned){
  returned=await api('sy_leader','/shipyard/records','POST',{...form,requestKey:crypto.randomUUID()});
  returned=await api('sy_manager','/shipyard/records/'+returned.id+'/review','POST',{version:returned.version,accept:false,opinion:'Prepare resubmission fixture'});
 }
 return {area,form,returned};
}
function cleanup(){
 assert.equal(sql('SELECT DATABASE();'),database);
 for(const suffix of fixtures){
  assert.match(suffix,/^[0-9a-f]{16}$/);
  const areaIds=sql(`SELECT id FROM sy_area WHERE name='Completion race ${suffix}';`).split('\n').filter(Boolean);
  for(const areaId of areaIds){
   assert.match(areaId,/^\d+$/);
   const records=`SELECT id FROM sy_record WHERE area_id=${areaId}`;
   sql(`START TRANSACTION;
    DELETE FROM sy_notice_read WHERE audit_id IN (SELECT id FROM sy_audit WHERE record_id IN (${records}));
    DELETE FROM sy_wechat_delivery WHERE audit_id IN (SELECT id FROM sy_audit WHERE record_id IN (${records}));
    DELETE FROM sy_audit WHERE record_id IN (${records});
    DELETE FROM sy_record_file WHERE record_id IN (${records});
    DELETE FROM sy_work_line WHERE record_id IN (${records});
    DELETE FROM sy_record WHERE area_id=${areaId};
    DELETE FROM sy_area WHERE id=${areaId}; COMMIT;`);
  }
  sql(`DELETE FROM sy_worker WHERE code='CR${suffix}';`);
 }
}
const out=new URL('../.local/completion-concurrency-result.json',import.meta.url);
let failure,cleaned=false;
try{
 assert.equal(sql('SELECT DATABASE();'),database);
 for(const user of ['admin','sy_leader','sy_manager'])await login(user);
 for(const action of ['completion-request','complete']){
  const f=await fixture(crypto.randomBytes(8).toString('hex'));
  const release=await holdArea(f.area.id);let released=false,completion,retry;
  try{
   completion=response(action==='complete'?'sy_manager':'sy_leader','/shipyard/areas/'+f.area.id+'/'+action,'POST',{});
   await waitFor(()=>areaWaiters(f.area.id)>=1,action+' waiting for area');
   retry=response('sy_leader','/shipyard/records/'+f.returned.id,'PUT',{...f.form,version:f.returned.version,requestKey:crypto.randomUUID()});
   await waitFor(()=>areaWaiters(f.area.id)>=2,'resubmission waiting for area');
   await release();released=true;
   const [a,b]=await Promise.all([completion,retry]);
   results.push({action,completionCode:a.code,resubmissionCode:b.code});
   assert.equal(a.code,400,'Completion must reject an area containing a returned record, without a deadlock');
   assert.equal(b.code,200,'Concurrent returned-record resubmission must succeed');
   const state=await api('sy_leader','/shipyard/bootstrap'),area=state.areas.find(a=>a.id===f.area.id);
   assert.equal(area.status,'进行中');assert.equal(area.completionRequestedAt,null);
  }finally{if(!released)await release();await Promise.allSettled([completion,retry].filter(Boolean));}
 }
 // A request waiting for an area must see a new work record committed ahead of it.
 const f=await fixture(crypto.randomBytes(8).toString('hex'),false);
 const release=await holdArea(f.area.id);let released=false,submission,completion;
 try{
  submission=response('sy_leader','/shipyard/records','POST',{...f.form,requestKey:crypto.randomUUID()});
  await waitFor(()=>areaWaiters(f.area.id)>=1,'new work waiting for area');
  completion=response('sy_leader','/shipyard/areas/'+f.area.id+'/completion-request','POST',{});
  await waitFor(()=>areaWaiters(f.area.id)>=2,'completion waiting after new work');
  await release();released=true;
  const [a,b]=await Promise.all([submission,completion]);
  results.push({action:'new-work-commits-before-completion',submissionCode:a.code,completionCode:b.code});
  assert.equal(a.code,200);assert.equal(b.code,400,'Completion must see the newly committed pending record');
  const area=(await api('sy_leader','/shipyard/bootstrap')).areas.find(a=>a.id===f.area.id);
  assert.equal(area.completionRequestedAt,null);
 }finally{if(!released)await release();await Promise.allSettled([submission,completion].filter(Boolean));}
}catch(error){failure=error;}
finally{
 try{cleanup();cleaned=true;}catch(error){failure=failure||error;}
 fs.writeFileSync(out,JSON.stringify({completed:!failure,date:new Date().toISOString(),database,results,cleaned,error:failure?.message},null,2));
}
if(failure)throw failure;
console.log('PASS completion concurrency: '+results.length+' deterministic races; fixtures cleaned');
