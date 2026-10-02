import { createRouter, createWebHashHistory } from 'vue-router'
import MainLayout from './layouts/MainLayout.vue'
import { api, setAuthProblemHandler } from './lib/api.js'
import { can, session, setUser } from './lib/session.js'

// Hash history: cms-core serves index.html as a plain static file, no server-side route fallback needed.
// meta.role: 'write' | 'supervise' | 'admin' limits who may open a page (the API enforces the same).
const routes = [
  { path: '/login', name: 'login', component: () => import('./pages/LoginPage.vue'), meta: { title: 'Sign in', public: true } },
  {
    path: '/',
    component: MainLayout,
    children: [
      { path: '', name: 'dashboard', component: () => import('./pages/DashboardPage.vue'), meta: { title: 'Dashboard' } },
      { path: 'issue', name: 'issue', component: () => import('./pages/IssueWizardPage.vue'), meta: { title: 'Issue card', role: 'write' } },
      { path: 'customers', name: 'customers', component: () => import('./pages/CustomersPage.vue'), meta: { title: 'Customers' } },
      { path: 'customers/:id', name: 'customer', component: () => import('./pages/CustomerDetailPage.vue'), props: true, meta: { title: 'Customer' } },
      { path: 'accounts', name: 'accounts', component: () => import('./pages/AccountsPage.vue'), meta: { title: 'Accounts' } },
      { path: 'accounts/:id', name: 'account', component: () => import('./pages/AccountDetailPage.vue'), props: true, meta: { title: 'Account' } },
      { path: 'cards', name: 'cards', component: () => import('./pages/CardsPage.vue'), meta: { title: 'Cards' } },
      { path: 'cards/:id', name: 'card', component: () => import('./pages/CardDetailPage.vue'), props: true, meta: { title: 'Card' } },
      { path: 'transactions', name: 'transactions', component: () => import('./pages/TransactionsPage.vue'), meta: { title: 'Transactions' } },
      { path: 'fraud', name: 'fraud', component: () => import('./pages/FraudAlertsPage.vue'), meta: { title: 'Fraud alerts' } },
      { path: 'setup/fee-plans', name: 'fee-plans', component: () => import('./pages/setup/FeePlansPage.vue'), meta: { title: 'Fee plans' } },
      { path: 'setup/fx-rates', name: 'fx-rates', component: () => import('./pages/setup/FxRatesPage.vue'), meta: { title: 'FX rates' } },
      { path: 'setup/fraud-rules', name: 'fraud-rules', component: () => import('./pages/setup/FraudRulesPage.vue'), meta: { title: 'Fraud rules' } },
      { path: 'approvals', name: 'approvals', component: () => import('./pages/ApprovalsPage.vue'), meta: { title: 'Approvals' } },
      { path: 'switch-simulator', name: 'switch-sim', component: () => import('./pages/SwitchSimulatorPage.vue'), meta: { title: 'Switch simulator', role: 'write' } },
      { path: 'notifications', name: 'notifications', component: () => import('./pages/NotificationsPage.vue'), meta: { title: 'Notifications' } },
      { path: 'setup/message-templates', name: 'message-templates', component: () => import('./pages/setup/MessageTemplatesPage.vue'), meta: { title: 'Message templates' } },
      { path: 'core-banking', name: 'core-banking', component: () => import('./pages/CoreBankingPage.vue'), meta: { title: 'Core banking' } },
      { path: 'gl', name: 'gl', component: () => import('./pages/GlPage.vue'), meta: { title: 'GL accounts' } },
      { path: 'batch', name: 'batch', component: () => import('./pages/BatchJobsPage.vue'), meta: { title: 'Batch jobs' } },
      { path: 'audit', name: 'audit', component: () => import('./pages/AuditPage.vue'), meta: { title: 'Audit log' } },
      { path: 'admin/users', name: 'users', component: () => import('./pages/UsersPage.vue'), meta: { title: 'Users', role: 'admin' } },
      { path: 'admin/approval-policy', name: 'approval-policy', component: () => import('./pages/ApprovalPolicyPage.vue'), meta: { title: 'Approval policy' } },
      { path: 'setup/currencies', name: 'currencies', component: () => import('./pages/setup/CurrenciesPage.vue'), meta: { title: 'Currencies' } },
      { path: 'setup/segments', name: 'segments', component: () => import('./pages/setup/SegmentsPage.vue'), meta: { title: 'Customer segments' } },
      { path: 'setup/account-types', name: 'account-types', component: () => import('./pages/setup/AccountTypesPage.vue'), meta: { title: 'Account types' } },
      { path: 'setup/products', name: 'products', component: () => import('./pages/setup/ProductsPage.vue'), meta: { title: 'Card products' } },
      { path: 'setup/products/new', name: 'product-new', component: () => import('./pages/setup/ProductEditPage.vue'), meta: { title: 'New card product', role: 'supervise' } },
      { path: 'setup/products/:code', name: 'product', component: () => import('./pages/setup/ProductEditPage.vue'), props: true, meta: { title: 'Card product' } },
      { path: 'setup/numbering', name: 'numbering', component: () => import('./pages/setup/NumberingPage.vue'), meta: { title: 'Numbering & settings' } },
      { path: ':pathMatch(.*)*', redirect: '/' }
    ]
  }
]

const router = createRouter({ history: createWebHashHistory(), routes })

setAuthProblemHandler(kind => {
  const here = router.currentRoute.value
  if (here.name === 'login') return
  router.replace({ name: 'login', query: { next: here.fullPath, mode: kind === 'password' ? 'password' : undefined } })
})

router.beforeEach(async to => {
  if (to.meta.public) return true
  if (!session.loaded || !session.user) {
    try {
      setUser(await api.get('/auth/me', { quiet: true }))
    } catch {
      return { name: 'login', query: { next: to.fullPath } }
    }
  }
  if (session.user.mustChangePassword) return { name: 'login', query: { next: to.fullPath, mode: 'password' } }
  if (to.meta.role && !can[to.meta.role]) return { name: 'dashboard' }
  return true
})

router.afterEach(to => {
  document.title = to.meta.title ? `${to.meta.title} · Quasar` : 'Quasar'
})

export default router
