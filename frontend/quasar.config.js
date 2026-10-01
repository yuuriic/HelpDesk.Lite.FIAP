/**
 * Configuracao do Quasar CLI (Vite).
 * Docs: https://v2.quasar.dev/quasar-cli-vite/quasar-config-file
 */
export default function () {
  return {
    boot: ['tema', 'sessao'],

    css: ['app.scss'],

    extras: ['roboto-font', 'material-icons'],

    build: {
      target: {
        browser: ['es2022', 'firefox115', 'chrome115', 'safari14'],
        node: 'node20'
      },
      vueRouterMode: 'history',
      // Repassa API_BASE_URL so quando definida no build; o padrao local fica num lugar so,
      // o src/services/api.js (FE-05).
      env: process.env.API_BASE_URL ? { API_BASE_URL: process.env.API_BASE_URL } : {}
    },

    devServer: {
      port: 9000,
      open: false
    },

    framework: {
      config: {
        notify: {
          position: 'top-right',
          timeout: 4000,
          actions: [{ icon: 'close', color: 'white', round: true }]
        }
      },
      plugins: ['Notify', 'Dialog', 'Loading']
    }
  }
}
