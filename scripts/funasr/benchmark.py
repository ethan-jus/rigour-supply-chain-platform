"""Send a local authorized sample to the private ASR endpoint; keep transcripts off logs."""
import argparse
import hashlib
import json
import os
import time
from pathlib import Path
import requests

parser=argparse.ArgumentParser()
parser.add_argument('audio',type=Path)
parser.add_argument('--url',default='http://127.0.0.1:18087')
parser.add_argument('--credential-file',type=Path,required=True)
parser.add_argument('--output',type=Path,required=True)
args=parser.parse_args()
token=args.credential_file.read_text().strip().split('=',1)[1]
started=time.perf_counter()
with args.audio.open('rb') as audio:
    response=requests.post(args.url+'/v1/transcriptions',headers={'Authorization':'Bearer '+token},
                           files={'file':('clip.audio',audio,'application/octet-stream')},data={'speaker_context':'[]'},timeout=450)
response.raise_for_status()
result=response.json()
result['roundTripMs']=round((time.perf_counter()-started)*1000)
result['audioSha256']=hashlib.sha256(args.audio.read_bytes()).hexdigest()
args.output.parent.mkdir(parents=True,exist_ok=True)
fd=os.open(args.output,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
with os.fdopen(fd,'w') as f:json.dump(result,f,ensure_ascii=False,indent=2)
print(json.dumps({k:result[k] for k in ['model','durationMs','processingMs','roundTripMs','peakRssMb']},ensure_ascii=False))
print('segments='+str(len(result['segments']))+' characters='+str(len(result['transcript'])))
