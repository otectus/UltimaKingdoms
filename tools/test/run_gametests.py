#!/usr/bin/env python3
"""Run Forge GameTests and reject exit-zero startup failures or incomplete suites."""
import pathlib,re,subprocess,sys
root=pathlib.Path(__file__).resolve().parents[2]
command=['/home/otectus/Projects/.mcmod-tools/gradlew-quiet.sh',str(root),'runGameTestServer',*sys.argv[1:]]
result=subprocess.run(command,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
print(result.stdout,end='')
match=re.search(r'^log: (.+)$',result.stdout,re.M)
if result.returncode or not match:raise SystemExit(result.returncode or 1)
log=pathlib.Path(match[1]).read_text(errors='replace')
completed=re.search(r'(\d+) GAME TESTS COMPLETE',log)
expected=sum(len(re.findall(r'@GameTest\(',p.read_text())) for p in (root/'src/gameTest/java').rglob('*.java'))
if not completed or int(completed[1])!=expected or not re.search(r'All \d+ required tests passed',log):
    print(f'FAIL runtime: expected {expected} completed tests and required-test pass marker; inspect {match[1]}')
    raise SystemExit(1)
print(f'PASS runtime: {expected} tests completed; all required tests passed')
