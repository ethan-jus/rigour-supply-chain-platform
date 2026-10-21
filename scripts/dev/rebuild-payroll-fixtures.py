#!/usr/bin/env python3
"""Run the explicit DEV payroll fixture maintenance tool with the active HR classpath.

Requires an existing HR JVM on the developer machine and compiled HR test helper classes.
No credentials are printed or committed. Inspect preview before explicit apply.
The Beijing fixture reset is DEV-only and deliberately does not create backups.
"""
import argparse
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('mode', choices=('preview', 'apply', 'generate'))
parser.add_argument('--tenant-code', required=True)
parser.add_argument('--month', required=True)
parser.add_argument('--hr-pid', required=True, type=int)
parser.add_argument('--supplement-commission', action='store_true', help='Only append missing Beijing test commission standards; never reset data')
args = parser.parse_args()
if args.supplement_commission and args.mode == 'generate':
    parser.error('Commission supplement supports preview/apply only')
root = Path(__file__).resolve().parents[2]
command = shlex.split(subprocess.check_output(['ps', '-p', str(args.hr_pid), '-o', 'command='], text=True))
if 'com.rigour.hr.HrApplication' not in command:
    raise SystemExit('The selected PID is not the HR application')
classpath = command[command.index('-classpath') + 1] if '-classpath' in command else command[command.index('-cp') + 1]
classpath += ':' + str(root / 'services/rg-scdp-hr/hr-server/target/test-classes')
env = os.environ.copy()
configuration = next(c for c in ET.parse(root / '.idea/workspace.xml').iter('configuration') if c.get('name') == 'HrApplication')
env.update({v.get('name'): v.get('value') for v in configuration.findall('./envs/env')})
with tempfile.TemporaryDirectory(prefix='hr-payroll-fixtures-') as classes:
    sources = [str(root / 'scripts/dev/RebuildPayrollFixtures.java')]
    if args.supplement_commission:
        sources.append(str(root / 'scripts/dev/SupplementPayrollCommissionFixtures.java'))
    main = 'SupplementPayrollCommissionFixtures' if args.supplement_commission else 'RebuildPayrollFixtures'
    compile_result = subprocess.run([str(Path(command[0]).with_name('javac')), '-cp', classpath, '-d', classes, *sources], cwd=root, env=env)
    if compile_result.returncode:
        raise SystemExit(compile_result.returncode)
    result = subprocess.run([command[0], '-cp', classes + ':' + classpath, 'com.rigour.hr.application.service.' + main, args.mode, args.tenant_code, args.month], cwd=root, env=env)
    raise SystemExit(result.returncode)
