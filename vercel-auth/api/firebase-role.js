const {getApps, initializeApp} = require("firebase-admin/app");
const {getAuth} = require("firebase-admin/auth");
const {ExternalAccountClient} = require("google-auth-library");
const {getVercelOidcToken} = require("@vercel/oidc");

function requiredEnvironment(name) {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(`Missing required environment variable: ${name}`);
  return value;
}

function firebaseApp() {
  if (getApps().length > 0) return getApps()[0];

  return initializeApp({
    credential: workloadIdentityCredential(),
    projectId: requiredEnvironment("GCP_PROJECT_ID"),
  });
}

function workloadIdentityCredential() {
  const projectNumber = requiredEnvironment("GCP_PROJECT_NUMBER");
  const poolId = requiredEnvironment("GCP_WORKLOAD_IDENTITY_POOL_ID");
  const providerId = requiredEnvironment("GCP_WORKLOAD_IDENTITY_POOL_PROVIDER_ID");
  const serviceAccount = requiredEnvironment("GCP_SERVICE_ACCOUNT_EMAIL");
  const client = ExternalAccountClient.fromJSON({
    type: "external_account",
    audience: `//iam.googleapis.com/projects/${projectNumber}/locations/global/` +
      `workloadIdentityPools/${poolId}/providers/${providerId}`,
    subject_token_type: "urn:ietf:params:oauth:token-type:jwt",
    token_url: "https://sts.googleapis.com/v1/token",
    service_account_impersonation_url:
      `https://iamcredentials.googleapis.com/v1/projects/-/serviceAccounts/` +
      `${serviceAccount}:generateAccessToken`,
    subject_token_supplier: {
      // google-auth-library passes its supplier context as an argument. Do not
      // forward that object to Vercel, where it would be interpreted as token
      // exchange options and change the audience expected by Google WIF.
      getSubjectToken: () => getVercelOidcToken(),
    },
  });
  if (!client) throw new Error("Unable to initialize Google workload identity.");
  client.scopes = ["https://www.googleapis.com/auth/cloud-platform"];

  return {
    getAccessToken: async () => {
      const accessToken = await client.getAccessToken();
      if (!accessToken.token) throw new Error("Google did not issue an access token.");
      return {
        access_token: accessToken.token,
        expires_in: Number(accessToken.res?.data?.expires_in || 3600),
      };
    },
  };
}

function bearerToken(request) {
  const authorization = request.headers.authorization;
  if (typeof authorization !== "string" || !authorization.startsWith("Bearer ")) {
    return null;
  }
  const token = authorization.slice("Bearer ".length).trim();
  return token.length > 0 && token.length <= 8192 ? token : null;
}

module.exports = async function handler(request, response) {
  response.setHeader("Cache-Control", "no-store");
  response.setHeader("Content-Type", "application/json; charset=utf-8");

  if (request.method !== "POST") {
    response.setHeader("Allow", "POST");
    return response.status(405).json({error: "method_not_allowed"});
  }

  const idToken = bearerToken(request);
  if (!idToken) {
    return response.status(401).json({error: "missing_token"});
  }

  try {
    const auth = getAuth(firebaseApp());
    const decoded = await auth.verifyIdToken(idToken);

    if (decoded.email_verified !== true ||
        decoded.firebase?.sign_in_provider !== "google.com") {
      return response.status(403).json({error: "google_account_required"});
    }

    if (decoded.role === "authenticated") {
      return response.status(200).json({status: "already_configured"});
    }

    const user = await auth.getUser(decoded.uid);
    await auth.setCustomUserClaims(decoded.uid, {
      ...(user.customClaims || {}),
      role: "authenticated",
    });

    return response.status(200).json({status: "updated"});
  } catch (error) {
    console.error("Firebase role bridge request failed", error?.code || error?.name || "error");
    return response.status(401).json({error: "invalid_token"});
  }
};
