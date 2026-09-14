# 复制到 D:\RigourDev 后可右键使用 PowerShell 运行。
param([ValidateSet('deploy','build','status','logs','rollback')][string]$Action='deploy', [string]$Version='')
$ErrorActionPreference = 'Stop'
chcp.com 65001 | Out-Null
$commandArgs = @('-d','RigourDev','-u','root','--exec','bash','/mnt/d/RigourDev/scripts/app-entry.sh',$Action)
if ($Version) { $commandArgs += @('--version',$Version) }
& wsl.exe @commandArgs
if ($LASTEXITCODE -ne 0) { throw '执行未成功，请查看上方错误和 D:\RigourDev\logs。' }
