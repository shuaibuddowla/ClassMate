/*
 * One-time owner script for existing Firebase Authentication users.
 *
 * Run only from a trusted machine or Google Cloud Shell with Application
 * Default Credentials for the intended Firebase project. Never commit a
 * service-account JSON key.
 */
const admin = require("firebase-admin");

admin.initializeApp({
  credential: admin.credential.applicationDefault(),
});

async function backfill(pageToken) {
  const page = await admin.auth().listUsers(1000, pageToken);

  for (const user of page.users) {
    if (user.customClaims?.role === "authenticated") {
      continue;
    }

    await admin.auth().setCustomUserClaims(user.uid, {
      ...(user.customClaims || {}),
      role: "authenticated",
    });
    console.log(`Updated ${user.uid}`);
  }

  if (page.pageToken) {
    await backfill(page.pageToken);
  }
}

backfill()
  .then(() => {
    console.log("Firebase role-claim backfill complete.");
    process.exit(0);
  })
  .catch((error) => {
    console.error("Firebase role-claim backfill failed:", error);
    process.exit(1);
  });
