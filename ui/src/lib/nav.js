// One navigation model for the orbit rail and the command bar.
import { can } from './session.js'

export function navGroups ({ devTools = false, pending = 0 } = {}) {
  const groups = [
    {
      title: 'Operations',
      items: [
        { to: '/', label: 'Dashboard', icon: 'space_dashboard', exact: true, keys: 'home overview pulse' },
        ...(can.write ? [{ to: '/issue', label: 'Issue card', icon: 'add_card', keys: 'new card issuance' }] : []),
        { to: '/customers', label: 'Customers', icon: 'groups', keys: 'cif clients' },
        { to: '/accounts', label: 'Accounts', icon: 'account_balance', keys: 'balances' },
        { to: '/cards', label: 'Cards', icon: 'credit_card', keys: 'pan' },
        { to: '/transactions', label: 'Transactions', icon: 'receipt_long', keys: 'iso authorisations' },
        { to: '/approvals', label: 'Approvals', icon: 'how_to_reg', badge: pending || null, keys: 'maker checker pending' }
      ]
    },
    {
      title: 'Setup',
      items: [
        { to: '/setup/products', label: 'Card products', icon: 'style', keys: 'bin range product' },
        { to: '/setup/account-types', label: 'Account types', icon: 'category' },
        { to: '/setup/segments', label: 'Customer segments', icon: 'diversity_3' },
        { to: '/setup/currencies', label: 'Currencies', icon: 'payments' },
        { to: '/setup/numbering', label: 'Numbering & settings', icon: 'pin', keys: 'sequence cif settings' }
      ]
    },
    {
      title: 'Control',
      items: [
        { to: '/core-banking', label: 'Core banking', icon: 'hub', keys: 'saf store forward stand-in stip host funds' },
        { to: '/gl', label: 'GL accounts', icon: 'account_tree', keys: 'ledger general' },
        { to: '/batch', label: 'Batch jobs', icon: 'schedule', keys: 'scheduler renewal expiry' },
        { to: '/audit', label: 'Audit log', icon: 'history', keys: 'activity trail' },
        { to: '/admin/approval-policy', label: 'Approval policy', icon: 'rule', keys: 'four eyes' },
        ...(can.admin ? [{ to: '/admin/users', label: 'Users', icon: 'manage_accounts', keys: 'operators roles' }] : [])
      ]
    }
  ]
  if (devTools && can.write) {
    groups.splice(2, 0, { title: 'Dev tools', items: [{ to: '/switch-simulator', label: 'Switch simulator', icon: 'cell_tower', keys: 'iso base24 test emv de55' }] })
  }
  return groups
}
