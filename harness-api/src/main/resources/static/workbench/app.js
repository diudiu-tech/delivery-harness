"use strict";
const $ = (id) => document.getElementById(id);
const state = {
  view: "cases",
  token: "",
  session: null,
  cases: [],
  audit: [],
  auditCursor: null,
  auditGeneration: 0,
  caseGeneration: 0,
  caseRequests: new AbortController(),
  detailGeneration: 0,
  editorGeneration: 0,
  evalGeneration: 0,
  current: null,
  execution: null,
  policy: null,
  run: null,
  cursor: null,
  poll: null,
  evalPoll: null,
  generation: 0,
  requests: new AbortController(),
};
const titles = {
  cases: ["案件工作台", "查看证据，复核建议，记录处理结果。"],
  policies: ["规则与政策", "明确适用条件，让每个决定可解释、可复现。"],
  knowledge: ["知识资料", "检索已发布资料，核对具体版本与引用。"],
  quality: ["评测中心", "用冻结的数据与配置验证质量改进。"],
  operations: ["运行与反馈", "定位失败原因，把纠正反馈转成持续改进。"],
  settings: ["运行配置", "管理实际执行配置与业务连接。"],
};
const labels = {
  OPEN: "待处理",
  ANALYZING: "分析中",
  QUEUED: "排队中",
  RUNNING: "执行中",
  SUCCESS: "完整完成",
  DEGRADED: "部分可用",
  FAILED: "失败",
  TIMED_OUT: "超时",
  CANCELLED: "已取消",
  PENDING_REVIEW: "待复核",
  IN_REVIEW: "复核中",
  NEEDS_EVIDENCE: "待补证",
  APPROVED: "审核通过",
  REJECTED: "已驳回",
  CLOSED: "已结案",
  CANDIDATE: "待评测",
  UPLOADED: "待核验",
  NOT_EXECUTED: "尚未执行补偿",
  COMPLETED: "评测完成",
  PUBLISHED: "已发布",
  DRAFT: "草稿",
  WITHDRAWN: "已撤回",
  DETERMINED: "已形成决定",
  INSUFFICIENT_EVIDENCE: "证据不足",
  OUT_OF_SCOPE: "政策未覆盖",
  POLICY_CONFLICT: "规则冲突",
  UNKNOWN: "未知",
  ON_TIME: "准时",
  LATE: "超时",
  OVERTIME: "超时投诉",
  DAMAGED: "餐品损坏",
  WRONG_ORDER: "错单",
  MISSING_ITEM: "缺件",
  PENDING: "待核验",
  VERIFIED: "已核验",
  BLOCKED: "不可使用",
  NOT_REQUIRED: "无需复核",
  NOT_REQUESTED: "尚未执行补偿",
};
// Switching identities must discard data and in-flight requests from the prior session.
function disconnect() {
  state.requests.abort();
  state.caseRequests.abort();
  state.caseRequests = new AbortController();
  state.caseGeneration += 1;
  state.detailGeneration += 1;
  state.auditGeneration += 1;
  state.editorGeneration += 1;
  state.evalGeneration += 1;
  state.requests = new AbortController();
  state.generation += 1;
  clearTimeout(state.poll);
  clearTimeout(state.evalPoll);
  state.token = "";
  state.view = "cases";
  [
    "case-query",
    "case-state",
    "case-risk",
    "case-complaint",
    "case-owner",
    "case-station",
    "knowledge-query",
  ].forEach((id) => ($(id).value = ""));
  clearTimeout(toast.timer);
  $("toast").textContent = "";
  $("toast").hidden = true;
  state.session = null;
  document.querySelectorAll(".nav").forEach((item) => (item.hidden = true));
  state.cases = [];
  state.audit = [];
  state.auditCursor = null;
  clearTimeout(loadCases.filterTimer);
  $("editor").close();
  $("editor-fields").replaceChildren();
  $("editor-preview-result").replaceChildren();
  $("editor-error").textContent = "";
  $("editor-error").hidden = true;
  $("editor-submit").disabled = false;
  $("review-reason").value = "";
  $("workspace").inert = true;
  state.current =
    state.execution =
    state.policy =
    state.run =
    state.cursor =
      null;
  [
    "case-list",
    "facts",
    "attachments",
    "decision",
    "review-info",
    "case-history",
    "policy-list",
    "policy-rules",
    "policy-validation",
    "knowledge-list",
    "knowledge-results",
    "eval-list",
    "eval-summary",
    "eval-results",
    "metrics",
    "execution-list",
    "feedback-list",
    "audit-list",
    "runtime-list",
    "integration-info",
  ].forEach((id) => $(id).replaceChildren());
  ["case-detail", "policy-detail", "eval-detail", "execution-progress"].forEach(
    (id) => ($(id).hidden = true),
  );
  $("case-list-area").hidden = false;
  $("cases-more").hidden = true;
  $("audit-more").hidden = true;
  $("case-count").textContent = "";
  $("audit-count").textContent = "";
  $("case-title").textContent = "";
  $("case-meta").textContent = "";
  $("case-badges").replaceChildren();
  $("execution-state").textContent = "";
  $("connection-warning").textContent = "请输入访问凭证后连接工作台。";
  $("connection-warning").hidden = false;
  $("identity").textContent = "尚未认证";
  $("mode").textContent = "需连接";
}

function node(tag, text, cls) {
  const n = document.createElement(tag);
  if (text !== undefined) n.textContent = String(text);
  if (cls) n.className = cls;
  return n;
}
function empty(container, text = "暂无记录。") {
  container.replaceChildren(node("div", text, "empty"));
}
function toast(text, error = false) {
  $("toast").textContent = text;
  $("toast").style.background = error ? "#8a2a35" : "#193c35";
  $("toast").hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => ($("toast").hidden = true), 6500);
}
function reportError(error) {
  if (error.name !== "AbortError") toast(error.message, true);
}
function principal() {
  return state.session?.principal || {};
}
function hasRole(...roles) {
  return roles.some((role) => (principal().roles || []).includes(role));
}
function sameIdentity(generation) {
  return generation === state.generation;
}
function unwrap(value) {
  return value?.data && !value.id && value.code !== undefined
    ? value.data
    : value;
}
function entity(value) {
  return value?.data && value.id
    ? {
        ...value.data,
        id: value.id,
        version: value.version,
        created_at: value.createdAt,
        updated_at: value.updatedAt,
        _entity: value,
      }
    : value;
}
function items(value) {
  const v = unwrap(value);
  return (Array.isArray(v) ? v : v?.items || v?.results || []).map(entity);
}
async function api(path, options = {}) {
  const generation = state.generation;
  const { signal, ...requestOptions } = options;
  const requestAbort = new AbortController();
  const sources = [state.requests.signal, signal].filter(Boolean);
  const cancel = () => requestAbort.abort();
  sources.forEach((source) => {
    if (source.aborted) cancel();
    else source.addEventListener("abort", cancel, { once: true });
  });
  const headers = {
    Accept: "application/json",
    ...(options.body ? { "Content-Type": "application/json" } : {}),
    ...(state.token ? { Authorization: `Bearer ${state.token}` } : {}),
  };
  try {
    const response = await fetch(`/api/v2${path}`, {
      ...requestOptions,
      signal: requestAbort.signal,
      credentials: "omit",
      headers: { ...headers, ...options.headers },
    });
    const text = await response.text();
    if (!sameIdentity(generation))
      throw new DOMException("请求已更新。", "AbortError");
    if (response.status === 401) {
      disconnect();
      throw new Error("访问凭证失效，请重新连接。");
    }
    let data;
    try {
      data = text ? JSON.parse(text) : null;
    } catch {
      throw new Error("服务返回了无法读取的响应。");
    }
    if (!response.ok)
      throw new Error(
        data?.error?.message ||
          data?.message ||
          `请求失败 (${response.status})`,
      );
    return unwrap(data);
  } finally {
    sources.forEach((source) => source.removeEventListener("abort", cancel));
  }
}

const post = (path, body, options = {}) =>
  api(path, { ...options, method: "POST", body: JSON.stringify(body || {}) });
function badge(value) {
  const n = node("span", labels[value] || value || "未知", "badge");
  if (
    [
      "DEGRADED",
      "NEEDS_EVIDENCE",
      "INSUFFICIENT_EVIDENCE",
      "UNKNOWN",
      "PENDING",
      "DRAFT",
      "OUT_OF_SCOPE",
    ].includes(value)
  )
    n.classList.add("warn");
  else if (
    [
      "FAILED",
      "REJECTED",
      "POLICY_CONFLICT",
      "INVALID",
      "BLOCKED",
      "TIMED_OUT",
    ].includes(value)
  )
    n.classList.add("error");
  return n;
}
function date(value) {
  if (!value) return "—";
  const d = new Date(value);
  return Number.isNaN(d.valueOf())
    ? String(value)
    : d.toLocaleString("zh-CN", { timeZone: "Asia/Shanghai", hour12: false });
}
function row(container, label, value) {
  const n = node("div", undefined, "field-row");
  n.append(
    node("span", label, "field-label"),
    node(
      "span",
      value === null || value === undefined ? "未知" : value,
      "field-value",
    ),
  );
  container.append(n);
}
function inspect(container, title, value) {
  const n = node("details"),
    s = node("summary", title);
  n.append(s, node("pre", JSON.stringify(value, null, 2)));
  container.append(n);
}
function button(label, action, cls = "secondary") {
  const n = node("button", label, cls);
  n.type = "button";
  n.addEventListener("click", () =>
    Promise.resolve()
      .then(action)
      .catch((e) => reportError(e)),
  );
  return n;
}
function permittedButton(label, action, roles, blocked = "") {
  const control = button(label, action);
  control.hidden = !hasRole(...roles);
  control.disabled = Boolean(blocked);
  control.title = blocked;
  return control;
}
function publicationBlock(record) {
  const user = principal().user_id || principal().userId;
  return user === record.created_by ? "请由另一位有权限的用户审核或发布。" : "";
}
function table(container, records, columns, actions) {
  if (!records.length) return empty(container);
  const t = node("table"),
    head = node("thead"),
    hr = node("tr");
  columns.forEach((c) => hr.append(node("th", c.title)));
  if (actions) hr.append(node("th", "操作"));
  head.append(hr);
  const body = node("tbody");
  records.forEach((record) => {
    const tr = node("tr");
    columns.forEach((c) => {
      const td = node("td");
      const v = c.value(record);
      if (v instanceof Node) td.append(v);
      else td.textContent = v ?? "—";
      tr.append(td);
    });
    if (actions) {
      const td = node("td");
      actions(record).forEach((b) => td.append(b));
      tr.append(td);
    }
    body.append(tr);
  });
  t.append(head, body);
  container.replaceChildren(t);
}
function form(title, fields, submit, submitLabel = "保存", preview) {
  const editorGeneration = ++state.editorGeneration;
  const generation = state.generation;
  $("editor-submit").disabled = false;
  $("editor-title").textContent = title;
  $("editor-preview-result").replaceChildren();
  $("editor-preview").hidden = !preview;
  $("editor-preview").onclick = async () => {
    try {
      const result = await preview(
        Object.fromEntries(new FormData($("editor-form"))),
      );
      if (
        editorGeneration !== state.editorGeneration ||
        !sameIdentity(generation)
      )
        return;
      $("editor-preview-result").replaceChildren();
      inspect($("editor-preview-result"), "切片与导入诊断", result);
    } catch (e) {
      if (
        editorGeneration !== state.editorGeneration ||
        !sameIdentity(generation)
      )
        return;
      $("editor-error").textContent = e.message;
      $("editor-error").hidden = false;
    }
  };
  $("editor-submit").textContent = submitLabel;
  $("editor-error").hidden = true;
  $("editor-fields").replaceChildren();
  fields.forEach((f) => {
    const label = node("label", f.label);
    let input;
    if (f.options) {
      input = node("select");
      f.options.forEach((o) => {
        const option = node("option", typeof o === "object" ? o.label : o);
        option.value = typeof o === "object" ? o.value : o;
        input.append(option);
      });
    } else input = node(f.multiline ? "textarea" : "input");
    input.name = f.name;
    if (f.type) input.type = f.type;
    if (f.min !== undefined) input.min = f.min;
    if (f.max !== undefined) input.max = f.max;
    if (f.step !== undefined) input.step = f.step;
    if (f.multiline) input.rows = f.rows || 4;
    if (f.required) input.required = true;
    if (f.readOnly) input.readOnly = true;
    if (f.value !== undefined) input.value = f.value;
    if (f.placeholder) input.placeholder = f.placeholder;
    if (!f.options && f.type !== "number") {
      input.maxLength =
        f.maxLength ||
        (f.name === "reason" || f.name === "authorization_basis"
          ? 2000
          : f.multiline
            ? 2000
            : 200);
    }
    label.append(input);
    if (f.hint) label.append(node("p", f.hint, "hint"));
    $("editor-fields").append(label);
  });
  $("editor-form").onsubmit = async (event) => {
    event.preventDefault();
    $("editor-error").hidden = true;
    $("editor-submit").disabled = true;
    try {
      await submit(
        Object.fromEntries(new FormData($("editor-form"))),
        generation,
      );
      if (
        editorGeneration === state.editorGeneration &&
        sameIdentity(generation)
      )
        $("editor").close();
    } catch (e) {
      if (
        editorGeneration !== state.editorGeneration ||
        !sameIdentity(generation)
      )
        return;
      $("editor-error").textContent = e.message;
      $("editor-error").hidden = false;
    } finally {
      if (editorGeneration === state.editorGeneration)
        $("editor-submit").disabled = false;
    }
  };
  $("editor").showModal();
}
async function session() {
  const generation = state.generation;
  try {
    const connected = await api("/session");
    if (!sameIdentity(generation)) return;
    state.session = connected;
    $("workspace").inert = false;
    const p = state.session.principal || state.session;
    const mode = state.session.mode || "demo";
    $("mode").textContent = mode === "demo" ? "合成演示数据" : "受控试点";
    $("identity").textContent =
      `${p.userId || p.user_id || "用户"} · ${p.tenantId || p.tenant_id || "demo"}`;
    $("connection-warning").hidden = mode !== "demo";
    $("connection-warning").textContent =
      "当前使用合成订单与示例政策，不对应真实订单或赔付。";
    const roles = p.roles || [];
    const actions = {
      "new-case-open": ["AGENT", "OPERATOR"],
      "analyze-abnormal": ["AGENT", "OPERATOR"],
      "analyze-compensation": ["AGENT", "OPERATOR"],
      "cancel-execution": ["AGENT", "OPERATOR"],
      "evidence-open": ["AGENT", "OPERATOR"],
      "review-claim": ["REVIEWER", "SUPERVISOR"],
      "review-approve": ["REVIEWER", "SUPERVISOR"],
      "review-reject": ["REVIEWER", "SUPERVISOR"],
      "review-return": ["REVIEWER", "SUPERVISOR"],
      "review-reassign": ["REVIEWER", "SUPERVISOR"],
      "exception-open": ["SUPERVISOR"],
      "case-close": ["OPERATOR", "SUPERVISOR"],
      "case-export": ["OPERATOR", "SUPERVISOR", "AUDITOR"],
      "policy-open": ["POLICY_EDITOR", "ADMIN"],
      "policy-validate": [
        "POLICY_EDITOR",
        "POLICY_PUBLISHER",
        "QUALITY",
        "ADMIN",
      ],
      "policy-publish": ["POLICY_PUBLISHER", "ADMIN"],
      "policy-withdraw": ["POLICY_PUBLISHER", "ADMIN"],
      "knowledge-open": ["POLICY_EDITOR", "QUALITY", "ADMIN"],
      "runtime-open": ["QUALITY", "ADMIN"],
      "runtime-route-open": ["QUALITY", "ADMIN"],
      "dataset-open": ["QUALITY", "ADMIN"],
      "eval-open": ["QUALITY", "ADMIN"],
    };
    Object.entries(actions).forEach(([id, allowed]) => {
      $(id).hidden = !allowed.some((r) => roles.includes(r));
    });
    const required = {
      cases: ["AGENT", "OPERATOR", "REVIEWER", "SUPERVISOR", "AUDITOR"],
      policies: ["POLICY_EDITOR", "POLICY_PUBLISHER", "ADMIN"],
      quality: ["QUALITY", "ADMIN"],
      settings: ["ADMIN", "QUALITY"],
      operations: ["OPERATOR", "AUDITOR", "QUALITY", "ADMIN"],
    };
    document.querySelectorAll(".nav").forEach((n) => {
      n.hidden =
        required[n.dataset.view] &&
        !required[n.dataset.view].some((r) => roles.includes(r));
    });
    if (document.querySelector(`.nav[data-view="${state.view}"]`).hidden) {
      state.view =
        document.querySelector(".nav:not([hidden])")?.dataset.view ||
        "knowledge";
    }
  } catch (e) {
    if (!sameIdentity(generation)) throw e;
    $("identity").textContent = "尚未认证";
    $("mode").textContent = "需连接";
    $("connection-warning").hidden = false;
    $("connection-warning").textContent = e.message;
    throw e;
  }
}
async function view(name) {
  state.view = name;
  clearTimeout(state.poll);
  clearTimeout(state.evalPoll);
  document
    .querySelectorAll(".view")
    .forEach((n) => (n.hidden = n.id !== `view-${name}`));
  document
    .querySelectorAll(".nav")
    .forEach((n) => n.classList.toggle("active", n.dataset.view === name));
  [$("page-title").textContent, $("page-description").textContent] =
    titles[name];
  const loaders = {
    cases: loadCases,
    policies: loadPolicies,
    knowledge: loadKnowledge,
    quality: loadQuality,
    operations: loadOperations,
    settings: loadSettings,
  };
  try {
    await loaders[name]();
  } catch (e) {
    reportError(e);
  }
}
function caseFilters() {
  return {
    status: $("case-state").value,
    order_id: $("case-query").value.trim(),
    risk_level: $("case-risk").value,
    complaint_type: $("case-complaint").value,
    assignee: $("case-owner").value.trim(),
    station_id: $("case-station").value.trim(),
  };
}
function refreshCaseFilters() {
  clearTimeout(loadCases.filterTimer);
  state.caseRequests.abort();
  state.caseRequests = new AbortController();
  state.caseGeneration += 1;
  state.cursor = null;
  state.cases = [];
  $("cases-more").hidden = true;
  empty($("case-list"), "正在按条件查询案件……");
  $("case-count").textContent = "";
  loadCases.filterTimer = setTimeout(() => loadCases().catch(reportError), 250);
}
async function loadCases(more = false) {
  if (more && !state.cursor) return;
  const generation = state.generation;
  const caseGeneration = ++state.caseGeneration;
  if (!more) {
    state.caseRequests.abort();
    state.caseRequests = new AbortController();
    state.cursor = null;
  }
  const q = new URLSearchParams({ limit: "20" });
  Object.entries(caseFilters()).forEach(([key, value]) => {
    if (value) q.set(key, value);
  });
  if (more) q.set("cursor", state.cursor);
  $("cases-more").disabled = true;
  try {
    const response = await api(`/cases?${q}`, {
      signal: state.caseRequests.signal,
    });
    if (!sameIdentity(generation) || caseGeneration !== state.caseGeneration)
      return;
    const next = items(response);
    state.cases = more ? [...state.cases, ...next] : next;
    state.cursor = response?.cursor || null;
    renderCases();
    $("cases-more").hidden = !state.cursor;
    if (state.current) await openCase(state.current.id);
  } finally {
    if (sameIdentity(generation) && caseGeneration === state.caseGeneration)
      $("cases-more").disabled = false;
  }
}
function renderCases() {
  table(
    $("case-list"),
    state.cases,
    [
      { title: "订单 / 案件", value: (c) => c.order_id || c.id },
      {
        title: "投诉类型",
        value: (c) => labels[c.complaint_type] || c.complaint_type,
      },
      { title: "状态", value: (c) => badge(c.status || c.review_status) },
      {
        title: "风险",
        value: (c) => (c.risk_level === "HIGH" ? "高风险" : "普通"),
      },
      { title: "负责人", value: (c) => c.assignee || "未领取" },
      { title: "站点", value: (c) => c.station_id },
      { title: "更新于", value: (c) => date(c.updated_at) },
    ],
    (c) => [button("查看", () => openCase(c.id))],
  );
  $("case-count").textContent =
    `已加载 ${state.cases.length} 件，筛选在服务端完成`;
}

function independentReviewBlock(c, decision) {
  const user = principal().user_id || principal().userId;
  const initiator = c.created_by;
  const decisionInitiator = decision?.created_by || c.decision?.created_by;
  if (!initiator || !decisionInitiator)
    return "发起人信息尚未核实，请重新分析后安排独立审核。";
  if (user === initiator || user === decisionInitiator)
    return "您是案件或决定的发起人，请由另一位审核人领取并审批。";
  return "";
}
function renderReviewPermissions(c, decision) {
  row($("review-info"), "案件发起人", c.created_by);
  row(
    $("review-info"),
    "决定发起人",
    decision?.created_by || c.decision?.created_by,
  );
  const blocked = independentReviewBlock(c, decision);
  const unavailable =
    blocked ||
    (!c.review_id || !c.current_decision_id ? "请先生成需要复核的决定。" : "");
  const user = principal().user_id || principal().userId;
  const claimBlock =
    unavailable ||
    (c.review?.status !== "PENDING" ? "当前复核已被领取或完成。" : "");
  const approveBlock =
    unavailable ||
    (c.risk_level === "HIGH" && !hasRole("SUPERVISOR")
      ? "高风险决定需要独立主管审批。"
      : "") ||
    (c.review?.status !== "IN_REVIEW" || c.review?.owner !== user
      ? "请先领取本案复核，再核对证据并填写理由。"
      : "");
  $("review-claim").disabled = Boolean(claimBlock);
  $("review-approve").disabled = Boolean(approveBlock);
  $("review-claim").title = claimBlock;
  $("review-approve").title = approveBlock;
  if (unavailable) $("review-info").append(node("p", unavailable, "notice"));
}
async function openCase(id) {
  clearTimeout(state.poll);
  const generation = state.generation;
  const detailGeneration = ++state.detailGeneration;
  const raw = await api(`/cases/${encodeURIComponent(id)}`);
  if (!sameIdentity(generation) || detailGeneration !== state.detailGeneration)
    return;
  state.current = entity(raw);
  $("review-reason").value = "";
  const c = state.current;
  $("case-list-area").hidden = true;
  $("case-detail").hidden = false;
  $("case-title").textContent = c.title || c.order_id || c.id;
  $("case-meta").textContent =
    `案件 ${c.id} · ${labels[c.complaint_type] || c.complaint_type || "异常核查"} · 版本 ${c.version}`;
  $("case-badges").replaceChildren(
    badge(c.status || "OPEN"),
    badge(c.review_status || "PENDING"),
  );
  $("review-info").replaceChildren();
  row($("review-info"), "负责人", c.assignee || c.owner || "未领取");
  row(
    $("review-info"),
    "补偿执行",
    "本工作台只提供建议；请通过既有业务流程处理",
  );
  const evidence = c.evidence_snapshot || c.order_snapshot || c.evidence || {};
  renderFacts(evidence);
  let execution = c.execution || c.latest_execution;
  let decision = c.decision || c.latest_decision;
  if (c.current_execution_id || c.execution_id || c.latest_execution_id) {
    try {
      execution = entity(
        await api(
          `/executions/${c.current_execution_id || c.execution_id || c.latest_execution_id}`,
        ),
      );
      decision =
        c.decision?.execution_id === (c.current_execution_id || c.execution_id)
          ? { ...(execution.output || {}), ...c.decision }
          : c.decision || execution.decision || execution.output;
    } catch (e) {
      reportError(e);
    }
  }
  if (!sameIdentity(generation) || detailGeneration !== state.detailGeneration)
    return;
  state.execution = execution;
  renderReviewPermissions(c, decision);
  renderFacts(evidence);
  renderDecision(decision || c.decision_snapshot, execution);
  renderMaterials(c);
  $("case-history").replaceChildren();
  (c.history || c.review_history || [])
    .slice()
    .reverse()
    .forEach((h) => {
      const n = node("div", undefined, "history-item");
      n.append(
        node("strong", labels[h.action] || h.action || "更新"),
        node("div", h.reason || ""),
        node(
          "small",
          `${h.actor || h.user_id || ""} · ${date(h.created_at || h.at)}`,
        ),
      );
      $("case-history").append(n);
    });
  if (!$("case-history").children.length)
    empty($("case-history"), "暂无处理记录。");
}
function renderFacts(evidence) {
  const box = $("facts");
  box.replaceChildren();
  const order = evidence.order || evidence.data || evidence;
  [
    ["订单状态", "status"],
    ["站点", "stationId"],
    ["订单金额", "orderAmount"],
    ["承诺送达", "expectedDeliveryTime"],
    ["实际送达", "deliveryTime"],
  ].forEach(([label, key]) =>
    row(
      box,
      label,
      order[key] ?? order[key.replace(/[A-Z]/g, (x) => "_" + x.toLowerCase())],
    ),
  );
  const timeline =
    state.current.timeline ||
    evidence.timeline ||
    state.current.decision?.timeline_v2 ||
    state.current.decision?.timeline ||
    state.execution?.output?.timeline_v2 ||
    state.execution?.output?.timeline;
  if (timeline) {
    row(
      box,
      "是否超时",
      labels[timeline.overtime_status] || timeline.overtime_minutes,
    );
    [
      ["调度等待", "dispatch_wait_minutes"],
      ["骑手到店", "to_shop_minutes"],
      ["出餐等待", "merchant_prep_minutes"],
      ["配送路途", "on_road_minutes"],
    ].forEach(([label, key]) => {
      const n = node("div", undefined, "timeline-row"),
        v = timeline[key];
      const bar = node("div", undefined, "timeline-bar");
      bar.style.width = `${Math.min(100, Math.max(0, Number(v) || 0) * 2)}%`;
      n.append(
        node("span", label),
        bar,
        node("span", v == null ? "未知" : `${v} 分钟`),
      );
      box.append(n);
    });
  }
  inspect(box, "核对事实来源与快照", evidence);
}
function renderDecision(decision, execution) {
  const box = $("decision");
  box.replaceChildren();
  if (!decision) {
    empty(box, "尚未生成决定。点击“分析异常”或“生成赔付建议”。");
    return;
  }
  const d = decision.data || decision;
  box.append(badge(d.decision_status || "DETERMINED"));
  const amount =
    d.amount?.value ??
    d.authoritative_amount ??
    (d.decision_status === "DETERMINED"
      ? (d.amount ?? d.suggested_amount)
      : null);
  if (amount !== undefined && amount !== null)
    box.append(
      node(
        "div",
        `${d.amount?.currency || d.currency || "CNY"} ${amount}`,
        "amount",
      ),
    );
  else box.append(node("p", "暂时无法确定金额或无需金额决定。", "hint"));
  row(box, "金额来源", d.decided_by || d.amount_decided_by || "确定性规则");
  row(box, "政策版本", d.policy_version || d.policy_source);
  const analysis = d.model_analysis || d.model_justification;
  const reason = d.model_adoptable
    ? analysis?.reason ||
      analysis?.primary_cause ||
      d.baseline_cause ||
      d.reason
    : d.reason ||
      d.baseline_cause ||
      "模型解释尚未通过证据核验，请依据事实与政策人工复核。";
  if (reason) box.append(node("p", reason, "text-block"));
  const limitations =
    d.validation_errors || d.review_reasons || d.approval_reasons || [];
  limitations.forEach((v) =>
    box.append(
      node("p", typeof v === "string" ? v : JSON.stringify(v), "notice"),
    ),
  );
  const message = d.verified_customer_message || d.customer_message;
  if (message) {
    box.append(node("h3", "客户说明草稿"));
    box.append(node("p", message, "quote text-block"));
    if (
      (d.verified_customer_message_status ?? d.customer_message_status) ===
      "VALIDATED"
    )
      box.append(
        button("复制已核验说明", async () => {
          await navigator.clipboard.writeText(message);
          toast("已复制，请人工确认后发送。");
        }),
      );
    else box.append(node("p", "说明尚未通过校验，请审核后使用。", "hint"));
  }
  inspect(box, "查看决定、规则与引用", d);
  if (execution) inspect(box, "查看执行步骤", execution.steps || execution);
}
function renderMaterials(c) {
  $("attachments").replaceChildren();
  (c.materials || []).forEach((material) => {
    const n = node("div", undefined, "rule-item");
    n.append(
      node("strong", material.description || material.id),
      badge(material.status || "UPLOADED"),
    );
    if (material.status === "UPLOADED")
      n.append(
        button("核验材料", async () =>
          form(
            "核验材料",
            [
              {
                name: "reason",
                label: "核验说明",
                required: true,
                multiline: true,
              },
            ],
            async (f) => {
              await post(`/materials/${material.id}/review`, {
                action: "VERIFY",
                reason: f.reason,
                expected_version: material.version,
              });
              await openCase(c.id);
            },
          ),
        ),
      );
    $("attachments").append(n);
  });
}
async function startExecution(scenario) {
  const current = state.current;
  const damage = current.materials?.find(
    (m) => m.status === "VERIFIED" && m.damage_level,
  )?.damage_level;
  const result = await post(
    "/executions",
    {
      case_id: current.id,
      scenario,
      ...(damage ? { damage_level: damage } : {}),
      expected_case_version: current.version,
    },
    { headers: { "Idempotency-Key": crypto.randomUUID() } },
  );
  if (state.current?.id !== current.id) return;
  state.execution = entity(result);
  const id = state.execution.execution_id || state.execution.id;
  toast("任务已创建。");
  $("execution-progress").hidden = false;
  await pollExecution(id);
}
async function pollExecution(id) {
  if (state.view !== "cases" || !state.current) return;
  const detailGeneration = state.detailGeneration;
  const caseId = state.current.id;
  const execution = entity(await api(`/executions/${id}`));
  if (
    detailGeneration !== state.detailGeneration ||
    state.current?.id !== caseId
  )
    return;
  state.execution = execution;
  $("execution-state").textContent =
    labels[execution.status || execution.execution_status] || execution.status;
  const terminal = ["SUCCESS", "DEGRADED", "FAILED", "CANCELLED", "TIMED_OUT"];
  if (terminal.includes(execution.status || execution.execution_status)) {
    $("execution-progress").hidden = true;
    await openCase(state.current.id);
    return;
  }
  state.poll = setTimeout(
    () => pollExecution(id).catch((e) => reportError(e)),
    900,
  );
}
async function review(action) {
  const c = state.current;
  if (!c) throw new Error("请先选择案件。");
  const reason = $("review-reason").value.trim();
  if (action !== "CLAIM" && !reason) throw new Error("请填写处理理由。");
  if (reason.length > 2000) throw new Error("处理理由不能超过 2000 字。");
  const reviewId = c.review_id || c.id;
  const d = c.decision || c.latest_decision || state.execution?.decision || {};
  if (["CLAIM", "APPROVE"].includes(action)) {
    const blocked = independentReviewBlock(c, d);
    if (blocked) throw new Error(blocked);
  }
  if (!c.review_id || !c.review?.version)
    throw new Error("当前没有可操作的复核记录，请刷新案件。");
  const body =
    action === "CLAIM"
      ? { expected_version: c.review.version }
      : {
          action: action === "NEEDS_EVIDENCE" ? "REQUEST_EVIDENCE" : action,
          reason,
          expected_version: c.review.version,
          decision_id: d.id || c.current_decision_id,
          decision_version: d.version,
          evidence_snapshot_id: c.evidence_snapshot_id,
        };
  await post(
    `/reviews/${reviewId}/${action === "CLAIM" ? "claim" : "actions"}`,
    body,
  );
  toast("处理记录已保存。");
  await openCase(c.id);
}
async function loadPolicies() {
  const records = items(await api("/policies/versions"));
  table(
    $("policy-list"),
    records,
    [
      { title: "政策", value: (r) => r.name || r.title || r.id },
      { title: "状态", value: (r) => badge(r.status || r.state) },
      { title: "规则数", value: (r) => r.rule_count ?? (r.rules || []).length },
      { title: "创建于", value: (r) => date(r.created_at) },
    ],
    (r) => [
      button("查看", async () => {
        state.policy = entity(await api(`/policies/versions/${r.id}`));
        $("policy-detail").hidden = false;
        $("policy-publish").disabled = Boolean(publicationBlock(state.policy));
        $("policy-publish").title = publicationBlock(state.policy);
        $("policy-rules").replaceChildren();
        (state.policy.rules || []).forEach((rule) => {
          const n = node("div", undefined, "rule-item");
          n.append(
            node(
              "h3",
              rule.ruleName || rule.rule_name || rule.ruleId || rule.rule_id,
            ),
            node("p", rule.content || rule.summary || "", "text-block"),
          );
          inspect(n, "条件与公式", {
            conditions: rule.conditions,
            actions: rule.actions,
          });
          $("policy-rules").append(n);
        });
        $("policy-validation").replaceChildren();
      }),
    ],
  );
}
async function loadKnowledge() {
  const records = items(await api("/knowledge/versions"));
  table(
    $("knowledge-list"),
    records,
    [
      { title: "资料", value: (r) => r.title || r.id },
      { title: "状态", value: (r) => badge(r.status || r.state) },
      { title: "类型", value: (r) => r.source_type || r.type },
      { title: "更新于", value: (r) => date(r.updated_at) },
    ],
    (r) => [
      button("查看原文与切片", async () => {
        const record = entity(await api(`/knowledge/versions/${r.id}`));
        $("knowledge-results").hidden = false;
        $("knowledge-results").replaceChildren();
        inspect($("knowledge-results"), "版本原文、范围与冻结切片", record);
      }),
      permittedButton(
        "审核",
        async () => {
          await post(`/knowledge/versions/${r.id}/review`, {
            expected_version: r.version,
            reason: "人工审核资料",
          });
          await loadKnowledge();
        },
        ["POLICY_PUBLISHER", "QUALITY", "ADMIN"],
        publicationBlock(r),
      ),
      permittedButton(
        "发布",
        async () => {
          await post(`/knowledge/versions/${r.id}/publish`, {
            expected_version: r.version,
            reason: "发布已核验资料",
          });
          await loadKnowledge();
        },
        ["POLICY_PUBLISHER", "QUALITY", "ADMIN"],
        publicationBlock(r),
      ),
      permittedButton(
        "撤回",
        async () => {
          await post(`/knowledge/versions/${r.id}/withdraw`, {
            expected_version: r.version,
            reason: "人工撤回",
          });
          await loadKnowledge();
        },
        ["POLICY_PUBLISHER", "QUALITY", "ADMIN"],
      ),
    ],
  );
}
async function loadQuality() {
  const records = items(await api("/eval/runs"));
  table(
    $("eval-list"),
    records,
    [
      { title: "运行", value: (r) => r.id },
      { title: "状态", value: (r) => badge(r.status || r.state) },
      { title: "配置", value: (r) => r.runtime_config_id },
      { title: "完成", value: (r) => `${r.completed || 0} / ${r.total || 0}` },
      { title: "开始于", value: (r) => date(r.created_at) },
    ],
    (r) => [button("查看", () => openRun(r.id))],
  );
}
async function openRun(id) {
  clearTimeout(state.evalPoll);
  const generation = state.generation;
  const evalGeneration = ++state.evalGeneration;
  const selected = entity(await api(`/eval/runs/${id}`));
  if (!sameIdentity(generation) || evalGeneration !== state.evalGeneration)
    return;
  state.run = selected;
  $("eval-detail").hidden = false;
  $("eval-summary").replaceChildren(badge(state.run.status));
  inspect($("eval-summary"), "运行配置与门禁结果", state.run);
  const result = await api(`/eval/runs/${id}/results`);
  if (!sameIdentity(generation) || evalGeneration !== state.evalGeneration)
    return;
  table($("eval-results"), items(result), [
    { title: "用例", value: (r) => r.sample?.sample_id || r.case_id || r.id },
    {
      title: "执行状态",
      value: (r) =>
        badge(r.execution?.status || r.status || r.execution_status),
    },
    {
      title: "评分 / 未度量",
      value: (r) =>
        JSON.stringify(
          r.scores || r.score || r.measurements || r.metrics || {},
        ),
    },
    {
      title: "失败原因",
      value: (r) => r.error || r.error_message || r.execution?.error || "—",
    },
  ]);
  clearTimeout(state.evalPoll);
  if (
    state.view === "quality" &&
    ["QUEUED", "RUNNING"].includes(state.run.status)
  )
    state.evalPoll = setTimeout(
      () => openRun(id).catch((e) => reportError(e)),
      1200,
    );
}
async function loadAudit(more = false) {
  if (!hasRole("AUDITOR", "ADMIN")) {
    empty($("audit-list"), "当前身份没有业务审计读取权限。");
    $("audit-more").hidden = true;
    return;
  }
  if (more && !state.auditCursor) return;
  const generation = state.generation;
  const auditGeneration = ++state.auditGeneration;
  if (!more) {
    state.audit = [];
    state.auditCursor = null;
  }
  const q = new URLSearchParams({ limit: "20" });
  if (more) q.set("cursor", state.auditCursor);
  $("audit-more").disabled = true;
  try {
    const page = await api(`/audit-events?${q}`);
    if (!sameIdentity(generation) || auditGeneration !== state.auditGeneration)
      return;
    const next = items(page);
    state.audit = more ? [...state.audit, ...next] : next;
    state.auditCursor = page.cursor || null;
    table($("audit-list"), state.audit, [
      { title: "操作", value: (r) => r.action },
      { title: "操作人", value: (r) => r.actor || r.actor_id },
      { title: "对象", value: (r) => r.target },
      { title: "时间", value: (r) => date(r.created_at) },
    ]);
    $("audit-count").textContent = `已加载 ${state.audit.length} 条审计记录`;
    $("audit-more").hidden = !state.auditCursor;
  } finally {
    if (sameIdentity(generation) && auditGeneration === state.auditGeneration)
      $("audit-more").disabled = false;
  }
}
async function loadOperations() {
  const generation = state.generation;
  const results = await Promise.allSettled([
    api("/metrics"),
    hasRole("AGENT", "OPERATOR", "REVIEWER", "SUPERVISOR", "QUALITY", "AUDITOR")
      ? api("/executions?limit=20")
      : Promise.resolve({ items: [] }),
    api("/feedback"),
    loadAudit(),
  ]);
  if (!sameIdentity(generation)) return;
  const names = ["运行指标", "执行记录", "纠正反馈", "业务审计"];
  results.forEach((r, i) => {
    if (r.status === "rejected" && r.reason.name !== "AbortError")
      toast(`${names[i]}: ${r.reason.message}`, true);
  });
  $("metrics").replaceChildren();
  if (results[0].status === "fulfilled") {
    const m = results[0].value;
    Object.entries(m.summary || m)
      .slice(0, 8)
      .forEach(([key, value]) => {
        if (typeof value === "object") return;
        const n = node("div", undefined, "card metric");
        n.append(node("span", key), node("strong", value));
        $("metrics").append(n);
      });
  }
  if (results[1].status === "fulfilled")
    table($("execution-list"), items(results[1].value), [
      { title: "执行", value: (r) => r.id },
      { title: "状态", value: (r) => badge(r.status || r.state) },
      { title: "案件", value: (r) => r.case_id },
    ]);
  if (results[2].status === "fulfilled")
    table(
      $("feedback-list"),
      items(results[2].value),
      [
        { title: "类型", value: (r) => r.category },
        { title: "状态", value: (r) => badge(r.status || r.state) },
        {
          title: "说明",
          value: (r) => "请查看详情核验更正",
        },
      ],
      feedbackActions,
    );
}

async function showRuntime(id) {
  const generation = state.generation;
  const record = entity(await api(`/runtime-configs/${id}`));
  if (!sameIdentity(generation)) return;
  form(
    "运行配置",
    [
      {
        name: "details",
        label: "冻结配置",
        multiline: true,
        value: JSON.stringify(record, null, 2),
        readOnly: true,
      },
    ],
    async () => {},
    "关闭",
  );
}

function feedbackActions(record) {
  const actions = [button("查看", () => openFeedback(record.id))];
  const next =
    {
      PENDING_VERIFICATION: ["CONFIRMED", "REJECTED"],
      CONFIRMED: ["LINKED_FIX"],
      LINKED_FIX: ["REGRESSION_VERIFIED"],
    }[record.status] || [];
  const names = {
    CONFIRMED: "核验确认",
    REJECTED: "拒绝更正",
    LINKED_FIX: "关联修复",
    REGRESSION_VERIFIED: "验证回归",
  };
  for (const status of next) {
    const self =
      status === "CONFIRMED" &&
      record.submitted_by === (principal().user_id || principal().userId);
    actions.push(
      permittedButton(
        names[status],
        () => openFeedback(record.id, status),
        ["QUALITY", "ADMIN"],
        self ? "提交者不能核验自己的更正。" : "",
      ),
    );
  }
  return actions;
}

async function openFeedback(id, status) {
  const generation = state.generation;
  const record = entity(await api(`/feedback/${id}`));
  if (!sameIdentity(generation)) return;
  if (
    status === "CONFIRMED" &&
    record.submitted_by === (principal().user_id || principal().userId)
  )
    throw new Error("请由另一位有权限的用户核验更正。");
  const fields = [
    {
      name: "details",
      label: "反馈与证据",
      multiline: true,
      readOnly: true,
      value: JSON.stringify(record, null, 2),
    },
  ];
  if (status)
    fields.push({
      name: "reason",
      label: "核验理由",
      required: true,
      maxLength: 2000,
      multiline: true,
    });
  if (status === "LINKED_FIX")
    fields.push({
      name: "issue_ref",
      label: "修复引用",
      required: true,
      maxLength: 256,
    });
  if (status === "REGRESSION_VERIFIED")
    fields.push({
      name: "eval_run_id",
      label: "包含此反馈样本且已通过的评测 ID",
      required: true,
      maxLength: 100,
    });
  form(
    status ? "更新反馈核验状态" : "反馈详情",
    fields,
    async (values) => {
      if (!sameIdentity(generation) || !status) return;
      await post(`/feedback/${id}/status`, {
        expected_version: record.version,
        status,
        reason: values.reason,
        ...(status === "LINKED_FIX" ? { issue_ref: values.issue_ref } : {}),
        ...(status === "REGRESSION_VERIFIED"
          ? { eval_run_id: values.eval_run_id }
          : {}),
      });
      if (sameIdentity(generation)) await loadOperations();
    },
    status ? "提交核验" : "关闭",
  );
}

async function loadSettings() {
  const generation = state.generation;
  const [configs, adapter] = await Promise.allSettled([
    api("/runtime-configs"),
    api("/adapters/status"),
  ]);
  if (!sameIdentity(generation)) return;
  if (configs.status === "rejected") throw configs.reason;
  const records = items(configs.value);
  table(
    $("runtime-list"),
    records,
    [
      { title: "配置", value: (r) => r.name || r.id },
      { title: "状态", value: (r) => badge(r.state) },
      {
        title: "模型",
        value: (r) => (r.deterministic_only ? "确定性模式" : r.model),
      },
      { title: "政策版本", value: (r) => r.policy_version },
      { title: "创建于", value: (r) => date(r.created_at) },
    ],
    (r) => [
      button("查看", () => showRuntime(r.id)),
      permittedButton(
        "发布",
        async () =>
          form(
            "发布候选配置",
            [
              {
                name: "eval_run_id",
                label: "已通过的评测运行 ID",
                required: true,
              },
            ],
            async (f) => {
              await post(`/runtime-configs/${r.id}/publish`, {
                expected_version: r.version,
                eval_run_id: f.eval_run_id,
              });
              toast("配置已发布。");
              await loadSettings();
            },
          ),
        ["QUALITY", "ADMIN"],
        publicationBlock(r),
      ),
    ],
  );
  $("integration-info").replaceChildren();
  inspect($("integration-info"), "当前身份与授权范围", state.session);
  if (adapter.status === "fulfilled")
    inspect($("integration-info"), "订单来源连接", adapter.value);
  else
    $("integration-info").append(
      node("p", "订单来源状态暂时无法读取。", "notice"),
    );
}
document
  .querySelectorAll(".nav")
  .forEach((n) => n.addEventListener("click", () => view(n.dataset.view)));
$("refresh").onclick = () => view(state.view);
$("logout").onclick = () => {
  disconnect();
  toast("访问凭证及当前页面数据已清除。");
};
$("case-query").oninput = refreshCaseFilters;
$("case-state").onchange = refreshCaseFilters;
$("case-risk").onchange = refreshCaseFilters;
$("case-complaint").onchange = refreshCaseFilters;
$("case-owner").oninput = refreshCaseFilters;
$("case-station").oninput = refreshCaseFilters;
$("audit-more").onclick = () => loadAudit(true).catch(reportError);
$("cases-more").onclick = () => loadCases(true).catch((e) => reportError(e));
$("case-back").onclick = () => {
  state.current = null;
  state.detailGeneration += 1;
  clearTimeout(state.poll);
  $("case-detail").hidden = true;
  $("case-list-area").hidden = false;
};
$("editor-close").onclick = $("editor-cancel").onclick = () =>
  $("editor").close();
$("login-open").onclick = () =>
  form(
    "访问凭证",
    [
      {
        name: "token",
        label: "组织提供的访问令牌",
        type: "password",
        required: true,
        maxLength: 8192,
        hint: "凭证仅保存在当前页面内存中，刷新后需重新输入。",
      },
    ],
    async (f) => {
      disconnect();
      state.token = f.token.trim();
      try {
        await session();
        await view(state.view);
      } catch (error) {
        reportError(error);
        throw error;
      }
    },
    "连接",
  );
$("new-case-open").onclick = () =>
  form(
    "新建案件",
    [
      {
        name: "order_id",
        label: "订单号",
        required: true,
        maxLength: 64,
        placeholder: "演示可用 DEMO-001 至 DEMO-006",
      },
      {
        name: "complaint_type",
        label: "投诉类型",
        options: [
          { value: "OVERTIME", label: "超时" },
          { value: "DAMAGED", label: "餐品损坏" },
          { value: "WRONG_ORDER", label: "错单" },
          { value: "MISSING_ITEM", label: "缺件" },
        ],
      },
      { name: "source_ticket_id", label: "关联业务工单号" },
      { name: "title", label: "案件说明", maxLength: 200 },
    ],
    async (f) => {
      const c = entity(await post("/cases", f));
      toast("案件已创建。");
      await loadCases();
      await openCase(c.id);
    },
    "创建案件",
  );
$("analyze-abnormal").onclick = () =>
  startExecution("abnormal_order_analysis").catch((e) => reportError(e));
$("analyze-compensation").onclick = () =>
  startExecution("compensation_suggestion").catch((e) => reportError(e));
$("cancel-execution").onclick = () =>
  post(`/executions/${state.execution.id}/cancel`, {}).catch((e) =>
    reportError(e),
  );
$("review-claim").onclick = () => review("CLAIM").catch((e) => reportError(e));
$("review-approve").onclick = () =>
  review("APPROVE").catch((e) => reportError(e));
$("review-reject").onclick = () =>
  review("REJECT").catch((e) => reportError(e));
$("review-return").onclick = () =>
  review("NEEDS_EVIDENCE").catch((e) => reportError(e));
$("evidence-open").onclick = () =>
  form(
    "补充证据",
    [
      { name: "description", label: "材料说明", required: true },
      { name: "content", label: "核查记录", required: true, multiline: true },
      {
        name: "damage_level",
        label: "损坏等级（如适用）",
        options: [
          { value: "", label: "不适用" },
          { value: "FULL", label: "全部损坏" },
          { value: "PARTIAL", label: "部分损坏" },
        ],
      },
    ],
    async (f, generation) => {
      const bytes = new TextEncoder().encode(f.content);
      const hash = Array.from(
        new Uint8Array(await crypto.subtle.digest("SHA-256", bytes)),
      )
        .map((b) => b.toString(16).padStart(2, "0"))
        .join("");
      if (!sameIdentity(generation))
        throw new DOMException("请求已更新。", "AbortError");
      let binary = "";
      bytes.forEach((b) => (binary += String.fromCharCode(b)));
      const body = {
        description: f.description,
        file_hash: hash,
        size_bytes: bytes.length,
        mime_type: "text/plain",
        source: "MANUAL",
        content_base64: btoa(binary),
      };
      if (f.damage_level) body.damage_level = f.damage_level;
      await post(`/cases/${state.current.id}/evidence`, body);
      toast("材料已保存，需要另一位审核人核验。");
      await openCase(state.current.id);
    },
  );
$("feedback-open").onclick = () =>
  form(
    "纠正反馈",
    [
      {
        name: "category",
        label: "问题类型",
        options: [
          "ATTRIBUTION_ERROR",
          "RULE_ERROR",
          "AMOUNT_ERROR",
          "EVIDENCE_ERROR",
          "MESSAGE_ERROR",
          "USABILITY",
        ],
      },
      {
        name: "reason_code",
        label: "原因类别",
        required: true,
        maxLength: 100,
      },
      {
        name: "field_path",
        label: "需要修正的字段",
        value: "amount",
        maxLength: 100,
      },
      {
        name: "original_value",
        label: "当前值",
        multiline: true,
        value: JSON.stringify(state.current.decision?.amount ?? null),
      },
      { name: "suggested_value", label: "建议值", multiline: true },
      {
        name: "evidence_refs",
        label: "依据材料 ID（逗号分隔）",
        value: state.current.evidence_snapshot_id || "",
      },
    ],
    async (f) => {
      const value = (x) => {
        try {
          return JSON.parse(x);
        } catch {
          return x;
        }
      };
      const correction = {
        reason_code: f.reason_code,
        field_path: f.field_path,
        original_value: value(f.original_value),
        suggested_value: value(f.suggested_value),
        evidence_refs: f.evidence_refs
          .split(/[,，]/)
          .map((x) => x.trim())
          .filter(Boolean),
      };
      await post("/feedback", {
        category: f.category,
        correction,
        case_id: state.current.id,
        decision_id:
          state.current.current_decision_id || state.current.decision?.id,
        decision_version: state.current.decision?.version || 1,
      });
      toast("反馈已进入核验队列。");
    },
  );
$("policy-open").onclick = () =>
  form(
    "建立政策草稿",
    [
      { name: "name", label: "政策名称", required: true },
      {
        name: "complaint_type",
        label: "适用投诉",
        options: ["OVERTIME", "DAMAGED", "WRONG_ORDER", "MISSING_ITEM"],
      },
      {
        name: "min",
        label: "最小超时分钟（含）",
        type: "number",
        min: 0,
        step: 1,
        value: 0,
      },
      {
        name: "max",
        label: "最大超时分钟（含，可留空）",
        type: "number",
        min: 0,
        step: 1,
      },
      {
        name: "amount",
        label: "固定金额（CNY）",
        type: "number",
        min: 0,
        step: "0.01",
        required: true,
        value: "0.00",
      },
      {
        name: "method",
        label: "补偿方式",
        options: ["COUPON", "REFUND", "RED_PACKET"],
      },
      { name: "reason", label: "政策依据", multiline: true, required: true },
    ],
    async (f) => {
      const id = `RULE-${Date.now()}`;
      const conditions = { complaint_type: f.complaint_type };
      if (f.min !== "") conditions.min_overtime_minutes = Number(f.min);
      if (f.max !== "") conditions.max_overtime_minutes = Number(f.max);
      await post("/policies/versions", {
        name: f.name,
        rules: [
          {
            ruleId: id,
            ruleName: f.name,
            ruleType: "compensation",
            enabled: true,
            priority: 100,
            currency: "CNY",
            conditions,
            actions: {
              amount_type: "FIXED",
              amount: Number(f.amount),
              method: f.method,
              approval_required: true,
            },
            content: f.reason,
          },
        ],
      });
      toast("政策草稿已建立。");
      await loadPolicies();
    },
  );
["validate", "publish", "withdraw"].forEach(
  (action) =>
    ($(`policy-${action}`).onclick = async () => {
      try {
        if (!state.policy) throw new Error("请先选择政策。");
        const result = await post(
          `/policies/versions/${state.policy.id}/${action}`,
          { expected_version: state.policy.version, reason: "工作台人工操作" },
        );
        $("policy-validation").replaceChildren();
        inspect($("policy-validation"), "操作结果", result);
        await loadPolicies();
        toast("操作已完成。");
      } catch (e) {
        reportError(e);
      }
    }),
);
$("knowledge-open").onclick = () =>
  form(
    "导入参考资料",
    [
      { name: "title", label: "标题", required: true, maxLength: 200 },
      { name: "source_type", label: "资料类型", options: ["document", "case"] },
      {
        name: "content",
        label: "原文",
        required: true,
        multiline: true,
        rows: 8,
        maxLength: 100000,
      },
      { name: "tags", label: "关键词（逗号分隔）" },
    ],
    async (f) => {
      await post("/knowledge/versions", {
        ...f,
        tags: f.tags
          .split(/[,，]/)
          .map((x) => x.trim())
          .filter(Boolean),
      });
      toast("资料已建立草稿，审核发布后可检索。");
      await loadKnowledge();
    },
    "建立草稿",
    (f) =>
      post("/knowledge/versions/preview", {
        ...f,
        tags: f.tags
          .split(/[,，]/)
          .map((x) => x.trim())
          .filter(Boolean),
      }),
  );
$("knowledge-search").onclick = async () => {
  try {
    const result = await post("/knowledge/search", {
      query: $("knowledge-query").value,
      top_k: 10,
    });
    $("knowledge-results").hidden = false;
    const records = items(result);
    table($("knowledge-results"), records, [
      { title: "标题", value: (r) => r.title || r.source_id },
      { title: "原文片段", value: (r) => r.content },
      { title: "相关性分数", value: (r) => r.score },
    ]);
  } catch (e) {
    reportError(e);
  }
};
$("runtime-open").onclick = async () => {
  try {
    const policies = items(await api("/policies/versions")).filter(
      (p) => (p.status || p.state) === "PUBLISHED",
    );
    if (!policies.length) throw new Error("请先发布一版政策。");
    form(
      "新建运行配置",
      [
        { name: "name", label: "配置名称", required: true },
        {
          name: "policy_version",
          label: "政策版本",
          options: policies.map((p) => ({
            value: p.id,
            label: p.name || p.id,
          })),
        },
        {
          name: "model",
          label: "模型标识",
          value: "qwen2.5:7b",
          required: true,
        },
        {
          name: "deterministic_only",
          label: "分析方式",
          options: [
            { value: "true", label: "确定性规则与模板" },
            { value: "false", label: "规则加模型说明" },
          ],
        },
        {
          name: "prompt",
          label: "说明生成指令",
          multiline: true,
          value: "只根据提供的证据解释结论，不修改赔付金额，不编造事实。",
        },
      ],
      async (f) => {
        await post("/runtime-configs", {
          ...f,
          deterministic_only: f.deterministic_only === "true",
          temperature: 0,
          max_tokens: 1000,
        });
        toast("候选配置已保存，通过评测并由另一位发布者确认后可上线。");
        await loadSettings();
      },
    );
  } catch (e) {
    reportError(e);
  }
};
$("eval-open").onclick = async () => {
  try {
    const [configs, datasets] = await Promise.all([
      api("/runtime-configs"),
      api("/eval/datasets"),
    ]);
    const c = items(configs),
      d = items(datasets);
    if (!c.length || !d.length)
      throw new Error("请先建立运行配置和评测数据集。");
    form(
      "启动评测",
      [
        {
          name: "runtime_config_id",
          label: "冻结运行配置",
          options: c.map((v) => ({ value: v.id, label: v.name || v.id })),
        },
        {
          name: "dataset_version",
          label: "冻结数据集",
          options: d.map((v) => ({ value: v.id, label: v.name || v.id })),
        },
      ],
      async (f) => {
        const r = entity(await post("/eval/runs", f));
        toast("评测任务已创建。");
        await loadQuality();
        await openRun(r.id);
      },
      "开始评测",
    );
  } catch (e) {
    reportError(e);
  }
};
$("dataset-open").onclick = () =>
  form(
    "建立冻结评测数据集",
    [
      { name: "name", label: "数据集名称", required: true },
      {
        name: "split",
        label: "数据用途",
        options: [
          "SYNTHETIC_SMOKE",
          "BOUNDARY_REGRESSION",
          "REAL_LABELLED",
          "FROZEN_BLIND",
        ],
      },
      {
        name: "cases",
        label: "结构化用例",
        multiline: true,
        rows: 10,
        required: true,
        hint: "每条用例需要固定订单证据及期望字段。未标注维度会报告“未度量”。",
        value: JSON.stringify(
          [
            {
              sample_id: "sample-001",
              scenario: "compensation_suggestion",
              input: { order_id: "DEMO-002", complaint_type: "OVERTIME" },
              expected_output: {
                amount: "20.00",
                currency: "CNY",
                decision_status: "DETERMINED",
              },
              expected_rule_ids: ["COMP-001"],
            },
          ],
          null,
          2,
        ),
      },
    ],
    async (f) => {
      let cases;
      try {
        cases = JSON.parse(f.cases);
      } catch {
        throw new Error("用例内容必须是有效 JSON 数组。");
      }
      if (!Array.isArray(cases)) throw new Error("用例内容必须为数组。");
      await post("/eval/datasets", {
        name: f.name,
        split: f.split,
        samples: cases,
      });
      toast("数据集已冻结。");
    },
  );
$("eval-replay").onclick = () =>
  post(`/eval/runs/${state.run.id}/replay`, {})
    .then((r) => {
      toast("重放已创建。");
      return openRun(entity(r).id);
    })
    .catch((e) => reportError(e));
$("eval-cancel").onclick = () =>
  post(`/eval/runs/${state.run.id}/cancel`, {})
    .then(() => openRun(state.run.id))
    .catch((e) => reportError(e));
disconnect();

$("exception-open").onclick = () =>
  form(
    "申请人工例外",
    [
      {
        name: "amount",
        label: "例外金额（CNY）",
        type: "number",
        min: 0,
        max: 99999999.99,
        step: "0.01",
        required: true,
      },
      {
        name: "method",
        label: "方式",
        options: ["REFUND", "COUPON", "RED_PACKET", "RESEND"],
      },
      { name: "authorization_basis", label: "例外授权依据", required: true },
      { name: "reason", label: "事实与理由", required: true, multiline: true },
    ],
    async (f) => {
      if (!/^\d{1,8}(\.\d{1,2})?$/.test(f.amount))
        throw new Error("金额须为非负数，最多 8 位整数和 2 位小数。");
      await post(`/cases/${state.current.id}/exceptions`, {
        ...f,
        amount: Number(f.amount),
        expected_case_version: state.current.version,
        evidence_snapshot_id: state.current.evidence_snapshot_id,
      });
      toast("例外申请已保存，需要另一位主管审核。");
      await openCase(state.current.id);
    },
  );
$("case-close").onclick = () =>
  form(
    "结案归档",
    [
      { name: "reason", label: "结案说明", required: true, multiline: true },
      {
        name: "external_receipt",
        label: "既有业务系统凭证（如有）",
        maxLength: 2000,
      },
    ],
    async (f) => {
      await post(`/cases/${state.current.id}/close`, {
        ...f,
        expected_version: state.current.version,
      });
      toast("案件已归档。");
      await openCase(state.current.id);
    },
  );
$("case-export").onclick = async () => {
  try {
    const data = await post(`/cases/${state.current.id}/export`, {});
    const url = URL.createObjectURL(
      new Blob([JSON.stringify(data, null, 2)], { type: "application/json" }),
    );
    const link = node("a");
    link.href = url;
    link.download = `case-${state.current.id}.json`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    toast("已导出授权范围内的案件记录。");
  } catch (e) {
    reportError(e);
  }
};

$("review-reassign").onclick = async () => {
  try {
    const c = state.current;
    const reviewers = items(
      await api(`/reviewers?case_id=${encodeURIComponent(c.id)}`),
    );
    if (!reviewers.length) throw new Error("当前范围没有可转派的审核人。");
    form(
      "转派复核",
      [
        {
          name: "target_user_id",
          label: "受权审核人",
          options: reviewers.map((r) => ({
            value: r.user_id || r.userId,
            label: r.user_id || r.userId,
          })),
        },
        { name: "reason", label: "转派原因", required: true, multiline: true },
      ],
      async (f) => {
        await post(`/reviews/${c.review_id}/actions`, {
          ...f,
          action: "REASSIGN",
          expected_version: c.review.version,
          decision_id: c.decision.id,
          decision_version: c.decision.version,
          evidence_snapshot_id: c.evidence_snapshot_id,
        });
        await openCase(c.id);
        toast("已转派给受权审核人。");
      },
    );
  } catch (e) {
    reportError(e);
  }
};
$("runtime-route-open").onclick = async () => {
  try {
    const configs = items(await api("/runtime-configs")).filter(
      (c) => c.state === "PUBLISHED",
    );
    if (!configs.length) throw new Error("需要至少一个已发布配置。");
    const choices = configs.map((c) => ({
      value: c.id,
      label: c.name || c.id,
    }));
    form(
      "配置场景灰度与回滚",
      [
        {
          name: "scenario",
          label: "场景",
          options: ["abnormal_order_analysis", "compensation_suggestion"],
        },
        { name: "primary_config_id", label: "候选配置", options: choices },
        {
          name: "fallback_config_id",
          label: "基线 / 回滚配置",
          options: choices,
        },
        {
          name: "rollout_percent",
          label: "候选比例（0–100%）",
          type: "number",
          value: 0,
          required: true,
        },
        { name: "city_codes", label: "城市范围（逗号分隔）" },
        { name: "station_ids", label: "站点范围（逗号分隔）" },
      ],
      async (f) => {
        await post("/runtime-routes", {
          ...f,
          rollout_percent: Number(f.rollout_percent),
          city_codes: f.city_codes.split(/[,，]/).filter(Boolean),
          station_ids: f.station_ids.split(/[,，]/).filter(Boolean),
        });
        toast("路由已发布；新执行按冻结配置运行。");
      },
    );
  } catch (e) {
    reportError(e);
  }
};
$("eval-retry").onclick = () =>
  post(`/eval/runs/${state.run.id}/retry`, {})
    .then((r) => openRun(entity(r).id))
    .catch((e) => reportError(e));
$("eval-compare").onclick = () =>
  form(
    "比较评测运行",
    [
      {
        name: "candidate_id",
        label: "相同数据集的候选运行 ID",
        required: true,
      },
    ],
    async (f) => {
      const comparison = await api(
        `/eval/runs/${state.run.id}/compare/${encodeURIComponent(f.candidate_id)}`,
      );
      inspect($("eval-summary"), "比较结果", comparison);
    },
  );
