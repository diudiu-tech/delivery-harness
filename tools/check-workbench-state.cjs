"use strict";
const fs = require("node:fs");
const vm = require("node:vm");
const assert = require("node:assert/strict");
const path = require("node:path");
const directory = path.resolve(
  __dirname,
  "../harness-api/src/main/resources/static/workbench",
);
const elements = new Map();
class Element {
  constructor(tag = "div") {
    this.tagName = tag;
    this.children = [];
    this.value = "";
    this.textContent = "";
    this.hidden = false;
    this.disabled = false;
    this.style = {};
    this.dataset = {};
    this.classList = { add() {}, toggle() {} };
  }
  append(...children) {
    this.children.push(...children);
  }
  replaceChildren(...children) {
    this.children = [...children];
  }
  addEventListener() {}
  close() {
    this.open = false;
  }
  showModal() {
    this.open = true;
  }
  click() {}
}
const html = fs.readFileSync(path.join(directory, "index.html"), "utf8");
for (const id of html.matchAll(/\bid="([^"]+)"/g))
  elements.set(id[1], new Element());
const navs = [
  "cases",
  "policies",
  "knowledge",
  "quality",
  "operations",
  "settings",
].map((view) => {
  const element = new Element("button");
  element.dataset.view = view;
  return element;
});
const views = navs.map((nav) =>
  Object.assign(new Element(), { id: `view-${nav.dataset.view}` }),
);
const requests = [];
let formValues = {};
let fetcher = async () => {
  throw new Error("Unexpected network call");
};
const document = {
  getElementById(id) {
    assert(elements.has(id), `Referenced HTML id exists: ${id}`);
    return elements.get(id);
  },
  createElement(tag) {
    return new Element(tag);
  },
  querySelectorAll(selector) {
    return selector === ".nav" ? navs : selector === ".view" ? views : [];
  },
  querySelector(selector) {
    if (selector === ".nav:not([hidden])")
      return navs.find((nav) => !nav.hidden);
    return navs.find(
      (nav) => selector === `.nav[data-view="${nav.dataset.view}"]`,
    );
  },
};
const sandbox = {
  document,
  Node: Element,
  AbortController,
  DOMException,
  URLSearchParams,
  URL,
  Blob,
  TextEncoder,
  FormData: class {
    [Symbol.iterator]() {
      return Object.entries(formValues)[Symbol.iterator]();
    }
  },
  crypto: require("node:crypto").webcrypto,
  navigator: { clipboard: { writeText: async () => {} } },
  setTimeout,
  clearTimeout,
  console,
  fetch: async (url, options) => {
    requests.push({ url, options });
    return fetcher(url, options);
  },
};
vm.createContext(sandbox);
vm.runInContext(
  fs.readFileSync(path.join(directory, "app.js"), "utf8"),
  sandbox,
);
const run = (source) => vm.runInContext(source, sandbox);
const tick = () => new Promise(setImmediate);
const response = (body, status = 200) => ({
  status,
  ok: status >= 200 && status < 300,
  text: async () => JSON.stringify(body),
});
const caseRecord = (id) => ({
  id,
  version: 2,
  order_id: id,
  created_by: "agent",
  current_decision_id: "decision-1",
  review_id: "review-1",
  review: { version: 3, status: "PENDING" },
  decision: { id: "decision-1", version: 2, created_by: "analyst" },
  evidence_snapshot_id: "evidence-1",
  materials: [],
  review_history: [],
});
(async () => {
  assert(
    navs.every((n) => n.hidden),
    "Unauthenticated navigation has no inferred roles",
  );
  assert.equal(
    requests.length,
    0,
    "No anonymous startup API call or implicit credentials",
  );
  run(
    'state.session={mode:"demo", principal:{user_id:"reviewer", roles:["REVIEWER","AUDITOR"]}}; state.token="test-token";',
  );
  const pending = [];
  fetcher = (url) => new Promise((resolve) => pending.push({ url, resolve }));
  elements.get("case-state").value = "PENDING_REVIEW";
  elements.get("case-query").value = "ORDER-EXACT";
  elements.get("case-risk").value = "HIGH";
  elements.get("case-complaint").value = "DAMAGED";
  elements.get("case-owner").value = "reviewer";
  elements.get("case-station").value = "ST-1";
  const older = run("loadCases()");
  const sent = new URL(pending[0].url, "http://localhost").searchParams;
  for (const [key, value] of Object.entries({
    status: "PENDING_REVIEW",
    order_id: "ORDER-EXACT",
    risk_level: "HIGH",
    complaint_type: "DAMAGED",
    assignee: "reviewer",
    station_id: "ST-1",
  }))
    assert.equal(sent.get(key), value);
  elements.get("case-query").value = "ORDER-NEW";
  const newer = run("loadCases()");
  pending[1].resolve(
    response({ items: [caseRecord("ORDER-NEW")], cursor: "new-cursor" }),
  );
  await newer;
  pending[0].resolve(
    response({ items: [caseRecord("ORDER-EXACT")], cursor: "old-cursor" }),
  );
  await older;
  assert.equal(
    run("state.cases[0].id"),
    "ORDER-NEW",
    "Late query cannot restore prior filter results",
  );
  assert.equal(run("state.cursor"), "new-cursor");
  assert.equal(
    requests[0].options.credentials,
    "omit",
    "Bearer requests do not send ambient cookies",
  );

  fetcher = async (url) => {
    const cursor = new URL(url, "http://localhost").searchParams.get("cursor");
    return response(
      cursor
        ? {
            items: [
              { event_id: "event-2", created_at: "2026-01-01T00:00:00Z" },
            ],
            cursor: "",
          }
        : {
            items: [
              { event_id: "event-1", created_at: "2026-01-01T00:00:00Z" },
            ],
            cursor: "opaque timestamp,event_id",
          },
    );
  };
  await run("loadAudit()");
  await run("loadAudit(true)");
  assert.equal(
    run("state.audit.length"),
    2,
    "Equal timestamps survive audit cursor pagination",
  );
  assert.equal(
    new URL(requests.at(-1).url, "http://localhost").searchParams.get("cursor"),
    "opaque timestamp,event_id",
  );
  assert.equal(elements.get("audit-more").hidden, true);

  run(`state.current=${JSON.stringify(caseRecord("ORDER-NEW"))}`);
  run("renderReviewPermissions(state.current,state.current.decision)");
  assert.equal(elements.get("review-claim").disabled, false);
  run(
    'state.session.principal.user_id="agent"; renderReviewPermissions(state.current,state.current.decision)',
  );
  assert.equal(elements.get("review-claim").disabled, true);
  assert.equal(elements.get("review-approve").disabled, true);
  await assert.rejects(run('review("CLAIM")'), /另一位审核人/);
  run('state.session.principal.user_id="reviewer";');
  fetcher = async (url) =>
    response(url.includes("/cases/") ? caseRecord("ORDER-NEW") : {});
  await run('review("CLAIM")');
  const claim = requests.findLast((request) => request.url.includes("/claim"));
  assert.deepEqual(
    JSON.parse(claim.options.body),
    { expected_version: 3 },
    "Claim uses the exact typed DTO",
  );

  formValues = {
    amount: "5.25",
    method: "REFUND",
    reason: "Verified evidence",
    authorization_basis: "Supervisor",
  };
  elements.get("exception-open").onclick();
  await elements.get("editor-form").onsubmit({ preventDefault() {} });
  const exception = requests.findLast((request) =>
    request.url.endsWith("/exceptions"),
  );
  assert.equal(
    JSON.parse(exception.options.body).amount,
    5.25,
    "StrictDecimal receives a JSON number",
  );
  assert.equal(elements.get("review-reason").value, "");

  fetcher = async (url) =>
    response(
      url === "/api/v2/session"
        ? {
            mode: "demo",
            principal: {
              user_id: "demo-publisher",
              roles: ["QUALITY", "POLICY_PUBLISHER"],
            },
          }
        : { items: [] },
    );
  await run("session()");
  assert.equal(
    navs.find((nav) => nav.dataset.view === "cases").hidden,
    true,
    "Publisher does not acquire case roles",
  );
  assert.equal(elements.get("new-case-open").hidden, true);
  assert.equal(
    run("state.view"),
    "policies",
    "Publisher enters an authorized management view",
  );
  await run("loadSettings()");
  assert(
    requests.some((request) => request.url === "/api/v2/adapters/status"),
    "Adapter status uses its own endpoint",
  );
  run('state.session.principal.roles=["REVIEWER","AUDITOR"];');

  run(
    'state.session={principal:{user_id:"verifier",roles:["QUALITY","ADMIN"]}}; state.token="test-token";',
  );
  fetcher = async (url) =>
    response(
      url === "/api/v2/feedback/fb-1"
        ? {
            id: "fb-1",
            version: 7,
            data: {
              status: "PENDING_VERIFICATION",
              submitted_by: "author",
              correction: { reason_code: "verified" },
            },
          }
        : url === "/api/v2/runtime-configs/config-1"
          ? { id: "config-1", version: 2, data: { model: "frozen-model" } }
          : { items: [], summary: {} },
    );
  await run('showRuntime("config-1")');
  assert(
    requests.some((r) => r.url === "/api/v2/runtime-configs/config-1"),
    "Runtime viewer fetches full authorized detail",
  );
  assert.equal(
    elements.get("editor-fields").children[0].children[0].readOnly,
    true,
  );
  await run('openFeedback("fb-1", "CONFIRMED")');
  formValues = { reason: "independently checked" };
  await elements.get("editor-form").onsubmit({ preventDefault() {} });
  const verified = JSON.parse(
    requests.findLast((r) => r.url === "/api/v2/feedback/fb-1/status").options
      .body,
  );
  assert.equal(verified.expected_version, 7);
  assert.equal(verified.status, "CONFIRMED");
  assert.equal(verified.reason, "independently checked");
  run('state.session.principal.user_id="author";');
  await assert.rejects(run('openFeedback("fb-1", "CONFIRMED")'), /另一位/);
  run('state.session.principal.roles=["REVIEWER","AUDITOR"];');

  run(
    'state.session.principal.user_id="reviewer"; state.session.principal.roles=["REVIEWER"]; state.current.risk_level="HIGH"; state.current.review={status:"IN_REVIEW",owner:"reviewer"}; renderReviewPermissions(state.current,state.current.decision);',
  );
  assert.equal(
    elements.get("review-approve").disabled,
    true,
    "High risk approval needs a supervisor",
  );
  assert.match(elements.get("review-approve").title, /主管/);
  run('state.session.principal.roles=["REVIEWER","AUDITOR"];');

  const oldRequests = [];
  fetcher = (url) =>
    new Promise((resolve) => oldRequests.push({ url, resolve }));
  const operations = run("loadOperations()");
  await tick();
  run("disconnect()");
  run(
    'state.session={principal:{user_id:"new-agent",roles:["AGENT"]}}; state.token="new-token";',
  );
  oldRequests.forEach((request) =>
    request.resolve(
      response({
        summary: { private_marker: 100 },
        items: [{ id: "old-private" }],
        cursor: "old",
      }),
    ),
  );
  await operations;
  assert.equal(
    elements.get("metrics").children.length,
    0,
    "Old operations results stay cleared after identity switch",
  );
  assert.equal(elements.get("audit-list").children.length, 0);
  assert.equal(elements.get("editor-fields").children.length, 0);
  assert.equal(elements.get("case-query").value, "");
  assert.equal(run("state.current"), null);
  assert.equal(run("state.auditCursor"), null);
  assert.equal(elements.get("workspace").inert, true);

  fetcher = async () => ({
    status: 401,
    ok: false,
    text: async () => "not-json",
  });
  await assert.rejects(run('api("/session")'), /凭证失效/);
  assert.equal(
    run("state.token"),
    "",
    "Even a malformed 401 clears credentials and old data",
  );
  run("disconnect()");
  assert(
    navs.every((n) => n.hidden),
    "Disconnect clears role navigation",
  );
  console.log(
    "PASS: startup auth, SQL query parameters, stale filter response, audit cursor, independent review, typed amounts, publisher roles, adapter endpoint, runtime detail, verified feedback version/self-check, identity clearing, malformed 401",
  );
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
