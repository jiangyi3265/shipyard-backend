import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { execFileSync, spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const local=path.join(root,'.local'); fs.mkdirSync(local,{recursive:true});
const test=process.argv.includes('--test');
const database=test?'shipyard33_test':'shipyard33';
if(process.env.SHIPYARD_DB_PASSWORD===undefined) throw new Error('Set SHIPYARD_DB_PASSWORD explicitly; local configuration files are never parsed for credentials.');
const password=process.env.SHIPYARD_DB_PASSWORD;
const user=process.env.SHIPYARD_DB_USER || 'root';
export function sql(query,db=database) {
 return execFileSync('mysql',['--host=127.0.0.1',`--user=${user}`,'--default-character-set=utf8mb4','--batch','--skip-column-names',...(db?[db]:[])],{input:query,encoding:'utf8',env:{...process.env,MYSQL_PWD:password},windowsHide:true});
}
const credentialsPath=path.join(local,`${database}-credentials.json`);
if(!fs.existsSync(credentialsPath)) fs.writeFileSync(credentialsPath,JSON.stringify({password:process.env.SHIPYARD_INITIAL_PASSWORD||`Sy!${crypto.randomBytes(16).toString('base64url')}`,secret:process.env.SHIPYARD_TOKEN_SECRET||crypto.randomBytes(48).toString('hex'),accounts:['admin','sy_leader','sy_manager','sy_owner','sy_accountant','sy_safety']},null,2),{mode:0o600});
const credentials=JSON.parse(fs.readFileSync(credentialsPath));
const command=process.argv[2];
if(command==='init') {
 sql(`CREATE DATABASE IF NOT EXISTS ${database} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;`,null);
 const exists=Number(sql(`SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${database}' AND table_name='sys_user';`));
 if(!exists) sql(fs.readFileSync(path.join(root,'sql/ry_20250522.sql'),'utf8'));
 sql(fs.readFileSync(path.join(root,'sql/shipyard.sql'),'utf8'));
 console.log(`Database ready: ${database}; local credentials: ${credentialsPath}`);
} else if(command==='start') {
 const redisPort=6383;
 try { execFileSync('redis-cli',['-p',String(redisPort),'PING'],{stdio:'pipe',windowsHide:true}); }
 catch {
  const redisDir=path.join(local,'redis');fs.mkdirSync(redisDir,{recursive:true});
  const conf=path.join(local,'redis.conf');fs.writeFileSync(conf,`bind 127.0.0.1\nport ${redisPort}\ndir .\nappendonly yes\n`);
  const out=fs.openSync(path.join(local,'redis.log'),'a');
  const child=spawn('redis-server',[conf],{cwd:redisDir,detached:true,windowsHide:true,stdio:['ignore',out,out]});child.unref();
 }
 const port=Number(process.env.SHIPYARD_PORT || (test?8081:8083));
 if(!Number.isInteger(port)||port<1024||port>65535) throw new Error('SHIPYARD_PORT must be between 1024 and 65535');
 try { await fetch(`http://127.0.0.1:${port}/captchaImage`); throw new Error(`Port ${port} already has a server; stop that server before starting another.`); }
 catch(e) { if(e.message.includes('already has')) throw e; }
 const out=fs.openSync(path.join(local,`${database}.log`),'a');
 const child=spawn('java',['-Duser.timezone=Asia/Shanghai','-jar',path.join(root,'ruoyi-admin/target/ruoyi-admin.jar'),'--spring.profiles.active=druid,shipyard'],{cwd:root,detached:true,windowsHide:true,stdio:['ignore',out,out],env:{...process.env,SHIPYARD_DB:database,SHIPYARD_DB_USER:user,SHIPYARD_DB_PASSWORD:password,SHIPYARD_INITIAL_PASSWORD:credentials.password,SHIPYARD_TOKEN_SECRET:credentials.secret,SHIPYARD_BOOTSTRAP:'true',SHIPYARD_PORT:String(port),SHIPYARD_REDIS_DB:test?'1':'0'}});
 child.unref();fs.writeFileSync(path.join(local,`${database}.pid`),String(child.pid));
 console.log(`Starting backend on ${port}; log .local/${database}.log`);
} else if(command==='query') {
 console.log(sql(fs.readFileSync(process.argv[3],'utf8')));
} else if(command!=='library') console.log('Usage: node scripts/local.mjs init|start [--test]');
