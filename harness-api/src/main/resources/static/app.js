const state = {
  fixtures: [],
  cases: [],
  activeCase: null,
};

const $ = (id) => document.getElementById(id);

function clearNode(node) {
  while (node.firstChild) {
    node.removeChild(node.firstChild);
  }
}

function el(tag, options = {}, ...children) {
  const node = document.createElement(tag);
  if (options.className) {
    node.className = options.className;
  }
  if (options.id) {
    node.id = options.id;
  }
  if (options.title) {
    node.title = options.title;
  }
  if (options.type) {
    node.type = options.type;
  }
  if (options.styleWidth) {
    node.style.width = options.styleWidth;
  }
  if (options.onClick) {
    node.addEventListener("click", options.onClick);
  }
  for (const child of children) {
    if (child === null || child === undefined) {
      continue;
    }
    if (typeof child === "string" || typeof child === "number") {
      node.appendChild(document.createTextNode(String(child)));
    } else {
      node.appendChild(child);
    }
  }
  return node;
}

function showBanner(kind, message) {
  const banner = $("banner");
  banner.className = `banner banner-${kind}`;
  banner.textContent = message;
}

function clearBanner() {
  const banner = $("banner");
  banner.className = "banner hidden";
  banner.textContent = "";
}

async function apiJson(url, options = {}) {
  const headers = { ...(options.headers || {}) };
  if (options.body !== undefined) {
    headers["Content-Type"] = "application/json";
  }
  const resp = await fetch(url, { ...options, headers });
  let payload = null;
  try {
    payload = await resp.json();
  } catch (_) {
    payload = null;
  }
  if (!resp.ok) {
    const msg = payload && payload.message ? payload.message : `HTTP ${resp.status}`;
    const err = new Error(msg);
    err.status = resp.status;
    err.payload = payload;
    throw err;
  }
  return payload;
}

function statusBadgeClass(status) {
  switch (status) {
    case "ELIGIBLE":
    case "KNOWN":
    case "OK":
    case "APPROVED_PENDING_EXECUTION":
    case "CLOSED":
      return "badge-green";
    case "NEEDS_EVIDENCE":
    case "WAITING_EVIDENCE":
    case "UNKNOWN":
    case "DEGRADED":
    case "PENDING_DECISION":
      return "badge-amber";
    case "EXCEEDS_CAP_ESCALATE":
    case "ESCALATED":
    case "INVALID":
    case "FAILED":
      return "badge-red";
    case "INELIGIBLE":
    case "NO_OVERTIME_ANOMALY":
      return "badge-blue";
    case "NO_APPLICABLE_POLICY":
    case "OUT_OF_SCOPE":
    case "MULTI_FACTOR":
    case "UNDETERMINED":
      return "badge-purple";
    default:
      return "badge-muted";
  }
}

function shortHash(hash) {
  if (!hash) {
    return "—";
  }
  return hash.length > 14 ? `${hash.slice(0, 14)}…` : hash;
}

function formatAmount(value) {
  if (value === null || value === undefined) {
    return "—（不可直接执行）";
  }
  return `¥${Number(value).toFixed(2)}`;
}

function highlightEvidenceRow(evidenceId) {
  const rows = document.querySelectorAll("#ledger-tbody tr");
  for (const row of rows) {
    row.classList.remove("highlighted");
  }
  const target = document.getElementById(`ev-row-${evidenceId}`);
  if (target) {
    target.classList.add("highlighted");
    target.scrollIntoView({ behavior: "smooth", block: "center" });
  }
}

function createEvChip(evidenceId, isContra = false) {
  return el(
    "button",
    {
      type: "button",
      className: isContra ? "ev-chip ev-chip-contra" : "ev-chip",
      title: `定位证据 ${evidenceId}`,
      onClick: () => highlightEvidenceRow(evidenceId),
    },
    evidenceId
  );
}

function renderEvidenceValueText(ev) {
  if (!ev) {
    return "—";
  }
  if (ev.state === "KNOWN") {
    return String(ev.value);
  }
  if (ev.state === "UNKNOWN") {
    const detail = ev.detail ? ` — ${ev.detail}` : "";
    return `${ev.reasonCode || "UNKNOWN"}${detail}`;
  }
  if (ev.state === "INVALID") {
    const raw = ev.rawValue !== undefined ? `raw=${ev.rawValue}; ` : "";
    return `${raw}${ev.violation || "INVALID"}`;
  }
  return JSON.stringify(ev);
}

function renderKv(label, value, title) {
  return el(
    "div",
    { className: "kv-item" },
    el("span", { className: "kv-label" }, label),
    typeof value === "string" || typeof value === "number"
      ? el("span", { className: "kv-value", title }, String(value))
      : value
  );
}

function latestRunOf(c) {
  if (!c || !Array.isArray(c.runs) || c.runs.length === 0) {
    return null;
  }
  return c.runs[c.runs.length - 1];
}

function renderCaseHeader(c, run) {
  const container = $("case-header");
  clearNode(container);

  const traceLink = $("active-trace-link");
  if (run && run.traceId) {
    traceLink.href = `/api/observe/traces/${encodeURIComponent(run.traceId)}`;
    traceLink.textContent = `Trace ${shortHash(run.traceId)}`;
    traceLink.classList.remove("hidden");
  } else {
    traceLink.classList.add("hidden");
  }

  container.appendChild(renderKv("Case ID", c.caseId));
  container.appendChild(renderKv("Order ID", c.orderId));
  container.appendChild(renderKv("Complaint", c.complaintType));
  container.appendChild(
    renderKv("Case Status", el("span", { className: `badge ${statusBadgeClass(c.status)}` }, c.status))
  );
  container.appendChild(renderKv("Version", `v${c.version}`));
  if (run) {
    container.appendChild(renderKv("Latest Run", `${run.runId} (#${run.runNumber})`));
    container.appendChild(
      renderKv("Run Health", el("span", { className: `badge ${statusBadgeClass(run.runHealth)}` }, run.runHealth))
    );
    container.appendChild(renderKv("Routing", run.routingAction));
    container.appendChild(renderKv("Policy", `${run.policyVersion} (${shortHash(run.policyHash)})`, run.policyHash));
    container.appendChild(renderKv("Snapshot SHA-256", shortHash(run.snapshotHash), run.snapshotHash));
  }
  if (c.pendingOverrideAmount !== undefined && c.pendingOverrideAmount !== null) {
    container.appendChild(renderKv("Pending Override", formatAmount(c.pendingOverrideAmount)));
  }
  if (c.approvedPayoutAmount !== undefined && c.approvedPayoutAmount !== null) {
    const methodSuffix = c.approvedPayoutMethod ? ` (${c.approvedPayoutMethod})` : "";
    container.appendChild(renderKv("Approved Payout", `${formatAmount(c.approvedPayoutAmount)}${methodSuffix}`));
  }
  if (c.executionReceiptId) {
    container.appendChild(renderKv("Execution Receipt", c.executionReceiptId));
  }
}

function renderDiagnosis(run) {
  const badge = $("diagnosis-outcome-badge");
  const container = $("diagnosis-content");
  clearNode(container);

  if (!run || !run.diagnosis) {
    badge.className = "badge badge-muted";
    badge.textContent = "N/A";
    container.appendChild(el("p", { className: "hint-text" }, "暂无诊断结果。"));
    return;
  }

  const diag = run.diagnosis;
  badge.className = `badge ${statusBadgeClass(diag.outcome)}`;
  badge.textContent = diag.outcome;

  const summaryParts = [el("div", {}, diag.summary || "")];
  const overtimeText = diag.overtimeMinutes
    ? `超时: ${renderEvidenceValueText(diag.overtimeMinutes)} 分钟`
    : "超时: —";
  const metaLine = `${overtimeText} · 各环节正超额之和: ${diag.sumOfPositiveExcessMinutes ?? 0} 分钟`;
  summaryParts.push(el("div", { className: "hint-text" }, metaLine));
  if (diag.primaryCandidate) {
    summaryParts.push(el("div", { className: "hint-text" }, `首要候选因子: ${diag.primaryCandidate}`));
  }
  container.appendChild(el("div", { className: "summary-box" }, ...summaryParts));

  const factors = Array.isArray(diag.factors) ? diag.factors : [];
  const maxExcess = Math.max(10, ...factors.map((f) => f.excessMinutes || 0));
  const list = el("div", { className: "factor-list" });

  for (const f of factors) {
    const pct = Math.min(100, Math.round(((f.excessMinutes || 0) / maxExcess) * 100));
    const rawDelta = f.rawDeltaMinutes ? renderEvidenceValueText(f.rawDeltaMinutes) : "—";
    const card = el(
      "div",
      { className: f.exceedsThreshold ? "factor-card exceeds" : "factor-card" },
      el(
        "div",
        { className: "factor-top" },
        el("strong", {}, `${f.label} (${f.code})`),
        el(
          "span",
          { className: f.exceedsThreshold ? "badge badge-amber" : "badge badge-muted" },
          `原始差值: ${rawDelta}m · 正超额: +${f.excessMinutes}m`
        )
      ),
      el(
        "div",
        { className: "factor-bar-track" },
        el("div", { className: "factor-bar-fill", styleWidth: `${pct}%` })
      )
    );

    const chipsRow = el("div", {});
    for (const id of f.supportingEvidenceIds || []) {
      chipsRow.appendChild(createEvChip(id, false));
    }
    for (const id of f.contradictingEvidenceIds || []) {
      chipsRow.appendChild(createEvChip(id, true));
    }
    if (chipsRow.childNodes.length > 0) {
      card.appendChild(chipsRow);
    }

    if (Array.isArray(f.evidenceGaps) && f.evidenceGaps.length > 0) {
      const ul = el("ul", { className: "gap-list" });
      for (const gap of f.evidenceGaps) {
        ul.appendChild(el("li", {}, gap));
      }
      card.appendChild(ul);
    }
    list.appendChild(card);
  }

  container.appendChild(list);

  if (Array.isArray(diag.evidenceGaps) && diag.evidenceGaps.length > 0) {
    const ul = el("ul", { className: "gap-list" });
    for (const gap of diag.evidenceGaps) {
      ul.appendChild(el("li", {}, gap));
    }
    container.appendChild(el("div", {}, el("strong", { className: "hint-text" }, "整体证据缺口:"), ul));
  }
}

function buildHighlightedParagraph(text, spans) {
  const p = el("p", { className: "msg-text" });
  const raw = String(text || "").normalize("NFKC").trim();
  if (!spans || spans.length === 0) {
    p.textContent = raw;
    return p;
  }

  const valid = spans
    .filter((s) => Number.isInteger(s.start) && Number.isInteger(s.end) && s.start >= 0 && s.end > s.start && s.end <= raw.length)
    .sort((a, b) => a.start - b.start);

  let cursor = 0;
  for (const s of valid) {
    if (s.start < cursor) {
      continue;
    }
    if (s.start > cursor) {
      p.appendChild(document.createTextNode(raw.slice(cursor, s.start)));
    }
    p.appendChild(el("mark", { title: s.signalType || "signal" }, raw.slice(s.start, s.end)));
    cursor = s.end;
  }
  if (cursor < raw.length) {
    p.appendChild(document.createTextNode(raw.slice(cursor)));
  }
  return p;
}

function renderMessages(snapshot, run) {
  const container = $("messages-content");
  clearNode(container);

  const messages = snapshot && Array.isArray(snapshot.messages) ? snapshot.messages : [];
  if (messages.length === 0) {
    container.appendChild(el("p", { className: "hint-text" }, "该快照无 IM 对话消息。"));
    return;
  }

  const signalsByEv = new Map();
  const signals = run && Array.isArray(run.unconfirmedSignals) ? run.unconfirmedSignals : [];
  for (const sig of signals) {
    const evId = sig.source_evidence_id;
    if (!evId) {
      continue;
    }
    if (!signalsByEv.has(evId)) {
      signalsByEv.set(evId, []);
    }
    signalsByEv.get(evId).push({
      start: sig.start_offset,
      end: sig.end_offset,
      signalType: sig.signal_type,
    });
  }

  for (const msg of messages) {
    const card = el(
      "div",
      { className: "msg-card" },
      el(
        "div",
        { className: "msg-head" },
        createEvChip(msg.evidenceId),
        el("span", { className: "badge badge-blue" }, `metadata: ${msg.speakerRole}`),
        el("span", { className: "hint-inline mono" }, msg.sentAt || "")
      ),
      buildHighlightedParagraph(msg.text, signalsByEv.get(msg.evidenceId) || [])
    );
    container.appendChild(card);
  }
}

function renderLedger(snapshot) {
  const tbody = $("ledger-tbody");
  const meta = $("snapshot-meta");
  clearNode(tbody);

  if (!snapshot || !snapshot.items) {
    meta.textContent = "";
    return;
  }

  meta.textContent = `${snapshot.snapshotId} · mode=${snapshot.sourceMode} · asOf=${snapshot.asOf}`;

  const entries = Object.values(snapshot.items);
  for (const item of entries) {
    const ev = item.value || {};
    const sourceText = ev.state === "KNOWN" && ev.source ? ev.source : "—";
    const row = el(
      "tr",
      { id: `ev-row-${item.evidenceId}` },
      el("td", { className: "mono" }, item.evidenceId),
      el("td", { className: "mono" }, item.type),
      el("td", {}, el("span", { className: `badge ${statusBadgeClass(ev.state)}` }, ev.state || "UNKNOWN")),
      el("td", {}, renderEvidenceValueText(ev)),
      el("td", { className: "mono" }, sourceText)
    );
    tbody.appendChild(row);
  }
}

function renderAdjudication(run) {
  const badge = $("adjudication-status-badge");
  const container = $("adjudication-content");
  clearNode(container);

  if (!run || !run.adjudication) {
    badge.className = "badge badge-muted";
    badge.textContent = "N/A";
    return;
  }

  const adj = run.adjudication;
  badge.className = `badge ${statusBadgeClass(adj.status)}`;
  badge.textContent = adj.status;

  const grid = el(
    "div",
    { className: "detail-grid" },
    renderKv("可执行金额 (actionableAmount)", formatAmount(adj.actionableAmount)),
    renderKv("规则计算额 (calculatedAmount)", formatAmount(adj.calculatedAmount)),
    renderKv("命中规则", adj.matchedRuleId ? `${adj.matchedRuleId} (${adj.matchedRuleName || ""})` : "无命中规则"),
    renderKv("建议方式 / 需审批", `${adj.suggestedMethod || "—"} / ${adj.approvalRequired ? "是" : "否"}`)
  );
  container.appendChild(grid);

  if (adj.renderedPolicyText) {
    container.appendChild(el("div", { className: "summary-box" }, adj.renderedPolicyText));
  }

  if (Array.isArray(adj.approvalReasons) && adj.approvalReasons.length > 0) {
    const ul = el("ul", { className: "gap-list" });
    for (const r of adj.approvalReasons) {
      ul.appendChild(el("li", {}, r));
    }
    container.appendChild(el("div", {}, el("strong", { className: "hint-text" }, "审批/裁决说明:"), ul));
  }

  if (Array.isArray(adj.missingEvidence) && adj.missingEvidence.length > 0) {
    container.appendChild(
      el("p", { className: "hint-text" }, `缺失必要证据: ${adj.missingEvidence.join(", ")}`)
    );
  }
  if (Array.isArray(adj.staleEvidence) && adj.staleEvidence.length > 0) {
    container.appendChild(
      el("p", { className: "hint-text" }, `过期证据: ${adj.staleEvidence.join(", ")}`)
    );
  }

  container.appendChild(
    el(
      "p",
      { className: "hint-text" },
      "安全边界：SingleSourcePolicyEngine 方法签名不接受 ClaimedFact，LLM 或正则信号无法改变赔付资格或金额。"
    )
  );
}

function renderSignals(run) {
  const container = $("signals-content");
  clearNode(container);

  if (!run) {
    return;
  }

  if (run.untrustedTextDetected) {
    container.appendChild(
      el("div", { className: "banner banner-warn" }, "检测到疑似 Prompt Injection / 不可信文本，已按路由矩阵保守处理。")
    );
  }
  if (run.verificationFailed) {
    container.appendChild(
      el("div", { className: "banner banner-warn" }, "部分提取声明未通过确定性校验（已被丢弃并记入风险路由）。")
    );
  }
  if (Array.isArray(run.degradedReasons) && run.degradedReasons.length > 0) {
    container.appendChild(
      el("div", { className: "banner banner-warn" }, `降级原因: ${run.degradedReasons.join(" | ")}`)
    );
  }

  const signals = Array.isArray(run.unconfirmedSignals) ? run.unconfirmedSignals : [];
  if (signals.length === 0) {
    container.appendChild(
      el(
        "p",
        { className: "hint-text" },
        "无待核实非结构化信号（默认关闭 LLM 提取，或未在 IM 对话中提取到通过确定性校验的声明）。"
      )
    );
    return;
  }

  for (const sig of signals) {
    const card = el(
      "div",
      { className: "signal-card" },
      el(
        "div",
        { className: "msg-head" },
        el("span", { className: "badge badge-amber" }, sig.signal_type || "SIGNAL"),
        el("span", { className: "badge badge-muted" }, `speaker=${sig.speaker_role || "?"}`),
        el("span", { className: "badge badge-muted" }, `extractor=${sig.extractor || "?"}`),
        sig.source_evidence_id ? createEvChip(sig.source_evidence_id) : null
      ),
      el("div", {}, `引用原文: 「${sig.quote || ""}」`),
      sig.statement ? el("div", { className: "hint-text" }, `说明: ${sig.statement}`) : null
    );
    container.appendChild(card);
  }
}

function renderDraft(c, run) {
  const badge = $("draft-status-badge");
  const container = $("draft-content");
  clearNode(container);

  const draft = c.latestCustomerDraft || (run ? run.customerDraft : null);
  if (!draft) {
    badge.className = "badge badge-muted";
    badge.textContent = "无草稿";
    return;
  }

  badge.className = draft.executionCompleted ? "badge badge-green" : "badge badge-amber";
  badge.textContent = draft.executionCompleted ? "已完成回执口径" : "未完成外部执行";

  container.appendChild(
    el(
      "div",
      { className: "hint-text" },
      `模板: ${draft.templateId} · 可直接发送: ${draft.sendableDirectly ? "是" : "否"}`
    )
  );
  container.appendChild(el("div", { className: "draft-box" }, draft.message || ""));
  if (draft.disclaimer) {
    container.appendChild(el("p", { className: "hint-text" }, draft.disclaimer));
  }
}

function renderAudit(c) {
  const container = $("audit-content");
  clearNode(container);

  const runs = Array.isArray(c.runs) ? c.runs : [];
  container.appendChild(el("p", { className: "hint-text" }, `Assessment Runs (${runs.length})`));
  for (const r of runs) {
    container.appendChild(
      el(
        "div",
        { className: "audit-item" },
        el("div", { className: "mono" }, `#${r.runNumber} ${r.runId} · ${r.runHealth} · ${r.routingAction}`),
        el("div", { className: "hint-text" }, `snapshot=${r.snapshotId} · executedAt=${r.executedAt}`)
      )
    );
  }

  const decisions = Array.isArray(c.decisions) ? c.decisions : [];
  container.appendChild(el("p", { className: "hint-text" }, `Human Decisions (${decisions.length})`));
  if (decisions.length === 0) {
    container.appendChild(el("p", { className: "hint-text" }, "尚未提交人工决策。"));
    return;
  }

  for (const d of decisions) {
    const details = [];
    if (d.finalAmount !== undefined && d.finalAmount !== null) {
      details.push(`amount=${formatAmount(d.finalAmount)}`);
    }
    if (d.finalMethod) {
      details.push(`method=${d.finalMethod}`);
    }
    if (d.overrideReasonCode) {
      details.push(`override=${d.overrideReasonCode}`);
    }
    if (d.exceptionReason) {
      details.push(`exception=${d.exceptionReason}`);
    }
    if (d.externalReceiptId) {
      details.push(`receipt=${d.externalReceiptId}`);
    }
    if (d.finalLiability) {
      details.push(`liability=${d.finalLiability}`);
    }
    if (d.note) {
      details.push(`note=${d.note}`);
    }

    container.appendChild(
      el(
        "div",
        { className: "audit-item decision" },
        el("div", { className: "mono" }, `${d.action} · ${d.operatorId} (${d.operatorIdentityMode})`),
        el("div", { className: "hint-text" }, `runId=${d.runId} · at=${d.decidedAt}`),
        details.length > 0 ? el("div", {}, details.join(" | ")) : null
      )
    );
  }
}

function updateDecisionHint(c) {
  const hint = $("decision-hint");
  const allowedByStatus = {
    WAITING_EVIDENCE: "可执行: ATTEST / ESCALATE / CLOSE",
    PENDING_DECISION: "可执行: ACCEPT / OVERRIDE / REQUEST_EVIDENCE / ESCALATE / CLOSE / ATTEST",
    ESCALATED: "可执行: APPROVE / RETURN_TO_REVIEW / REJECT_CLOSE / REQUEST_EVIDENCE / ATTEST",
    APPROVED_PENDING_EXECUTION: "可执行: RECORD_EXECUTION_RECEIPT",
    CLOSED: "可执行: REOPEN",
  };
  hint.textContent = allowedByStatus[c.status] || "";
}

function renderActiveCase(c) {
  state.activeCase = c;
  if (!c) {
    $("empty-state").classList.remove("hidden");
    $("workbench").classList.add("hidden");
    return;
  }

  $("empty-state").classList.add("hidden");
  $("workbench").classList.remove("hidden");

  const run = latestRunOf(c);
  renderCaseHeader(c, run);
  renderDiagnosis(run);
  renderMessages(c.latestSnapshot, run);
  renderLedger(c.latestSnapshot);
  renderAdjudication(run);
  renderSignals(run);
  renderDraft(c, run);
  updateDecisionHint(c);
  renderAudit(c);
}

function renderCaseSelector() {
  const select = $("case-select");
  clearNode(select);

  if (state.cases.length === 0) {
    select.appendChild(el("option", {}, "— 暂无 Case，请先创建 —"));
    return;
  }

  for (const c of state.cases) {
    const opt = el(
      "option",
      {},
      `${c.caseId} | ${c.orderId} | ${c.complaintType} | ${c.status} (v${c.version})`
    );
    opt.value = c.caseId;
    if (state.activeCase && state.activeCase.caseId === c.caseId) {
      opt.selected = true;
    }
    select.appendChild(opt);
  }
}

async function loadFixtures() {
  const group = $("fixture-group");
  const select = $("fixture-select");
  const note = $("profile-note");

  try {
    const resp = await apiJson("/api/demo/fixtures");
    const fixtures = Array.isArray(resp.data) ? resp.data : [];
    state.fixtures = fixtures;
    if (fixtures.length === 0) {
      group.classList.add("hidden");
      note.textContent = "未发现 Demo 样本，可手动输入订单号。";
      return;
    }

    clearNode(select);
    for (const f of fixtures) {
      const opt = el(
        "option",
        {},
        `${f.orderId} [${f.category}] — ${f.title} (${f.defaultComplaintType})`
      );
      opt.value = f.orderId;
      select.appendChild(opt);
    }
    group.classList.remove("hidden");
    note.textContent = `已加载 ${fixtures.length} 条 demo 契约样本。`;
    applySelectedFixture();
  } catch (err) {
    group.classList.add("hidden");
    if (err.status === 404) {
      note.textContent = "未启用 demo Profile（/api/demo/fixtures 为 404）；请手动输入订单号或使用 --spring.profiles.active=demo 启动。";
    } else {
      note.textContent = `加载 Demo 样本失败: ${err.message}`;
    }
  }
}

function applySelectedFixture() {
  const orderId = $("fixture-select").value;
  const fixture = state.fixtures.find((f) => f.orderId === orderId);
  if (!fixture) {
    return;
  }
  $("order-id-input").value = fixture.orderId;
  if (fixture.defaultComplaintType) {
    $("complaint-type-select").value = fixture.defaultComplaintType;
  }
}

async function refreshCases(selectCaseId = null) {
  try {
    const resp = await apiJson("/api/cases?limit=50");
    state.cases = Array.isArray(resp.data) ? resp.data : [];
    renderCaseSelector();
    if (selectCaseId) {
      const found = state.cases.find((c) => c.caseId === selectCaseId);
      if (found) {
        renderActiveCase(found);
        $("case-select").value = found.caseId;
      }
    } else if (!state.activeCase && state.cases.length > 0) {
      renderActiveCase(state.cases[0]);
      $("case-select").value = state.cases[0].caseId;
    }
  } catch (err) {
    showBanner("error", `刷新 Case 列表失败: ${err.message}`);
  }
}

async function onCreateCase() {
  clearBanner();
  const orderId = $("order-id-input").value.trim();
  const complaintType = $("complaint-type-select").value;
  const idemKey = $("idempotency-key-input").value.trim();

  if (!orderId) {
    showBanner("error", "请输入 order_id");
    return;
  }

  const headers = {};
  if (idemKey) {
    headers["Idempotency-Key"] = idemKey;
  }

  try {
    const resp = await apiJson("/api/cases", {
      method: "POST",
      headers,
      body: JSON.stringify({ order_id: orderId, complaint_type: complaintType }),
    });
    const created = resp.data;
    showBanner("ok", `已创建/评估 Case ${created.caseId}（status=${created.status}, traceId=${resp.traceId || "—"}）`);
    renderActiveCase(created);
    await refreshCases(created.caseId);
  } catch (err) {
    showBanner("error", `创建 Case 失败（HTTP ${err.status || "?"}）: ${err.message}`);
  }
}

async function onAttestSubmit(event) {
  event.preventDefault();
  clearBanner();
  const c = state.activeCase;
  if (!c) {
    return;
  }

  const payload = {
    evidence_type: $("attest-type").value,
    value: $("attest-value").value,
    operator_id: $("attest-operator").value.trim(),
    expected_case_version: c.version,
  };

  try {
    const resp = await apiJson(`/api/cases/${encodeURIComponent(c.caseId)}/attestations`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
    const updated = resp.data;
    showBanner("ok", `举证成功，已生成新 Run 并更新 Case 至 v${updated.version}（status=${updated.status}）`);
    renderActiveCase(updated);
    await refreshCases(updated.caseId);
  } catch (err) {
    showBanner("error", `举证失败（HTTP ${err.status || "?"}）: ${err.message}`);
  }
}

async function onDecisionSubmit(event) {
  event.preventDefault();
  clearBanner();
  const c = state.activeCase;
  const run = latestRunOf(c);
  if (!c || !run) {
    return;
  }

  const payload = {
    run_id: run.runId,
    expected_case_version: c.version,
    action: $("decision-action").value,
    operator_id: $("decision-operator").value.trim(),
  };

  const amountStr = $("decision-amount").value.trim();
  if (amountStr !== "") {
    payload.final_amount = Number(amountStr);
  }
  const methodStr = $("decision-method").value.trim();
  if (methodStr) {
    payload.final_method = methodStr;
  }
  const overrideCode = $("decision-override-code").value.trim();
  if (overrideCode) {
    payload.override_reason_code = overrideCode;
  }
  const exceptionReason = $("decision-exception-reason").value.trim();
  if (exceptionReason) {
    payload.exception_reason = exceptionReason;
  }
  const receiptId = $("decision-receipt-id").value.trim();
  if (receiptId) {
    payload.external_receipt_id = receiptId;
  }
  const liability = $("decision-liability").value.trim();
  if (liability) {
    payload.final_liability = liability;
  }
  const note = $("decision-note").value.trim();
  if (note) {
    payload.note = note;
  }

  try {
    const resp = await apiJson(`/api/cases/${encodeURIComponent(c.caseId)}/decisions`, {
      method: "POST",
      body: JSON.stringify(payload),
    });
    const updated = resp.data;
    showBanner("ok", `决策 ${payload.action} 已提交，Case 更新至 v${updated.version}（status=${updated.status}）`);
    renderActiveCase(updated);
    await refreshCases(updated.caseId);
  } catch (err) {
    showBanner("error", `提交决策失败（HTTP ${err.status || "?"}）: ${err.message}`);
  }
}

function wireEvents() {
  $("fixture-select").addEventListener("change", applySelectedFixture);
  $("create-case-btn").addEventListener("click", onCreateCase);
  $("refresh-cases-btn").addEventListener("click", () => refreshCases(state.activeCase ? state.activeCase.caseId : null));
  $("case-select").addEventListener("change", async (e) => {
    const caseId = e.target.value;
    if (!caseId) {
      return;
    }
    try {
      const resp = await apiJson(`/api/cases/${encodeURIComponent(caseId)}`);
      renderActiveCase(resp.data);
    } catch (err) {
      showBanner("error", `读取 Case 失败: ${err.message}`);
    }
  });
  $("attest-form").addEventListener("submit", onAttestSubmit);
  $("decision-form").addEventListener("submit", onDecisionSubmit);
}

async function init() {
  wireEvents();
  await loadFixtures();
  await refreshCases();
}

init();
