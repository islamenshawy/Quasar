import { createApp } from 'vue'
import { Quasar, Notify, Dialog, Loading, Dark, LocalStorage } from 'quasar'
import '@quasar/extras/material-icons/material-icons.css'
import '@quasar/extras/material-icons-outlined/material-icons-outlined.css'
import 'quasar/src/css/index.sass'
import './app.css'

import App from './App.vue'
import router from './router.js'

createApp(App)
  .use(Quasar, {
    plugins: { Notify, Dialog, Loading, Dark, LocalStorage },
    config: {
      notify: { position: 'top-right', timeout: 3500 },
      dark: 'auto'
    }
  })
  .use(router)
  .mount('#app')
