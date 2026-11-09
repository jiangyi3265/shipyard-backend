// Destructive test-account changes are restricted to the dedicated loopback audit API.
// No production credentials, account IDs or URLs are read by this test.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import crypto from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {credentials} from './test-env.mjs';
const base='http://127.0.0.1:8081',out=new URL('../.local/button-audit/',import.meta.url);
const phase=process.env.SHIPYARD_AUDIT_PHASE||'after';assert.ok(['before','after'].includes(phase));
const accounts=[],testRoles=[],passed=[],failed=[],run=Date.now().toString().slice(-10);let admin,manager,leader;
async function call(token,url,method='GET',body){
 const r=await fetch(base+url,{method,headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},body:body===undefined?undefined:JSON.stringify(body)});
 return r.json();
}
async function ok(token,url,method='GET',body){const r=await call(token,url,method,body);assert.equal(r.code,200,url+': '+r.msg);return r.data??r;}
async function loginResponse(username,password=credentials.password){
 const c=await ok(null,'/captchaImage');const code=JSON.parse(execFileSync('redis-cli',['-h','127.0.0.1','-p','6383','-n','1','--raw','GET','captcha_codes:'+c.uuid],{encoding:'utf8',windowsHide:true}));
 return call(null,'/login','POST',{username,password,uuid:c.uuid,code:String(code)});
}
async function login(username,password=credentials.password){const r=await loginResponse(username,password);assert.equal(r.code,200,r.msg);return r.token;}
async function check(name,fn){try{await fn();passed.push(name);console.log('PASS '+name);}catch(e){failed.push({name,message:String(e.message).split(credentials.password).join('[redacted]')});console.log('FAIL '+name);}}
try{
 admin=await login('admin');manager=await login('sy_manager');leader=await login('sy_leader');
 const catalog=await ok(admin,'/shipyard/accounts'),role=catalog.roles.find(r=>r.key==='sy_leader'),managerRole=catalog.roles.find(r=>r.key==='sy_manager');
 const state=await ok(leader,'/shipyard/bootstrap'),project=state.projects[0];assert.ok(role&&managerRole&&project);
 async function account(label,roleIds=[role.id]){
  const username='a'+run+'_'+label.slice(0,3),password=crypto.randomBytes(16).toString('hex');
  await ok(admin,'/shipyard/accounts','PUT',{username,name:'隔离鉴权'+label,password,status:'0',roleIds,projectIds:[project.id]});
  const user=(await ok(admin,'/shipyard/accounts')).users.find(u=>u.username===username);assert.ok(user);accounts.push(user.id);
  return {...user,password,tokens:[await login(username,password),await login(username,password)]};
 }
 for(const action of ['status','roles','edit','delete'])await check('系统用户 '+action+' 成功后该账号两个旧会话均401，其他账号仍可访问',async()=>{
  const user=await account(action);for(const token of user.tokens)await ok(token,'/getInfo');
  if(action==='status')await ok(admin,'/system/user/changeStatus','PUT',{userId:user.id,status:'1'});
  if(action==='roles')await ok(admin,'/system/user/authRole?userId='+user.id+'&roleIds='+managerRole.id,'PUT');
  if(action==='edit')await ok(admin,'/system/user','PUT',{userId:user.id,userName:user.username,nickName:'隔离修改后的名称',deptId:103,status:'0',roleIds:[role.id],postIds:[]});
  if(action==='delete')await ok(admin,'/system/user/'+user.id,'DELETE');
  for(const token of user.tokens){assert.equal((await call(token,'/getInfo')).code,401);assert.equal((await call(token,'/shipyard/bootstrap')).code,401);}
  await ok(admin,'/getInfo');await ok(leader,'/shipyard/bootstrap');
  if(action==='status'||action==='delete'){
   const rejected=await loginResponse(user.username,user.password);assert.notEqual(rejected.code,200);assert.doesNotMatch(rejected.msg,/验证码/,'Disabled/deleted login must fail for identity, not a missing challenge');
  }
 });
 await check('系统修改因账号重名失败时，不撤销目标已登录会话',async()=>{
  const user=await account('failed');const r=await call(admin,'/system/user','PUT',{userId:user.id,userName:'admin',nickName:'不能保存',deptId:103,status:'0',roleIds:[role.id],postIds:[]});assert.notEqual(r.code,200);for(const token of user.tokens)await ok(token,'/getInfo');
 });
 await check('本人修改密码保留当前设备、撤销其他设备，旧密码失效且新密码可登录',async()=>{
  const user=await account('pwd'),newPassword=crypto.randomBytes(16).toString('hex');
  await ok(user.tokens[0],'/system/user/profile/updatePwd','PUT',{oldPassword:user.password,newPassword});
  await ok(user.tokens[0],'/getInfo');assert.equal((await call(user.tokens[1],'/getInfo')).code,401);
  const rejected=await loginResponse(user.username,user.password);assert.notEqual(rejected.code,200);assert.doesNotMatch(rejected.msg,/验证码/);
  await ok(await login(user.username,newPassword),'/getInfo');
 });
 for(const [index,action] of ['edit','status','scope','cancel','cancelAll','selectAll'].entries())await check('系统角色 '+action+' 成功后目标旧会话均401，未受影响账号保持登录',async()=>{
  const key='sy_audit_'+run+'_'+index,body={roleName:'隔离角色'+run+index,roleKey:key,roleSort:90,status:'0',dataScope:'1',menuCheckStrictly:true,deptCheckStrictly:true,menuIds:[],deptIds:[]};
  await ok(admin,'/system/role','POST',body);const custom=(await ok(admin,'/system/role/optionselect')).find(r=>r.roleKey===key);assert.ok(custom);testRoles.push(custom.roleId);
  const user=await account('r'+index,action==='selectAll'?[role.id]:[custom.roleId]);for(const token of user.tokens)await ok(token,'/getInfo');
  if(action==='edit')await ok(admin,'/system/role','PUT',{...body,roleId:custom.roleId,roleName:body.roleName+'改'});
  if(action==='status')await ok(admin,'/system/role/changeStatus','PUT',{roleId:custom.roleId,status:'1'});
  if(action==='scope')await ok(admin,'/system/role/dataScope','PUT',{roleId:custom.roleId,dataScope:'5',deptIds:[]});
  if(action==='cancel')await ok(admin,'/system/role/authUser/cancel','PUT',{roleId:custom.roleId,userId:user.id});
  if(action==='cancelAll'||action==='selectAll')await ok(admin,'/system/role/authUser/'+action+'?roleId='+custom.roleId+'&userIds='+user.id,'PUT');
  for(const token of user.tokens){assert.equal((await call(token,'/getInfo')).code,401);assert.equal((await call(token,'/shipyard/bootstrap')).code,401);}
  await ok(admin,'/getInfo');await ok(leader,'/shipyard/bootstrap');
 });
 const areaName='审核类型校验'+run;await ok(admin,'/shipyard/master/area','PUT',{projectId:project.id,name:areaName,area:10,rate:10});
 const area=(await ok(admin,'/shipyard/bootstrap')).areas.find(a=>a.name===areaName),worker=state.people[0];assert.ok(area&&worker);
 async function work(){return ok(leader,'/shipyard/records','POST',{requestKey:crypto.randomUUID(),kind:'用工',type:'包工',projectId:project.id,areaId:area.id,people:[worker.id],start:state.serverDate,end:state.serverDate,amount:10,allocations:{[worker.id]:10},note:'审批参数类型隔离回归'});}
 const invalid=[['缺失',undefined],['null',null],['字符串','false'],['数字',0],['数组',[]],['对象',{}]];
 for(const [label,value]of invalid)await check('审批 accept='+label+' 返回400且版本/状态/审计不变',async()=>{
  const record=await work(),before=await ok(manager,'/shipyard/records/'+record.id),payload={version:record.version,opinion:'即使意见存在也不应当作退回',...(value===undefined?{}:{accept:value})};
  const result=await call(manager,'/shipyard/records/'+record.id+'/review','POST',payload),after=await ok(manager,'/shipyard/records/'+record.id);
  assert.equal(result.code,400);assert.equal(after.version,before.version);assert.equal(after.status,before.status);assert.deepEqual(after.audit,before.audit);
 });
 await check('Boolean false/true 保持合法退回和通过流程',async()=>{
  for(const accept of [false,true]){const record=await work(),result=await ok(manager,'/shipyard/records/'+record.id+'/review','POST',{version:record.version,accept,opinion:'合法布尔值'});assert.equal(result.status,accept?'待结算':'已退回');assert.equal(result.version,record.version+1);}
 });
}catch(e){failed.push({name:'setup',message:String(e.message).split(credentials.password).join('[redacted]')});}
finally{
 for(const id of accounts)try{const r=await call(admin,'/system/user/'+id,'DELETE');if(![200,500].includes(r.code))throw Error('cleanup response '+r.code);}catch(e){failed.push({name:'cleanup-test-account',message:e.message});}
 for(const id of testRoles)try{await ok(admin,'/system/role/'+id,'DELETE');}catch(e){failed.push({name:'cleanup-test-role',message:e.message});}
 const result={at:new Date().toISOString(),phase,isolatedApi:base,completed:failed.length===0,passed,failed,testAccounts:accounts.length};
 fs.mkdirSync(out,{recursive:true});fs.writeFileSync(new URL('auth-revocation-'+phase+'.json',out),JSON.stringify(result,null,2));console.log(JSON.stringify({completed:result.completed,passed:passed.length,failed:failed.length}));if(failed.length)process.exitCode=1;
}
