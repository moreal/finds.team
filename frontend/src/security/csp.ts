export function createCspNonce() {
  return crypto.randomUUID().replaceAll("-", "");
}

export function getCspNonce() {
  if (typeof document !== "undefined") {
    const serverNonce = document
      .querySelector('meta[property="csp-nonce"]')
      ?.getAttribute("content");
    if (serverNonce) return serverNonce;
  }

  return createCspNonce();
}

export function createContentSecurityPolicy(nonce: string) {
  return [
    "default-src 'self'",
    `script-src 'nonce-${nonce}' 'strict-dynamic'`,
    "object-src 'none'",
    "base-uri 'none'",
    "frame-ancestors 'none'",
  ].join("; ");
}
