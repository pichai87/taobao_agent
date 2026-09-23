(() => {
  "use strict";
  // 凭据只留在页面内存，不写入 URL、日志或本地存储。
  const state = { auth: "", csrf: "", csrfHeader: "", runId: "", timer: null,
    generation: 0, events: [], busy: false, submission: null, modelSession: null, streamAbort: null, defaultMode: "OFFLINE_RULES" };
  const $ = id => document.getElementById(id);
  const statuses = { QUEUED: "排队中", RUNNING: "执行中", WAITING_FOR_REVIEW: "等待你确认",
    SUCCEEDED: "分析完成", FAILED: "分析失败", CANCELLED: "已取消", INTERRUPTED: "执行中断" };
  const nodes = { plan: "识别问题", semantic: "读取指标口径", query: "受控查询",
    calculate: "计算指标", critic: "校验结果", report: "生成报告", skill: "选择分析方法", run: "任务", model: "大模型决策", tool: "工具调用", review: "人工确认",
    intent_agent: "意图识别 Agent", supervisor: "Supervisor 总调度器", query_agent: "Query 查询专家", analysis_agent: "Analysis 分析专家",
    ops_agent: "Ops 运维专家（只读）", other_agent: "Other 通用兜底专家", handoff: "专家任务交接" };
  const eventStatuses = { STARTED: "开始", SUCCEEDED: "完成", FAILED: "失败", SELECTED: "已选择",
    STOPPED: "已停止", CANCELLED: "已取消", REJECTED: "已拒绝", APPROVED: "已确认", ROUTED: "已路由", TOOL_RESULT: "工具返回", EVIDENCE: "证据就绪", DATA_REQUEST: "申请数据", CACHE_HIT: "复用证据", FALLBACK: "规则降级" };
  const intents = { QUERY: "指标查询", COMPARE: "日期对比", ATTRIBUTION: "品类贡献拆分",
    TOP_N: "品类排名", TREND: "七日趋势", DEFINITION: "指标定义" };
  const categories = { appliances: "家电", beauty: "美妆", food: "食品" };
  const errors = {
    QUESTION_METRIC_NOT_SUPPORTED: "当前 Agent 报告主链只分析 GMV。订单数、UV、CVR、AOV 请在语义查询工作台查看，不能用 GMV 冒充回答。",
    INVALID_TOP_N: "排名数量无法确定或超出 1 到 100，请明确写 Top 5 或前五名。",
    METRIC_OVERFLOW: "指标数量超过当前数值类型范围，已停止计算以防止溢出。",
    DATA_COVERAGE_INCOMPLETE: "部分订单缺少流量记录，不能安全计算完整指标，请补齐数据。",
    QUERY_RESULT_LIMIT_EXCEEDED: "查询结果超过允许行数，请缩小日期或品类范围，系统没有返回截断的总额。",
    UV_CROSS_DATE_AGGREGATION: "跨天 UV 不能直接相加。请选择日期维度，或只查一天。",
    INVALID_SEMANTIC_PLAN: "指标、维度、日期或查询行数不符合语义模型约束。",
    INVALID_METRIC_DEFINITION: "指标定义不合法，请使用已有字段和允许的公式。",
    METRIC_ALREADY_EXISTS: "指标代码已存在，请换一个代码，不能覆盖已有口径。",
    INVALID_EXECUTION_MODE: "不支持这种执行方式，请选择页面列出的模式。",
    INTENT_ROUTE_CONFLICT: "模型路由与问题中明确的数据要求冲突，已停止，请明确问题后重试。",
    AGENT_TOOL_FORBIDDEN: "专家请求了职责范围以外的工具，后端已拒绝。",
    HANDOFF_INVALID: "专家数据包的任务、日期或范围不符合要求。",
    HANDOFF_EVIDENCE_REQUIRED: "专家交接缺少完整证据，不能生成成功报告。",
    AGENT_ROUND_LIMIT: "单个专家达到模型调用上限，已停止。",
    MULTI_ROUND_LIMIT: "多 Agent 全局达到 16 轮模型调用上限，已停止。",
    MULTI_TOOL_BUDGET: "多 Agent 全局达到 24 次工具调用上限，已停止。",
    MULTI_HANDOFF_LIMIT: "达到 6 次专家派发上限，已停止循环交接。",
    MULTI_TOKEN_BUDGET: "多 Agent 累计报告 token 超过预算，已停止。",
    MULTI_TIME_BUDGET: "多 Agent 达到 180 秒操作边界时间预算，已停止。",
    MODEL_SESSION_EXPIRED: "临时模型配置已过期、清除或不属于当前账号，请重新配置密钥。",
    MODEL_CONSENT_REQUIRED: "请先确认数据发送范围和模型计费提示。",
    INVALID_MODEL_SETTINGS: "模型配置格式不正确，请检查密钥、业务空间 ID 和 qwen 模型名称。",
    MODEL_PROVIDER_NOT_ALLOWED: "只允许页面列出的百炼官方地域。",
    MODEL_HTTPS_REQUIRED: "非本机连接必须使用 HTTPS，不能明文传输模型密钥。",
    MODEL_SESSION_CAPACITY: "临时模型会话已满，请清除不用的配置后重试。",
    MODEL_AUTH_FAILED: "百炼拒绝认证，请检查密钥是否有效、是否与地域匹配。",
    MODEL_REQUEST_REJECTED: "百炼拒绝请求，请检查模型权限、名称和业务空间。",
    MODEL_RATE_LIMITED: "百炼限流或额度不足，请检查账户后再试。",
    MODEL_TIMEOUT: "模型响应超时，本次已停止，不自动重试扣费。",
    MODEL_CONNECTION_FAILED: "无法连接百炼或响应超出允许大小，请检查网络后重试。",
    MODEL_PROVIDER_FAILED: "百炼服务返回错误，本次已停止。",
    MODEL_RESPONSE_INVALID: "模型返回了不完整或不符合协议的工具请求。",
    MODEL_ROUND_LIMIT: "达到 8 轮模型调用上限，已停止。",
    MODEL_TOOL_BUDGET: "达到 12 次工具请求上限，已停止。",
    MODEL_TOKEN_BUDGET: "模型报告的累计 token 超过预算，已停止。",
    MODEL_CONTEXT_LIMIT: "模型上下文超出大小限制，已停止。",
    MODEL_TIME_BUDGET: "达到任务时间预算，已停止。",
    MODEL_TOOL_RETRY_LIMIT: "模型多次选择了错误参数或遗漏前置步骤，已停止。",
    MODEL_EVIDENCE_REQUIRED: "模型没有获取足够的工具证据，不能生成成功报告。",
    MODEL_BUSY: "该账号已有模型任务正在运行，请等待完成或取消。",
    MODEL_RUN_NOT_RESUMABLE: "本批模型循环不支持中断恢复，请重新配置并新建任务。",
    TOOL_NOT_ALLOWED: "模型请求了未经允许的工具，后端已拒绝执行。",
    NO_DATA: "目标日期没有数据，请尝试 2026-09-12。",
    NO_COMPARISON_DATA: "对比日期没有数据，请尝试 2026-09-11。",
    UNSUPPORTED_QUESTION: "当前只支持 GMV 查询、对比、贡献、趋势、排名和定义。",
    INVALID_REQUEST: "输入不合法，请检查问题和日期。",
    INVALID_RUN_STATE: "任务状态已变化，请刷新任务进度。",
    RUN_NOT_FOUND: "任务不存在或不属于当前账号。",
    CAPACITY_EXCEEDED: "服务繁忙，请稍后重新提交。",
    METRIC_NOT_SUPPORTED: "当前只支持 GMV 分析。",
    MODEL_PLAN_REJECTED: "模型返回的意图不在许可范围内。",
    MODEL_MODE_CHANGED: "运行模式已改变，不能直接恢复旧任务。",
    EXECUTION_FAILED: "执行异常，请保留任务 ID 排查。",
    UNAUTHORIZED: "账号或密码不正确，或登录已失效，请重新连接。",
    FORBIDDEN: "没有操作权限或安全令牌失效，请退出后重新连接。",
    NETWORK_ERROR: "服务未响应或连接中断。请确认启动窗口仍在运行，再重试。"
  };
  const message = code => (errors[code] || "请求失败，请保留错误代码排查。") + "（" + code + "）";
  const money = value => value == null ? "—" : new Intl.NumberFormat("zh-CN", {
    style: "currency", currency: "CNY", maximumFractionDigits: 2 }).format(value);
  const mode = value => value === "OFFLINE_RULES" ? "离线规则 · 未调用大模型" :
    value === "LLM_MULTI_AGENT" ? "多 Agent 专家协作 · 有费用与次数限制" :
    value === "LLM_TOOL_CALLING" ? "单 Agent 工具调用 · 对照模式" : "模型意图分类 · " + value;
  const updateMode = () => {
    const selected = $("execution-mode").value === "multi" ? "LLM_MULTI_AGENT" : $("execution-mode").value === "tools" ? "LLM_TOOL_CALLING" : state.defaultMode;
    $("mode-badge").textContent = mode(selected);
    $("mode-description").textContent = selected === "LLM_MULTI_AGENT" ?
      "意图识别 → 总调度器 → 独立专家；分析专家向查询专家申请数据，再计算和解释。全局最多 16 轮模型、24 次工具、6 次派发。含受限 GMV 因子诊断树；简单问题可能比单 Agent 更慢、更贵。" : selected === "LLM_TOOL_CALLING" ?
      "由模型决定工具、读取结果后继续决策。最多 8 轮模型请求、12 次工具请求；只读查询，不能执行任意 SQL。模型解释需核对。" :
      selected === "OFFLINE_RULES" ? "当前用规则识别问题，真实执行数据库查询和六步工作流，不调用大模型。" :
      "当前服务器模式只用模型分类，工具流程固定；选择模型工具调用模式才会自主选择工具。";
  };
  const showModelSession = session => {
    state.modelSession = session;
    $("tool-mode-option").disabled = !session;
    $("multi-mode-option").disabled = !session;
    $("model-status").textContent = session ? "临时配置已保存（尚不代表联通验证）：" + session.model +
      "；到期：" + new Date(session.expiresAt).toLocaleString("zh-CN") + "；目标地址：" + session.endpoint :
      "尚未配置，仍可使用离线分析。";
    if (!session) $("execution-mode").value = "default";
    updateMode();
  };
  const active = run => ["RUNNING", "QUEUED"].includes(run.status);
  const setConnection = (text, status) => {
    $("connection-text").textContent = text;
    $("connection-dot").className = "dot " + status;
  };
  const api = async (path, options = {}) => {
    const headers = new Headers(options.headers || {});
    headers.set("Authorization", state.auth);
    // 认证失败显示在表单中，不触发浏览器原生 Basic 弹窗。
    headers.set("X-Requested-With", "XMLHttpRequest");
    if (options.method === "POST") headers.set(state.csrfHeader, state.csrf);
    let response;
    try {
      response = await fetch(path, { ...options, headers, credentials: "same-origin",
        cache: "no-store", signal: AbortSignal.timeout(15000) });
    } catch (_) { throw new Error("NETWORK_ERROR"); }
    if (!response.ok) {
      if (response.status === 401) throw new Error("UNAUTHORIZED");
      if (response.status === 403) throw new Error("FORBIDDEN");
      let code = "HTTP_" + response.status;
      try { code = (await response.json()).code || code; } catch (_) { /* 非 JSON 错误页 */ }
      throw new Error(code);
    }
    return response.json();
  };
  const stopWatching = () => {
    state.streamAbort?.abort(); state.streamAbort = null;
    clearTimeout(state.timer);
    state.timer = null;
    return ++state.generation;
  };
  const renderEvents = () => {
    $("events").replaceChildren();
    if (!state.events.length) {
      const row = document.createElement("li");
      row.textContent = "尚无执行步骤；待确认任务不会提前查询业务数据。";
      $("events").append(row);
    }
    state.events.forEach(event => {
      const row = document.createElement("li");
      // 服务器文本只当文本显示，不能作为 HTML 执行。
      row.textContent = (nodes[event.node] || event.node) + " · " +
        (eventStatuses[event.status] || event.status) + " · " + (errors[event.detail] || event.detail) + "（" + event.elapsedMs + " ms）";
      $("events").append(row);
    });
  };
  const renderChecks = run => {
    $("collaboration-card").hidden = run.mode !== "LLM_MULTI_AGENT";
    $("agent-participants").replaceChildren(); $("agent-handoffs").replaceChildren();
    if (run.mode === "LLM_MULTI_AGENT") {
      const actors = ["intent_agent", "supervisor", "query_agent", "analysis_agent", "ops_agent", "other_agent"];
      actors.filter(actor => state.events.some(e => e.node === actor)).forEach(actor => {
        const li = document.createElement("li");
        const count = state.events.filter(e => e.node === actor && e.status === "STARTED").length;
        li.textContent = nodes[actor] + (actor === "supervisor" ? "：规则调度与交接校验，不额外调用模型" : "：已发起 " + count + " 轮模型决策");
        $("agent-participants").append(li);
      });
      const handoffs = state.events.filter(e => e.node === "handoff");
      handoffs.forEach(e => { const li = document.createElement("li"); li.textContent = (eventStatuses[e.status] || e.status) + " · " + e.detail; $("agent-handoffs").append(li); });
      if (!handoffs.length) { const li = document.createElement("li"); li.textContent = "尚无专家交接事件，不能据此认定协作已成功。"; $("agent-handoffs").append(li); }
    }
    const done = node => state.events.some(e => e.node === node && e.status === "SUCCEEDED");
    const definition = run.status === "SUCCEEDED" && run.report && !run.report.data;
    $("database-check").textContent = definition ? "本次不查询业务表（定义、状态或能力说明）" :
      done("query") && run.report?.data ? "本次业务查询已完成" :
      done("query") ? "查询节点已完成，等待报告确认" : "本次业务查询尚未验证通过";
    $("workflow-check").textContent = run.status === "SUCCEEDED" && done("report") ?
      "本次工作流已完成，报告可查看" : (statuses[run.status] || run.status);
  };
  const renderReport = run => {
    const report = run.report;
    $("report").hidden = !report;
    if (!report) return;
    const data = report.data;
    $("diagnosis-card").hidden = !data?.diagnosis;
    $("diagnosis-tree").replaceChildren(); $("diagnosis-limits").replaceChildren();
    if (data?.diagnosis) {
      const renderNode = node => {
        const item = document.createElement("li");
        item.textContent = node.label + "：" + money(node.contribution) + "；停止=" + node.stopReason + "；未展开=" + money(node.remainingContribution);
        if (node.children?.length) { const list = document.createElement("ul"); node.children.forEach(child => list.append(renderNode(child))); item.append(list); }
        return item;
      };
      const tree = document.createElement("ul"); tree.append(renderNode(data.diagnosis.root)); $("diagnosis-tree").append(tree);
      data.diagnosis.limitations.forEach(text => { const item = document.createElement("li"); item.textContent = text; $("diagnosis-limits").append(item); });
    }
    ["metrics-grid", "contribution-card", "rows-card"].forEach(id => { $(id).hidden = !data; });
    $("report-title").textContent = report.title.replace(/\b(QUERY|COMPARE|ATTRIBUTION|TOP_N|TREND|DEFINITION)\b/g, value => intents[value]);
    $("conclusion").textContent = report.conclusion;
    $("limitation").textContent = report.limitation;
    $("model-answer-card").hidden = !report.modelAnswer;
    $("model-answer").textContent = report.modelAnswer || "";
    $("sql").textContent = report.sql || "本次没有执行 SQL（数据库查询语句）。";
    $("evidence").replaceChildren();
    (report.evidence || []).forEach(item => {
      const row = document.createElement("li");
      row.textContent = item.title + "：" + item.text + "（版本 " + item.version + "）";
      $("evidence").append(row);
    });
    if (!data) return;
    $("current-gmv").textContent = money(data.current.gmv);
    $("current-date").textContent = run.request.date;
    $("previous-gmv").textContent = data.previous ? money(data.previous.gmv) : "无对比";
    $("previous-date").textContent = data.previous ? run.request.compareDate : "本次不是对比分析";
    const delta = data.previous ? data.current.gmv - data.previous.gmv : null;
    $("delta-gmv").textContent = money(delta);
    $("delta-gmv").className = delta == null ? "" : delta < 0 ? "negative" : "positive";
    $("change-rate").textContent = data.changeRate == null ? "无可用变化率" : (data.changeRate * 100).toFixed(2) + "%";
    $("contributions").replaceChildren();
    if (!data.contributions.length) $("contributions").textContent = "本次不进行品类贡献拆分。";
    data.contributions.forEach(item => {
      const row = document.createElement("div");
      row.className = "contribution";
      const name = document.createElement("span");
      name.textContent = categories[item.dimension] || item.dimension;
      const amount = document.createElement("strong");
      amount.textContent = money(item.delta);
      amount.className = item.delta < 0 ? "negative" : "positive";
      row.append(name, amount);
      $("contributions").append(row);
    });
    $("rows").replaceChildren();
    data.rows.forEach(item => {
      const row = document.createElement("tr");
      [item.date, categories[item.category] || item.category, money(item.gmv), item.orders, item.uv].forEach(value => {
        const cell = document.createElement("td");
        cell.textContent = value;
        row.append(cell);
      });
      $("rows").append(row);
    });
  };
  const refreshHistory = async () => {
    const generation = state.generation;
    const runs = await api("/api/runs");
    if (generation !== state.generation) return;
    $("history").replaceChildren(new Option("选择任务查看进度或报告", ""));
    runs.forEach(run => $("history").append(new Option(
      (statuses[run.status] || run.status) + " · " + run.request.question + " · " + run.id.slice(0, 8), run.id)));
    $("history").value = state.runId;
  };
  const actionButton = (label, handler) => {
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = label;
    button.onclick = async () => {
      if (state.busy) return;
      state.busy = true; button.disabled = true;
      try { await handler(); }
      catch (error) { $("run-error").textContent = message(error.message); }
      finally { state.busy = false; button.disabled = false; }
    };
    $("run-actions").append(button);
  };
  const renderActions = run => {
    $("run-actions").replaceChildren();
    const action = path => async () => {
      const generation = stopWatching();
      try { await api("/api/runs/" + run.id + "/" + path, { method: "POST" }); }
      finally { if (generation === state.generation) await pollRun(generation); }
    };
    if (run.status === "WAITING_FOR_REVIEW") actionButton("确认并执行", action("approve"));
    if (run.status === "INTERRUPTED" && !["LLM_TOOL_CALLING", "LLM_MULTI_AGENT"].includes(run.mode)) actionButton("从检查点恢复", action("resume"));
    if (active(run) || run.status === "WAITING_FOR_REVIEW") actionButton("取消任务", action("cancel"));
    actionButton("刷新任务进度", async () => { await pollRun(stopWatching()); });
  };
  // 串行轮询：上次请求结束才安排下次；generation 防止旧任务覆盖新任务。
  const pollRun = async generation => {
    const id = state.runId;
    try {
      const run = await api("/api/runs/" + id);
      let events;
      do {
        const after = state.events.at(-1)?.sequence || 0;
        events = await api("/api/runs/" + id + "/events?after=" + after);
        if (generation !== state.generation) return;
        state.events.push(...events);
      } while(events.length>=1000);
      $("status-card").hidden = false;
      $("run-title").textContent = run.request.question;
      $("status-badge").textContent = statuses[run.status] || run.status;
      $("status-detail").textContent = "任务 ID：" + id + " · " + mode(run.mode) +
        (run.errorCode ? " · " + message(run.errorCode) : "");
      $("run-error").textContent = "";
      renderEvents(); renderChecks(run); renderActions(run); renderReport(run);
      if (active(run)) {
        if (globalThis.EcomStream) startStream(generation);
        else state.timer = setTimeout(() => pollRun(generation), 800);
      }
      else await refreshHistory();
    } catch (error) {
      if (generation !== state.generation) return;
      $("run-error").textContent = "进度暂时无法读取，任务可能仍在后台运行。" + message(error.message);
      $("run-actions").replaceChildren();
      actionButton("重试读取进度", async () => { await pollRun(stopWatching()); });
    }
  };
  const startStream = generation => {
    const controller = new AbortController(); state.streamAbort = controller;
    const timeout = setTimeout(() => controller.abort(), 70000);
    const after = state.events.at(-1)?.sequence || 0;
    let terminal = false;
    globalThis.EcomStream.connect({ url: "/api/runs/" + state.runId + "/stream?after=" + after,
      auth: state.auth, signal: controller.signal, onEvent: event => {
        if (generation !== state.generation) return;
        if (event.name === "node" && Number.isSafeInteger(event.data.sequence) && event.data.sequence > (state.events.at(-1)?.sequence || 0)) {
          state.events.push(event.data); renderEvents();
        } else if (event.name === "status") {
          $("status-badge").textContent = statuses[event.data] || event.data;
          if (!["RUNNING", "QUEUED"].includes(event.data)) { terminal = true; controller.abort(); }
        }
      }
    }).catch(() => { /* 网络/代理不支持时保留游标，退回状态读取后重连。 */ }).finally(() => {
      clearTimeout(timeout);
      if (generation !== state.generation) return;
      state.streamAbort = null;
      state.timer = setTimeout(() => pollRun(generation), terminal ? 0 : 1500);
    });
  };
  const watch = async id => {
    const generation = stopWatching();
    state.runId = id; state.events = [];
    $("report").hidden = true; $("status-card").hidden = true;
    $("database-check").textContent = "正在读取本次查询状态";
    $("workflow-check").textContent = "正在读取任务进度";
    await pollRun(generation);
  };
  const workbenchAction = async action => {
    $("semantic-status").textContent = "处理中…";
    try { await action(); $("semantic-status").textContent = "完成。查询结果和限制如下；没有调用大模型。"; }
    catch (error) { $("semantic-status").textContent = message(error.message); }
  };
  const postJson = (path, data) => api(path, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(data) });
  $("semantic-form").addEventListener("submit", event => {
    event.preventDefault(); return workbenchAction(async () => {
      const query = { metrics: [$("semantic-metric").value], dimensions: $("semantic-dimension").value ? [$("semantic-dimension").value] : [],
        start: $("semantic-start").value, end: $("semantic-end").value, category: null, limit: 500 };
      const plan = await postJson("/api/semantic/plan", query);
      const rows = await postJson("/api/semantic/query", query);
      const knowledge = await api("/api/semantic/knowledge?metric=" + encodeURIComponent(query.metrics[0]) + "&question=口径");
      $("semantic-result").textContent = "null 表示未知 / 分母为零 / 覆盖不完整，不代表 0。\n" + JSON.stringify({ plan, rows, knowledge }, null, 2);
    });
  });
  $("metric-register-form").addEventListener("submit", event => {
    event.preventDefault(); return workbenchAction(async () => {
      const metric = await postJson("/api/semantic/metrics", { code: $("metric-code").value, name: $("metric-name").value,
        formula: $("metric-formula").value, numerator: $("metric-numerator").value,
        denominator: $("metric-formula").value === "RATIO" ? $("metric-denominator").value : null,
        unit: $("metric-unit").value, description: $("metric-description").value });
      $("semantic-metric").append(new Option(metric.code, metric.code));
      $("semantic-result").textContent = JSON.stringify(metric, null, 2);
    });
  });
  $("memory-form").addEventListener("submit", event => {
    event.preventDefault(); return workbenchAction(() => postJson("/api/semantic/memory", { summary: $("memory-summary").value }));
  });
  $("memory-load").onclick = () => workbenchAction(async () => { $("memory-summary").value = (await api("/api/semantic/memory")).summary; });
  // Per-account acceptance records. Server versions prevent silent cross-tab overwrites.
  const requirementRows = new Map();
  let requirementsLoaded = false, requirementsLoading = false;
  const reviewErrors = {
    REVIEW_VERSION_CONFLICT: "记录已被另一页面更新。请先复制你的备注，再刷新此表重新修改。",
    INVALID_REQUIREMENT_REVIEW: "请选择有效的完成度，并填写不超过 3000 字的备注。",
    REVIEW_CONTAINS_SECRET: "备注疑似包含 API Key，请删除密钥后再保存。"
  };
  const boardSummary = () => {
    const rows = [...requirementRows.values()];
    $("requirements-summary").textContent = `共 ${rows.length} 项；已保存的实现勾选 ${rows.filter(r => r.saved.implemented).length} 项；需返工 ${rows.filter(r => r.saved.rework).length} 项；未保存 ${rows.filter(r => r.dirty).length} 项。这不是企业级完成率。`;
    const filter = $("requirements-filter").value;
    rows.forEach(r => { r.tr.hidden = filter === "unfinished" ? r.implemented.checked : filter === "rework" ? !r.rework.checked : false; });
  };
  const renderRequirement = item => {
    const d = item.definition, saved = item.review;
    const tr = document.createElement("tr");
    const cells = Array.from({ length: 5 }, () => document.createElement("td"));
    const title = document.createElement("strong"); title.textContent = d.name;
    const layer = document.createElement("p"); layer.className = "hint"; layer.textContent = d.layer;
    cells[0].append(title, layer);
    const baseline = document.createElement("p"); baseline.className = "hint";
    baseline.textContent = "当前代码基线：" + ({ NONE: "尚未实现", PARTIAL: "部分实现", IMPLEMENTED: "本项已实现" }[d.extent] || "见备注") + "。个人旧验收记录不会自动覆盖。";
    cells[0].append(baseline);
    const criteria = document.createElement("p"); criteria.textContent = d.criteria;
    const source = document.createElement("p"); source.className = "hint"; source.textContent = d.source;
    cells[1].append(criteria, source);
    const field = (cell, text, control) => {
      const label = document.createElement("label"); label.textContent = text;
      control.setAttribute("aria-label", d.name + "：" + text); label.append(control); cell.append(label); return control;
    };
    const check = (cell, text, checked) => {
      const el = document.createElement("input"); el.type = "checkbox"; el.checked = checked; return field(cell, text, el);
    };
    const select = (cell, text, values, value) => {
      const el = document.createElement("select"); values.forEach(([v, t]) => el.append(new Option(t, v))); el.value = value; return field(cell, text, el);
    };
    const implemented = check(cells[2], "已实现", saved.implemented);
    const extent = select(cells[2], "代码完成度", [["NONE", "尚未实现"], ["PARTIAL", "部分实现"], ["IMPLEMENTED", "本项已实现"]], saved.extent);
    const verification = select(cells[3], "验证程度", [["NOT_TESTED", "尚未验收"], ["AUTOMATED", "自动化测试"], ["LOCAL_E2E", "本机网页端到端"], ["LIVE_PROVIDER", "真实模型端到端"]], saved.verification);
    const rework = check(cells[3], "需要打回更新", saved.rework);
    const notes = document.createElement("textarea"); notes.value = saved.notes; notes.maxLength = 3000; notes.required = true;
    field(cells[4], "验收备注", notes);
    const save = document.createElement("button"); save.type = "button"; save.textContent = "保存此项";
    save.setAttribute("aria-label", d.name + "：保存此项");
    const feedback = document.createElement("p"); feedback.className = "hint"; feedback.setAttribute("aria-live", "polite");
    feedback.textContent = saved.version ? `已保存版本 ${saved.version}` : "项目默认记录，尚未个人修改";
    cells[4].append(save, feedback); tr.append(...cells);
    const row = { tr, implemented, extent, verification, rework, notes, save, saved, dirty: false };
    const dirty = () => { row.dirty = true; feedback.textContent = "有未保存的修改"; boardSummary(); };
    implemented.onchange = () => { extent.value = implemented.checked ? "IMPLEMENTED" : "PARTIAL"; dirty(); };
    extent.onchange = () => { implemented.checked = extent.value === "IMPLEMENTED"; dirty(); };
    verification.onchange = dirty; rework.onchange = dirty; notes.oninput = dirty;
    save.onclick = async () => {
      if (!notes.value.trim() || notes.value.length > 3000) { feedback.textContent = reviewErrors.INVALID_REQUIREMENT_REVIEW; return; }
      save.disabled = true;
      const controls = [implemented, extent, verification, rework, notes]; controls.forEach(c => { c.disabled = true; });
      try {
        const updated = await api("/api/requirements/" + encodeURIComponent(d.id), { method: "POST", headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ implemented: implemented.checked, extent: extent.value, verification: verification.value, rework: rework.checked,
            notes: notes.value.trim(), version: row.saved.version }) });
        row.saved = updated.review; row.dirty = false;
        feedback.textContent = `保存成功，版本 ${row.saved.version}（仅当前账号）`; boardSummary();
      } catch (error) { feedback.textContent = reviewErrors[error.message] || message(error.message); }
      finally { save.disabled = false; controls.forEach(c => { c.disabled = false; }); }
    };
    requirementRows.set(d.id, row); $("requirements-rows").append(tr);
  };
  const loadRequirements = async () => {
    if (requirementsLoading) return;
    if ([...requirementRows.values()].some(r => r.save.disabled)) { $("requirements-error").textContent = "正在保存，请稍后刷新。"; return; }
    if ([...requirementRows.values()].some(r => r.dirty) && !confirm("刷新会放弃本表尚未保存的修改。需要保留时请取消并先复制备注。确定刷新吗？")) return;
    requirementsLoading = true; $("requirements-refresh").disabled = true; $("requirements-error").textContent = "";
    try {
      const data = await api("/api/requirements");
      requirementRows.clear(); $("requirements-rows").replaceChildren(); data.items.forEach(renderRequirement);
      requirementsLoaded = true; boardSummary();
    } catch (error) { $("requirements-error").textContent = message(error.message); }
    finally { requirementsLoading = false; $("requirements-refresh").disabled = false; }
  };
  $("requirements-refresh").onclick = loadRequirements;
  $("requirements-filter").onchange = boardSummary;
  $("requirements-board").addEventListener("toggle", () => {
    if ($("requirements-board").open && !requirementsLoaded) loadRequirements();
  });

  $("login-form").addEventListener("submit", async event => {
    event.preventDefault();
    $("login-button").disabled = true; $("login-error").textContent = "";
    const bytes = new TextEncoder().encode($("username").value + ":" + $("password").value);
    state.auth = "Basic " + btoa(Array.from(bytes, byte => String.fromCharCode(byte)).join(""));
    try {
      const csrf = await api("/api/csrf");
      state.csrf = csrf.token; state.csrfHeader = csrf.headerName;
      const capabilities = await api("/api/capabilities");
      state.defaultMode = capabilities.mode;
      updateMode();
      $("login-card").hidden = true; $("workspace").hidden = false; $("logout-button").hidden = false;
      $("password").value = "";
      setConnection("已连接分析服务", "online");
      $("backend-check").textContent = "认证接口已响应，正在检查服务健康";
      try {
        const health = await api("/actuator/health");
        $("backend-check").textContent = health.status === "UP" ? "接口可访问，健康检查通过" : "接口可访问，健康检查未通过";
      } catch (_) { $("backend-check").textContent = "接口可访问，健康检查暂不可用"; }
      try { await refreshHistory(); } catch (error) { $("run-error").textContent = message(error.message); }
      try {
        const settings = await api("/api/model-session");
        showModelSession(settings.configured ? settings.session : null);
      } catch (error) { $("model-error").textContent = message(error.message); }
    } catch (error) {
      state.auth = ""; state.csrf = "";
      setConnection("未连接", "error");
      $("login-error").textContent = message(error.message);
    } finally { $("login-button").disabled = false; }
  });
  // 清除服务器临时模型密钥；正在进行的 HTTP 请求无法撤回，下轮调用将被拒绝。
  $("logout-button").onclick = async () => {
    try { await api("/api/model-session/clear", { method: "POST" }); stopWatching(); location.reload(); }
    catch (error) { $("run-error").textContent = "未能确认服务器密钥清除，请恢复连接后重试；密钥仍受 30 分钟到期限制。" + message(error.message); }
  };
  $("execution-mode").onchange = updateMode;
  $("model-form").addEventListener("submit", async event => {
    event.preventDefault();
    $("model-save").disabled = true; $("model-error").textContent = "";
    try {
      const settings = await api("/api/model-session", { method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ provider: $("model-provider").value, workspaceId: $("model-workspace").value.trim(),
          model: $("model-name").value.trim(), apiKey: $("model-key").value.trim(), consent: $("model-consent").checked }) });
      $("model-key").value = "";
      showModelSession(settings);
      $("execution-mode").value = "multi"; updateMode();
      state.submission = null;
    } catch (error) { $("model-error").textContent = message(error.message); }
    finally { $("model-save").disabled = false; }
  });
  $("model-clear").onclick = async () => {
    try {
      await api("/api/model-session/clear", { method: "POST" });
      $("model-key").value = ""; $("model-error").textContent = ""; showModelSession(null); state.submission = null;
    } catch (error) { $("model-error").textContent = message(error.message); }
  };
  $("history-button").onclick = async () => {
    try { await refreshHistory(); } catch (error) { $("run-error").textContent = message(error.message); }
  };
  $("history").onchange = () => { if ($("history").value) watch($("history").value); };
  $("run-form").addEventListener("submit", async event => {
    event.preventDefault();
    const useModel = ["tools", "multi"].includes($("execution-mode").value);
    if (useModel && (!state.modelSession || Date.parse(state.modelSession.expiresAt) <= Date.now())) {
      $("run-error").textContent = message("MODEL_SESSION_EXPIRED"); return;
    }
    $("run-button").disabled = true; $("run-error").textContent = "";
    const body = JSON.stringify({ question: $("question").value.trim(), date: $("date").value,
      compareDate: $("compare-date").value || null, metric: $("metric").value,
      reviewRequired: $("review-required").checked,
      ...(useModel ? { modelSessionId: state.modelSession.id, executionMode: $("execution-mode").value === "multi" ? "LLM_MULTI_AGENT" : "LLM_TOOL_CALLING" } : {}) });
    // 连接中断后，相同表单重试复用幂等键，避免重复创建任务。
    if (!state.submission || state.submission.body !== body) state.submission = { body, key: crypto.randomUUID() };
    const generation = stopWatching();
    try {
      const run = await api("/api/runs", { method: "POST", headers: {
        "Content-Type": "application/json", "Idempotency-Key": state.submission.key }, body });
      if (generation !== state.generation) return;
      state.submission = null;
      await watch(run.id);
    } catch (error) {
      if (generation === state.generation) {
        if (error.message !== "NETWORK_ERROR") state.submission = null;
        $("run-error").textContent = message(error.message) + " 可重试；连接中断时也可刷新任务列表确认是否已创建。";
      }
    } finally { $("run-button").disabled = false; }
  });
})();
