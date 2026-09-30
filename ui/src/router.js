import { createRouter, createWebHashHistory } from 'vue-router'
import MainLayout from './layouts/MainLayout.vue'

// Hash history: cms-core serves index.html as a plain static file, no server-side route fallback needed.
const routes = [
  {
    path: '/',
    component: MainLayout,
    children: [
      { path: '', name: 'dashboard', component: () => import('./pages/DashboardPage.vue'), meta: { title: 'Dashboard' } },
      { path: 'issue', name: 'issue', component: () => import('./pages/IssueWizardPage.vue'), meta: { title: 'Issue card' } },
      { path: 'customers', name: 'customers', component: () => import('./pages/CustomersPage.vue'), meta: { title: 'Customers' } },
      { path: 'customers/:id', name: 'customer', component: () => import('./pages/CustomerDetailPage.vue'), props: true, meta: { title: 'Customer' } },
      { path: 'accounts', name: 'accounts', component: () => import('./pages/AccountsPage.vue'), meta: { title: 'Accounts' } },
      { path: 'accounts/:id', name: 'account', component: () => import('./pages/AccountDetailPage.vue'), props: true, meta: { title: 'Account' } },
      { path: 'cards', name: 'cards', component: () => import('./pages/CardsPage.vue'), meta: { title: 'Cards' } },
      { path: 'cards/:id', name: 'card', component: () => import('./pages/CardDetailPage.vue'), props: true, meta: { title: 'Card' } },
      { path: 'transactions', name: 'transactions', component: () => import('./pages/TransactionsPage.vue'), meta: { title: 'Transactions' } },
      { path: 'gl', name: 'gl', component: () => import('./pages/GlPage.vue'), meta: { title: 'GL accounts' } },
      { path: 'audit', name: 'audit', component: () => import('./pages/AuditPage.vue'), meta: { title: 'Audit log' } },
      { path: 'setup/currencies', name: 'currencies', component: () => import('./pages/setup/CurrenciesPage.vue'), meta: { title: 'Currencies' } },
      { path: 'setup/segments', name: 'segments', component: () => import('./pages/setup/SegmentsPage.vue'), meta: { title: 'Customer segments' } },
      { path: 'setup/account-types', name: 'account-types', component: () => import('./pages/setup/AccountTypesPage.vue'), meta: { title: 'Account types' } },
      { path: 'setup/products', name: 'products', component: () => import('./pages/setup/ProductsPage.vue'), meta: { title: 'Card products' } },
      { path: 'setup/products/new', name: 'product-new', component: () => import('./pages/setup/ProductEditPage.vue'), meta: { title: 'New card product' } },
      { path: 'setup/products/:code', name: 'product', component: () => import('./pages/setup/ProductEditPage.vue'), props: true, meta: { title: 'Card product' } },
      { path: 'setup/numbering', name: 'numbering', component: () => import('./pages/setup/NumberingPage.vue'), meta: { title: 'Numbering & settings' } },
      { path: ':pathMatch(.*)*', redirect: '/' }
    ]
  }
]

const router = createRouter({ history: createWebHashHistory(), routes })

router.afterEach(to => {
  document.title = to.meta.title ? `${to.meta.title} · CMS Console` : 'CMS Console'
})

export default router
