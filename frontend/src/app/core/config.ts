/** Runtime settings. Local development uses the Keycloak realm from docker compose. */
export const appSettings = {
  auth: {
    authority: 'http://localhost:8180/realms/tms',
    clientId: 'tms-web',
    scope: 'openid profile email'
  },
  apiBase: '/api/v1'
} as const;
