package com.gaibang.replyagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gaibang.replyagent.agent.Guardrails
import com.gaibang.replyagent.domain.model.AppConfig
import com.gaibang.replyagent.domain.model.CustomRule
import com.gaibang.replyagent.domain.model.DedupeKey
import com.gaibang.replyagent.domain.model.FallbackMode
import com.gaibang.replyagent.domain.model.Identity
import com.gaibang.replyagent.domain.model.IdentityKind
import com.gaibang.replyagent.domain.model.LedgerStatus
import com.gaibang.replyagent.domain.model.RuleAction
import com.gaibang.replyagent.domain.model.RuleField
import com.gaibang.replyagent.domain.model.RuleOp
import com.gaibang.replyagent.domain.model.StrategyDefaults
import com.gaibang.replyagent.domain.model.StrategyProfile

/* ══════════════ 策略配置（需求书第七章全部可自定义项） ══════════════ */

@Composable
fun StrategyPage(app: AppState, cfg: AppConfig) {
    val s = cfg.strategy()
    val t = tokens()
    Column(Modifier.verticalScroll(rememberScrollState())) {
        PageHeader(
            title = "策略配置",
            subtitle = "这一页的每一项都会渲染进提示词，大部分还在本地代码里强制执行，模型不听话也拦得住。"
        )

        Section("预设", "多套策略并存：一个号只回提问，另一个号什么都回，跑之前选一下。") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                cfg.strategies.forEach { p ->
                    if (p.id == cfg.activeStrategyId) PushButton(style = PushButtonStyle.Accent, onClick = {}) { Text(p.name) }
                    else PushButton(style = PushButtonStyle.Secondary, onClick = { app.update { c -> c.copy(activeStrategyId = p.id) } }) { Text(p.name) }
                }
            }
            SectionSpace(8)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PushButton(style = PushButtonStyle.Secondary, onClick = {
                    val id = "st-${System.currentTimeMillis() % 100000}"
                    app.update { c ->
                        c.copy(
                            strategies = c.strategies + StrategyProfile(id = id, name = "新策略 ${c.strategies.size + 1}"),
                            activeStrategyId = id
                        )
                    }
                }) { Text("＋ 空白新建") }
                PushButton(style = PushButtonStyle.Secondary, onClick = {
                    val id = "st-${System.currentTimeMillis() % 100000}"
                    app.update { c -> c.copy(strategies = c.strategies + s.copy(id = id, name = "${s.name} 副本"), activeStrategyId = id) }
                }) { Text("复制当前") }
                if (cfg.strategies.size > 1) {
                    PushButton(style = PushButtonStyle.Secondary, onClick = {
                        app.update { c -> c.copy(strategies = c.strategies.filter { it.id != s.id }, activeStrategyId = c.strategies.first { it.id != s.id }.id) }
                    }) { Text("删除当前") }
                }
            }
        }

        var nameDraft by remember(s.id) { mutableStateOf(s.name) }
        Section("这套策略叫什么", "") {
            TextFieldRow("名称", nameDraft, { v -> nameDraft = v; edit(app, s.id) { it.copy(name = v) } })
            SectionSpace(10)
            ChoiceRow(
                label = "使用的人设",
                help = "人设内容在「提示词」页维护。",
                options = cfg.prompts,
                selected = cfg.prompts.firstOrNull { it.id == s.promptId } ?: cfg.prompts.first(),
                onSelected = { p -> edit(app, s.id) { it.copy(promptId = p.id) } },
                render = { it.name }
            )
        }

        Section("回复范围", "先本地筛，再进模型。被过滤掉的评论一分钱 token 都不花。") {
            SwitchRow("只回顶层评论", s.scope.topLevelOnly, { v -> edit(app, s.id) { it.copy(scope = it.scope.copy(topLevelOnly = v)) } },
                help = "关掉后会尝试楼中楼，抓取量与费用都会涨")
            SwitchRow("跳过本人与主 UP 已经回过的", s.scope.skipAlreadyAnswered,
                { v -> edit(app, s.id) { it.copy(scope = it.scope.copy(skipAlreadyAnswered = v)) } },
                help = "平台支持读子回复时会按需补查楼中楼再判断")
            IntRow("最短内容长度", s.scope.minContentLength.toLong(),
                { v -> edit(app, s.id) { it.copy(scope = it.scope.copy(minContentLength = v.toInt())) } },
                help = "短于此长度基本是「前排」「1」这种", unit = "字", range = 1L..50L)
            IntRow("最长内容长度", s.scope.maxContentLength.toLong(),
                { v -> edit(app, s.id) { it.copy(scope = it.scope.copy(maxContentLength = v.toInt())) } },
                help = "超长评论多半需要人看，不适合自动回", unit = "字", range = 10L..3000L)
            SectionSpace(8)
            ChipsEditor("只回含这些词的（留空=不限制）", s.scope.onlyIfContains,
                { list -> edit(app, s.id) { it.copy(scope = it.scope.copy(onlyIfContains = list)) } },
                help = "命中任意一个才送进模型")
            SectionSpace(10)
            ChipsEditor("绝不回含这些词的", s.scope.neverIfContains,
                { list -> edit(app, s.id) { it.copy(scope = it.scope.copy(neverIfContains = list)) } })
        }

        Section("名单", "UID 精确匹配优先，昵称做大小写不敏感匹配。黑名单在抓取和发送前各查一次。") {
            IdentityGroup(app, s, IdentityKind.SELF, "我自己", "运行时自动用当前登录 UID 比对，这里的昵称只是备注")
            IdentityGroup(app, s, IdentityKind.MAIN_UP, "主 UP", "主 UP 自己发过的评论不回；楼中楼里出现过主 UP 也算已回")
            IdentityGroup(app, s, IdentityKind.BLACKLIST, "黑名单", "永远不回这些人，即使模型想回也会被本地拦下")
        }

        Section("自定义规则", "按顺序匹配，第一条命中即生效。字段可选昵称、UID、内容、是否顶层、点赞数、父评论 id。") {
            s.scope.customRules.forEachIndexed { i, rule -> RuleRow(app, s, i, rule) }
            SectionSpace(6)
            PushButton(style = PushButtonStyle.Secondary, onClick = {
                val rule = CustomRule(id = "r${System.currentTimeMillis() % 100000}", name = "规则 ${s.scope.customRules.size + 1}")
                edit(app, s.id) { it.copy(scope = it.scope.copy(customRules = it.scope.customRules + rule)) }
            }) { Text("＋ 加一条规则") }
        }

        Section("发送节奏", "B 站对频率很敏感（12014 是发送太快，12051 是内容重复）。宁可慢，不要撞风控。") {
            IntRow("两条回复最小间隔", s.rate.minIntervalMs,
                { v -> edit(app, s.id) { it.copy(rate = it.rate.copy(minIntervalMs = v)) } },
                unit = "毫秒", range = 1000L..600_000L,
                help = "还会在此之上加随机抖动，避免机械等间隔")
            IntRow("抖动上限", s.rate.jitterMs,
                { v -> edit(app, s.id) { it.copy(rate = it.rate.copy(jitterMs = v)) } },
                unit = "毫秒", range = 0L..120_000L)
            IntRow("单轮最多发送", s.rate.perRunCap.toLong(),
                { v -> edit(app, s.id) { it.copy(rate = it.rate.copy(perRunCap = v.toInt())) } },
                unit = "条", range = 1L..200L)
            IntRow("每天最多发送", s.rate.perDayCap.toLong(),
                { v -> edit(app, s.id) { it.copy(rate = it.rate.copy(perDayCap = v.toInt())) } },
                unit = "条", range = 1L..500L,
                help = "按台账里的 SENT 条数统计，重启也认账")
            IntRow("撞限流后冷却", s.rate.cooldownOnLimitMs,
                { v -> edit(app, s.id) { it.copy(rate = it.rate.copy(cooldownOnLimitMs = v)) } },
                unit = "毫秒", range = 10_000L..3_600_000L,
                help = "遇到 12014 / -509 / 12052 时整轮暂停这么久")
            IntRow("失败退避基数", s.rate.backoffBaseMs,
                { v -> edit(app, s.id) { it.copy(rate = it.rate.copy(backoffBaseMs = v)) } },
                unit = "毫秒", range = 100L..60_000L, help = "指数退避的起点")
            IntRow("退避上限", s.rate.backoffMaxMs,
                { v -> edit(app, s.id) { it.copy(rate = it.rate.copy(backoffMaxMs = v)) } },
                unit = "毫秒", range = 1_000L..600_000L)
            IntRow("连续失败几轮中止", s.rate.maxConsecutiveFailures.toLong(),
                { v -> edit(app, s.id) { it.copy(rate = it.rate.copy(maxConsecutiveFailures = v.toInt())) } },
                unit = "次", range = 1L..20L,
                help = "达到后本轮直接暂停，不是继续撞")
        }

        Section("台账", "同一条评论不回两次，靠的是这份落盘记录而不是模型记性。") {
            IntRow("保留天数", s.ledger.retentionDays.toLong(),
                { v -> edit(app, s.id) { it.copy(ledger = it.ledger.copy(retentionDays = v.toInt())) } },
                unit = "天", range = 7L..3650L)
            SwitchRow("发送前再查一次台账", s.ledger.recheckBeforeSend,
                { v -> edit(app, s.id) { it.copy(ledger = it.ledger.copy(recheckBeforeSend = v)) } },
                help = "关掉后并发场景可能重复回，不建议")
            SectionSpace(8)
            ChoiceRow(
                label = "去重主键",
                help = "B 站会把相似评论折叠、id 可能变；按内容指纹更稳，但会误伤完全相同的不同提问。",
                options = DedupeKey.entries.toList(),
                selected = s.ledger.dedupeBy,
                onSelected = { k -> edit(app, s.id) { it.copy(ledger = it.ledger.copy(dedupeBy = k)) } },
                render = { when (it) { DedupeKey.COMMENT_ID -> "评论 id"; DedupeKey.CONTENT_FINGERPRINT -> "作者+内容指纹" } }
            )
            SectionSpace(10)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PushButton(style = PushButtonStyle.Secondary, onClick = {
                    val n = app.ledger.prune(s.ledger.retentionDays)
                    AppLogNote(app, "按保留期清理 $n 条")
                }) { Text("按保留期清理") }
                PushButton(style = PushButtonStyle.Secondary, onClick = { app.ledger.compact(); AppLogNote(app, "台账已压缩重写") }) { Text("压缩文件") }
                Text("当前 ${app.ledger.size()} 条", style = AppTheme.type.bodySmall, color = t.subtle)
            }
        }

        Section("兜底与阈值", "模型拿不准的时候怎么办，这里定死，不给模型自由发挥。") {
            SliderRow(
                "自动回复的最低把握度", s.minConfidenceForAutoReply,
                { v -> edit(app, s.id) { it.copy(minConfidenceForAutoReply = v) } },
                help = "低于这个值就按下面的兜底方式处理", steps = 99
            )
            ChoiceRow(
                label = "兜底方式",
                help = "第一次用建议选「只报告」，先积累几天再放开。",
                options = FallbackMode.entries.toList(),
                selected = s.fallback,
                onSelected = { m -> edit(app, s.id) { it.copy(fallback = m) } },
                render = {
                    when (it) {
                        FallbackMode.REPORT_ONLY -> "只报告（转人工，不发）"
                        FallbackMode.SKIP -> "直接跳过"
                        FallbackMode.AUTO_REPLY -> "照样自动发（风险自担）"
                    }
                }
            )
            SectionSpace(10)
            IntRow("回复长度上限", s.maxReplyChars.toLong(),
                { v -> edit(app, s.id) { it.copy(maxReplyChars = v.toInt()) } },
                unit = "字", range = 10L..Guardrails.REPLY_MAX_HARD_LIMIT.toLong(),
                help = "硬上限 ${Guardrails.REPLY_MAX_HARD_LIMIT} 字，超过会被护栏拦下")
            SwitchRow("允许回复带图", s.allowImageReply,
                { v -> edit(app, s.id) { it.copy(allowImageReply = v) } },
                help = "关闭时即使模型想带图也不会上传")
            SectionSpace(8)
            ChipsEditor("回复里绝不允许出现的词", s.forbiddenReplySubstrings,
                { list -> edit(app, s.id) { it.copy(forbiddenReplySubstrings = list) } },
                help = "命中即转人工，例如你的 QQ 群号、外链域名、口头禅")
            SectionSpace(10)
            ChipsEditor("允许出现在回复里的链接域名", s.allowedLinkHosts,
                { list -> edit(app, s.id) { it.copy(allowedLinkHosts = list) } },
                help = "留空表示一律不允许带链接")
            SectionSpace(10)
            TextFieldRow(
                "给模型的补充说明", s.extraNotesForPrompt,
                { v -> edit(app, s.id) { it.copy(extraNotesForPrompt = v) } },
                help = "会原样拼进 <system_rules>，例如「涉及更新计划一律说等公告」",
                singleLine = false, lines = 3
            )
        }

        Section("关不掉的底线", "不写进配置、界面上也改不了的一组，硬编码在 Guardrails 里。", tone = t.warn) {
            Text(
                "· 评论正文先进 <untrusted_comment_data>，零宽字符与控制字符剔除、行首「system:」这类角色标记全角化、超长截断；\n" +
                    "· 回复文本必须过长度、内部标记、禁用词、链接白名单四道检查，不过就转人工；\n" +
                    "· 模型输出里出现被诱导的痕迹会记进日志（只记录，不因此改变规则）；\n" +
                    "· 密码相关能力在接口层就不存在。",
                style = AppTheme.type.bodySmall
            )
        }
    }
}

private fun edit(app: AppState, id: String, transform: (StrategyProfile) -> StrategyProfile) {
    app.update { c -> c.copy(strategies = c.strategies.map { if (it.id == id) transform(it) else it }) }
}

@Composable
private fun IdentityGroup(app: AppState, s: StrategyProfile, kind: IdentityKind, title: String, help: String) {
    val items = s.identities.filter { it.kind == kind }
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = AppTheme.type.titleSmall, modifier = Modifier.weight(1f))
            PushButton(style = PushButtonStyle.Secondary, onClick = {
                val n = s.identities.count { it.kind == kind } + 1
                val item = Identity(id = "${kind.name}-${System.currentTimeMillis() % 100000}", kind = kind, name = "$title $n")
                edit(app, s.id) { it.copy(identities = it.identities + item) }
            }) { Text("＋") }
        }
        Text(help, style = AppTheme.type.bodySmall,
            color = AppTheme.color.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        items.forEach { item ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                TextFieldRow("昵称", item.name, { v -> editIdentity(app, s.id, item.id) { it.copy(name = v) } }, modifier = Modifier.weight(1f))
                TextFieldRow("UID", item.uid, { v -> editIdentity(app, s.id, item.id) { it.copy(uid = v.filter { c -> c.isDigit() }) } }, modifier = Modifier.weight(1f))
                SwitchRow("启用", item.enabled, { v -> editIdentity(app, s.id, item.id) { it.copy(enabled = v) } })
                if (kind != IdentityKind.SELF || item.id != "id-self") {
                    GhostButton("删", onClick = { edit(app, s.id) { it.copy(identities = it.identities.filter { x -> x.id != item.id }) } })
                }
            }
            if (item.note.isNotBlank()) {
                Text(item.note, style = AppTheme.type.bodySmall, color = AppTheme.color.onSurfaceVariant)
            }
        }
        if (items.isEmpty()) EmptyHint("（空）")
    }
}

private fun editIdentity(app: AppState, sid: String, id: String, transform: (Identity) -> Identity) {
    app.update { c ->
        c.copy(strategies = c.strategies.map { s ->
            if (s.id == sid) s.copy(identities = s.identities.map { if (it.id == id) transform(it) else it }) else s
        })
    }
}

@Composable
private fun RuleRow(app: AppState, s: StrategyProfile, index: Int, rule: CustomRule) {
    val t = tokens()
    SurfaceForCard {
        Column(Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                TextFieldRow("规则名", rule.name, { v -> editRule(app, s.id, rule.id) { it.copy(name = v) } }, modifier = Modifier.weight(1f))
                SwitchRow("启用", rule.enabled, { v -> editRule(app, s.id, rule.id) { it.copy(enabled = v) } })
                GhostButton("删", onClick = {
                    edit(app, s.id) { it.copy(scope = it.scope.copy(customRules = it.scope.customRules.filter { r -> r.id != rule.id })) }
                })
            }
            SectionSpace(8)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChoiceRow(
                    label = "字段", options = RuleField.entries.toList(), selected = rule.field,
                    onSelected = { v -> editRule(app, s.id, rule.id) { it.copy(field = v) } },
                    render = { it.cn() }, modifier = Modifier.weight(1f)
                )
                ChoiceRow(
                    label = "条件", options = RuleOp.entries.toList(), selected = rule.op,
                    onSelected = { v -> editRule(app, s.id, rule.id) { it.copy(op = v) } },
                    render = { it.cn() }, modifier = Modifier.weight(1f)
                )
                ChoiceRow(
                    label = "动作", options = RuleAction.entries.toList(), selected = rule.action,
                    onSelected = { v -> editRule(app, s.id, rule.id) { it.copy(action = v) } },
                    render = {
                        when (it) {
                            RuleAction.SKIP -> "跳过（不进模型）"
                            RuleAction.ESCALATE -> "转人工"
                            RuleAction.FORCE_REPLY -> "强制进模型"
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }
            SectionSpace(8)
            TextFieldRow(
                "比较值", rule.value,
                { v -> editRule(app, s.id, rule.id) { it.copy(value = v) } },
                help = when (rule.op) {
                    RuleOp.IN, RuleOp.NOT_IN -> "多个值用逗号分隔"
                    RuleOp.REGEX -> "正则，按 Java Regex 语法"
                    RuleOp.GT, RuleOp.LT -> "填数字"
                    else -> "文本"
                }
            )
        }
    }
}

private fun editRule(app: AppState, sid: String, rid: String, transform: (CustomRule) -> CustomRule) {
    app.update { c ->
        c.copy(strategies = c.strategies.map { s ->
            if (s.id == sid) s.copy(scope = s.scope.copy(customRules = s.scope.customRules.map { if (it.id == rid) transform(it) else it })) else s
        })
    }
}

private fun RuleField.cn(): String = when (this) {
    RuleField.AUTHOR_UID -> "作者 UID"
    RuleField.AUTHOR_NAME -> "作者昵称"
    RuleField.CONTENT -> "评论内容"
    RuleField.IS_TOP_LEVEL -> "是否顶层"
    RuleField.LIKE_COUNT -> "点赞数"
    RuleField.PARENT_ID -> "父评论 id"
}

private fun RuleOp.cn(): String = when (this) {
    RuleOp.EQ -> "等于"
    RuleOp.NE -> "不等于"
    RuleOp.CONTAINS -> "包含"
    RuleOp.REGEX -> "正则匹配"
    RuleOp.IN -> "属于列表"
    RuleOp.NOT_IN -> "不属于列表"
    RuleOp.GT -> "大于"
    RuleOp.LT -> "小于"
}

private fun AppLogNote(app: AppState, msg: String) {
    com.gaibang.replyagent.logging.AppLogger.i("ui", msg)
}
