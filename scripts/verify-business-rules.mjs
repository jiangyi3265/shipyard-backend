// Q01–Q24 business rules against the isolated loopback API only. No production URL or credential is used.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import crypto from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {credentials} from './test-env.mjs';
const base='http://127.0.0.1:8081',tokens={},passed=[],failed=[],key=()=>crypto.randomUUID(),run=Date.now().toString().slice(-8);
async function call(user,path,method='GET',body){
 const r=await fetch(base+path,{method,headers:{'Content-Type':'application/json',...(tokens[user]?{Authorization:'Bearer '+tokens[user]}:{})},body:body===undefined?undefined:JSON.stringify(body)});
 return r.json();
}
async function api(user,path,method='GET',body,code=200){const r=await call(user,path,method,body);assert.equal(r.code,code,method+' '+path+': '+r.msg);return r.data??r;}
async function login(user,password=credentials.password){
 const c=await api(null,'/captchaImage');const code=JSON.parse(execFileSync('redis-cli',['-p','6383','-n','1','--raw','GET','captcha_codes:'+c.uuid],{encoding:'utf8',windowsHide:true}));
 const r=await api(null,'/login','POST',{username:user,password,uuid:c.uuid,code:String(code)});tokens[user]=r.token;
}
async function upload(user){
 const form=new FormData();form.append('file',new Blob([fs.readFileSync(new URL('../../船厂/static/test/receipt-placeholder.png',import.meta.url))],{type:'image/png'}),'receipt.png');
 const r=await(await fetch(base+'/shipyard/files',{method:'POST',headers:{Authorization:'Bearer '+tokens[user]},body:form})).json();assert.equal(r.code,200,r.msg);return r.data.id;
}
async function check(name,fn){try{await fn();passed.push(name);console.log('PASS '+name);}catch(e){failed.push({name,message:String(e.message).split(credentials.password).join('[redacted]')});console.log('FAIL '+name+' — '+e.message);}}
const shift=(day,n)=>{const d=new Date(day+'T12:00:00Z');d.setUTCDate(d.getUTCDate()+n);return d.toISOString().slice(0,10);};
const nextMonth=month=>{const [y,m]=month.split('-').map(Number);return m===12?(y+1)+'-01':y+'-'+String(m+1).padStart(2,'0');};
const boot=user=>api(user,'/shipyard/bootstrap');
try{
 await api(null,'/shipyard/bootstrap','GET',undefined,401);
 for(const name of credentials.accounts)await login(name);
 let state=await boot('admin');const p=state.projects[0],today=state.serverDate,month=today.slice(0,7);
 for(let i=0;i<3;i++)await api('admin','/shipyard/master/person','PUT',{name:'规则工人'+run+i,code:'R'+run+i,team:'规则验证',rate:30});
 state=await boot('admin');const workers=state.people.filter(w=>w.code.startsWith('R'+run)).map(w=>w.id);assert.equal(workers.length,3);
 const leaderId=(await boot('sy_leader')).user.id;
 async function newArea(label,leader){
  await api('admin','/shipyard/master/area','PUT',{projectId:p.id,name:'规则'+run+label,area:10,rate:60,...(leader===undefined?{}:{leaderId:leader})});
  return (await boot('admin')).areas.find(a=>a.name==='规则'+run+label);
 }
 const hourly=(area,extra={})=>({requestKey:key(),projectId:p.id,areaId:area.id,kind:'用工',type:'点工',people:[workers[0]],hours:{[workers[0]]:1},note:'规则验证点工',date:today,...extra});
 const approve=(user,r)=>api(user,'/shipyard/records/'+r.id+'/review','POST',{version:r.version,accept:true});
 const areaA=await newArea('A',leaderId),areaB=await newArea('B');

 await check('Q09 点工/加班禁止未来日期，组长只能补报近7天，管理员可登记更早记录，报销罚款不能填未来日期',async()=>{
  await api('sy_leader','/shipyard/records','POST',hourly(areaB,{date:shift(today,1)}),400);
  await api('sy_leader','/shipyard/records','POST',hourly(areaB,{date:shift(today,-8)}),400);
  const seven=await api('sy_leader','/shipyard/records','POST',hourly(areaB,{date:shift(today,-7)}));assert.equal(seven.date,shift(today,-7));
  const old=await api('admin','/shipyard/records','POST',hourly(areaB,{date:shift(today,-20)}));assert.equal(old.date,shift(today,-20));
  const file=await upload('sy_leader');
  await api('sy_leader','/shipyard/records','POST',{requestKey:key(),projectId:p.id,kind:'报销',category:'劳保用品',amount:12,note:'未来报销',files:[file],date:shift(today,1)},400);
  // A returned record keeps its original date when resubmitted without a new one, even when it is now older than 7 days.
  let r=await api('admin','/shipyard/records','POST',hourly(areaB,{date:shift(today,-10),people:[workers[1]],hours:{[workers[1]]:1}}));
  r=await api('sy_manager','/shipyard/records/'+r.id+'/review','POST',{version:r.version,accept:false,opinion:'核对'});
  const {date:_drop,...noDate}=hourly(areaB,{people:[workers[1]],hours:{[workers[1]]:1}});
  r=await api('admin','/shipyard/records/'+r.id,'PUT',{...noDate,version:r.version});assert.equal(r.date,shift(today,-10));
 });
 await check('Q06 申请人作废已退回单据，审批人确认或驳回，作废单不再计入工时',async()=>{
  let r=await api('sy_leader','/shipyard/records','POST',hourly(areaB,{people:[workers[2]],hours:{[workers[2]]:20}}));
  r=await api('sy_manager','/shipyard/records/'+r.id+'/review','POST',{version:r.version,accept:false,opinion:'人数不对'});
  await api('sy_leader','/shipyard/records/'+r.id+'/void-request','POST',{version:r.version,reason:''},400);
  await api('sy_manager','/shipyard/records/'+r.id+'/void-request','POST',{version:r.version,reason:'不是本人'},400);
  r=await api('sy_leader','/shipyard/records/'+r.id+'/void-request','POST',{version:r.version,reason:'重复登记，不再继续'});assert.equal(r.status,'作废待确认');
  await api('sy_leader','/shipyard/records/'+r.id+'/void-review','POST',{version:r.version,accept:true},403);
  await api('sy_manager','/shipyard/records/'+r.id+'/void-review','POST',{version:r.version,accept:false,opinion:''},400);
  r=await api('sy_manager','/shipyard/records/'+r.id+'/void-review','POST',{version:r.version,accept:false,opinion:'请先核对再决定'});assert.equal(r.status,'已退回');
  r=await api('sy_leader','/shipyard/records/'+r.id+'/void-request','POST',{version:r.version,reason:'确认不再继续'});
  r=await api('sy_manager','/shipyard/records/'+r.id+'/void-review','POST',{version:r.version,accept:true,opinion:'同意作废'});assert.equal(r.status,'已作废');
  assert.ok(r.audit.some(a=>a.action==='确认作废'));
  // 20 voided hours no longer block 20 new hours for the same worker and day.
  await api('sy_leader','/shipyard/records','POST',hourly(areaB,{people:[workers[2]],hours:{[workers[2]]:20}}));
  await api('sy_leader','/shipyard/records/'+r.id+'/void-request','POST',{version:r.version,reason:'再次'},400);
 });
 await check('Q04/Q05/Q07 经理不能直接完工；指定负责组长才可提交；未处理罚款不阻挡完工',async()=>{
  const created=await api('sy_leader','/shipyard/records','POST',hourly(areaA));await approve('sy_manager',created);
  await api('sy_safety','/shipyard/records','POST',{requestKey:key(),projectId:p.id,areaId:areaA.id,kind:'罚款',person:workers[0],amount:50,note:'未戴安全帽'});
  await api('sy_manager','/shipyard/areas/'+areaA.id+'/complete','POST',{},400);
  const accounts=await api('admin','/shipyard/accounts'),leaderRole=accounts.roles.find(r=>r.key==='sy_leader');
  const username='rl'+run;await api('admin','/shipyard/accounts','PUT',{username,name:'规则负责组长'+run,password:crypto.randomBytes(12).toString('hex'),status:'0',roleIds:[leaderRole.id],projectIds:[p.id]});
  const other=(await api('admin','/shipyard/accounts')).users.find(u=>u.username===username);
  const areaC=await newArea('C',other.id);assert.equal(areaC.leader,'规则负责组长'+run);
  const mine=await api('sy_leader','/shipyard/records','POST',hourly(areaC));await approve('sy_manager',mine);
  await api('sy_leader','/shipyard/areas/'+areaC.id+'/completion-request','POST',{},400);
  await api('admin','/shipyard/master/area','PUT',{projectId:p.id,name:'规则'+run+'X',area:10,rate:60,leaderId:41004},400);
  await api('sy_leader','/shipyard/areas/'+areaA.id+'/completion-request','POST',{});
  await api('sy_manager','/shipyard/areas/'+areaA.id+'/complete','POST',{});
  assert.equal((await boot('sy_manager')).areas.find(a=>a.id===areaA.id).status,'已完工');
 });
 await check('Q10 已完工区域填写原因后重新开工，可继续登记',async()=>{
  await api('sy_leader','/shipyard/areas/'+areaA.id+'/reopen','POST',{reason:'补做'},403);
  await api('sy_manager','/shipyard/areas/'+areaA.id+'/reopen','POST',{reason:''},400);
  await api('sy_manager','/shipyard/areas/'+areaA.id+'/reopen','POST',{reason:'甲方要求返工'});
  const area=(await boot('sy_manager')).areas.find(a=>a.id===areaA.id);assert.equal(area.status,'进行中');assert.equal(area.completedAt,null);
  await api('sy_leader','/shipyard/records','POST',hourly(areaA,{people:[workers[1]],hours:{[workers[1]]:1}}));
 });
 let contract;
 await check('Q08/Q20 包工工资归属月在工期内可选，混合施工分别显示金额',async()=>{
  const areaD=await newArea('D');const end=nextMonth(month)+'-05';
  const body={requestKey:key(),projectId:p.id,areaId:areaD.id,kind:'用工',type:'包工',people:[workers[0]],allocations:{[workers[0]]:300},amount:300,start:today,end,note:'跨月包工'};
  await api('sy_leader','/shipyard/records','POST',{...body,payrollMonth:nextMonth(nextMonth(month))},400);
  contract=await api('sy_leader','/shipyard/records','POST',{...body,payrollMonth:nextMonth(month)});assert.equal(contract.payrollMonth,nextMonth(month));
  contract=await approve('sy_manager',contract);
  const h=await api('sy_leader','/shipyard/records','POST',hourly(areaD));await approve('sy_manager',h);
  const s=await boot('sy_accountant');const line=s.payroll.find(l=>l.recordId===contract.id);assert.equal(line.month,nextMonth(month));
  const area=s.areas.find(a=>a.id===areaD.id);assert.equal(area.type,'混合施工');assert.equal(area.contract,300);assert.equal(area.hourly,30);assert.equal(area.total,330);
  await api('sy_accountant','/shipyard/payroll/settle','POST',{projectId:p.id,month,lineIds:[line.id],requestKey:key(),date:today,method:'现金',proof:await upload('sy_accountant')},400);
 });
 let batchLedger;
 await check('Q12 工资结算必须填写实际支付日期、方式和凭证，批次可查',async()=>{
  const s=await boot('sy_accountant');const line=s.payroll.find(l=>l.recordId===contract.id);const proof=await upload('sy_accountant');
  const base={projectId:p.id,month:nextMonth(month),lineIds:[line.id]};
  await api('sy_accountant','/shipyard/payroll/settle','POST',{...base,requestKey:key()},400);
  await api('sy_accountant','/shipyard/payroll/settle','POST',{...base,requestKey:key(),date:shift(today,1),method:'现金',proof},400);
  await api('sy_accountant','/shipyard/payroll/settle','POST',{...base,requestKey:key(),date:today,method:'支付宝',proof},400);
  await api('sy_accountant','/shipyard/payroll/settle','POST',{...base,requestKey:key(),date:shift(today,-1),method:'银行转账',proof});
  const after=await boot('sy_accountant');const paid=after.payroll.find(l=>l.id===line.id);assert.ok(paid.paid);
  const batch=after.payrollBatches.find(b=>b.id===paid.paymentId);assert.equal(batch.method,'银行转账');assert.equal(batch.paidDate,shift(today,-1));assert.equal(batch.proof,proof);
  batchLedger=after.transactions.find(t=>t.source==='payroll'&&t.sourceId===batch.id);assert.equal(batchLedger.date,shift(today,-1));
  await api('sy_owner','/shipyard/files/'+proof.slice(5));
 });
 await check('Q15 冲正需另一名管理员或负责人批准，保留原流水并新增对冲流水，工资回到待结算',async()=>{
  await api('sy_safety','/shipyard/ledger/'+batchLedger.id+'/reversal','POST',{reason:'金额错误'},403);
  await api('sy_accountant','/shipyard/ledger/'+batchLedger.id+'/reversal','POST',{reason:''},400);
  await api('sy_accountant','/shipyard/ledger/'+batchLedger.id+'/reversal','POST',{reason:'实际未付款，误登记'});
  await api('sy_accountant','/shipyard/ledger/'+batchLedger.id+'/reversal','POST',{reason:'重复'},400);
  let s=await boot('sy_owner');let entry=s.transactions.find(t=>t.id===batchLedger.id);assert.equal(entry.reversal.status,'pending');
  await api('sy_accountant','/shipyard/ledger/reversals/'+entry.reversal.id+'/review','POST',{version:entry.reversal.version,accept:true},400);
  await api('sy_owner','/shipyard/ledger/reversals/'+entry.reversal.id+'/review','POST',{version:entry.reversal.version,accept:false,opinion:''},400);
  await api('sy_owner','/shipyard/ledger/reversals/'+entry.reversal.id+'/review','POST',{version:entry.reversal.version,accept:true,opinion:'同意冲正'});
  s=await boot('sy_accountant');entry=s.transactions.find(t=>t.id===batchLedger.id);assert.equal(entry.amount,batchLedger.amount);assert.equal(entry.reversal.status,'approved');
  const offset=s.transactions.find(t=>t.source==='reversal'&&t.sourceId===batchLedger.id);assert.equal(offset.amount,-batchLedger.amount);
  const line=s.payroll.find(l=>l.recordId===contract.id);assert.equal(line.paid,false);assert.ok(s.payrollBatches.find(b=>b.id===batchLedger.sourceId).reversedAt);
  assert.equal((await api('sy_accountant','/shipyard/records/'+contract.id)).status,'待结算');
  await api('sy_accountant','/shipyard/ledger/'+offset.id+'/reversal','POST',{reason:'再冲'},400);
  await api('sy_accountant','/shipyard/payroll/settle','POST',{projectId:p.id,month:nextMonth(month),lineIds:[line.id],requestKey:key(),date:today,method:'现金',proof:await upload('sy_accountant')});
 });
 await check('Q17/Q15 报销申请人不能登记自己报销的付款；付款冲正后可重新付款',async()=>{
  let r=await api('admin','/shipyard/records','POST',{requestKey:key(),projectId:p.id,kind:'报销',category:'材料采购',amount:66,note:'规则报销',files:[await upload('admin')],date:today});
  r=await api('sy_manager','/shipyard/records/'+r.id+'/review','POST',{version:r.version,accept:true,signature:await upload('sy_manager')});
  r=await api('sy_owner','/shipyard/records/'+r.id+'/review','POST',{version:r.version,accept:true,signature:await upload('sy_owner')});assert.equal(r.status,'待付款');
  await api('admin','/shipyard/records/'+r.id+'/payment','POST',{version:r.version,date:today,method:'现金',proof:await upload('admin')},400);
  r=await api('sy_accountant','/shipyard/records/'+r.id+'/payment','POST',{version:r.version,date:today,method:'现金',proof:await upload('sy_accountant')});assert.equal(r.status,'已付款');
  let entry=(await boot('sy_accountant')).transactions.find(t=>t.source==='expense'&&t.sourceId===r.id);
  await api('sy_accountant','/shipyard/ledger/'+entry.id+'/reversal','POST',{reason:'付错金额'});
  entry=(await boot('admin')).transactions.find(t=>t.id===entry.id);
  await api('admin','/shipyard/ledger/reversals/'+entry.reversal.id+'/review','POST',{version:entry.reversal.version,accept:true});
  r=await api('sy_accountant','/shipyard/records/'+r.id);assert.equal(r.status,'待付款');assert.equal(r.proof,undefined);
  r=await api('sy_accountant','/shipyard/records/'+r.id+'/payment','POST',{version:r.version,date:today,method:'银行转账',proof:await upload('sy_accountant')});assert.equal(r.status,'已付款');
  assert.equal((await boot('sy_accountant')).transactions.filter(t=>t.source==='expense'&&t.sourceId===r.id).length,2);
 });
 await check('Q13 罚款单独收款，收款日期不能晚于今天',async()=>{
  let r=await api('sy_safety','/shipyard/records','POST',{requestKey:key(),projectId:p.id,areaId:areaB.id,kind:'罚款',person:workers[1],amount:20,note:'规则罚款',date:today});
  r=await approve('sy_manager',r);assert.equal(r.status,'会计待处理');
  await api('sy_accountant','/shipyard/records/'+r.id+'/fine-receipt','POST',{version:r.version,note:'现金收取',date:shift(today,1)},400);
  r=await api('sy_accountant','/shipyard/records/'+r.id+'/fine-receipt','POST',{version:r.version,note:'现金收取',date:shift(today,-2)});assert.equal(r.paidDate,shift(today,-2));
 });
 await check('Q11/Q22 期初余额每工程一次、更正走冲正；手工收支必须上传凭证并显示登记人',async()=>{
  let s=await boot('sy_accountant');
  for(const old of s.transactions.filter(t=>t.projectId===p.id&&t.source==='opening'&&t.reversal?.status!=='approved')){
   if(!old.reversal)await api('sy_accountant','/shipyard/ledger/'+old.id+'/reversal','POST',{reason:'测试前重置期初'});
   const pending=(await boot('sy_owner')).transactions.find(t=>t.id===old.id).reversal;
   await api('sy_owner','/shipyard/ledger/reversals/'+pending.id+'/review','POST',{version:pending.version,accept:true});
  }
  await api('sy_accountant','/shipyard/ledger/opening','POST',{projectId:p.id,amount:0,date:today,requestKey:key()},400);
  await api('sy_accountant','/shipyard/ledger/opening','POST',{projectId:p.id,amount:1000,date:shift(today,1),requestKey:key()},400);
  await api('sy_leader','/shipyard/ledger/opening','POST',{projectId:p.id,amount:1000,date:today,requestKey:key()},403);
  await api('sy_accountant','/shipyard/ledger/opening','POST',{projectId:p.id,amount:1000,date:shift(today,-30),requestKey:key(),note:'启用系统前账户余额'});
  await api('sy_accountant','/shipyard/ledger/opening','POST',{projectId:p.id,amount:500,date:today,requestKey:key()},400);
  await api('sy_accountant','/shipyard/ledger','POST',{projectId:p.id,title:'废料变卖'+run,amount:88,date:today,requestKey:key()},400);
  await api('sy_accountant','/shipyard/ledger','POST',{projectId:p.id,title:'期初余额',amount:88,date:today,requestKey:key(),files:[await upload('sy_accountant')]},400);
  const voucher=await upload('sy_accountant');
  await api('sy_accountant','/shipyard/ledger','POST',{projectId:p.id,title:'废料变卖'+run,amount:88,date:today,requestKey:key(),files:[voucher]});
  s=await boot('sy_owner');const entry=s.transactions.find(t=>t.title==='废料变卖'+run);
  assert.deepEqual(entry.files,[voucher]);assert.ok(entry.creator);
  assert.equal(s.transactions.filter(t=>t.projectId===p.id&&t.source==='opening'&&t.reversal?.status!=='approved').length,1);
  await api('sy_owner','/shipyard/files/'+voucher.slice(5));await api('sy_safety','/shipyard/files/'+voucher.slice(5),'GET',undefined,403);
 });
 await check('Q01/Q23/Q21 组长与会计不能同账号；账号权限只授管理员；未绑定微信不能解绑',async()=>{
  const accounts=await api('admin','/shipyard/accounts'),role=k=>accounts.roles.find(r=>r.key===k).id;
  const account=(roles,suffix)=>({username:'rq'+run+suffix,name:'规则角色'+run+suffix,password:crypto.randomBytes(12).toString('hex'),status:'0',roleIds:roles,projectIds:[p.id]});
  await api('admin','/shipyard/accounts','PUT',account([role('sy_leader'),role('sy_accountant')],'a'),400);
  await api('admin','/shipyard/accounts','PUT',account([role('sy_leader'),role('sy_accountant'),role('sy_manager')],'b'));
  const manager=accounts.roles.find(r=>r.key==='sy_manager');
  await api('admin','/shipyard/roles/'+manager.id+'/permissions','PUT',{permissions:[...manager.permissions,'shipyard:account:edit']},400);
  await api('sy_manager','/shipyard/accounts','GET',undefined,403);
  const plain=(await api('admin','/shipyard/accounts')).users.find(u=>u.username==='rq'+run+'b');
  await api('admin','/shipyard/accounts/'+plain.id+'/wechat-unbind','POST',{},400);
  await api('sy_manager','/shipyard/accounts/'+plain.id+'/wechat-unbind','POST',{},403);
  await api('admin','/shipyard/accounts','PUT',{...account([role('sy_manager')],'b'),id:plain.id,status:'1',password:''});
 });
}catch(e){failed.push({name:'setup',message:String(e.message)});console.log('FAIL setup — '+e.message);}
const result={at:new Date().toISOString(),passed,failed};
fs.mkdirSync(new URL('../.local/button-audit/',import.meta.url),{recursive:true});
fs.writeFileSync(new URL('../.local/button-audit/business-rules-result.json',import.meta.url),JSON.stringify(result,null,2));
console.log(JSON.stringify({passed:passed.length,failed:failed.length}));
if(failed.length)process.exitCode=1;
