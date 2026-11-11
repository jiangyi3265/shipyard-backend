"""Read-only package check for mobile actions introduced by the business-rule update.

Usage: python scripts/verify-mobile-backend-contract.py --backend-jar PATH
       --mobile-dir PATH --output PATH
This checks compiled controller route constants, not live API execution or DB schema.
"""
import argparse, json, sys, zipfile
from pathlib import Path

p=argparse.ArgumentParser()
p.add_argument('--backend-jar',required=True)
p.add_argument('--mobile-dir',required=True)
p.add_argument('--output',required=True)
args=p.parse_args()
mobile=Path(args.mobile_dir)
source=(mobile/'services/store.js').read_text(encoding='utf-8')
routes={
 '/records/{id}/void-request':'/void-request',
 '/records/{id}/void-review':'/void-review',
 '/areas/{id}/reopen':'/reopen',
 '/ledger/opening':'/ledger/opening',
 '/ledger/{id}/reversal':'/reversal',
 '/ledger/reversals/{id}/review':'/ledger/reversals/',
}
with zipfile.ZipFile(args.backend_jar) as z:
 controller=z.read('BOOT-INF/classes/com/ruoyi/shipyard/ShipyardController.class')
 checks=[{'route':route,'mobileUses':needle in source,'backendDefines':route.encode() in controller}
         for route,needle in routes.items()]
result={'completed':all(c['mobileUses'] and c['backendDefines'] for c in checks),
        'checks':checks,'readOnly':True,'databaseAccessed':False,
        'limitations':['Checks controller route constants only; real API and schema tests are still required.']}
out=Path(args.output);out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(result,indent=2),encoding='utf-8')
print(json.dumps(result))
sys.exit(0 if result['completed'] else 1)
