const $ = selector => document.querySelector(selector);
const $$ = selector => [...document.querySelectorAll(selector)];

let state = {};
let currentView = 'overview';
let draftReqLines = [];
let inventoryTerm = '';
let inventoryWarehouse = '';
let ingredientTerm = '';

const esc = value => String(value ?? '').replace(/[&<>"']/g, character => ({
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;'
}[character]));
const money = value => '¥ ' + Number(value || 0).toLocaleString('zh-CN', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2
});
const number = value => Number(value || 0);
const fmtDate = value => value ? String(value).slice(0, 10) : '-';
const fmtDateTime = value => value ? new Date(value).toLocaleString('zh-CN', {hour12: false}) : '-';
const userField = key => state.user?.[key] ?? state.user?.[key.toLowerCase()] ?? state.user?.[key.toUpperCase()];

async function api(url, options = {}) {
  const headers = {
    'Content-Type': 'application/json',
    'X-CSRF-Token': sessionStorage.csrf || '',
    ...options.headers
  };
  const response = await fetch('/api' + url, {...options, headers});
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw Error(data.message || '请求失败');
  return data;
}

function toast(message) {
  const element = $('#toast');
  element.textContent = message;
  element.style.display = 'block';
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => element.style.display = 'none', 2600);
}

function permissions() {
  if (Array.isArray(state.permissions)) return state.permissions;
  const raw = userField('PERMISSIONS') || '';
  return String(raw).split(',').map(value => value.trim()).filter(Boolean);
}

function can(permission) {
  return permissions().includes(permission);
}

function canAny(...values) {
  return values.some(value => can(value));
}

function viewAllowed(view) {
  if (view === 'overview' || view === 'settings') return true;
  if (view === 'inventory') return canAny('stock', 'report');
  if (view === 'ingredients') return can('stock');
  if (view === 'requisition') return canAny('kitchen', 'stock');
  if (view === 'purchase' || view === 'suppliers') return can('purchase');
  if (view === 'reports') return can('report');
  return false;
}

function syncHeader() {
  const name = userField('NAME') || userField('USERNAME') || '食材用户';
  const role = userField('ROLE_NAME') || '工作空间';
  $('#userName').textContent = name;
  $('#roleName').textContent = role;
  $('#userRole').textContent = role;
  $('#avatar').textContent = String(name).slice(0, 1);
  const alertCount = number(state.stats?.EXPIRING_COUNT) + number(state.stats?.LOW_COUNT);
  $('#navAlert').textContent = alertCount;
  $('#today').textContent = new Date().toLocaleDateString('zh-CN', {
    year: 'numeric',
    month: 'long',
    day: 'numeric'
  });
}

function applyPermissions() {
  $$('[data-permission]').forEach(element => {
    element.classList.toggle('hidden', !can(element.dataset.permission));
  });
  $$('[data-any-permission]').forEach(element => {
    const allowed = element.dataset.anyPermission.split(',').some(permission => can(permission.trim()));
    element.classList.toggle('hidden', !allowed);
  });
}

function boot(data) {
  state = data;
  $('#login').classList.add('hidden');
  $('#app').classList.remove('hidden');
  syncHeader();
  applyPermissions();
  render('overview');
}

async function refresh() {
  state = await api('/data');
  syncHeader();
  applyPermissions();
  render(currentView);
}

function render(view) {
  if (!viewAllowed(view)) view = 'overview';
  if (!views[view]) view = 'overview';
  document.body.classList.remove('nav-open');
  currentView = view;
  $$('.nav').forEach(button => button.classList.toggle('active', button.dataset.view === view));
  const names = {
    overview: '运营概览',
    inventory: '库存台账',
    ingredients: '食材档案',
    requisition: '门店领料',
    purchase: '采购管理',
    suppliers: '供应商',
    reports: '统计报表',
    settings: '系统设置'
  };
  $('#pageName').textContent = names[view];
  $('#pageTitle').textContent = view === 'overview' ? '早上好，今天也辛苦了。' : names[view];
  $('#quickAction').style.display = view === 'overview' && can('kitchen') ? 'block' : 'none';
  $('#content').innerHTML = views[view]();
  applyPermissions();
  wire(view);
}

function cards() {
  const stats = state.stats || {};
  return `<div class="metrics">
    <div class="metric"><div class="metric-top"><span>库存总值</span><span class="metric-icon">¥</span></div><div class="metric-value">${money(stats.STOCK_VALUE)}</div><div class="metric-foot"><span class="up">实时</span> · 当前批次成本汇总</div></div>
    <div class="metric"><div class="metric-top"><span>食材品类</span><span class="metric-icon">类</span></div><div class="metric-value">${stats.INGREDIENT_COUNT || 0}<small> 类</small></div><div class="metric-foot">覆盖 ${(state.categories || []).length} 个业务分类</div></div>
    <div class="metric"><div class="metric-top"><span>临期预警</span><span class="metric-icon">△</span></div><div class="metric-value">${stats.EXPIRING_COUNT || 0}<small> 批</small></div><div class="metric-foot"><span class="warn">三日内到期，优先处理</span></div></div>
    <div class="metric"><div class="metric-top"><span>库存不足</span><span class="metric-icon">!</span></div><div class="metric-value">${stats.LOW_COUNT || 0}<small> 项</small></div><div class="metric-foot"><span class="warn">低于安全库存线</span></div></div>
  </div>`;
}

function expiringBatches() {
  return (state.batches || [])
    .filter(batch => daysUntil(batch.EXPIRY_DATE) <= 3)
    .sort((left, right) => new Date(left.EXPIRY_DATE) - new Date(right.EXPIRY_DATE));
}

function categoryHealth() {
  const groups = new Map();
  (state.ingredients || []).forEach(ingredient => {
    const name = ingredient.CATEGORY_NAME || '未分类';
    const group = groups.get(name) || {stock: 0, max: 0};
    group.stock += number(ingredient.STOCK);
    group.max += number(ingredient.MAX_STOCK);
    groups.set(name, group);
  });
  return [...groups.entries()].map(([name, values]) => ({
    name,
    percent: values.max > 0 ? Math.min(100, Math.round(values.stock / values.max * 100)) : 0
  }));
}

function daysUntil(value) {
  if (!value) return 999;
  return Math.ceil((new Date(value) - Date.now()) / 86400000);
}

function healthPanel() {
  const items = categoryHealth();
  return `<div class="health-list">${items.map(item => `<div class="health-item"><div class="health-head"><span>${esc(item.name)}</span><b>${item.percent}%</b></div><div class="bar"><i class="${item.percent < 55 ? 'amber' : ''}" style="width:${item.percent}%"></i></div></div>`).join('') || '<div class="empty">暂无库存健康数据</div>'}</div>`;
}

function alertsPanel() {
  const alerts = expiringBatches();
  return alerts.slice(0, 4).map(item => {
    const days = daysUntil(item.EXPIRY_DATE);
    const expired = days < 0;
    return `<div class="alert-item"><i class="alert-dot ${expired ? 'red' : ''}"></i><div><b>${esc(item.INGREDIENT_NAME)}</b><span>${expired ? '已过期' : '即将到期'} · ${esc(item.BATCH_NO)}</span></div><time>${expired ? `逾期 ${Math.abs(days)} 天` : `${days} 天`}</time></div>`;
  }).join('') || '<div class="empty">当前没有临期批次</div>';
}

function movementPanel() {
  const items = (state.movements || []).slice(0, 5);
  return `<div class="movement-list">${items.map(item => {
    const quantity = number(item.QUANTITY);
    const sign = item.KIND === 'IN' ? '+' : item.KIND === 'OUT' ? '-' : quantity > 0 ? '+' : '';
    return `<div class="movement-item"><span class="movement-kind ${item.KIND === 'OUT' || (item.KIND === 'ADJUST' && quantity < 0) ? 'out' : ''}">${esc(item.KIND)}</span><div><b>${esc(item.INGREDIENT_NAME)}</b><span>${esc(item.BATCH_NO)} · ${fmtDateTime(item.CREATED_AT)}</span></div><strong>${sign}${quantity}</strong></div>`;
  }).join('') || '<div class="empty">暂无库存流水</div>'}</div>`;
}

function requisitionStatus(status) {
  if (status === 'PENDING') return ['待审批', 'amber'];
  if (status === 'APPROVED') return ['已通过', 'green'];
  if (status === 'COMPLETED') return ['已完成', 'gray'];
  return [status || '未知', 'gray'];
}

function reqTable(limit, statusFilter = '') {
  let rows = [...(state.requisitions || [])];
  if (statusFilter) rows = rows.filter(row => row.STATUS === statusFilter);
  if (limit) rows = rows.slice(0, limit);
  return `<table><thead><tr><th>申请单号</th><th>门店</th><th>申请人</th><th>申请时间</th><th>明细</th><th>状态</th><th>操作</th></tr></thead><tbody>${rows.map(row => {
    const status = requisitionStatus(row.STATUS);
    const operations = [`<button class="link-button detail-req" data-id="${row.ID}">查看</button>`];
    if (row.STATUS === 'PENDING' && can('stock')) {
      operations.push(`<button class="link-button approve-req" data-id="${row.ID}">审批</button>`);
    } else if (row.STATUS === 'APPROVED' && can('stock')) {
      operations.push(`<button class="link-button complete-req" data-id="${row.ID}">确认出库</button>`);
    }
    return `<tr><td data-label="申请单号"><code>${esc(row.NUMBER)}</code></td><td data-label="门店">${esc(row.STORE_NAME)}</td><td data-label="申请人">${esc(row.CREATOR)}</td><td data-label="申请时间">${fmtDateTime(row.CREATED_AT)}</td><td data-label="明细">${row.LINE_COUNT || 0} 项 / ${number(row.TOTAL_QUANTITY)}</td><td data-label="状态"><span class="status ${status[1]}">${status[0]}</span></td><td data-label="操作"><div class="table-actions">${operations.join('')}</div></td></tr>`;
  }).join('') || '<tr><td colspan="7" class="empty">暂无领料申请</td></tr>'}</tbody></table>`;
}

function purchaseStatus(status) {
  if (status === 'PENDING') return ['待入库', 'amber'];
  if (status === 'RECEIVED') return ['已入库', 'green'];
  return [status || '未知', 'gray'];
}

function purchaseTable() {
  return `<table><thead><tr><th>订单编号</th><th>供应商</th><th>到货仓库</th><th>金额</th><th>明细</th><th>状态</th><th>操作</th></tr></thead><tbody>${(state.purchaseOrders || []).map(order => {
    const status = purchaseStatus(order.STATUS);
    const operation = order.STATUS === 'PENDING' && can('purchase')
      ? `<button class="link-button receive-po" data-id="${order.ID}">确认入库</button>`
      : '-';
    return `<tr><td data-label="订单编号"><code>${esc(order.NUMBER)}</code></td><td data-label="供应商">${esc(order.SUPPLIER_NAME)}</td><td data-label="到货仓库">${esc(order.WAREHOUSE_NAME)}</td><td data-label="金额">${money(order.TOTAL)}</td><td data-label="明细">${order.LINE_COUNT || 0} 项</td><td data-label="状态"><span class="status ${status[1]}">${status[0]}</span></td><td data-label="操作">${operation}</td></tr>`;
  }).join('') || '<tr><td colspan="7" class="empty">暂无采购订单</td></tr>'}</tbody></table>`;
}

function suppliersTable(term = '') {
  const keyword = term.trim().toLowerCase();
  const rows = (state.suppliers || []).filter(supplier => !keyword ||
    [supplier.NAME, supplier.CONTACT, supplier.PHONE, supplier.ADDRESS]
      .join(' ').toLowerCase().includes(keyword));
  return rows.map(supplier => `<tr><td data-label="供应商"><b>${esc(supplier.NAME)}</b></td><td data-label="联系人">${esc(supplier.CONTACT || '-')}</td><td data-label="电话">${esc(supplier.PHONE || '-')}</td><td data-label="地址">${esc(supplier.ADDRESS || '-')}</td><td data-label="状态"><span class="status green">合作中</span></td></tr>`).join('') ||
    '<tr><td colspan="5" class="empty">没有匹配的供应商</td></tr>';
}

function usersTable() {
  return (state.users || []).map(user => `<tr><td data-label="账号"><b>${esc(user.USERNAME)}</b></td><td data-label="显示名称">${esc(user.NAME)}</td><td data-label="角色"><span class="status ${user.ROLE_CODE === 'ADMIN' ? 'green' : 'gray'}">${esc(user.ROLE_NAME)}</span></td><td data-label="门店">${esc(user.STORE_NAME || '全部数据')}</td><td data-label="状态"><span class="status ${user.ACTIVE ? 'green' : 'red'}">${user.ACTIVE ? '启用' : '停用'}</span></td></tr>`).join('') ||
    '<tr><td colspan="5" class="empty">暂无账户</td></tr>';
}

function auditLogsTable() {
  const labels = {
    CREATE_REQUISITION: '创建领料单',
    APPROVE_REQUISITION: '审批领料单',
    COMPLETE_REQUISITION: '领料出库',
    CREATE_PURCHASE: '创建采购单',
    RECEIVE_PURCHASE: '采购入库',
    CREATE_SUPPLIER: '新增供应商',
    CREATE_INGREDIENT: '新增食材',
    UPDATE_INGREDIENT: '编辑食材',
    ADJUST_STOCK: '库存盘点',
    UPDATE_PROFILE: '修改资料',
    CHANGE_PASSWORD: '修改密码',
    CREATE_USER: '创建账户'
  };
  return (state.auditLogs || []).slice(0, 20).map(log => `<tr><td data-label="时间">${fmtDateTime(log.CREATED_AT)}</td><td data-label="操作人">${esc(log.ACTOR_NAME || '系统')}</td><td data-label="操作"><span class="status gray">${esc(labels[log.ACTION] || log.ACTION)}</span></td><td data-label="详情">${esc(log.DETAIL || '-')}</td></tr>`).join('') ||
    '<tr><td colspan="4" class="empty">暂无操作记录</td></tr>';
}

function monthBuckets(count = 6) {
  const now = new Date();
  return Array.from({length: count}, (_, index) => {
    const date = new Date(now.getFullYear(), now.getMonth() - (count - 1 - index), 1);
    return {
      key: `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`,
      label: `${date.getMonth() + 1}月`,
      total: 0
    };
  });
}

function purchaseTrend() {
  const months = monthBuckets();
  (state.purchaseOrders || []).forEach(order => {
    const date = new Date(order.CREATED_AT);
    const key = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
    const month = months.find(item => item.key === key);
    if (month) month.total += number(order.TOTAL);
  });
  const max = Math.max(...months.map(item => item.total), 1);
  return {months, max, total: months.reduce((sum, item) => sum + item.total, 0)};
}

function categoryCosts() {
  const groups = new Map();
  (state.purchaseLines || []).forEach(line => {
    const name = line.CATEGORY_NAME || '其他';
    groups.set(name, (groups.get(name) || 0) + number(line.QUANTITY) * number(line.UNIT_COST));
  });
  const total = [...groups.values()].reduce((sum, value) => sum + value, 0);
  return [...groups.entries()]
    .map(([name, value]) => ({name, value, percent: total > 0 ? Math.round(value / total * 100) : 0}))
    .sort((left, right) => right.value - left.value);
}

const views = {
  overview: () => `${cards()}
    <div class="page-section">
      <div class="section-head">
        <div><h2>库存运行状态</h2><p>按分类汇总当前库存，并标出最近需要处理的批次</p></div>
        ${canAny('stock', 'report') ? '<button class="link-button" data-go="inventory">打开库存台账 →</button>' : ''}
      </div>
      <div class="dashboard-grid">
        <div class="panel">
          <div class="panel-title-line"><div><span class="panel-index">01 / STOCK HEALTH</span><h2>分类库存水位</h2></div><span class="status green">实时汇总</span></div>
          ${healthPanel()}
        </div>
        <div class="panel">
          <div class="panel-title-line"><div><span class="panel-index">02 / ATTENTION</span><h2>需要关注</h2></div><span class="status amber">${expiringBatches().length} 条</span></div>
          <div class="attention-list">${alertsPanel()}</div>
        </div>
      </div>
      <div class="lower-grid">
        <div class="panel">
          <div class="panel-title-line"><div><span class="panel-index">03 / MOVEMENT</span><h2>最近库存流水</h2></div><span class="kpi-note">${(state.movements || []).length} 条</span></div>
          ${movementPanel()}
        </div>
        <div class="panel">
          <div class="panel-title-line"><div><span class="panel-index">04 / REQUISITION</span><h2>最近领料动态</h2></div><button class="link-button" data-go="requisition">查看全部 →</button></div>
          ${reqTable(3)}
        </div>
      </div>
    </div>`,

  inventory: () => `<div class="toolbar"><input class="search" id="filter" value="${esc(inventoryTerm)}" placeholder="⌕  搜索食材名称或批次"><select class="select" id="warehouseFilter"><option value="">全部仓库</option>${(state.warehouses || []).map(warehouse => `<option value="${warehouse.ID}" ${String(inventoryWarehouse) === String(warehouse.ID) ? 'selected' : ''}>${esc(warehouse.NAME)}</option>`).join('')}</select><button class="secondary" onclick="location.href='/api/export/inventory'">⇩ 导出台账</button></div><div class="full-panel"><div class="section-head"><div><span class="panel-index">INVENTORY / BATCH LEDGER</span><h2>库存批次台账</h2><p>共 <span id="batchCount">${(state.batches || []).length}</span> 条批次记录 · 按到期日排序</p></div><span class="status green">实时数据</span></div><table><thead><tr><th>食材</th><th>批次号</th><th>所在仓库</th><th>当前数量</th><th>单位成本</th><th>到期日期</th><th>状态</th><th>操作</th></tr></thead><tbody id="batchRows">${batchRows(filteredBatches())}</tbody></table></div>`,

  ingredients: () => `<div class="toolbar"><input class="search" id="ingredientFilter" value="${esc(ingredientTerm)}" placeholder="⌕  搜索食材、分类或规格"><button class="primary" id="newIngredient">＋ 新增食材</button></div><div class="full-panel"><div class="section-head"><div><span class="panel-index">MASTER DATA / INGREDIENTS</span><h2>食材主数据</h2><p>共 ${(state.ingredients || []).length} 种食材 · 安全库存与保质期设置</p></div><span class="status green">基础档案</span></div><table><thead><tr><th>食材</th><th>分类</th><th>规格</th><th>单位</th><th>参考价</th><th>保质期</th><th>库存线</th><th>当前库存</th><th>操作</th></tr></thead><tbody id="ingredientRows">${ingredientRows()}</tbody></table></div>`,

  requisition: () => `<div class="toolbar">${can('kitchen') ? '<button class="primary" id="newReq">＋ 创建领料申请</button>' : ''}<select class="select" id="reqStatus"><option value="">全部状态</option><option value="PENDING">待审批</option><option value="APPROVED">已通过</option><option value="COMPLETED">已完成</option></select></div><div class="full-panel"><div class="section-head"><div><h2>领料申请</h2><p>提交、审批和确认出库会同步更新库存流水</p></div></div><div id="reqRows">${reqTable(null)}</div></div>`,

  purchase: () => {
    const total = (state.purchaseOrders || []).reduce((sum, order) => sum + number(order.TOTAL), 0);
    const pending = (state.purchaseOrders || []).filter(order => order.STATUS === 'PENDING').length;
    return `<div class="metrics compact-metrics" style="margin-bottom:18px"><div class="metric"><div class="metric-top"><span>采购总额</span><span class="metric-icon">¥</span></div><div class="metric-value">${money(total)}</div><div class="metric-foot">来自真实采购订单</div></div><div class="metric"><div class="metric-top"><span>待入库订单</span><span class="metric-icon">!</span></div><div class="metric-value">${pending}<small> 笔</small></div><div class="metric-foot">确认后自动增加库存</div></div></div><div class="full-panel"><div class="section-head"><div><span class="panel-index">PURCHASE / ORDER FLOW</span><h2>采购订单</h2><p>采购计划、供应商报价及到货确认</p></div><button class="primary" id="newPurchase">＋ 新建采购单</button></div><div id="purchaseRows">${purchaseTable()}</div></div>`;
  },

  suppliers: () => `<div class="toolbar"><input class="search" id="supplierFilter" placeholder="⌕  搜索供应商"><button class="primary" id="newSupplier">＋ 新增供应商</button></div><div class="full-panel"><div class="section-head"><div><span class="panel-index">SUPPLIER / DIRECTORY</span><h2>供应商档案</h2><p>共 ${(state.suppliers || []).length} 家合作供应商</p></div></div><table><thead><tr><th>供应商名称</th><th>联系人</th><th>联系电话</th><th>地址</th><th>合作状态</th></tr></thead><tbody id="supplierRows">${suppliersTable()}</tbody></table></div>`,

  reports: () => {
    const trend = purchaseTrend();
    const costs = categoryCosts();
    return `<div class="report-grid"><div class="panel"><div class="section-head"><div><span class="panel-index">REPORT / PURCHASE TREND</span><h2>采购金额趋势</h2><p>近 6 个月采购支出</p></div><span class="kpi-note">${money(trend.total)}</span></div><div class="chart">${trend.months.map(month => `<div class="column" style="height:${Math.max(6, Math.round(month.total / trend.max * 96))}%"><strong>${month.total ? Math.round(month.total / 1000) + 'k' : '0'}</strong></div>`).join('')}</div><div class="chart-labels">${trend.months.map(month => `<span>${month.label}</span>`).join('')}</div></div><div class="panel"><div class="section-head"><div><span class="panel-index">REPORT / COST MIX</span><h2>食材成本占比</h2><p>按采购明细分类汇总</p></div></div><div class="health-list">${costs.map((item, index) => `<div class="health-item"><div class="health-head"><span>${esc(item.name)}</span><b>${item.percent}%</b></div><div class="bar"><i class="${index === 1 ? 'amber' : ''}" style="width:${item.percent}%"></i></div></div>`).join('') || '<div class="empty">暂无采购明细</div>'}</div></div></div><div class="panel report-export"><div class="section-head"><div><span class="panel-index">EXPORT / CSV</span><h2>报表导出</h2><p>导出当前库存批次台账</p></div><button class="primary" onclick="location.href='/api/export/inventory'">⇩ 导出库存台账</button></div></div>${can('system') ? `<div class="panel audit-panel"><div class="section-head"><div><span class="panel-index">SECURITY / AUDIT TRAIL</span><h2>操作审计</h2><p>最近 20 条关键业务与账户操作</p></div><span class="status green">管理员可见</span></div><table><thead><tr><th>时间</th><th>操作人</th><th>操作</th><th>详情</th></tr></thead><tbody>${auditLogsTable()}</tbody></table></div>` : ''}`;
  },

  settings: () => `<div class="settings-grid">
    <div class="form-panel">
      <div class="section-head"><div><span class="panel-index">PROFILE / ACCOUNT</span><h2>个人资料</h2><p>当前登录账号与数据范围</p></div></div>
      <div class="form-grid">
        <label>登录账号<input value="${esc(userField('USERNAME'))}" disabled></label>
        <label>显示名称<input id="displayName" value="${esc(userField('NAME'))}" maxlength="50"></label>
        <label>所属角色<input value="${esc(userField('ROLE_NAME'))}" disabled></label>
        <label>数据范围<input value="${esc(userField('STORE_ID') ? '本门店' : '全部数据')}" disabled></label>
      </div>
      <div class="form-actions"><button class="primary" id="saveProfile">保存资料</button></div>
    </div>
    <div class="form-panel">
      <div class="section-head"><div><span class="panel-index">SECURITY / PASSWORD</span><h2>修改密码</h2><p>修改后请使用新密码登录</p></div></div>
      <div class="form-grid password-grid">
        <label>当前密码<input id="currentPassword" type="password" autocomplete="current-password" maxlength="72"></label>
        <label>新密码<input id="newPassword" type="password" autocomplete="new-password" maxlength="72" placeholder="至少 8 个字符"></label>
        <label>确认新密码<input id="confirmPassword" type="password" autocomplete="new-password" maxlength="72"></label>
      </div>
      <div class="form-actions"><button class="primary" id="changePassword">更新密码</button></div>
    </div>
  </div>
  ${can('system') ? `<div class="settings-account-grid">
    <div class="full-panel">
      <div class="section-head"><div><span class="panel-index">SYSTEM / USER DIRECTORY</span><h2>账户与权限</h2><p>共 ${(state.users || []).length} 个系统账号</p></div><span class="status green">管理员可见</span></div>
      <table><thead><tr><th>账号</th><th>显示名称</th><th>角色</th><th>门店</th><th>状态</th></tr></thead><tbody>${usersTable()}</tbody></table>
    </div>
    <div class="form-panel">
      <div class="section-head"><div><span class="panel-index">SYSTEM / NEW ACCOUNT</span><h2>创建账户</h2><p>分配角色和门店访问范围</p></div></div>
      <div class="form-grid account-form-grid">
        <label>登录账号<input id="newUsername" autocomplete="off" maxlength="30" placeholder="字母、数字或 ._-"></label>
        <label>显示名称<input id="newDisplayName" maxlength="50"></label>
        <label>初始密码<input id="newUserPassword" type="password" autocomplete="new-password" maxlength="72"></label>
        <label>确认密码<input id="newUserPasswordConfirm" type="password" autocomplete="new-password" maxlength="72"></label>
        <label>账号角色<select id="newUserRole">${(state.roles || []).map(role => `<option value="${role.ID}">${esc(role.NAME)}</option>`).join('')}</select></label>
        <label>所属门店<select id="newUserStore"><option value="">不绑定门店</option>${storeOptions()}</select></label>
      </div>
      <p class="form-hint">厨房账号必须绑定门店，管理员与仓储账号默认拥有全局数据范围。</p>
      <div class="form-actions"><button class="primary" id="createUser">创建账户</button></div>
    </div>
  </div>` : ''}`
};

function batchRows(rows) {
  return (rows || []).map(batch => {
    const days = daysUntil(batch.EXPIRY_DATE);
    const status = days < 0 ? ['已过期', 'red'] : days <= 3 ? ['临期', 'amber'] : ['正常', 'green'];
    return `<tr><td data-label="食材"><b>${esc(batch.INGREDIENT_NAME)}</b><small class="cell-note">${esc(batch.SUPPLIER_NAME || '未记录供应商')}</small></td><td data-label="批次号"><code>${esc(batch.BATCH_NO)}</code></td><td data-label="所在仓库">${esc(batch.WAREHOUSE_NAME)}</td><td data-label="当前数量"><b>${number(batch.QUANTITY)}</b></td><td data-label="单位成本">${money(batch.UNIT_COST)}</td><td data-label="到期日期">${fmtDate(batch.EXPIRY_DATE)}</td><td data-label="状态"><span class="status ${status[1]}">${status[0]}</span></td><td data-label="操作">${can('stock') ? `<button class="link-button adjust-stock" data-id="${batch.ID}">盘点</button>` : '-'}</td></tr>`;
  }).join('') || '<tr><td colspan="8" class="empty">暂无库存记录</td></tr>';
}

function ingredientRows(term = ingredientTerm) {
  const keyword = term.trim().toLowerCase();
  const rows = (state.ingredients || []).filter(ingredient => !keyword ||
    [ingredient.NAME, ingredient.CATEGORY_NAME, ingredient.SPECIFICATION, ingredient.UNIT]
      .join(' ').toLowerCase().includes(keyword));
  return rows.map(ingredient => `<tr>
    <td data-label="食材"><b>${esc(ingredient.NAME)}</b></td>
    <td data-label="分类">${esc(ingredient.CATEGORY_NAME)}</td>
    <td data-label="规格">${esc(ingredient.SPECIFICATION || '-')}</td>
    <td data-label="单位">${esc(ingredient.UNIT)}</td>
    <td data-label="参考价">${money(ingredient.PRICE)}</td>
    <td data-label="保质期">${ingredient.SHELF_DAYS ?? '-'} 天</td>
    <td data-label="库存线">${number(ingredient.MIN_STOCK)} / ${number(ingredient.MAX_STOCK)}</td>
    <td data-label="当前库存"><b>${number(ingredient.STOCK)}</b></td>
    <td data-label="操作"><button class="link-button edit-ingredient" data-id="${ingredient.ID}">编辑</button></td>
  </tr>`).join('') || '<tr><td colspan="9" class="empty">没有匹配的食材</td></tr>';
}

function wireIngredientButtons() {
  $$('.edit-ingredient').forEach(button => button.onclick = () => openIngredientForm(button.dataset.id));
}

function updateIngredientRows() {
  $('#ingredientRows').innerHTML = ingredientRows();
  wireIngredientButtons();
}

function filteredBatches() {
  const keyword = inventoryTerm.trim().toLowerCase();
  return (state.batches || []).filter(batch => {
    const matchesText = !keyword || `${batch.INGREDIENT_NAME} ${batch.BATCH_NO}`.toLowerCase().includes(keyword);
    const matchesWarehouse = !inventoryWarehouse || String(batch.WAREHOUSE_ID) === String(inventoryWarehouse);
    return matchesText && matchesWarehouse;
  });
}

function updateInventoryRows() {
  const rows = filteredBatches();
  $('#batchRows').innerHTML = batchRows(rows);
  $('#batchCount').textContent = rows.length;
  wireStockButtons();
}

function wireStockButtons() {
  $$('.adjust-stock').forEach(button => button.onclick = () => openStockAdjustForm(button.dataset.id));
}

function openStockAdjustForm(batchId) {
  const batch = (state.batches || []).find(item => String(item.ID) === String(batchId));
  if (!batch) return toast('库存批次不存在');
  $('#stockAdjustForm')?.remove();
  $('#content').insertAdjacentHTML('afterbegin', `<div class="form-panel operation-form" id="stockAdjustForm">
    <div class="section-head"><div><span class="panel-index">STOCKTAKE / ADJUSTMENT</span><h2>库存盘点</h2><p>${esc(batch.INGREDIENT_NAME)} · ${esc(batch.BATCH_NO)} · 当前 ${number(batch.QUANTITY)}</p></div><button class="link-button" id="closeAdjust">关闭</button></div>
    <div class="form-grid"><label>实际数量<input id="actualQuantity" type="number" min="0" step="0.001" value="${number(batch.QUANTITY)}"></label><label>盘点说明<input id="adjustNote" maxlength="160" placeholder="例如：盘亏、盘盈或损耗"></label></div>
    <div class="form-actions"><button class="secondary" id="cancelAdjust">取消</button><button class="primary" id="saveAdjust" data-id="${batch.ID}">确认调整</button></div>
  </div>`);
  $('#closeAdjust').onclick = $('#cancelAdjust').onclick = () => $('#stockAdjustForm').remove();
  $('#saveAdjust').onclick = async () => {
    const actualQuantity = Number($('#actualQuantity').value);
    if (!Number.isFinite(actualQuantity) || actualQuantity < 0) return toast('请输入有效的实际数量');
    await act('adjust-stock', {
      batchId: batch.ID,
      actualQuantity,
      note: $('#adjustNote').value
    });
  };
}

function openIngredientForm(ingredientId = '') {
  const ingredient = (state.ingredients || []).find(item => String(item.ID) === String(ingredientId));
  const firstCategory = state.categories?.[0]?.ID || '';
  $('#ingredientForm')?.remove();
  $('#content').insertAdjacentHTML('afterbegin', `<div class="form-panel operation-form" id="ingredientForm">
    <div class="section-head"><div><span class="panel-index">MASTER DATA / INGREDIENT FORM</span><h2>${ingredient ? '编辑食材' : '新增食材'}</h2><p>维护分类、规格、保质期和库存线</p></div><button class="link-button" id="closeIngredient">关闭</button></div>
    <div class="form-grid">
      <label>食材名称<input id="ingredientName" maxlength="100" value="${esc(ingredient?.NAME || '')}"></label>
      <label>分类<select id="ingredientCategory">${(state.categories || []).map(category => `<option value="${category.ID}" ${String(category.ID) === String(ingredient?.CATEGORY_ID || firstCategory) ? 'selected' : ''}>${esc(category.NAME)}</option>`).join('')}</select></label>
      <label>规格<input id="ingredientSpecification" maxlength="100" value="${esc(ingredient?.SPECIFICATION || '')}"></label>
      <label>计量单位<input id="ingredientUnit" maxlength="20" value="${esc(ingredient?.UNIT || 'kg')}"></label>
      <label>参考单价<input id="ingredientPrice" type="number" min="0" step="0.01" value="${number(ingredient?.PRICE).toFixed(2)}"></label>
      <label>保质期（天）<input id="ingredientShelfDays" type="number" min="0" step="1" value="${ingredient?.SHELF_DAYS ?? 7}"></label>
      <label>安全库存<input id="ingredientMinStock" type="number" min="0" step="0.001" value="${number(ingredient?.MIN_STOCK).toFixed(3)}"></label>
      <label>目标库存<input id="ingredientMaxStock" type="number" min="0" step="0.001" value="${number(ingredient?.MAX_STOCK).toFixed(3)}"></label>
    </div>
    <div class="form-actions"><button class="secondary" id="cancelIngredient">取消</button><button class="primary" id="saveIngredient" data-id="${ingredient?.ID || ''}">保存食材</button></div>
  </div>`);
  $('#closeIngredient').onclick = $('#cancelIngredient').onclick = () => $('#ingredientForm').remove();
  $('#saveIngredient').onclick = async () => {
    const name = $('#ingredientName').value.trim();
    const unit = $('#ingredientUnit').value.trim();
    const price = Number($('#ingredientPrice').value);
    const shelfDays = Number($('#ingredientShelfDays').value);
    const minStock = Number($('#ingredientMinStock').value);
    const maxStock = Number($('#ingredientMaxStock').value);
    if (!name) return toast('食材名称不能为空');
    if (!unit) return toast('计量单位不能为空');
    if ([price, shelfDays, minStock, maxStock].some(value => !Number.isFinite(value) || value < 0)) return toast('价格、保质期和库存线必须有效');
    if (maxStock > 0 && maxStock < minStock) return toast('目标库存不能小于安全库存');
    await act('save-ingredient', {
      id: $('#saveIngredient').dataset.id || '',
      name,
      categoryId: $('#ingredientCategory').value,
      specification: $('#ingredientSpecification').value,
      unit,
      price,
      shelfDays,
      minStock,
      maxStock
    });
  };
}

function refreshReqRows() {
  const status = $('#reqStatus')?.value || '';
  $('#reqRows').innerHTML = reqTable(null, status);
  wireReqButtons();
}

function wireReqButtons() {
  $$('.detail-req').forEach(button => button.onclick = () => showRequisitionDetail(button.dataset.id));
  $$('.approve-req').forEach(button => button.onclick = () => {
    if (confirm('确认审批这张领料单？')) act('approve-requisition', {id: button.dataset.id});
  });
  $$('.complete-req').forEach(button => button.onclick = () => {
    if (confirm('确认出库？库存将按到期日优先扣减。')) act('complete-requisition', {id: button.dataset.id});
  });
}

function showRequisitionDetail(id) {
  const requisition = (state.requisitions || []).find(item => String(item.ID) === String(id));
  if (!requisition) return toast('领料单不存在');
  const lines = (state.requisitionLines || []).filter(line => String(line.REQUISITION_ID) === String(id));
  const status = requisitionStatus(requisition.STATUS);
  $('#requisitionModal')?.remove();
  document.body.insertAdjacentHTML('beforeend', `<div class="modal-backdrop" id="requisitionModal">
    <div class="modal-card">
      <div class="modal-head"><div><span class="panel-index">REQUISITION / DETAIL</span><h2>${esc(requisition.NUMBER)}</h2><p>${esc(requisition.STORE_NAME)} · ${esc(requisition.CREATOR)} · ${fmtDateTime(requisition.CREATED_AT)}</p></div><button class="modal-close" id="closeRequisition" aria-label="关闭">×</button></div>
      <div class="modal-meta"><span class="status ${status[1]}">${status[0]}</span><span>${lines.length} 项明细</span><span>合计 ${number(requisition.TOTAL_QUANTITY)}</span></div>
      <div class="modal-lines">${lines.map(line => `<div class="modal-line"><div><b>${esc(line.INGREDIENT_NAME)}</b><span>${esc(line.REQUISITION_NUMBER)}</span></div><strong>${number(line.QUANTITY)} ${esc(line.UNIT)}</strong></div>`).join('') || '<div class="empty">这张领料单没有明细</div>'}</div>
      ${requisition.NOTE ? `<div class="modal-note"><span>备注</span><p>${esc(requisition.NOTE)}</p></div>` : ''}
      <div class="modal-actions"><button class="secondary" id="dismissRequisition">关闭</button></div>
    </div>
  </div>`);
  $('#closeRequisition').onclick = $('#dismissRequisition').onclick = closeRequisitionModal;
  $('#requisitionModal').onclick = event => {
    if (event.target.id === 'requisitionModal') closeRequisitionModal();
  };
}

function closeRequisitionModal() {
  $('#requisitionModal')?.remove();
}

function refreshPurchaseRows() {
  $('#purchaseRows').innerHTML = purchaseTable();
  wirePurchaseButtons();
}

function wirePurchaseButtons() {
  $$('.receive-po').forEach(button => button.onclick = () => {
    if (confirm('确认采购入库？将生成新的库存批次。')) act('receive-purchase', {id: button.dataset.id});
  });
}

function wire(view) {
  $$('[data-go]').forEach(button => button.onclick = () => render(button.dataset.go));
  wireReqButtons();
  if (view === 'inventory') {
    $('#filter').oninput = event => {
      inventoryTerm = event.target.value;
      updateInventoryRows();
    };
    $('#warehouseFilter').onchange = event => {
      inventoryWarehouse = event.target.value;
      updateInventoryRows();
    };
    wireStockButtons();
  }
  if (view === 'ingredients') {
    $('#newIngredient').onclick = () => openIngredientForm();
    $('#ingredientFilter').oninput = event => {
      ingredientTerm = event.target.value;
      updateIngredientRows();
    };
    wireIngredientButtons();
  }
  if (view === 'requisition') {
    $('#newReq')?.addEventListener('click', newRequisition);
    $('#reqStatus').onchange = refreshReqRows;
    wireReqButtons();
  }
  if (view === 'purchase') {
    $('#newPurchase').onclick = newPurchase;
    wirePurchaseButtons();
  }
  if (view === 'suppliers') {
    $('#newSupplier').onclick = newSupplier;
    $('#supplierFilter').oninput = event => {
      $('#supplierRows').innerHTML = suppliersTable(event.target.value);
    };
  }
  if (view === 'settings') {
    $('#saveProfile').onclick = saveProfile;
    $('#changePassword').onclick = changePassword;
    if ($('#createUser')) {
      $('#createUser').onclick = createUser;
      $('#newUserRole').onchange = syncNewUserStore;
      syncNewUserStore();
    }
  }
}

async function act(action, body) {
  try {
    await api('/action/' + action, {method: 'POST', body: JSON.stringify(body)});
    toast('操作成功');
    await refresh();
  } catch (error) {
    toast(error.message);
  }
}

function storeOptions(selected = '') {
  return (state.stores || []).map(store => `<option value="${store.ID}" ${String(selected) === String(store.ID) ? 'selected' : ''}>${esc(store.NAME)}</option>`).join('');
}

function warehouseOptions(storeId, selected = '') {
  return (state.warehouses || [])
    .filter(warehouse => !storeId || warehouse.STORE_ID == null || String(warehouse.STORE_ID) === String(storeId))
    .map(warehouse => `<option value="${warehouse.ID}" ${String(selected) === String(warehouse.ID) ? 'selected' : ''}>${esc(warehouse.NAME)}</option>`)
    .join('');
}

function ingredientOptions(selected = '') {
  return (state.ingredients || []).map(ingredient => `<option value="${ingredient.ID}" ${String(selected) === String(ingredient.ID) ? 'selected' : ''}>${esc(ingredient.NAME)} · ${esc(ingredient.UNIT)}</option>`).join('');
}

function newRequisition() {
  if (!can('kitchen')) return toast('当前角色不能创建领料单');
  draftReqLines = [];
  const ownStore = userField('STORE_ID');
  const stores = ownStore ? (state.stores || []).filter(store => String(store.ID) === String(ownStore)) : (state.stores || []);
  const storeId = stores[0]?.ID || '';
  $('#content').insertAdjacentHTML('afterbegin', `<div class="form-panel operation-form" id="reqForm">
    <div class="section-head"><h2>创建领料申请</h2><button class="link-button" id="closeReq">关闭</button></div>
    <div class="form-grid"><label>门店<select id="reqStore">${stores.map(store => `<option value="${store.ID}">${esc(store.NAME)}</option>`).join('')}</select></label><label>出库仓库<select id="reqWarehouse">${warehouseOptions(storeId)}</select></label><label>备注<textarea id="reqNote" maxlength="500" placeholder="填写本次领料用途"></textarea></label></div>
    <div class="inline-entry"><select id="reqIngredient">${ingredientOptions()}</select><input id="reqQuantity" type="number" min="0.001" step="0.001" placeholder="数量"><button class="secondary" id="addReqLine">添加明细</button></div>
    <div id="reqDraft"></div>
    <div class="form-actions"><button class="secondary" id="cancelReq">取消</button><button class="primary" id="saveReq">提交申请</button></div>
  </div>`);
  $('#reqStore').onchange = event => {
    $('#reqWarehouse').innerHTML = warehouseOptions(event.target.value);
  };
  $('#addReqLine').onclick = addReqLine;
  $('#closeReq').onclick = $('#cancelReq').onclick = () => $('#reqForm').remove();
  $('#saveReq').onclick = submitRequisition;
  renderReqDraft();
}

function addReqLine() {
  const ingredientId = $('#reqIngredient').value;
  const ingredient = (state.ingredients || []).find(item => String(item.ID) === String(ingredientId));
  const quantity = Number($('#reqQuantity').value);
  if (!ingredient || !Number.isFinite(quantity) || quantity <= 0) return toast('请选择食材并填写有效数量');
  const existing = draftReqLines.find(line => String(line.ingredientId) === String(ingredientId));
  if (existing) {
    existing.quantity += quantity;
  } else {
    draftReqLines.push({ingredientId, name: ingredient.NAME, unit: ingredient.UNIT, quantity});
  }
  $('#reqQuantity').value = '';
  renderReqDraft();
}

function renderReqDraft() {
  const container = $('#reqDraft');
  if (!container) return;
  container.innerHTML = draftReqLines.map((line, index) => `<div class="detail-row"><span>${esc(line.name)}</span><b>${line.quantity} ${esc(line.unit)}</b><button class="link-button remove-line" data-index="${index}">移除</button></div>`).join('') || '<p class="cell-note">尚未添加领料明细</p>';
  $$('.remove-line').forEach(button => button.onclick = () => {
    draftReqLines.splice(Number(button.dataset.index), 1);
    renderReqDraft();
  });
}

async function submitRequisition() {
  if (!draftReqLines.length) return toast('请至少添加一条领料明细');
  if (!$('#reqStore').value || !$('#reqWarehouse').value) return toast('请选择门店和出库仓库');
  await act('create-requisition', {
    storeId: $('#reqStore').value,
    warehouseId: $('#reqWarehouse').value,
    note: $('#reqNote').value,
    lines: draftReqLines.map(line => ({ingredientId: line.ingredientId, quantity: line.quantity}))
  });
  draftReqLines = [];
}

function newPurchase() {
  if (!can('purchase')) return toast('当前角色不能创建采购单');
  if (!(state.suppliers || []).length) return toast('请先新增供应商');
  const firstIngredient = state.ingredients?.[0];
  $('#content').insertAdjacentHTML('afterbegin', `<div class="form-panel operation-form" id="purchaseForm">
    <div class="section-head"><h2>新建采购单</h2><button class="link-button" id="closePurchase">关闭</button></div>
    <div class="form-grid"><label>供应商<select id="poSupplier">${(state.suppliers || []).map(supplier => `<option value="${supplier.ID}">${esc(supplier.NAME)}</option>`).join('')}</select></label><label>到货仓库<select id="poWarehouse">${warehouseOptions('')}</select></label><label>食材<select id="poIngredient">${ingredientOptions(firstIngredient?.ID)}</select></label><label>数量<input id="poQuantity" type="number" min="0.001" step="0.001" value="1"></label><label>单价<input id="poUnitCost" type="number" min="0" step="0.01" value="${number(firstIngredient?.PRICE).toFixed(2)}"></label></div>
    <div class="form-actions"><button class="secondary" id="cancelPurchase">取消</button><button class="primary" id="savePurchase">创建采购单</button></div>
  </div>`);
  $('#poIngredient').onchange = event => {
    const ingredient = (state.ingredients || []).find(item => String(item.ID) === String(event.target.value));
    $('#poUnitCost').value = number(ingredient?.PRICE).toFixed(2);
  };
  $('#closePurchase').onclick = $('#cancelPurchase').onclick = () => $('#purchaseForm').remove();
  $('#savePurchase').onclick = async () => {
    const quantity = Number($('#poQuantity').value);
    const unitCost = Number($('#poUnitCost').value);
    if (quantity <= 0 || unitCost < 0) return toast('数量和单价必须有效');
    await act('create-purchase', {
      supplierId: $('#poSupplier').value,
      warehouseId: $('#poWarehouse').value,
      lines: [{ingredientId: $('#poIngredient').value, quantity, unitCost}]
    });
  };
}

function newSupplier() {
  if (!can('purchase')) return toast('当前角色不能新增供应商');
  $('#content').insertAdjacentHTML('afterbegin', `<div class="form-panel operation-form" id="supplierForm">
    <div class="section-head"><h2>新增供应商</h2><button class="link-button" id="closeSupplier">关闭</button></div>
    <div class="form-grid"><label>供应商名称<input id="supplierName" maxlength="100"></label><label>联系人<input id="supplierContact" maxlength="50"></label><label>联系电话<input id="supplierPhone" maxlength="30"></label><label>地址<input id="supplierAddress" maxlength="200"></label></div>
    <div class="form-actions"><button class="secondary" id="cancelSupplier">取消</button><button class="primary" id="saveSupplier">保存供应商</button></div>
  </div>`);
  $('#closeSupplier').onclick = $('#cancelSupplier').onclick = () => $('#supplierForm').remove();
  $('#saveSupplier').onclick = async () => {
    const name = $('#supplierName').value.trim();
    if (!name) return toast('供应商名称不能为空');
    await act('create-supplier', {
      name,
      contact: $('#supplierContact').value,
      phone: $('#supplierPhone').value,
      address: $('#supplierAddress').value
    });
  };
}

async function changePassword() {
  const currentPassword = $('#currentPassword').value;
  const newPassword = $('#newPassword').value;
  const confirmPassword = $('#confirmPassword').value;
  if (!currentPassword) return toast('请输入当前密码');
  if (newPassword.length < 8) return toast('新密码至少需要 8 个字符');
  if (newPassword !== confirmPassword) return toast('两次输入的新密码不一致');
  await act('change-password', {currentPassword, newPassword});
}

function syncNewUserStore() {
  const roleId = $('#newUserRole')?.value;
  const role = (state.roles || []).find(item => String(item.ID) === String(roleId));
  const storeSelect = $('#newUserStore');
  if (!storeSelect) return;
  const kitchen = role?.CODE === 'KITCHEN';
  storeSelect.disabled = !kitchen;
  if (!kitchen) {
    storeSelect.value = '';
  } else if (!storeSelect.value) {
    storeSelect.value = state.stores?.[0]?.ID || '';
  }
}

async function createUser() {
  const username = $('#newUsername').value.trim();
  const name = $('#newDisplayName').value.trim();
  const password = $('#newUserPassword').value;
  const confirmPassword = $('#newUserPasswordConfirm').value;
  const roleId = $('#newUserRole').value;
  const role = (state.roles || []).find(item => String(item.ID) === String(roleId));
  if (!/^[a-z0-9._-]{3,30}$/i.test(username)) return toast('账号格式不正确');
  if (!name) return toast('显示名称不能为空');
  if (password.length < 8) return toast('初始密码至少需要 8 个字符');
  if (password !== confirmPassword) return toast('两次输入的密码不一致');
  const storeId = role?.CODE === 'KITCHEN' ? $('#newUserStore').value : '';
  if (role?.CODE === 'KITCHEN' && !storeId) return toast('请为厨房账号选择门店');
  await act('create-user', {username, password, name, roleId, storeId});
}

function saveProfile() {
  const name = $('#displayName').value.trim();
  if (!name) return toast('显示名称不能为空');
  act('update-profile', {name});
}

$('#loginForm').onsubmit = async event => {
  event.preventDefault();
  $('#loginError').textContent = '';
  try {
    const data = await api('/login', {
      method: 'POST',
      body: JSON.stringify({username: $('#username').value, password: $('#password').value})
    });
    sessionStorage.csrf = data.csrf;
    boot(data);
    await refresh();
  } catch (error) {
    $('#loginError').textContent = error.message;
  }
};

$$('[data-user]').forEach(button => button.onclick = () => {
  $('#username').value = button.dataset.user;
  $('#password').value = 'Demo@123456';
});

function closeNavigation() {
  document.body.classList.remove('nav-open');
}

$('#navToggle').onclick = () => document.body.classList.toggle('nav-open');
$('#navClose').onclick = closeNavigation;
$('#navBackdrop').onclick = closeNavigation;
document.addEventListener('keydown', event => {
  if (event.key === 'Escape') {
    closeNavigation();
    closeRequisitionModal();
  }
});

$$('.nav').forEach(button => button.onclick = () => render(button.dataset.view));
$('#quickAction').onclick = () => {
  if (currentView !== 'requisition') render('requisition');
  newRequisition();
};
$('#logout').onclick = async () => {
  try {
    await api('/logout', {method: 'POST'});
  } finally {
    sessionStorage.clear();
    location.reload();
  }
};

(function tick() {
  const element = $('#loginTime');
  if (!element) return;
  const now = new Date();
  element.textContent = now.toLocaleTimeString('zh-CN', {
    hour12: false,
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  });
  setTimeout(tick, 1000);
})();

(async () => {
  try {
    const data = await api('/me');
    sessionStorage.csrf = data.csrf;
    boot(data);
    await refresh();
  } catch (error) {
    // An anonymous visitor stays on the login screen.
  }
})();
