"use strict";

/* ===================================================================
 * PubTrack frontend: plain JavaScript, no framework.
 * All user-provided text is inserted with textContent (never innerHTML)
 * so manuscript titles or review comments cannot inject HTML/JS (XSS).
 * =================================================================== */

const state = {
    users: [],
    journals: [],
    user: null,      // the user we are "acting as"
    page: 1,
    view: "manuscripts",
    openId: null,    // manuscript open in the drawer
};

const STATUS_LABELS = {
    SUBMITTED: "Submitted",
    UNDER_REVIEW: "Under review",
    REVISION_REQUESTED: "Revision requested",
    ACCEPTED: "Accepted",
    REJECTED: "Rejected",
    WITHDRAWN: "Withdrawn",
};
const RECOMMENDATION_LABELS = {
    ACCEPT: "Accept",
    MINOR_REVISION: "Minor revision",
    MAJOR_REVISION: "Major revision",
    REJECT: "Reject",
};

/* ---------------------------------------------------------------- helpers */

const $ = (id) => document.getElementById(id);

/** Creates an element: el("td", {class: "num", text: "5"}, child1, child2) */
function el(tag, props = {}, ...children) {
    const node = document.createElement(tag);
    for (const [key, value] of Object.entries(props)) {
        if (value === undefined || value === null) continue;
        if (key === "class") node.className = value;
        else if (key === "text") node.textContent = value;
        else if (key.startsWith("on")) node.addEventListener(key.slice(2), value);
        else node.setAttribute(key, value);
    }
    for (const child of children.flat()) {
        if (child === null || child === undefined || child === false) continue;
        node.append(child instanceof Node ? child : document.createTextNode(String(child)));
    }
    return node;
}

async function api(method, path, data) {
    const options = { method, headers: {} };
    if (state.user) options.headers["X-User-Id"] = String(state.user.id);
    if (data) options.body = new URLSearchParams(data); // sent as application/x-www-form-urlencoded
    const response = await fetch(path, options);
    const json = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(json.error || `Request failed (${response.status})`);
    return json;
}

function formatDate(iso) {
    if (!iso) return "—";
    return new Date(iso).toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" });
}

function formatDateTime(iso) {
    if (!iso) return "—";
    return new Date(iso).toLocaleString("en-IN", {
        day: "numeric", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit",
    });
}

function statusBadge(status) {
    return el("span", { class: `badge ${status}`, text: STATUS_LABELS[status] || status });
}

function emptyRow(colspan, message) {
    return el("tr", {}, el("td", { colspan, class: "empty", text: message }));
}

let toastTimer;
function toast(message, isError = false) {
    const t = $("toast");
    t.textContent = message;
    t.className = isError ? "toast error" : "toast";
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => t.classList.add("hidden"), 3500);
}

/** Runs an async action and reports any error as a toast. */
async function attempt(fn) {
    try {
        await fn();
    } catch (err) {
        toast(err.message, true);
    }
}

function rememberUser(id) {
    try { localStorage.setItem("pubtrack.user", String(id)); } catch (e) { /* storage unavailable */ }
}

function savedUserId() {
    try { return Number(localStorage.getItem("pubtrack.user")); } catch (e) { return 0; }
}

/* ------------------------------------------------------------ navigation */

function applyRoleToTabs() {
    document.querySelectorAll("#tabs button").forEach((b) => {
        const role = b.dataset.role;
        b.classList.toggle("hidden", Boolean(role) && role !== state.user.role);
    });
    const activeTab = document.querySelector(`#tabs button[data-view="${state.view}"]`);
    if (activeTab.classList.contains("hidden")) state.view = "manuscripts";
}

function showView(view) {
    state.view = view;
    document.querySelectorAll("#tabs button").forEach((b) => b.classList.toggle("active", b.dataset.view === view));
    document.querySelectorAll(".view").forEach((s) => s.classList.toggle("hidden", s.id !== `view-${view}`));
    if (view === "manuscripts") loadManuscripts();
    if (view === "reviews") loadMyReviews();
    if (view === "dashboard") loadDashboard();
}

/* ---------------------------------------------------------- manuscripts */

async function loadManuscripts() {
    await attempt(async () => {
        const params = new URLSearchParams({ page: state.page });
        const q = $("filter-q").value.trim();
        if (q) params.set("q", q);
        if ($("filter-status").value) params.set("status", $("filter-status").value);
        if ($("filter-journal").value) params.set("journalId", $("filter-journal").value);

        const result = await api("GET", `/api/manuscripts?${params}`);
        const rows = result.items.map((m) =>
            el("tr", { class: "clickable", onclick: () => openDetail(m.id) },
                el("td", { text: m.id }),
                el("td", { text: m.title }),
                el("td", { text: m.journalName }),
                el("td", { text: m.authorName }),
                el("td", {}, statusBadge(m.status)),
                el("td", { text: m.reviewsTotal ? `${m.reviewsDone}/${m.reviewsTotal}` : "—" }),
                el("td", { text: formatDate(m.updatedAt) }),
            ));
        $("manuscript-rows").replaceChildren(...(rows.length ? rows : [emptyRow(7, "No manuscripts found")]));
        renderPager(result);
    });
}

function renderPager({ page, pageSize, total }) {
    const pages = Math.max(1, Math.ceil(total / pageSize));
    $("pager").replaceChildren(
        el("span", { text: `${total} manuscript${total === 1 ? "" : "s"} · page ${page} of ${pages}` }),
        el("span", {},
            el("button", { class: "btn", text: "← Prev", ...(page <= 1 ? { disabled: "" } : {}),
                onclick: () => { state.page--; loadManuscripts(); } }),
            el("button", { class: "btn", text: "Next →", ...(page >= pages ? { disabled: "" } : {}),
                onclick: () => { state.page++; loadManuscripts(); } }),
        ),
    );
}

/* --------------------------------------------------------------- detail */

async function openDetail(id) {
    state.openId = id;
    await attempt(async () => {
        const detail = await api("GET", `/api/manuscripts/${id}`);
        await renderDetail(detail);
        $("overlay").classList.remove("hidden");
        $("drawer").classList.remove("hidden");
    });
}

function closeDetail() {
    state.openId = null;
    $("overlay").classList.add("hidden");
    $("drawer").classList.add("hidden");
}

async function renderDetail({ manuscript: m, reviews, history, actions }) {
    const keywords = (m.keywords || "").split(",").map((k) => k.trim()).filter(Boolean);
    const body = [
        statusBadge(m.status),
        el("h2", { text: m.title }),
        el("div", { class: "meta" },
            `#${m.id} · ${m.journalName} · by ${m.authorName} · round ${m.currentRound}`),
        el("div", { class: "meta" },
            `Submitted ${formatDate(m.submittedAt)}` + (m.decidedAt ? ` · decided ${formatDate(m.decidedAt)}` : "")),
    ];

    if (actions.length) {
        body.push(el("h3", { text: "Actions" }), await renderActions(m, reviews, actions));
    }

    body.push(el("h3", { text: "Abstract" }), el("p", { class: "abstract", text: m.abstractText }));
    if (keywords.length) {
        body.push(el("div", { class: "chips" }, keywords.map((k) => el("span", { class: "chip", text: k }))));
    }

    body.push(el("h3", { text: "Reviews" }));
    if (!reviews.length) {
        const hint = state.user.role === "AUTHOR"
            ? "Reviews become visible once the editor has made a decision."
            : "No reviews yet.";
        body.push(el("p", { class: "meta", text: hint }));
    }
    for (const r of reviews) {
        const status = r.completedAt
            ? el("span", { class: "badge done", text: RECOMMENDATION_LABELS[r.recommendation] })
            : r.overdue
                ? el("span", { class: "badge overdue", text: `Overdue (due ${formatDate(r.dueAt)})` })
                : el("span", { class: "badge", text: `Pending · due ${formatDate(r.dueAt)}` });
        body.push(el("div", { class: "review" },
            el("div", { class: "head" }, el("span", { text: `${r.reviewerName} · round ${r.round}` }), status),
            r.comments ? el("p", { text: r.comments }) : null,
        ));
    }

    body.push(el("h3", { text: "History" }), el("ul", { class: "timeline" }, history.map((h) =>
        el("li", {},
            el("div", {}, h.fromStatus ? `${STATUS_LABELS[h.fromStatus]} → ` : "", el("strong", { text: STATUS_LABELS[h.toStatus] })),
            el("div", { class: "when", text: `${formatDateTime(h.changedAt)} · ${h.changedBy}` }),
            h.note ? el("div", { text: h.note }) : null,
        ))));

    $("drawer-body").replaceChildren(...body);
}

/** Builds a form for each action the server says this user can take. */
async function renderActions(m, reviews, actions) {
    const box = el("div", { class: "actions" });
    const act = (fn, success) => attempt(async () => {
        await fn();
        toast(success);
        await openDetail(m.id);
        if (state.view === "manuscripts") loadManuscripts();
        if (state.view === "reviews") loadMyReviews();
    });

    if (actions.includes("ASSIGN_REVIEWER")) {
        const stats = await api("GET", "/api/stats");
        const taken = new Set(reviews.filter((r) => r.round === m.currentRound).map((r) => r.reviewerId));
        const select = el("select", {}, stats.reviewers
            .filter((r) => !taken.has(r.id))
            .map((r) => el("option", { value: r.id, text: `${r.name} — ${r.pending} pending` })));
        box.append(el("div", { class: "row" }, select,
            el("button", { class: "btn primary", text: "Assign reviewer",
                onclick: () => act(() => api("POST", `/api/manuscripts/${m.id}/reviewers`, { reviewerId: select.value }),
                    "Reviewer assigned") })));
    }

    if (actions.includes("DECIDE")) {
        const decision = el("select", {},
            el("option", { value: "ACCEPT", text: "Accept" }),
            el("option", { value: "REVISE", text: "Request revision" }),
            el("option", { value: "REJECT", text: "Reject" }));
        const note = el("input", { placeholder: "Note to the author (optional)", maxlength: 500 });
        box.append(el("div", { class: "row" }, decision,
            el("button", { class: "btn primary", text: "Record decision",
                onclick: () => act(() => api("POST", `/api/manuscripts/${m.id}/decision`,
                    { decision: decision.value, note: note.value }), "Decision recorded") })), note);
    }

    if (actions.includes("DESK_REJECT")) {
        const note = el("input", { placeholder: "Reason for desk rejection (optional)", maxlength: 500 });
        box.append(note, el("div", { class: "row" },
            el("button", { class: "btn danger", text: "Desk reject",
                onclick: () => act(() => api("POST", `/api/manuscripts/${m.id}/decision`,
                    { decision: "REJECT", note: note.value }), "Manuscript rejected") })));
    }

    for (const action of actions.filter((a) => a.startsWith("REVIEW:"))) {
        const assignmentId = action.split(":")[1];
        const rec = el("select", {}, Object.entries(RECOMMENDATION_LABELS)
            .map(([value, text]) => el("option", { value, text })));
        const comments = el("textarea", { rows: 5, placeholder: "Comments for the editor and author (min. 20 characters)" });
        box.append(el("strong", { text: "Your review" }), rec, comments, el("div", { class: "row" },
            el("button", { class: "btn primary", text: "Submit review",
                onclick: () => act(() => api("POST", `/api/reviews/${assignmentId}`,
                    { recommendation: rec.value, comments: comments.value }), "Review submitted") })));
    }

    if (actions.includes("RESUBMIT")) {
        const note = el("input", { placeholder: "What changed in this revision? (optional)", maxlength: 500 });
        box.append(note, el("div", { class: "row" },
            el("button", { class: "btn primary", text: "Submit revised version",
                onclick: () => act(() => api("POST", `/api/manuscripts/${m.id}/resubmit`, { note: note.value }),
                    "Revision submitted") })));
    }

    if (actions.includes("WITHDRAW")) {
        box.append(el("div", { class: "row" },
            el("button", { class: "btn danger", text: "Withdraw manuscript",
                onclick: () => {
                    if (confirm("Withdraw this manuscript? This cannot be undone.")) {
                        act(() => api("POST", `/api/manuscripts/${m.id}/withdraw`), "Manuscript withdrawn");
                    }
                } })));
    }
    return box;
}

/* --------------------------------------------------------------- submit */

async function submitManuscript(event) {
    event.preventDefault();
    const form = event.target;
    await attempt(async () => {
        const created = await api("POST", "/api/manuscripts", Object.fromEntries(new FormData(form)));
        form.reset();
        toast("Manuscript submitted");
        showView("manuscripts");
        openDetail(created.id);
    });
}

/* ------------------------------------------------------------- reviews */

async function loadMyReviews() {
    await attempt(async () => {
        const list = await api("GET", "/api/reviews/mine");
        const rows = list.map((r) => {
            const status = r.completedAt
                ? el("span", { class: "badge done", text: `Done · ${RECOMMENDATION_LABELS[r.recommendation]}` })
                : r.overdue ? el("span", { class: "badge overdue", text: "Overdue" })
                    : el("span", { class: "badge", text: "Pending" });
            return el("tr", { class: "clickable", onclick: () => openDetail(r.manuscriptId) },
                el("td", { text: r.manuscriptTitle }),
                el("td", { text: r.round }),
                el("td", { text: formatDate(r.assignedAt) }),
                el("td", { text: formatDate(r.dueAt) }),
                el("td", {}, status));
        });
        $("review-rows").replaceChildren(...(rows.length ? rows : [emptyRow(5, "No review assignments")]));
    });
}

/* ------------------------------------------------------------ dashboard */

async function loadDashboard() {
    await attempt(async () => {
        const { journals, reviewers, overdue } = await api("GET", "/api/stats");
        const sum = (rows, key) => rows.reduce((total, r) => total + Number(r[key] || 0), 0);

        const decided = sum(journals, "accepted") + sum(journals, "rejected");
        const tiles = [
            ["Total submissions", sum(journals, "total")],
            ["In progress", sum(journals, "in_progress")],
            ["Acceptance rate", decided ? `${Math.round((100 * sum(journals, "accepted")) / decided)}%` : "—"],
            ["Overdue reviews", overdue.length],
        ];
        $("stat-tiles").replaceChildren(...tiles.map(([label, value]) =>
            el("div", { class: "tile" }, el("div", { class: "label", text: label }), el("div", { class: "value", text: value }))));

        const n = (v) => (v === null || v === undefined ? "—" : v);
        $("journal-stats").replaceChildren(...journals.map((j) => el("tr", {},
            el("td", { text: j.name }),
            el("td", { class: "num", text: j.total }),
            el("td", { class: "num", text: j.in_progress }),
            el("td", { class: "num", text: j.accepted }),
            el("td", { class: "num", text: j.rejected }),
            el("td", { class: "num", text: j.acceptance_rate === null ? "—" : `${j.acceptance_rate}%` }),
            el("td", { class: "num", text: n(j.avg_days_to_decision) }))));

        $("reviewer-stats").replaceChildren(...reviewers.map((r) => el("tr", {},
            el("td", { text: r.name }),
            el("td", { class: "num", text: r.assigned }),
            el("td", { class: "num", text: r.completed }),
            el("td", { class: "num", text: r.pending }),
            el("td", { class: "num", text: r.overdue }),
            el("td", { class: "num", text: r.late }),
            el("td", { class: "num", text: n(r.avg_turnaround_days) }))));

        const overdueRows = overdue.map((o) => el("tr", { class: "clickable", onclick: () => openDetail(o.manuscript_id) },
            el("td", { text: o.title }),
            el("td", { text: o.journal }),
            el("td", { text: o.reviewer }),
            el("td", { text: formatDate(o.due_at) }),
            el("td", { class: "num", text: o.days_overdue }),
            el("td", { text: o.reminder_sent ? "Sent" : "Not yet" })));
        $("overdue-rows").replaceChildren(...(overdueRows.length ? overdueRows : [emptyRow(6, "Nothing overdue")]));
    });
}

/* ----------------------------------------------------------------- init */

function selectUser(id) {
    state.user = state.users.find((u) => u.id === id) || state.users[0];
    $("user-select").value = state.user.id;
    rememberUser(state.user.id);
    state.page = 1;
    closeDetail();
    applyRoleToTabs();
    showView(state.view);
}

async function init() {
    await attempt(async () => {
        [state.users, state.journals] = await Promise.all([api("GET", "/api/users"), api("GET", "/api/journals")]);

        const groups = { EDITOR: "Editors", AUTHOR: "Authors", REVIEWER: "Reviewers" };
        $("user-select").replaceChildren(...Object.entries(groups).map(([role, label]) =>
            el("optgroup", { label }, state.users.filter((u) => u.role === role)
                .map((u) => el("option", { value: u.id, text: u.fullName })))));

        const journalOptions = () => state.journals.map((j) => el("option", { value: j.id, text: j.name }));
        $("filter-journal").append(...journalOptions());
        $("submit-journal").replaceChildren(...journalOptions());

        const saved = savedUserId();
        const firstEditor = state.users.find((u) => u.role === "EDITOR");
        selectUser(state.users.some((u) => u.id === saved) ? saved : firstEditor.id);
    });
}

$("user-select").addEventListener("change", (e) => selectUser(Number(e.target.value)));
$("tabs").addEventListener("click", (e) => {
    if (e.target.dataset.view) showView(e.target.dataset.view);
});
$("filters").addEventListener("submit", (e) => {
    e.preventDefault();
    state.page = 1;
    loadManuscripts();
});
$("submit-form").addEventListener("submit", submitManuscript);
$("drawer-close").addEventListener("click", closeDetail);
$("overlay").addEventListener("click", closeDetail);
document.addEventListener("keydown", (e) => { if (e.key === "Escape") closeDetail(); });

init();
