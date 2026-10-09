# 文档一致性校验（只读脚本，1.7.10 新增）
#
# 用途：README 与两份桌面台账里的"版本号 / 关键数字"必须与**代码与资源实测**一致；
# 本脚本只读、不改任何文件，发现不一致时以退出码 1 报出差异。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts/doc-consistency.ps1
#   powershell -ExecutionPolicy Bypass -File scripts/doc-consistency.ps1 -LedgerDir 'C:\Users\xxx\Desktop'

param(
    [string]$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path,
    # 台账默认在仓库内的 docs/（clone 下来也能跑）；想指向桌面台账用 -LedgerDir 覆盖
    [string]$LedgerDir = (Join-Path (Resolve-Path (Join-Path $PSScriptRoot '..')).Path 'docs')
)

$ErrorActionPreference = 'Stop'
$problems = New-Object System.Collections.ArrayList

function Add-Problem([string]$message) {
    [void]$problems.Add($message)
    Write-Host ("  ✗ " + $message) -ForegroundColor Red
}

function Check-Contains([string]$file, [string]$label, [string]$needle, [string]$expected) {
    if (-not (Test-Path -LiteralPath $file)) {
        Add-Problem ("找不到文件：" + $file)
        return
    }
    $text = [System.IO.File]::ReadAllText($file)
    if (-not $text.Contains($needle)) {
        Add-Problem ("$label 缺少「$needle」（应为 $expected；文件：$file）")
    }
}

# ==================== 实测真值 ====================
$mainJava = Join-Path $ProjectRoot 'src\main\java\com\summy\reliquary\SummyReliquary.java'
$configJava = Join-Path $ProjectRoot 'src\main\java\com\summy\reliquary\config\ReliquaryConfig.java'
$netJava = Join-Path $ProjectRoot 'src\main\java\com\summy\reliquary\net\ReliquaryNetworking.java'
$recipeDir = Join-Path $ProjectRoot 'src\main\resources\data\summy-reliquary\recipes'

$itemCount = (Select-String -Path $mainJava -Pattern 'ITEMS\.register\("').Count
$tabCount = (Select-String -Path $mainJava -Pattern 'out\.accept\(').Count
$recipeCount = (Get-ChildItem -LiteralPath $recipeDir -Filter '*.json').Count
# 进度数与配置键数都从源码/资源实测（1.7.10 收尾起不再硬编码）
$advancementDir = Join-Path $ProjectRoot 'src\main\resources\data\summy-reliquary\advancements'
$advancementCount = (Get-ChildItem -LiteralPath $advancementDir -Filter '*.json').Count
$allSections = @(Select-String -Path $configJava -Pattern '\.push\("' |
        ForEach-Object { ($_.Line -replace '.*\.push\("([a-z_]+)".*', '$1') } |
        Sort-Object -Unique)
$sectionCount = $allSections.Count
# 配置键数：按源码 `ReliquaryConfig` 里的 `.define*` 调用数统计（分段的同名键各算一个）
$configKeyCount = (Select-String -Path $configJava -Pattern '\.define(?:InRange|Enum|Int|Boolean|Double)?\("').Count

# 单段键数（例：golden_razor 段）：取 `.push("<段名>")` 到下一个 `builder.pop()` 之间的 `.define*` 数
function Get-SectionKeyCount([string]$path, [string]$section) {
    $text = [System.IO.File]::ReadAllText($path)
    $start = $text.IndexOf('.push("' + $section + '")')
    if ($start -lt 0) { return 0 }
    $end = $text.IndexOf('builder.pop()', $start)
    if ($end -lt 0) { $end = $text.Length }
    return ([regex]::Matches($text.Substring($start, $end - $start), '\.define(?:InRange|Enum|Int|Boolean|Double)?\("')).Count
}

$razorKeys = Get-SectionKeyCount $configJava 'golden_razor'
$protocol = ((Select-String -Path $netJava -Pattern 'VERSION\s*=\s*"([^"]+)"' | Select-Object -First 1).Matches[0].Groups[1].Value)

# 自检行数：取最新一份 devcheck 日志里 `[DEVCHECK]` 的行数
$devcheckLog = Get-ChildItem -LiteralPath $ProjectRoot -Filter 'devcheck-*.log' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
$devcheckLines = 0
if ($devcheckLog) {
    $devcheckLines = (Get-Content -LiteralPath $devcheckLog.FullName |
            Select-String -Pattern '\[DEVCHECK\]').Count
}

Write-Host ("实测：物品 {0} / 创造页 {1} / 配方 {2} / 进度 {3} / 配置段 {4} / 配置键 {5}（golden_razor {6}） / 协议 `"{7}`" / 自检 {8} 行（{9}）" -f `
        $itemCount, $tabCount, $recipeCount, $advancementCount, $sectionCount, $configKeyCount, $razorKeys, $protocol, $devcheckLines, `
        ($(if ($devcheckLog) { $devcheckLog.Name } else { '没有 devcheck 日志' })))

# 自检行数允许小幅浮动：「光环 / 美德提示行」dump 会读存档里的赎罪状态（两行都在 / 都不在差 2 行）
if ($devcheckLines -lt 583 -or $devcheckLines -gt 592) {
    Add-Problem ("自检行数 " + $devcheckLines + " 超出预期的 583~592 区间（文档写的正是这个区间）")
}

# ==================== 与文档比对 ====================
$readme = Join-Path $ProjectRoot 'README.md'
$progress = Join-Path $LedgerDir 'SummyReliquary-进度文档-1.8.5-forge.md'
$events = Join-Path $LedgerDir 'SummyReliquary-事件文本与触发关系-1.8.5.md'

Write-Host '检查 README…'
Check-Contains $readme 'README' '## 1.8.5 变更' '当前版本小节'
Check-Contains $readme 'README' ('`1.8.5-forge`') '当前版本号'
Check-Contains $readme 'README' ("注册物品 / 创造页 / 配方 / 进度不变（**" + $itemCount + " / " + $tabCount + " / " + $recipeCount + " / " + $advancementCount + "**）") '物品 / 创造页 / 配方 / 进度数'
# 1.8.0：创造页与配方数已并入上一条断言
# 1.8.0：配方数已并入上一条断言
Check-Contains $readme 'README' '自检 **585 行全绿**' '自检行数'
Check-Contains $readme 'README' '完全拦截' '1.8.5 关键口径'

Write-Host '检查《进度文档》…'
Check-Contains $progress '进度文档' ("## 2. 内容总表（" + $itemCount + " 件已注册物品）") '第 2 章标题的物品数'
Check-Contains $progress '进度文档' '**585 行 `[DEVCHECK]`**' '第 11 章自检行数'
Check-Contains $progress '进度文档' '版本 1.8.5-forge' '第 14 章版本号'
Check-Contains $progress '进度文档' ("$sectionCount 个配置段 / $configKeyCount 个键") '配置段总览（简介）'
Check-Contains $progress '进度文档' ("注册物品 **" + $itemCount + "**、进度 **" + $advancementCount + "**、配方 **" + $recipeCount + "**、配置段 **" + $sectionCount + "**") '当前计数行'
Check-Contains $progress '进度文档' '实例 config 因历史残留键' '配置键数的实例口径说明'
Check-Contains $progress '进度文档' ('`[shadow_dash]`（**1.7.5 新增，14 键**）') '配置清单·shadow_dash 段'
Check-Contains $progress '进度文档' ('`[golden_razor]`（**1.7.10 新增，' + $razorKeys + ' 键**）') '配置清单·golden_razor 段'
Check-Contains $progress '进度文档' 'GenesisUsedMessage' '网络章节·创世纪动画包'
Check-Contains $progress '进度文档' 'doc-consistency.ps1' '第 11 章·本脚本的用法'

Write-Host '检查致谢与二创声明…'
$credits = Join-Path $ProjectRoot 'CREDITS.md'
$modsToml = Join-Path $ProjectRoot 'src\main\resources\META-INF\mods.toml'
Check-Contains $readme 'README' '## 致谢与免责声明' '致谢与免责声明小节'
Check-Contains $readme 'README' 'The Binding of Isaac' '《以撒的结合》致谢'
Check-Contains $readme 'README' '525277385@qq.com' '联系邮箱'
Check-Contains $credits 'CREDITS' 'The Binding of Isaac' '《以撒的结合》致谢'
Check-Contains $credits 'CREDITS' '自定义许可' '自定义许可口径'
Check-Contains $credits 'CREDITS' '禁止任何商业用途' '禁商用条款'
Check-Contains $credits 'CREDITS' '525277385@qq.com' '联系邮箱'
Check-Contains $modsToml 'mods.toml' 'The Binding of Isaac' '游戏内描述的二创说明'
Check-Contains $progress '进度文档' '525277385@qq.com' '二创与致谢指引'

Write-Host '检查《事件文本与触发关系》…'
Check-Contains $events '事件台账' '（1.8.5-forge）' '版本行'
Check-Contains $events '事件台账' '### 1.39 金刀片（1.7.10）' '金刀片文案小节'
Check-Contains $events '事件台账' '灰正体尾行' '700 台词的口径'

Write-Host '检查 README 去历史与 CHANGELOG…'
$changelog = Join-Path $ProjectRoot 'CHANGELOG.md'
Check-Contains $changelog 'CHANGELOG' '## 1.8.5 变更' '最新版本小节'
Check-Contains $changelog 'CHANGELOG' '## 1.4.4 变更' '最老版本小节'
$readmeText = [System.IO.File]::ReadAllText($readme)
foreach ($old in @('## 1.7.9 变更', '## 1.6.10 变更', '## 1.4.4 变更')) {
    if ($readmeText.Contains($old)) {
        Add-Problem ("README 仍含历史小节「$old」（历史记录应只在 CHANGELOG.md）")
    }
}

Write-Host '检查省略号规范…'
foreach ($file in @($readme, $changelog, $credits, $progress, $events)) {
    if (-not (Test-Path -LiteralPath $file)) { continue }
    $text = [System.IO.File]::ReadAllText($file)
    if ([regex]::Matches($text, '(?<!…)(…)(?!…)').Count -gt 0) {
        Add-Problem ("中文文档里仍有单字符省略号「…」（应写成「……」）：" + $file)
    }
    if ($text.Contains('.......')) {
        Add-Problem ("文档里出现 7 个点的省略号（应写成「……」）：" + $file)
    }
}
foreach ($lang in @('zh_cn', 'en_us')) {
    $langPath = Join-Path $ProjectRoot ("src\main\resources\assets\summy-reliquary\lang\" + $lang + ".json")
    if (-not (Test-Path -LiteralPath $langPath)) { continue }
    $langText = [System.IO.File]::ReadAllText($langPath)
    if ([regex]::Matches($langText, '(?<!…)(…)(?!…)').Count -gt 0) {
        Add-Problem ($lang + ".json 里仍有单字符省略号「…」")
    }
    if ($langText.Contains('....')) {
        Add-Problem ($lang + ".json 里出现 4 个及以上连续的点（英文应写 3 个点）")
    }
}

Write-Host '检查 README 配置章覆盖…'
$missingSections = @($allSections | Where-Object { $readmeText -notmatch ('\[' + [regex]::Escape($_) + '\]') })
if ($missingSections.Count -gt 0) {
    Add-Problem ("README 配置章缺少配置段：" + ($missingSections -join ', '))
}

Write-Host '检查 README 结构（概述 / 进度表 / 旧表述）…'
Check-Contains $readme 'README' '## 模组概述' '模组概述小节'
foreach ($ach in @('遗物：七罪', '成交', '新鲜灵魂', '黑暗秘典', '兽印', '夜魇', '恶魔之焰', '魔眼', '魔王', '终焉')) {
    Check-Contains $readme 'README' $ach ('进度表条目「' + $ach + '」')
}
Check-Contains $readme 'README' '**恶魔体系**' '恶魔体系说明'
Check-Contains $readme 'README' '近乎完美' '新成就「近乎完美」'
Check-Contains $readme 'README' '佩戴率' '纯洁无瑕佩戴率判据'
Check-Contains $readme 'README' 'flawless_min_wearing_percent' '佩戴率配置键'
Check-Contains $progress '进度文档' '近乎完美' '新成就「近乎完美」'
Check-Contains $readme 'README' '**灵台三件套的合成表**' '灵台三件套正式配方'
$staleTexts = @{
    '最多 5 心' = '黑心固定上限的旧口径'
    '上限 5 颗' = '黑心固定上限的旧口径'
    '上限为 5 心' = '黑心固定上限的旧口径'
    '（占位材料）' = '灵台三件套的占位配方'
    '不清恶魔线' = '创世纪不清恶魔线的旧口径'
    '撤销本模组全部进度' = '创世纪撤销进度的旧口径'
}
foreach ($pair in $staleTexts.GetEnumerator()) {
    foreach ($file in @($readme, $progress, $events)) {
        if (-not (Test-Path -LiteralPath $file)) { continue }
        $text = [System.IO.File]::ReadAllText($file)
        if ($text.Contains($pair.Key)) {
            Add-Problem ("仍有旧表述「" + $pair.Key + "」（" + $pair.Value + "）：" + $file)
        }
    }
}

# ==================== 结果 ====================
if ($problems.Count -eq 0) {
    Write-Host '文档一致性校验：全部通过 ✓' -ForegroundColor Green
    exit 0
}
Write-Host ("文档一致性校验：发现 " + $problems.Count + " 处不一致 ✗") -ForegroundColor Red
exit 1
