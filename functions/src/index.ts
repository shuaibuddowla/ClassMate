/*
 * functions/src/index.ts
 * Cloud Function to send FCM notifications on new Firestore notice.
 */

import {onDocumentCreated} from "firebase-functions/v2/firestore";
import * as admin from "firebase-admin";

admin.initializeApp();

/**
 * Triggers when a new document is created in the 'notices' collection.
 * Sends a push notification to the appropriate topic.
 */
export const onnoticecreated = onDocumentCreated("notices/{noticeId}",
  async (event) => {
    const snapshot = event.data;

    // Safety check for data
    if (!snapshot) {
      console.log("No data found in the document.");
      return;
    }

    const data = snapshot.data();
    const title = data.title || "New Announcement";
    const body = data.body || "A new notice has been posted in the app.";
    const isCancel = data.isCancel === true;

    // Logic: Cancellations go to 'cancellations' topic, others to 'notices'
    const topic = isCancel ? "cancellations" : "notices";

    const message: admin.messaging.Message = {
      notification: {
        title: title,
        body: body,
      },
      // Custom data to help the Android app navigate and highlight
      data: {
        OPEN_TAB: "notices",
        noticeId: event.params.noticeId,
      },
      topic: topic,
    };

    try {
      const response = await admin.messaging().send(message);
      console.log(`Sent to topic [${topic}]:`, response);
    } catch (error) {
      console.error("Error sending FCM message:", error);
    }
  });
